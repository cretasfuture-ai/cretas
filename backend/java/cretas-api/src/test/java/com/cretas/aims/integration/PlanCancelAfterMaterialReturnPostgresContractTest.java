package com.cretas.aims.integration;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.production.SecondaryPlanFeedService;
import com.cretas.aims.service.production.SettlementSemiStockingService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真 PostgreSQL + 真 Hibernate: 报工全部撤回后, 计划能不能取消 (2026-11-10 F006 计划 f86d2c6d)。
 *
 * <p>现场: 报工撤回把真实消耗软删了, 随后关领料单写了两条退料流水 (source_type=MATERIAL_RETURN, 数量为负,
 * production_plan_id 指向本计划)。取消仍 409 PLAN_HAS_PRODUCTION_DATA, 提示却说「撤回完成后即可取消」。
 *
 * <p>为什么要真库: 「撤回后还剩什么」取决于 {@code @Where(deleted_at IS NULL)} 软删过滤和按计划查流水的
 * 真实查询, Mockito 直接给列表就绕过了这两层。
 *
 * <p>开关同 {@link SecondaryHoldPostgresContractTest}, 跑在 java-gate-pr 的 PostgreSQL 车道。
 */
@SpringBootTest(classes = PlanCancelAfterMaterialReturnPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class PlanCancelAfterMaterialReturnPostgresContractTest {

    private static final String F = "PCMR-PG";
    /** 与 FactoryMaterialRequisitionServiceImpl 写入的退料流水来源同值 (字面量: 流水落库的就是这个串)。 */
    private static final String MATERIAL_RETURN = "MATERIAL_RETURN";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({ProductionPlanServiceImpl.class, WipInventoryServiceImpl.class,
            SecondaryPlanFeedService.class, SettlementSemiStockingService.class,
            com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl.class,
            com.cretas.aims.service.scheduling.SchedulingResourceWriteLock.class,
            // 取消之后收掉名下排程的级联 (#1579), 经切面挂在取消入口上 —— 与生产同一条接线
            com.cretas.aims.service.scheduling.CancelledPlanScheduleCascade.class,
            com.cretas.aims.service.scheduling.CancelledPlanScheduleCascadeAspect.class,
            // 开工: 与取消级联同一加锁顺序 (日计划 → 排程行)
            com.cretas.aims.service.scheduling.impl.LineScheduleServiceImpl.class})
    @org.springframework.context.annotation.EnableAspectJAutoProxy
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

    @MockBean ProductionPlanMapper planMapper;
    @MockBean SchedulingService schedulingService;
    @MockBean ExcelUtil excelUtil;
    @MockBean InventoryLowStockEventPublisher lowStockEvents;
    @MockBean ProductFamilyResolver familyResolver;
    @MockBean UnitContractService unitContractService;
    @MockBean IntermediateOwnershipGuard ownershipGuard;
    @MockBean com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;
    @MockBean com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope processSecretLlmCostScope;
    @MockBean com.cretas.aims.service.finance.ProductionCostConsistency productionCostConsistency;
    @MockBean com.cretas.aims.service.finance.ReturnedCostSynchronizationService returnedCostSynchronizationService;
    // 排程落库 (SchedulingPlanCrudServiceImpl) 的通知 / 懒加载依赖, 与 CancelledDayPlanReopenPostgresContractTest 同
    @MockBean com.cretas.aims.service.NotificationService notificationService;
    @MockBean com.cretas.aims.service.PushNotificationService pushNotificationService;
    @MockBean com.cretas.aims.service.scheduling.core.WorkerAssignmentService workerAssignmentService;
    @MockBean com.cretas.aims.service.scheduling.core.SchedulingAIService schedulingAIService;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ProductionPlanService planService;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductionBatchRepository batches;
    @Autowired MaterialConsumptionRepository consumptions;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
    }

    /** 已开工计划 + 一个报工批次 + 一笔报工消耗 (带计划 id) + 关领料单写的两条退料流水。 */
    private record Fixture(String planId, Integer reportConsumptionId) {
    }

    private Fixture startedPlanWithReportConsumptionAndReturns() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(state -> {
            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("PCMR-" + suffix);
            plan.setProductTypeId("FG-1");
            plan.setPlannedQuantity(BigDecimal.ONE);
            plan.setPlannedUnit("盒");
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setCreatedBy(7L);
            plan.setPlanSourceType("NORMAL");
            plan.setSourceType(PlanSourceType.SAFETY_STOCK);   // 存货生产: 停产也走得到同一判据
            plans.save(plan);

            ProductionBatch batch = new ProductionBatch();
            batch.setFactoryId(F);
            batch.setBatchNumber("PB-" + suffix);
            batch.setProductTypeId("FG-1");
            batch.setQuantity(BigDecimal.ONE);
            batch.setUnit("盒");
            batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
            batch.setProductionPlanId(plan.getId());
            batch = batches.save(batch);

            MaterialConsumption used = consumption(plan.getId(), batch.getId(), "1.000", "RAW_MATERIAL", "报工消耗");
            consumptions.save(consumption(plan.getId(), null, "-1.200",
                    MATERIAL_RETURN,
                    "production material return: requisition=R1, item=I1"));
            consumptions.save(consumption(plan.getId(), null, "-1.000",
                    MATERIAL_RETURN,
                    "production material return: requisition=R1, item=I2"));
            return new Fixture(plan.getId(), consumptions.save(used).getId());
        });
    }

    private MaterialConsumption consumption(String planId, Long batchId, String qty, String sourceType, String notes) {
        BigDecimal quantity = new BigDecimal(qty);
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(F);
        c.setProductionPlanId(planId);
        c.setProductionBatchId(batchId);
        c.setBatchId("MB-1");
        c.setQuantity(quantity);
        c.setUnitPrice(new BigDecimal("10.00"));
        c.setTotalCost(quantity.multiply(new BigDecimal("10.00")));
        c.setConsumptionTime(LocalDateTime.now());
        c.setConsumedAt(LocalDateTime.now());
        c.setRecordedBy(7L);
        c.setMaterialTypeId("MT-1");
        c.setSourceType(sourceType);
        c.setNotes(notes);
        return c;
    }

    /** 报工撤回对消耗的真实动作: 软删 (deleted_at), 不是物理删除。 */
    private void reverseReportConsumption(Fixture f) {
        jdbc.update("update material_consumptions set deleted_at = now() where id = ?", f.reportConsumptionId());
    }

    private String status(Fixture f) {
        return jdbc.queryForObject("select status from production_plans where id = ?", String.class, f.planId());
    }

    private int liveReturnRows(Fixture f) {
        return jdbc.queryForObject("select count(*) from material_consumptions where production_plan_id = ? "
                + "and source_type = 'MATERIAL_RETURN' and deleted_at is null", Integer.class, f.planId());
    }

    @Test
    void afterAllReportsReversedAndRequisitionClosedThePlanCanBeCancelled() {
        Fixture f = startedPlanWithReportConsumptionAndReturns();
        reverseReportConsumption(f);
        assertThat(liveReturnRows(f)).as("前提: 先关领料单, 两条退料流水仍在").isEqualTo(2);

        planService.cancelProductionPlan(F, f.planId(), "验收计划撤回后取消", 7L);

        assertThat(status(f)).isEqualTo("CANCELLED");
        assertThat(liveReturnRows(f)).as("取消不碰退料流水").isEqualTo(2);
    }

    @Test
    void unreversedReportConsumptionStillBlocksAndSaysWhatRemains() {
        Fixture f = startedPlanWithReportConsumptionAndReturns();

        assertThatThrownBy(() -> planService.cancelProductionPlan(F, f.planId(), "想直接取消", 7L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("PLAN_HAS_PRODUCTION_DATA");
                    assertThat(ex.getMessage())
                            .as("说清还剩哪类: 只数那一笔真实消耗, 两条退料流水不计入")
                            .contains("1 笔未冲销的物料消耗");
                });
        assertThat(status(f)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void returnRowsAloneAreNotProductionActivityForStopEither() {
        Fixture f = startedPlanWithReportConsumptionAndReturns();
        reverseReportConsumption(f);

        assertThatThrownBy(() -> planService.stopProduction(F, f.planId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).as("停产与取消同一判据: 只剩退料 = 空计划, 导向取消")
                                .isEqualTo("PLAN_HAS_NO_PRODUCTION_ACTIVITY"));
        assertThat(status(f)).isEqualTo("IN_PROGRESS");
    }

    // ---------------------------------------------------------------------------------------------
    // 取消生产计划时, 它的排程行 (2026-11-10 F006 PP-AUTO-20261110-0131 现场):
    // 计划已取消, 排程行仍是 pending 留在草稿日调度计划里, 生成排程报「被它排满」, 插单却说「已取消, 不排」。
    // ---------------------------------------------------------------------------------------------

    @Autowired com.cretas.aims.repository.SchedulingPlanRepository schedulingPlans;
    @Autowired com.cretas.aims.repository.LineScheduleRepository lineSchedules;
    @Autowired com.cretas.aims.repository.scheduling.ApsConstraintQueryRepository constraintQueries;

    private String plan(ProductionPlanStatus status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(state -> {
            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("PCSR-" + suffix);
            plan.setProductTypeId("FG-1");
            plan.setPlannedQuantity(new BigDecimal("200"));
            plan.setPlannedUnit("袋");
            plan.setStatus(status);
            plan.setCreatedBy(7L);
            plan.setPlanSourceType("NORMAL");
            plan.setSourceType(PlanSourceType.SAFETY_STOCK);
            plan.setIsLocked(false);
            return plans.save(plan).getId();
        });
    }

    private String dayPlan(String factory, java.time.LocalDate day) {
        return tx.execute(state -> {
            com.cretas.aims.entity.SchedulingPlan c = new com.cretas.aims.entity.SchedulingPlan();
            c.setFactoryId(factory);
            c.setPlanDate(day);
            c.setPlanName(day + " 调度计划");
            c.setCreatedBy(7L);
            c.setStatus(com.cretas.aims.entity.SchedulingPlan.PlanStatus.draft);
            return schedulingPlans.saveAndFlush(c).getId();
        });
    }

    private String row(String dayPlanId, String productionPlanId, com.cretas.aims.entity.LineSchedule.ScheduleStatus st) {
        return tx.execute(state -> {
            com.cretas.aims.entity.SchedulingPlan c = schedulingPlans.findById(dayPlanId).orElseThrow();
            com.cretas.aims.entity.LineSchedule ls = new com.cretas.aims.entity.LineSchedule();
            ls.setPlan(c);
            ls.setProductionLineId("LINE-1");
            ls.setProductionPlanId(productionPlanId);
            ls.setSequenceOrder(0);
            ls.setPlannedStartTime(c.getPlanDate().atStartOfDay());
            ls.setPlannedEndTime(c.getPlanDate().plusDays(24).atTime(23, 59));
            ls.setPlannedQuantity(100);
            ls.setStatus(st);
            if (st == com.cretas.aims.entity.LineSchedule.ScheduleStatus.in_progress) {
                ls.setActualStartTime(c.getPlanDate().atTime(7, 0));
            }
            return lineSchedules.saveAndFlush(ls).getId();
        });
    }

    private void assign(String rowId, long userId) {
        jdbc.update("insert into worker_assignments (id, schedule_id, user_id, status, is_temporary) "
                + "values (?, ?, ?, 'assigned', false)", UUID.randomUUID().toString(), rowId, userId);
    }

    private String rowStatus(String rowId) {
        return jdbc.queryForObject("select status from line_schedules where id = ?", String.class, rowId);
    }

    private java.util.Map<String, Object> totals(String dayPlanId) {
        return jdbc.queryForMap("select total_batches, total_workers from scheduling_plans where id = ?", dayPlanId);
    }

    @Test
    void cancellingAPlanVoidsItsNotStartedRowsAndRecountsTheDayPlan() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 12);
        String cancelled = plan(ProductionPlanStatus.PENDING);
        String other = plan(ProductionPlanStatus.PENDING);
        String dayPlan = dayPlan(F, day);
        String mine = row(dayPlan, cancelled, com.cretas.aims.entity.LineSchedule.ScheduleStatus.pending);
        String theirs = row(dayPlan, other, com.cretas.aims.entity.LineSchedule.ScheduleStatus.pending);
        assign(mine, 101);
        // 另一天: 这个计划已开工的一条 (阴性对照: 已开工不动)
        String startedDay = dayPlan(F, day.plusDays(1));
        String started = row(startedDay, cancelled, com.cretas.aims.entity.LineSchedule.ScheduleStatus.in_progress);
        // 别的工厂指向同一计划 id 的一条 (阴性对照: 只动本厂)
        String elsewhere = row(dayPlan("PCSR-OTHER", day), cancelled, com.cretas.aims.entity.LineSchedule.ScheduleStatus.pending);
        jdbc.update("update scheduling_plans set total_batches = 2, total_workers = 1 where id = ?", dayPlan);
        // 第三天: 待开始超时被标成延误、但没实际开工的一条 (延误 ≠ 开工, 也要作废; 两个日调度计划都要重算)
        String delayedDay = dayPlan(F, day.plusDays(2));
        String delayed = row(delayedDay, cancelled, com.cretas.aims.entity.LineSchedule.ScheduleStatus.delayed);
        jdbc.update("update scheduling_plans set total_batches = 1, total_workers = 0 where id = ?", delayedDay);

        planService.cancelProductionPlan(F, cancelled, "QA 改量重测", 7L);

        assertThat(rowStatus(delayed)).as("延误但没开工的也作废").isEqualTo("cancelled");
        assertThat(((Number) totals(delayedDay).get("total_batches")).intValue()).as("第二个日调度计划也重算").isZero();

        assertThat(status(new Fixture(cancelled, null))).isEqualTo("CANCELLED");
        assertThat(rowStatus(mine)).as("没开工的排程随计划作废, 不再占产线").isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("select adjustment_reason from line_schedules where id = ?", String.class, mine))
                .contains("生产计划已取消");
        assertThat(rowStatus(theirs)).as("同一天别的计划的排程不动").isEqualTo("pending");
        assertThat(rowStatus(started)).as("已开工的不动").isEqualTo("in_progress");
        assertThat(rowStatus(elsewhere)).as("别的工厂不动").isEqualTo("pending");
        java.util.Map<String, Object> t = totals(dayPlan);
        System.out.println("PLAN_CANCEL_DAY_TOTALS " + t);
        assertThat(((Number) t.get("total_batches")).intValue()).as("只剩别人那一条").isEqualTo(1);
        assertThat(((Number) t.get("total_workers")).intValue()).as("派在作废排程上的人不算").isEqualTo(0);
        // 生成排程读的占用 (与 ScheduleConstraintReader 同一查询) 里不再有它
        List<String> occupying = tx.execute(state -> constraintQueries.findLiveEndingFrom(F, day.atStartOfDay()).stream()
                .filter(ls -> cancelled.equals(ls.getProductionPlanId()))
                .map(com.cretas.aims.entity.LineSchedule::getId)
                .toList());
        assertThat(occupying).as("这个计划还占着产线的只剩已开工那条").containsExactly(started);
    }

    @Test
    void cancellingAnAlreadyCancelledPlanAgainVoidsRowsLeftBehindBeforeTheFix() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 17);
        // 修复前取消的计划: 计划已取消, 排程行仍 pending (现场 0131 的样子)
        String cancelled = plan(ProductionPlanStatus.CANCELLED);
        String dayPlan = dayPlan(F, day);
        String leftover = row(dayPlan, cancelled, com.cretas.aims.entity.LineSchedule.ScheduleStatus.pending);
        jdbc.update("update scheduling_plans set total_batches = 1, total_workers = 0 where id = ?", dayPlan);
        String notesBefore = jdbc.queryForObject("select coalesce(notes, '') from production_plans where id = ?",
                String.class, cancelled);

        planService.cancelProductionPlan(F, cancelled, "再点一次取消", 7L);

        assertThat(rowStatus(leftover)).as("再取消一次, 把遗留的排程补作废").isEqualTo("cancelled");
        assertThat(((Number) totals(dayPlan).get("total_batches")).intValue()).isEqualTo(0);
        assertThat(jdbc.queryForObject("select coalesce(notes, '') from production_plans where id = ?",
                String.class, cancelled)).as("幂等: 不追加取消原因").isEqualTo(notesBefore);
    }

    // ------------------------------------------------ 并发: 取消生产计划 与 生成排程落库 同时进行

    @Autowired com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl scheduleCrud;
    @Autowired jakarta.persistence.EntityManager em;

    private String activeLine() {
        return tx.execute(state -> {
            com.cretas.aims.entity.ProductionLine line = new com.cretas.aims.entity.ProductionLine();
            line.setFactoryId(F);
            line.setName("一号线-" + UUID.randomUUID().toString().substring(0, 6));
            line.setStatus(com.cretas.aims.entity.ProductionLine.LineStatus.active);
            line.setHourlyCapacity(new BigDecimal("999"));
            em.persist(line);
            em.flush();
            return line.getId();
        });
    }

    /** 生成排程最后一步 (引擎读完计划之后的落库), 给这个计划在这一天写一条排程。 */
    private String saveSchedule(String lineId, String productionPlanId, java.time.LocalDate day) {
        com.cretas.aims.dto.scheduling.AutoScheduleItem item = new com.cretas.aims.dto.scheduling.AutoScheduleItem(
                lineId, productionPlanId, null, 0, day.atStartOfDay(), day.plusDays(1).atTime(23, 59), 200,
                java.util.Map.of("days", List.of()));
        return scheduleCrud.createAutoSchedulePlan(F, day, null, null, List.of(item), 7L).getId();
    }

    @Test
    void scheduleSaveWaitingOnACancelInFlightIsRejectedAndLeavesNoRowBehind() throws Exception {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 12, 1);
        String planId = plan(ProductionPlanStatus.PENDING);
        String lineId = activeLine();

        List<String> outcome = holdThenRace(() -> {
            planService.cancelProductionPlan(F, planId, "并发: 先取消", 7L);
            return "cancelled";
        }, () -> saveSchedule(lineId, planId, day));

        System.out.println("CANCEL_FIRST_RACE " + outcome);
        assertThat(outcome.get(0)).isEqualTo("cancelled");
        assertThat(outcome.get(1)).as("等到的是已取消: 不再给它写排程").isEqualTo("409");
        assertThat(jdbc.queryForObject("select count(*) from line_schedules where production_plan_id = ? "
                + "and status <> 'cancelled'", Integer.class, planId)).as("已取消的计划名下没有在用的排程").isZero();
    }

    @Test
    void cancelWaitingOnAScheduleSaveInFlightVoidsTheRowItJustWrote() throws Exception {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 12, 3);
        String planId = plan(ProductionPlanStatus.PENDING);
        String lineId = activeLine();

        List<String> outcome = holdThenRace(() -> saveSchedule(lineId, planId, day), () -> {
            planService.cancelProductionPlan(F, planId, "并发: 后取消", 7L);
            return "cancelled";
        });

        System.out.println("SAVE_FIRST_RACE " + outcome);
        String dayPlan = outcome.get(0);
        assertThat(outcome.get(1)).isEqualTo("cancelled");
        assertThat(jdbc.queryForList("select status from line_schedules where production_plan_id = ?",
                String.class, planId)).as("取消等到排程提交后, 把它刚写的那条作废").containsExactly("cancelled");
        assertThat(((Number) totals(dayPlan).get("total_batches")).intValue()).isZero();
    }

    @Autowired com.cretas.aims.service.scheduling.core.LineScheduleService lineScheduleService;

    /**
     * 开工与取消级联的加锁顺序必须一致 (日计划 → 排程行), 否则两边各拿一把互相等 = 死锁。
     * 持锁方锁住日计划, 并在锁内重算汇总数 (模拟取消级联 / 插单); 此时开工必须卡在日计划上、还没锁排程行,
     * 放行后不能把汇总数写回它锁之前读到的旧值。
     */
    @Test
    void startingARowWaitsOnItsDayPlanBeforeTakingTheRowAndKeepsTotalsWrittenMeanwhile() throws Exception {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 12, 5);
        String planId = plan(ProductionPlanStatus.PENDING);
        String dayPlan = dayPlan(F, day);
        String rowId = row(dayPlan, planId, com.cretas.aims.entity.LineSchedule.ScheduleStatus.pending);
        jdbc.update("update scheduling_plans set status = 'confirmed', total_batches = 1, total_workers = 0 where id = ?",
                dayPlan);

        java.util.concurrent.atomic.AtomicReference<String> rowWhileWaiting = new java.util.concurrent.atomic.AtomicReference<>();
        List<String> outcome = holdThenRace(() -> {
            jdbc.queryForObject("select id from scheduling_plans where id = ? for update", String.class, dayPlan);
            jdbc.update("update scheduling_plans set total_batches = 7, total_workers = 3 where id = ?", dayPlan);
            return "held";
        }, () -> lineScheduleService.startSchedule(F, rowId).getStatus(), () -> rowWhileWaiting.set(run(() -> tx.execute(s -> {
            jdbc.queryForObject("select id from line_schedules where id = ? for update nowait", String.class, rowId);
            return "row-free";
        }))));

        System.out.println("START_LOCK_ORDER rowWhileWaiting=" + rowWhileWaiting.get() + " outcome=" + outcome
                + " day=" + totals(dayPlan));
        assertThat(rowWhileWaiting.get()).as("开工卡在日计划上时还没锁排程行 (先锁行就会和取消级联死锁)")
                .isEqualTo("row-free");
        assertThat(outcome).containsExactly("held", "in_progress");
        assertThat(rowStatus(rowId)).isEqualTo("in_progress");
        assertThat(jdbc.queryForObject("select status from scheduling_plans where id = ?", String.class, dayPlan))
                .as("已确认的日计划开工后进行中").isEqualTo("in_progress");
        assertThat(totals(dayPlan)).as("等锁期间写下的汇总数没被开工的整行保存改回旧值")
                .containsEntry("total_batches", 7).containsEntry("total_workers", 3);
    }

    private List<String> holdThenRace(java.util.function.Supplier<String> holder,
                                      java.util.function.Supplier<String> waiter) throws Exception {
        return holdThenRace(holder, waiter, () -> { });
    }

    /**
     * 持锁方在事务里跑完 holder 但先不提交; 证明等待方卡在库锁上之后 (先跑 whileWaiting 探针) 再放行
     * (同 CancelledDayPlanReopenPostgresContractTest)。返回 [持锁方结果, 等待方结果]; BusinessException 记成它的状态码。
     */
    private List<String> holdThenRace(java.util.function.Supplier<String> holder,
                                      java.util.function.Supplier<String> waiter,
                                      Runnable whileWaiting) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            int waitersBefore = lockWaiters();
            java.util.concurrent.Future<String> h = pool.submit(() -> run(() -> tx.execute(s -> {
                String id = holder.get();
                held.countDown();
                await(release);
                return id;
            })));
            assertThat(held.await(60, java.util.concurrent.TimeUnit.SECONDS)).as("持锁方跑完了").isTrue();
            java.util.concurrent.Future<String> w = pool.submit(() -> run(() -> tx.execute(s -> waiter.get())));
            long deadline = System.currentTimeMillis() + 30_000;
            while (lockWaiters() <= waitersBefore && !w.isDone() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(lockWaiters()).as("等待方确实卡在库锁上 (否则测的不是并发)").isGreaterThan(waitersBefore);
            whileWaiting.run();
            release.countDown();
            return List.of(h.get(60, java.util.concurrent.TimeUnit.SECONDS),
                    w.get(60, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static String run(java.util.function.Supplier<String> body) {
        try {
            return body.get();
        } catch (BusinessException e) {
            return String.valueOf(e.getCode());
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("没有等到放行");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int lockWaiters() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
                + "and wait_event_type = 'Lock'", Integer.class);
    }
}
