package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.shortage.RescheduleProposal;
import com.cretas.aims.service.scheduling.ScheduleLineLocks;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 局部重排的 JPQL 在真实映射上跑 —— H2, 以及设了 CRETAS_WORKFLOW_PG_URL 时的 PostgreSQL (CI 的 PG 层)。
 * 父表 (工厂 / 用户 / 产品 / 原料 / 排程计划) 都是真造的行, 所以两种库都走同一套数据, 外键照常生效。
 *
 * <p>证明: 只取本厂、这张订单、还没开工的任务 —— 批次或计划已有报工的不算, 排程计划已完成 / 取消的不算;
 * 重叠只算在排 / 在做且不含被挪的任务; 加锁取任务按 id 排; 到货日取已提交以后的在走采购单最晚日期, 草稿不算,
 * 并数出没填日期的单; 产线锁在 PG 上真取到。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = ShortageScheduleQueryRepository.class, includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {ShortageScheduleQueryRepository.class, RescheduleProposalRepository.class}))
class ShortageScheduleRepositoryQueryValidationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String safe = DisposablePostgresTargetGuard.requireSafeUrl(url);
            String bound = safe + "?stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    private static boolean onH2() {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        return url == null || url.isBlank();
    }

    @Autowired TestEntityManager em;
    @Autowired ShortageScheduleQueryRepository schedules;
    @Autowired RescheduleProposalRepository proposals;

    private Long userId;
    private int planSeq;

    @BeforeEach
    void parents() {
        for (String f : List.of("F1", "F2")) {
            Factory factory = new Factory();
            factory.setId(f);
            factory.setName(f);
            em.persist(factory);
        }
        User u = new User();
        u.setFactoryId("F1");
        u.setUsername("planner-" + UUID.randomUUID());
        u.setPasswordHash("unused");
        u.setIsActive(true);
        em.persist(u);
        em.flush();
        userId = u.getId();

        ProductType pt = new ProductType();
        pt.setId("P1");
        pt.setFactoryId("F1");
        pt.setCode("P1");
        pt.setName("产品一");
        pt.setUnit("kg");
        em.persist(pt);
        for (String m : List.of("M1", "M2")) {
            RawMaterialType rm = new RawMaterialType();
            rm.setId(m);
            rm.setFactoryId("F1");
            rm.setCode(m);
            rm.setName(m);
            rm.setUnit("kg");
            em.persist(rm);
        }
        em.flush();
    }

    private SchedulingPlan sp(String factoryId, SchedulingPlan.PlanStatus status) {
        SchedulingPlan p = new SchedulingPlan();
        p.setId(UUID.randomUUID().toString());
        p.setFactoryId(factoryId);
        p.setPlanDate(DAY.minusDays(planSeq++));   // (工厂, 计划日期) 唯一; 任务时间不依赖它
        p.setStatus(status);
        em.persist(p);
        return p;
    }

    private ProductionPlan pp(String factoryId, String salesOrderId) {
        ProductionPlan p = new ProductionPlan();
        p.setId(UUID.randomUUID().toString());
        p.setFactoryId(factoryId);
        p.setPlanNumber("PP-" + UUID.randomUUID().toString().substring(0, 12));
        p.setProductTypeId("P1");
        p.setPlannedQuantity(BigDecimal.TEN);
        p.setPlannedUnit("kg");
        p.setSourceOrderId(salesOrderId);
        p.setCreatedBy(userId);
        em.persist(p);
        return p;
    }

    private LineSchedule task(SchedulingPlan sp, ProductionPlan pp, String line, int fromHour, int toHour,
                              LineSchedule.ScheduleStatus status) {
        LineSchedule ls = new LineSchedule();
        ls.setId(UUID.randomUUID().toString());
        ls.setPlan(sp);
        ls.setProductionLineId(line);
        ls.setProductionPlanId(pp == null ? null : pp.getId());
        ls.setPlannedStartTime(DAY.atTime(fromHour, 0));
        ls.setPlannedEndTime(DAY.atTime(toHour, 0));
        ls.setStatus(status);
        em.persist(ls);
        return ls;
    }

    private ProductionBatch batch(ProductionPlan pp) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(pp.getFactoryId());
        b.setBatchNumber("B-" + UUID.randomUUID().toString().substring(0, 12));
        b.setProductTypeId("P1");
        b.setQuantity(BigDecimal.TEN);
        b.setUnit("kg");
        b.setStatus(ProductionBatchStatus.IN_PROGRESS);
        b.setBatchType("REGULAR");
        b.setCostIncomplete(false);
        b.setProductionPlanId(pp.getId());
        em.persist(b);
        return b;
    }

    private void report(ProductionBatch b) {
        ProductionReport r = new ProductionReport();
        r.setFactoryId(b.getFactoryId());
        r.setBatchId(b.getId());
        r.setWorkerId(userId);
        r.setReportType("PROGRESS");
        r.setReportMode(com.cretas.aims.entity.enums.ReportMode.MODE_1);
        r.setReportDate(DAY);
        r.setDataCorrected(false);
        em.persist(r);
    }

    @Test
    void notStartedTasksAreThisFactoryThisOrderAndNotYetReportedOrClosed() {
        SchedulingPlan f1 = sp("F1", SchedulingPlan.PlanStatus.confirmed);
        SchedulingPlan f1Done = sp("F1", SchedulingPlan.PlanStatus.completed);
        SchedulingPlan f2 = sp("F2", SchedulingPlan.PlanStatus.confirmed);
        ProductionPlan mine = pp("F1", "SO-1");
        ProductionPlan reported = pp("F1", "SO-1");
        ProductionPlan otherOrder = pp("F1", "SO-2");
        ProductionPlan otherFactory = pp("F2", "SO-1");
        LineSchedule wanted = task(f1, mine, "L1", 8, 16, LineSchedule.ScheduleStatus.pending);
        task(f1, mine, "L1", 16, 20, LineSchedule.ScheduleStatus.in_progress);   // 已在做
        task(f1Done, mine, "L2", 8, 16, LineSchedule.ScheduleStatus.pending);    // 排程计划已完成
        task(f1, reported, "L3", 8, 16, LineSchedule.ScheduleStatus.pending);    // 计划已报工, 任务状态却还是 pending
        task(f1, otherOrder, "L1", 8, 16, LineSchedule.ScheduleStatus.pending);
        task(f2, otherFactory, "L1", 8, 16, LineSchedule.ScheduleStatus.pending);
        report(batch(reported));
        em.flush();

        assertThat(schedules.findNotStartedForSalesOrder("F1", "SO-1")).extracting(LineSchedule::getId)
                .containsExactly(wanted.getId());
    }

    @Test
    void aReportOnTheTasksOwnBatchAlsoCountsAsStarted() {
        SchedulingPlan f1 = sp("F1", SchedulingPlan.PlanStatus.confirmed);
        ProductionPlan mine = pp("F1", "SO-1");
        ProductionPlan unrelated = pp("F1", "SO-9");
        ProductionBatch b = batch(unrelated);   // 批次挂在别的计划上, 任务直接指向它
        LineSchedule t = task(f1, mine, "L1", 8, 16, LineSchedule.ScheduleStatus.pending);
        em.flush();
        t.setBatchId(b.getId());
        report(b);
        em.flush();

        assertThat(schedules.findNotStartedForSalesOrder("F1", "SO-1")).isEmpty();
    }

    @Test
    void lockingTasksIsFactoryScopedAndOrderedById() {
        SchedulingPlan f1 = sp("F1", SchedulingPlan.PlanStatus.confirmed);
        SchedulingPlan f2 = sp("F2", SchedulingPlan.PlanStatus.confirmed);
        LineSchedule a = task(f1, null, "L1", 8, 9, LineSchedule.ScheduleStatus.pending);
        LineSchedule b = task(f1, null, "L1", 9, 10, LineSchedule.ScheduleStatus.pending);
        LineSchedule foreign = task(f2, null, "L1", 8, 9, LineSchedule.ScheduleStatus.pending);
        em.flush();

        List<String> expected = java.util.stream.Stream.of(a.getId(), b.getId()).sorted().toList();
        assertThat(schedules.lockByIds("F1", List.of(b.getId(), a.getId(), foreign.getId())))
                .extracting(LineSchedule::getId).containsExactlyElementsOf(expected);
    }

    @Test
    void overlapCountsLiveTasksOnTheLineButNotTheOnesBeingMoved() {
        SchedulingPlan f1 = sp("F1", SchedulingPlan.PlanStatus.confirmed);
        LineSchedule moving = task(f1, null, "L1", 8, 12, LineSchedule.ScheduleStatus.pending);
        LineSchedule busy = task(f1, null, "L1", 10, 14, LineSchedule.ScheduleStatus.in_progress);
        task(f1, null, "L1", 10, 14, LineSchedule.ScheduleStatus.cancelled);
        task(f1, null, "L1", 11, 14, LineSchedule.ScheduleStatus.completed);
        task(f1, null, "L2", 10, 14, LineSchedule.ScheduleStatus.pending);
        task(f1, null, "L1", 12, 15, LineSchedule.ScheduleStatus.pending);   // 窗口 [9, 12) 之后才开始, 不算
        em.flush();

        List<LineSchedule> clash = schedules.findOverlapping("F1", "L1", DAY.atTime(9, 0), DAY.atTime(12, 0),
                Set.of(moving.getId()));
        assertThat(clash).extracting(LineSchedule::getId).containsExactly(busy.getId());
        assertThat(schedules.findOverlapping("F2", "L1", DAY.atTime(9, 0), DAY.atTime(12, 0), Set.of("x"))).isEmpty();
    }

    @Test
    void arrivalIsTheLatestDateAmongSubmittedOrdersAndCountsUndatedOnes() {
        item(order("F1", PurchaseOrderStatus.DRAFT, DAY.plusDays(20)), "M1", "10", "0");     // 草稿不算
        item(order("F1", PurchaseOrderStatus.SUBMITTED, DAY.plusDays(2)), "M1", "10", "0");
        item(order("F1", PurchaseOrderStatus.APPROVED, DAY.plusDays(5)), "M1", "10", "4");
        item(order("F1", PurchaseOrderStatus.CANCELLED, DAY.plusDays(9)), "M1", "10", "0");  // 取消不算
        item(order("F1", PurchaseOrderStatus.APPROVED, DAY.plusDays(9)), "M1", "10", "10");  // 收完不算
        item(order("F2", PurchaseOrderStatus.APPROVED, DAY.plusDays(9)), "M1", "10", "0");   // 别厂不算
        item(order("F1", PurchaseOrderStatus.APPROVED, DAY.plusDays(9)), "M2", "10", "0");   // 别的料不算
        em.flush();

        Object[] row = schedules.findLiveOrderArrival("F1", "M1").get(0);
        assertThat(asDate(row[0])).isEqualTo(DAY.plusDays(5));
        assertThat(((Number) row[1]).longValue()).isZero();
        assertThat(((Number) row[2]).longValue()).isEqualTo(2);

        item(order("F1", PurchaseOrderStatus.SUBMITTED, null), "M1", "3", "0");
        em.flush();
        row = schedules.findLiveOrderArrival("F1", "M1").get(0);
        assertThat(((Number) row[1]).longValue()).isEqualTo(1);
        assertThat(((Number) schedules.findLiveOrderArrival("F1", "M2").get(0)[2]).longValue()).isEqualTo(1);
    }

    @Test
    void lineLocksAreTakenInsideTheTransaction() {
        ScheduleLineLocks locks = new ScheduleLineLocks(em.getEntityManager());
        assertThatCode(() -> {
            locks.lock("F1", List.of("L2", "L1", "L1"));
            locks.lock("F1", List.of("L1"));   // 同一事务里重入不阻塞
        }).doesNotThrowAnyException();
        assertThat(ScheduleLineLocks.key("F1", "L1")).isEqualTo("line_schedule:F1:L1");
    }

    @Test
    void pessimisticQueryKeepsTheManagedSnapshotUntilExplicitRefresh() {
        assumeTrue(!onH2(), "ProductionPlan JSONB refresh requires PostgreSQL; H2 stores JSONB as a quoted string");
        ProductionPlan plan = pp("F1", "SO-REFRESH");
        ProductionPlan otherFactory = pp("F2", "SO-REFRESH");
        em.flush();
        // A bulk update models another committed writer without changing this
        // persistence context. All database predicates remain tenant qualified.
        int changed = em.getEntityManager().createNativeQuery(
                        "UPDATE production_plans SET planned_quantity = :quantity WHERE id = :id AND factory_id = :factory")
                .setParameter("quantity", new BigDecimal("20"))
                .setParameter("id", plan.getId()).setParameter("factory", "F1")
                .executeUpdate();
        assertThat(changed).isEqualTo(1);

        ProductionPlan locked = em.getEntityManager().createQuery(
                        "SELECT p FROM ProductionPlan p WHERE p.id = :id AND p.factoryId = :factory", ProductionPlan.class)
                .setParameter("id", plan.getId()).setParameter("factory", "F1")
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
        assertThat(locked).isSameAs(plan);
        assertThat(locked.getPlannedQuantity()).isEqualByComparingTo("10");

        em.getEntityManager().refresh(locked, LockModeType.PESSIMISTIC_WRITE);
        assertThat(locked.getFactoryId()).isEqualTo("F1");
        assertThat(locked.getPlannedQuantity()).isEqualByComparingTo("20");
        em.getEntityManager().refresh(otherFactory);
        assertThat(otherFactory.getPlannedQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void proposalsRoundTripTheirJsonAndAreScopedToFactoryAndCase() {
        assumeTrue(!onH2(), "jsonb round trip needs PG (H2 stores the JSON as a quoted string)");
        RescheduleProposal p = new RescheduleProposal();
        p.setId(UUID.randomUUID().toString());
        p.setFactoryId("F1");
        p.setCaseId("C1");
        p.setBaseline(List.of(Map.of("scheduleId", "S1", "start", "2026-11-10T08:00")));
        p.setCandidates(List.of(Map.of("key", "SAME_LINE_POSTPONE", "applicable", true)));
        p.setExpiresAt(LocalDateTime.of(2026, 11, 10, 9, 0));
        p.setCreatedByType("FACTORY_USER");
        p.setCreatedById(7L);
        proposals.saveAndFlush(p);
        em.clear();

        RescheduleProposal back = proposals.findByIdAndFactoryIdAndCaseId(p.getId(), "F1", "C1").orElseThrow();
        assertThat(back.getBaseline().get(0).get("start")).isEqualTo("2026-11-10T08:00");
        assertThat(back.getCandidates().get(0).get("applicable")).isEqualTo(true);
        assertThat(proposals.findByIdAndFactoryIdAndCaseId(p.getId(), "F2", "C1")).isEmpty();
        assertThat(proposals.findTop5ByFactoryIdAndCaseIdOrderByCreatedAtDesc("F1", "C1")).hasSize(1);
    }

    private static LocalDate asDate(Object v) {
        return v instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) v;
    }

    private PurchaseOrder order(String factoryId, PurchaseOrderStatus status, LocalDate expected) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId(UUID.randomUUID().toString());
        o.setFactoryId(factoryId);
        o.setOrderNumber("PO-" + UUID.randomUUID());
        o.setOrderDate(DAY);
        o.setExpectedDeliveryDate(expected);
        o.setStatus(status);
        o.setCreatedBy(userId);
        em.persist(o);
        return o;
    }

    private void item(PurchaseOrder o, String material, String qty, String received) {
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setPurchaseOrderId(o.getId());
        i.setMaterialTypeId(material);
        i.setUnit("kg");
        i.setPriceUnit("kg");
        i.setQuantity(new BigDecimal(qty));
        i.setReceivedQuantity(new BigDecimal(received));
        i.setUnitPrice(BigDecimal.ONE);
        em.persist(i);
    }
}
