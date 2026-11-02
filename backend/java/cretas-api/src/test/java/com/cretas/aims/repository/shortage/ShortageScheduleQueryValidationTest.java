package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.shortage.RescheduleProposal;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 局部重排的 JPQL 在真实映射上跑 (H2; 设了 CRETAS_WORKFLOW_PG_URL 时在 PG 上跑): 只取本厂、这张订单、还没开工的任务; 重叠只算在排 / 在做且不含自己;
 * 到货日取在走采购单的最晚日期, 并数出没填日期的单。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = ShortageScheduleQueryRepository.class, includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {ShortageScheduleQueryRepository.class, RescheduleProposalRepository.class}))
class ShortageScheduleQueryValidationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
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

    private SchedulingPlan sp(String factoryId) {
        SchedulingPlan p = new SchedulingPlan();
        p.setId(UUID.randomUUID().toString());
        p.setFactoryId(factoryId);
        p.setPlanDate(DAY);
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
        p.setPlannedUnit("KG");
        p.setSourceOrderId(salesOrderId);
        p.setCreatedBy(1L);
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

    @Test
    void notStartedTasksAreThisFactoryThisOrderAndPendingOnly() {
        assumeTrue(onH2(), "seeds bypass FK parents; H2 only (PG covers the schema in the migration test)");
        em.getEntityManager().createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        SchedulingPlan f1 = sp("F1");
        SchedulingPlan f2 = sp("F2");
        ProductionPlan mine = pp("F1", "SO-1");
        ProductionPlan otherOrder = pp("F1", "SO-2");
        ProductionPlan otherFactory = pp("F2", "SO-1");
        LineSchedule wanted = task(f1, mine, "L1", 8, 16, LineSchedule.ScheduleStatus.pending);
        task(f1, mine, "L1", 16, 20, LineSchedule.ScheduleStatus.in_progress);
        task(f1, otherOrder, "L1", 8, 16, LineSchedule.ScheduleStatus.pending);
        task(f2, otherFactory, "L1", 8, 16, LineSchedule.ScheduleStatus.pending);
        em.flush();

        assertThat(schedules.findNotStartedForSalesOrder("F1", "SO-1")).extracting(LineSchedule::getId)
                .containsExactly(wanted.getId());
        assertThat(schedules.lockByIds("F2", List.of(wanted.getId()))).isEmpty();
        assertThat(schedules.lockByIds("F1", List.of(wanted.getId()))).hasSize(1);
    }

    @Test
    void overlapCountsLiveTasksOnTheLineButNotTheOnesBeingMoved() {
        assumeTrue(onH2(), "seeds bypass FK parents; H2 only (PG covers the schema in the migration test)");
        em.getEntityManager().createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        SchedulingPlan f1 = sp("F1");
        LineSchedule moving = task(f1, null, "L1", 8, 12, LineSchedule.ScheduleStatus.pending);
        LineSchedule busy = task(f1, null, "L1", 10, 14, LineSchedule.ScheduleStatus.in_progress);
        task(f1, null, "L1", 10, 14, LineSchedule.ScheduleStatus.cancelled);
        task(f1, null, "L1", 12, 14, LineSchedule.ScheduleStatus.completed);
        task(f1, null, "L2", 10, 14, LineSchedule.ScheduleStatus.pending);
        task(f1, null, "L1", 12, 15, LineSchedule.ScheduleStatus.pending);   // 窗口 [9, 12) 之后才开始, 不算
        em.flush();

        List<LineSchedule> clash = schedules.findOverlapping("F1", "L1", DAY.atTime(9, 0), DAY.atTime(12, 0),
                Set.of(moving.getId()));
        assertThat(clash).extracting(LineSchedule::getId).containsExactly(busy.getId());
        assertThat(schedules.findOverlapping("F2", "L1", DAY.atTime(9, 0), DAY.atTime(12, 0), Set.of("x"))).isEmpty();
    }

    @Test
    void arrivalIsTheLatestDateAmongLiveOrdersAndCountsUndatedOnes() {
        assumeTrue(onH2(), "seeds bypass FK parents; H2 only (PG covers the schema in the migration test)");
        em.getEntityManager().createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        item(order("F1", PurchaseOrderStatus.DRAFT, DAY.plusDays(2)), "M1", "10", "0");
        item(order("F1", PurchaseOrderStatus.APPROVED, DAY.plusDays(5)), "M1", "10", "4");
        item(order("F1", PurchaseOrderStatus.CANCELLED, DAY.plusDays(9)), "M1", "10", "0");   // 取消不算
        item(order("F1", PurchaseOrderStatus.APPROVED, DAY.plusDays(9)), "M1", "10", "10");   // 收完不算
        item(order("F2", PurchaseOrderStatus.APPROVED, DAY.plusDays(9)), "M1", "10", "0");    // 别厂不算
        item(order("F1", PurchaseOrderStatus.APPROVED, DAY.plusDays(9)), "M2", "10", "0");    // 别的料不算
        em.flush();

        Object[] row = schedules.findLiveOrderArrival("F1", "M1").get(0);
        assertThat(row[0]).isEqualTo(DAY.plusDays(5));
        assertThat(((Number) row[1]).longValue()).isZero();
        assertThat(((Number) row[2]).longValue()).isEqualTo(2);

        item(order("F1", PurchaseOrderStatus.SUBMITTED, null), "M1", "3", "0");
        em.flush();
        row = schedules.findLiveOrderArrival("F1", "M1").get(0);
        assertThat(((Number) row[1]).longValue()).isEqualTo(1);
        assertThat(((Number) schedules.findLiveOrderArrival("F1", "M9").get(0)[2]).longValue()).isZero();
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

    private PurchaseOrder order(String factoryId, PurchaseOrderStatus status, LocalDate expected) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId(UUID.randomUUID().toString());
        o.setFactoryId(factoryId);
        o.setOrderNumber("PO-" + UUID.randomUUID());
        o.setOrderDate(DAY);
        o.setExpectedDeliveryDate(expected);
        o.setStatus(status);
        o.setCreatedBy(1L);
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
