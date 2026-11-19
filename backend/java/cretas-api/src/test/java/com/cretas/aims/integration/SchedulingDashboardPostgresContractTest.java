package com.cretas.aims.integration;

import com.cretas.aims.dto.scheduling.LineScheduleDTO;
import com.cretas.aims.dto.scheduling.SchedulingAlertDTO;
import com.cretas.aims.dto.scheduling.SchedulingDashboardDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SchedulingAlert;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkerAssignment;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [PROTO-SCHED-DASH] 调度中心「利用率全 0、今日排程空」+「预警处理 500」, 真 PostgreSQL + 真 Hibernate。
 *
 * <p>一、Dashboard ({@link SchedulingPlanCrudServiceImpl#getDashboard}) 三种样本:
 * 只有调度排程 / 只有工作流 IN_PROGRESS 批次 / 两者都有 (同一份活只算一次)。每个用例另放一家工厂的同日数据,
 * 证明按 factory_id 隔离。
 *
 * <p>二、预警处理: 按 prod 实况 (2026-11-19 只读核对) 在 scheduling_alerts 上挂旧的 update_timestamp 触发器
 * (表里没有 updated_at), 先证明 resolve 在迁移前确实失败 (阳性对照), 再跑 V20261120_97 迁移 (两遍, 幂等) 后 resolve 成功落库。
 *
 * <p>开关同 {@link CancelledDayPlanReopenPostgresContractTest}, 跑在 postgres-reversal-concurrency 车道。
 */
@SpringBootTest(classes = SchedulingDashboardPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SchedulingDashboardPostgresContractTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 21);
    private static final String MIGRATION = "db/flyway/V20261120_97__scheduling_alerts_drop_stale_update_trigger.sql";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({SchedulingPlanCrudServiceImpl.class, SchedulingResourceWriteLock.class})
    static class Runtime { }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @MockBean NotificationService notificationService;
    @MockBean PushNotificationService pushNotificationService;
    @MockBean WorkerAssignmentService workerAssignmentService;
    @MockBean SchedulingAIService schedulingAIService;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager em;
    @Autowired SchedulingPlanCrudServiceImpl crud;

    private TransactionTemplate tx;
    /** 每个用例一家新工厂: 被测工厂 F 与同日也有数据的旁厂 O。 */
    private String factory;
    private String other;
    private String line1;
    private String line2;
    private String planA;   // 建议产线 = line2
    private String planB;   // 建议产线 = line1

    @BeforeEach
    void committedFixture() {
        tx = new TransactionTemplate(transactionManager);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        factory = "SD-" + tag;
        other = "SO-" + tag;
        tx.executeWithoutResult(s -> {
            Long userId = null;
            for (String fid : List.of(factory, other)) {
                Factory f = new Factory();
                f.setId(fid);
                f.setName(fid);
                em.persist(f);
                User u = new User();
                u.setFactoryId(fid);
                u.setUsername("sd-" + UUID.randomUUID());
                u.setPasswordHash("unused-test-fixture");
                u.setRoleCode("factory_super_admin");
                em.persist(u);
                ProductType p = new ProductType();
                p.setId(fid + "-PT");
                p.setFactoryId(fid);
                p.setCode(fid + "-C");
                p.setName("产品");
                p.setUnit("袋");
                p.setDailyCapacity(new BigDecimal("100"));
                p.setDailyCapacityUnit("袋");
                em.persist(p);
                em.flush();
                if (fid.equals(factory)) userId = u.getId();
            }
            line1 = line(factory, "一号线", ProductionLine.LineStatus.active);
            line2 = line(factory, "二号线", ProductionLine.LineStatus.active);
            line(factory, "三号线(检修)", ProductionLine.LineStatus.maintenance);
            line(other, "旁厂线", ProductionLine.LineStatus.active);
            planA = productionPlan(factory, "A", line2, userId);
            planB = productionPlan(factory, "B", line1, userId);
        });
    }

    @AfterEach
    void dropReproducedTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS scheduling_alerts_update_timestamp ON scheduling_alerts");
    }

    // ==================== 一、Dashboard ====================

    @Test
    void onlySchedulingPlanRowsAreListedAndCountedWithWorkerStats() {
        String[] ids = tx.execute(s -> {
            SchedulingPlan plan = schedulingPlan(factory, DAY, SchedulingPlan.PlanStatus.draft);
            LineSchedule pending = row(plan, line1, null, null, LineSchedule.ScheduleStatus.pending, 8);
            LineSchedule running = row(plan, line1, null, null, LineSchedule.ScheduleStatus.in_progress, 10);
            row(plan, line2, null, null, LineSchedule.ScheduleStatus.cancelled, 12);   // 已取消: 不算, 也不占线
            assign(pending, 201L, WorkerAssignment.AssignmentStatus.checked_in, false, 80);
            assign(running, 201L, WorkerAssignment.AssignmentStatus.working, false, null);   // 同一人两条: 按人去重
            assign(running, 202L, WorkerAssignment.AssignmentStatus.assigned, true, null);
            // 噪声: 前一天的计划、旁厂同日的计划
            row(schedulingPlan(factory, DAY.minusDays(1), SchedulingPlan.PlanStatus.confirmed),
                    line2, null, null, LineSchedule.ScheduleStatus.in_progress, 9);
            row(schedulingPlan(other, DAY, SchedulingPlan.PlanStatus.confirmed),
                    line2, null, null, LineSchedule.ScheduleStatus.in_progress, 9);
            return new String[]{pending.getId(), running.getId()};
        });

        SchedulingDashboardDTO d = crud.getDashboard(factory, DAY);
        print("ONLY_SCHEDULE", d);

        assertThat(d.getTodaySchedules()).extracting(LineScheduleDTO::getId).containsExactly(ids[0], ids[1]);
        assertThat(d.getTodaySchedules()).extracting(LineScheduleDTO::getSource).containsOnly("SCHEDULE");
        assertThat(d.getTodaySchedules()).extracting(LineScheduleDTO::getProductionLineName).containsOnly("一号线");
        assertThat(d.getTotalSchedules()).isEqualTo(2);
        assertThat(d.getPendingSchedules()).isEqualTo(1);
        assertThat(d.getInProgressSchedules()).isEqualTo(1);
        assertThat(d.getPlanStats().getTotalPlans()).isEqualTo(1);
        assertThat(d.getPlanStats().getDraftPlans()).isEqualTo(1);
        assertThat(d.getLineStats().getActiveLines()).as("检修线不算启用").isEqualTo(2);
        assertThat(d.getLineStats().getOccupiedLines()).isEqualTo(1);
        assertThat(d.getLineStats().getUtilizationRate()).isEqualByComparingTo("0.5");
        assertThat(d.getWorkerStats().getTotalAssigned()).isEqualTo(2);
        assertThat(d.getWorkerStats().getCheckedIn()).isEqualTo(1);
        assertThat(d.getWorkerStats().getTemporaryWorkers()).isEqualTo(1);
        assertThat(d.getWorkerStats().getAveragePerformance()).isEqualByComparingTo("80.0");
        assertThat(d.getLineStats().getAverageEfficiency()).as("量不到就是 null, 不是 0").isNull();
        assertThat(d.getUnavailableReasons()).containsKey("lineStats.averageEfficiency")
                .doesNotContainKey("workerStats.workflowBatches");
    }

    @Test
    void onlyWorkflowInProgressBatchesShowUpAsTodaysScheduleWithoutAnySchedulingPlan() {
        Long[] ids = tx.execute(s -> {
            Long running = batch(factory, planA, ProductionBatchStatus.IN_PROGRESS, DAY.atTime(8, 0), "REGULAR");
            Long legacy = batch(factory, null, ProductionBatchStatus.PRODUCING, DAY.minusDays(1).atTime(20, 0), "REGULAR");
            // 不该出现的: 中间工件 / 已完工 / 暂停 / 明天才开工 / 旁厂
            batch(factory, planB, ProductionBatchStatus.IN_PROGRESS, DAY.atTime(9, 0), "CLERK_WIP");
            batch(factory, planB, ProductionBatchStatus.COMPLETED, DAY.atTime(7, 0), "REGULAR");
            batch(factory, planB, ProductionBatchStatus.PAUSED, DAY.atTime(7, 0), "REGULAR");
            batch(factory, planB, ProductionBatchStatus.IN_PROGRESS, DAY.plusDays(1).atTime(6, 0), "REGULAR");
            batch(other, null, ProductionBatchStatus.IN_PROGRESS, DAY.atTime(8, 0), "REGULAR");
            return new Long[]{legacy, running};
        });

        SchedulingDashboardDTO d = crud.getDashboard(factory, DAY);
        print("ONLY_WORKFLOW", d);

        assertThat(d.getTodaySchedules()).extracting(LineScheduleDTO::getBatchId).containsExactly(ids[0], ids[1]);
        assertThat(d.getTodaySchedules()).extracting(LineScheduleDTO::getSource).containsOnly("WORKFLOW_BATCH");
        assertThat(d.getTodaySchedules()).extracting(LineScheduleDTO::getStatus).containsOnly("in_progress");
        LineScheduleDTO fromPlanA = d.getTodaySchedules().get(1);
        assertThat(fromPlanA.getId()).isEqualTo("batch-" + ids[1]);
        assertThat(fromPlanA.getProductionLineName()).as("产线取自所属计划的建议产线").isEqualTo("二号线");
        assertThat(fromPlanA.getPlanNumber()).isEqualTo(factory + "-PLAN-A");
        assertThat(fromPlanA.getPlannedQuantity()).as("单位可能不一致, 不填数量").isNull();
        assertThat(d.getTotalSchedules()).isEqualTo(2);
        assertThat(d.getInProgressSchedules()).isEqualTo(2);
        assertThat(d.getPlanStats().getTotalPlans()).as("工作流批次不是调度计划").isZero();
        assertThat(d.getLineStats().getOccupiedLines()).isEqualTo(1);
        assertThat(d.getLineStats().getUtilizationRate()).isEqualByComparingTo("0.5");
        assertThat(d.getWorkerStats().getTotalAssigned()).isZero();
        assertThat(d.getWorkerStats().getAveragePerformance()).isNull();
        assertThat(d.getUnavailableReasons()).containsKeys("workerStats.workflowBatches",
                "workerStats.averagePerformance");
    }

    @Test
    void scheduleRowsAndWorkflowBatchesTogetherCountTheSameWorkOnlyOnce() {
        Object[] ids = tx.execute(s -> {
            Long b1 = batch(factory, planA, ProductionBatchStatus.IN_PROGRESS, DAY.atTime(8, 0), "REGULAR");
            Long b2 = batch(factory, planB, ProductionBatchStatus.IN_PROGRESS, DAY.atTime(8, 30), "REGULAR");
            Long b3 = batch(factory, null, ProductionBatchStatus.IN_PROGRESS, DAY.atTime(9, 0), "REGULAR");
            SchedulingPlan plan = schedulingPlan(factory, DAY, SchedulingPlan.PlanStatus.in_progress);
            LineSchedule r1 = row(plan, line1, b1, planA, LineSchedule.ScheduleStatus.in_progress, 8);   // 挂着 b1
            LineSchedule r2 = row(plan, line1, null, planB, LineSchedule.ScheduleStatus.pending, 11);    // 按计划 B 排, 没挂批次
            return new Object[]{r1.getId(), r2.getId(), b1, b2, b3};
        });

        SchedulingDashboardDTO d = crud.getDashboard(factory, DAY);
        print("BOTH", d);

        assertThat(d.getTodaySchedules()).extracting(LineScheduleDTO::getId)
                .containsExactly((String) ids[0], (String) ids[1], "batch-" + ids[4]);
        assertThat(d.getTodaySchedules()).extracting(LineScheduleDTO::getBatchId)
                .as("b1 已被 r1 引用, b2 的计划已被 r2 排上: 都不再单列")
                .doesNotContain((Long) ids[3]).containsOnlyOnce((Long) ids[2]);
        assertThat(d.getTotalSchedules()).isEqualTo(3);
        assertThat(d.getInProgressSchedules()).as("r1 + b3").isEqualTo(2);
        assertThat(d.getPendingSchedules()).isEqualTo(1);
        assertThat(d.getPlanStats().getInProgressPlans()).isEqualTo(1);
        // b1 所属计划 A 建议二号线, 但 b1 实际排在一号线 (r1): 去重后二号线不算占用
        assertThat(d.getLineStats().getOccupiedLines()).isEqualTo(1);
        assertThat(d.getLineStats().getUtilizationRate()).isEqualByComparingTo("0.5");
    }

    @Test
    void noActiveLineMeansUtilizationIsUnknownNotZero() {
        jdbc.update("update production_lines set status = 'maintenance' where factory_id = ?", factory);

        SchedulingDashboardDTO d = crud.getDashboard(factory, DAY);
        print("NO_LINES", d);

        assertThat(d.getLineStats().getActiveLines()).isZero();
        assertThat(d.getLineStats().getUtilizationRate()).isNull();
        assertThat(d.getUnavailableReasons()).containsKey("lineStats.utilizationRate");
        assertThat(d.getTodaySchedules()).isEmpty();
    }

    // ==================== 二、预警处理 (scheduling_alerts 上的旧触发器) ====================

    @Test
    void resolveFailsOnTheProdTriggerShapeBeforeTheMigration() {
        installProdTrigger();
        String alertId = alert(factory);

        assertThatThrownBy(() -> crud.resolveAlert(factory, alertId, 7L, "已处理"))
                .satisfies(e -> assertThat(rootMessage(e)).contains("updated_at"));
        assertThat(jdbc.queryForObject("select is_resolved from scheduling_alerts where id = ?", Boolean.class, alertId))
                .isFalse();
    }

    @Test
    void resolveSucceedsAfterTheMigrationWhichIsIdempotent() throws IOException {
        installProdTrigger();
        String alertId = alert(factory);
        String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        jdbc.execute(sql);
        jdbc.execute(sql);   // 幂等: 第二遍什么也不做

        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid = 'scheduling_alerts'::regclass "
                + "and not tgisinternal", Integer.class)).isZero();
        SchedulingAlertDTO resolved = crud.resolveAlert(factory, alertId, 7L, "已处理");
        assertThat(resolved.getIsResolved()).isTrue();
        var row = jdbc.queryForMap("select is_resolved, resolved_by, resolution_notes from scheduling_alerts where id = ?",
                alertId);
        System.out.println("SCHED_ALERT_RESOLVED " + row);
        assertThat(row.get("is_resolved")).isEqualTo(true);
        assertThat(((Number) row.get("resolved_by")).longValue()).isEqualTo(7L);
        assertThat(row.get("resolution_notes")).isEqualTo("已处理");
    }

    // ==================== fixtures ====================

    /** prod 实况: 共用函数写 NEW.updated_at, scheduling_alerts 上挂着它 (而这张表没有 updated_at)。 */
    private void installProdTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION update_timestamp() RETURNS TRIGGER AS $$
                BEGIN NEW.updated_at = CURRENT_TIMESTAMP; RETURN NEW; END; $$ LANGUAGE plpgsql""");
        jdbc.execute("DROP TRIGGER IF EXISTS scheduling_alerts_update_timestamp ON scheduling_alerts");
        jdbc.execute("CREATE TRIGGER scheduling_alerts_update_timestamp BEFORE UPDATE ON scheduling_alerts "
                + "FOR EACH ROW EXECUTE FUNCTION update_timestamp()");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = "
                + "'scheduling_alerts' and column_name = 'updated_at'", Integer.class)).as("与 prod 一致: 没有该列").isZero();
    }

    private String alert(String fid) {
        return tx.execute(s -> {
            SchedulingAlert a = new SchedulingAlert();
            a.setFactoryId(fid);
            a.setAlertType(SchedulingAlert.AlertType.deadline_risk);
            a.setSeverity(SchedulingAlert.Severity.critical);
            a.setMessage("交期风险");
            em.persist(a);
            return a.getId();
        });
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        StringBuilder all = new StringBuilder();
        while (t != null) {
            all.append(t.getMessage()).append(" | ");
            t = t.getCause() == t ? null : t.getCause();
        }
        return all.toString();
    }

    private String line(String fid, String name, ProductionLine.LineStatus status) {
        ProductionLine l = new ProductionLine();
        l.setFactoryId(fid);
        l.setName(name);
        l.setStatus(status);
        l.setHourlyCapacity(new BigDecimal("100"));
        em.persist(l);
        em.flush();
        return l.getId();
    }

    private String productionPlan(String fid, String code, String suggestedLine, Long userId) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(fid + "-PP-" + code);
        plan.setFactoryId(fid);
        plan.setPlanNumber(fid + "-PLAN-" + code);
        plan.setProductTypeId(fid + "-PT");
        plan.setPlannedQuantity(new BigDecimal("50"));
        plan.setPlannedUnit("袋");
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setSourceType(PlanSourceType.MANUAL);
        plan.setCreatedBy(userId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setSuggestedProductionLineId(suggestedLine);
        em.persist(plan);
        return plan.getId();
    }

    private SchedulingPlan schedulingPlan(String fid, LocalDate day, SchedulingPlan.PlanStatus status) {
        SchedulingPlan p = new SchedulingPlan();
        p.setFactoryId(fid);
        p.setPlanDate(day);
        p.setPlanName(fid + " " + day);
        p.setStatus(status);
        em.persist(p);
        return p;
    }

    private LineSchedule row(SchedulingPlan plan, String lineId, Long batchId, String productionPlanId,
                             LineSchedule.ScheduleStatus status, int hour) {
        LineSchedule r = new LineSchedule();
        r.setPlan(plan);
        r.setProductionLineId(lineId);
        r.setBatchId(batchId);
        r.setProductionPlanId(productionPlanId);
        r.setStatus(status);
        r.setPlannedStartTime(plan.getPlanDate().atTime(hour, 0));
        r.setPlannedEndTime(plan.getPlanDate().atTime(hour + 1, 0));
        r.setPlannedQuantity(10);
        em.persist(r);
        em.flush();
        return r;
    }

    private void assign(LineSchedule schedule, Long userId, WorkerAssignment.AssignmentStatus status,
                        boolean temporary, Integer score) {
        WorkerAssignment a = new WorkerAssignment();
        a.setSchedule(schedule);
        a.setUserId(userId);
        a.setStatus(status);
        a.setIsTemporary(temporary);
        a.setPerformanceScore(score);
        em.persist(a);
    }

    private Long batch(String fid, String planId, ProductionBatchStatus status, LocalDateTime start, String type) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(fid);
        b.setBatchNumber("SD-" + UUID.randomUUID().toString().substring(0, 8));
        b.setProductTypeId(fid + "-PT");
        b.setQuantity(new BigDecimal("50"));
        b.setUnit("袋");
        b.setStatus(status);
        b.setStartTime(start);
        b.setProductionPlanId(planId);
        b.setBatchType(type);
        em.persist(b);
        em.flush();
        return b.getId();
    }

    private static void print(String tag, SchedulingDashboardDTO d) {
        System.out.println("SCHED_DASH_" + tag + " rows=" + d.getTodaySchedules().stream()
                .map(r -> r.getSource() + ":" + r.getId() + ":" + r.getBatchId() + ":" + r.getStatus()).toList()
                + " total=" + d.getTotalSchedules() + " inProgress=" + d.getInProgressSchedules()
                + " lines=" + d.getLineStats() + " workers=" + d.getWorkerStats()
                + " plans=" + d.getPlanStats() + " reasons=" + d.getUnavailableReasons().keySet());
    }
}
