package com.cretas.aims.repository.scheduling;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-S4-CANCEL-GHOST-DAILY 的 JPQL 在真实映射上跑 —— H2, 以及设了 CRETAS_WORKFLOW_PG_URL 时的 PostgreSQL (CI 的 PG 层)。
 * 父表 (工厂 / 用户 / 产品 / 排程计划 / 生产计划 / 批次) 都是真造的行, 两种库同一套数据, 外键照常生效。
 *
 * <p>证明: 自动排程 / 插单的占用 ({@link ApsConstraintQueryRepository#findLiveEndingFrom}) 不算已取消生产计划名下
 * 没开工的任务 (直接挂计划的, 和只挂批次的), 没取消的、已开工的照算; 级联 UPDATE 只作废这家厂、这个已取消计划名下
 * 没开工的任务, 写明原因, 不删行; 计划没取消时一条不动。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = ApsConstraintQueryRepository.class, includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {ApsConstraintQueryRepository.class, CancelledPlanScheduleRepository.class}))
class CancelledPlanScheduleRepositoryQueryValidationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 15);

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

    @Autowired TestEntityManager em;
    @Autowired ApsConstraintQueryRepository constraints;
    @Autowired CancelledPlanScheduleRepository cascadeRows;

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
        for (String f : List.of("F1", "F2")) {
            ProductType pt = new ProductType();
            pt.setId("P-" + f);
            pt.setFactoryId(f);
            pt.setCode("P-" + f);
            pt.setName("产品");
            pt.setUnit("kg");
            em.persist(pt);
        }
        em.flush();
    }

    @Test
    void apsOccupancySkipsNotStartedRowsOfCancelledPlansOnly() {
        SchedulingPlan sp = sp("F1");
        ProductionPlan cancelled = pp("F1");
        ProductionPlan live = pp("F1");
        ProductionPlan cancelledViaBatch = pp("F1");
        LineSchedule ghost = task(sp, cancelled, LineSchedule.ScheduleStatus.pending, false);
        LineSchedule started = task(sp, cancelled, LineSchedule.ScheduleStatus.in_progress, true);
        LineSchedule pendingButStarted = task(sp, cancelled, LineSchedule.ScheduleStatus.pending, true);
        LineSchedule livePending = task(sp, live, LineSchedule.ScheduleStatus.pending, false);
        LineSchedule batchGhost = task(sp, null, LineSchedule.ScheduleStatus.pending, false);
        LineSchedule manualNoPlan = task(sp, null, LineSchedule.ScheduleStatus.pending, false);
        em.flush();
        batchGhost.setBatchId(batch(cancelledViaBatch).getId());
        cancel(cancelled);
        cancel(cancelledViaBatch);
        em.flush();

        List<String> live1 = constraints.findLiveEndingFrom("F1", DAY.atStartOfDay()).stream()
                .map(LineSchedule::getId).toList();
        System.out.println("GHOST_APS_PG ghost=" + ghost.getId() + " batchGhost=" + batchGhost.getId() + " live=" + live1);

        assertThat(live1).doesNotContain(ghost.getId(), batchGhost.getId())
                .containsExactlyInAnyOrder(started.getId(), pendingButStarted.getId(), livePending.getId(),
                        manualNoPlan.getId());
    }

    @Test
    void cascadeVoidsOnlyThisFactorysNotStartedRowsOfTheCancelledPlan() {
        SchedulingPlan sp = sp("F1");
        SchedulingPlan otherSp = sp("F2");
        ProductionPlan plan = pp("F1");
        ProductionPlan untouchedPlan = pp("F1");
        LineSchedule direct = task(sp, plan, LineSchedule.ScheduleStatus.pending, false);
        LineSchedule viaBatch = task(sp, null, LineSchedule.ScheduleStatus.pending, false);
        LineSchedule started = task(sp, plan, LineSchedule.ScheduleStatus.in_progress, true);
        LineSchedule pendingButStarted = task(sp, plan, LineSchedule.ScheduleStatus.pending, true);
        LineSchedule other = task(sp, untouchedPlan, LineSchedule.ScheduleStatus.pending, false);
        LineSchedule foreign = task(otherSp, plan, LineSchedule.ScheduleStatus.pending, false);   // 别厂排程挂着同一个 id
        em.flush();
        viaBatch.setBatchId(batch(plan).getId());
        em.flush();

        LocalDateTime now = DAY.atTime(9, 30);
        assertThat(cascadeRows.cancelNotStartedOfCancelledPlan("F1", plan.getId(), now))
                .as("计划还没取消: 一条不动").isZero();

        cancel(plan);
        em.flush();
        int voided = cascadeRows.cancelNotStartedOfCancelledPlan("F1", plan.getId(), now);
        em.clear();
        System.out.println("GHOST_CASCADE_PG voided=" + voided);

        assertThat(voided).isEqualTo(2);
        assertThat(reload(direct).getStatus()).isEqualTo(LineSchedule.ScheduleStatus.cancelled);
        assertThat(reload(direct).getAdjustmentReason()).isEqualTo(CancelledPlanScheduleRepository.CASCADE_REASON);
        assertThat(reload(direct).getLastAdjustmentTime()).isEqualTo(now);
        assertThat(reload(viaBatch).getStatus()).isEqualTo(LineSchedule.ScheduleStatus.cancelled);
        assertThat(reload(started).getStatus()).isEqualTo(LineSchedule.ScheduleStatus.in_progress);
        assertThat(reload(pendingButStarted).getStatus()).isEqualTo(LineSchedule.ScheduleStatus.pending);
        assertThat(reload(other).getStatus()).isEqualTo(LineSchedule.ScheduleStatus.pending);
        assertThat(reload(foreign).getStatus()).isEqualTo(LineSchedule.ScheduleStatus.pending);
        assertThat(cascadeRows.countCascadeCancelled("F1", plan.getId())).isEqualTo(2);
        assertThat(cascadeRows.countStartedNotFinished("F1", plan.getId())).isEqualTo(2);
        assertThat(cascadeRows.findFactoryIdOfPlan(plan.getId())).contains("F1");
        assertThat(cascadeRows.cancelNotStartedOfCancelledPlan("F1", plan.getId(), now)).as("重复级联").isZero();
    }

    // ==================== fixtures ====================

    private LineSchedule reload(LineSchedule row) {
        return em.find(LineSchedule.class, row.getId());
    }

    private void cancel(ProductionPlan p) {
        p.setStatus(ProductionPlanStatus.CANCELLED);
        em.persist(p);
    }

    private SchedulingPlan sp(String factoryId) {
        SchedulingPlan p = new SchedulingPlan();
        p.setId(UUID.randomUUID().toString());
        p.setFactoryId(factoryId);
        p.setPlanDate(DAY.minusDays(planSeq++));   // (工厂, 计划日期) 唯一; 任务时间不依赖它
        p.setStatus(SchedulingPlan.PlanStatus.draft);
        em.persist(p);
        return p;
    }

    private ProductionPlan pp(String factoryId) {
        ProductionPlan p = new ProductionPlan();
        p.setId(UUID.randomUUID().toString());
        p.setFactoryId(factoryId);
        p.setPlanNumber("PP-" + UUID.randomUUID().toString().substring(0, 12));
        p.setProductTypeId("P-" + factoryId);
        p.setPlannedQuantity(BigDecimal.TEN);
        p.setPlannedUnit("kg");
        p.setCreatedBy(userId);
        em.persist(p);
        return p;
    }

    private LineSchedule task(SchedulingPlan sp, ProductionPlan pp, LineSchedule.ScheduleStatus status, boolean started) {
        LineSchedule ls = new LineSchedule();
        ls.setId(UUID.randomUUID().toString());
        ls.setPlan(sp);
        ls.setProductionLineId("L1");
        ls.setProductionPlanId(pp == null ? null : pp.getId());
        ls.setPlannedStartTime(DAY.atTime(8, 0));
        ls.setPlannedEndTime(DAY.atTime(16, 0));
        ls.setStatus(status);
        if (started) {
            ls.setActualStartTime(DAY.atTime(8, 5));
        }
        em.persist(ls);
        return ls;
    }

    private ProductionBatch batch(ProductionPlan pp) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(pp.getFactoryId());
        b.setBatchNumber("B-" + UUID.randomUUID().toString().substring(0, 12));
        b.setProductTypeId(pp.getProductTypeId());
        b.setQuantity(BigDecimal.TEN);
        b.setUnit("kg");
        b.setStatus(ProductionBatchStatus.IN_PROGRESS);
        b.setBatchType("REGULAR");
        b.setCostIncomplete(false);
        b.setProductionPlanId(pp.getId());
        em.persist(b);
        return b;
    }
}
