package com.cretas.aims.service.impl;

import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.Day;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.PlanResult;
import com.cretas.aims.dto.scheduling.SchedulePreviewRequest;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkerAssignment;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.ProductionPlanType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.scheduling.ApsConstraintQueryRepository;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService.Proposal;
import com.cretas.aims.service.scheduling.daily.DailyCapacityScheduleGenerator;
import com.cretas.aims.service.shortage.DeliveryRiskService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 紧急插单「重开已取消的日调度计划 + 确认」在真实 PostgreSQL 上 (缺口 17 补丁):
 * 提交后的行、唯一约束 (factory_id, plan_date)、{@code SELECT ... FOR UPDATE} 行锁、两个插单并发重开同一天。
 *
 * <p>引擎 (缺口 18) 是桩, 其余 (调度计划 / 排程行 / 生产计划 / 约束读取) 都是真实仓库与真实事务。
 * 没有 {@code CRETAS_WORKFLOW_PG_URL} 时整类跳过, 由 CI 的 postgres-reversal-concurrency 作业执行。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
class UrgentInsertScheduleReopenPostgresContractTest {

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProductionPlanRepository plans;
    @Autowired LineScheduleRepository lineSchedules;
    @Autowired SchedulingPlanRepository schedulingPlans;
    @Autowired ApsConstraintQueryRepository constraints;
    @Autowired JdbcTemplate jdbc;

    private String factory;
    private String product;
    private String line;
    private Long actor;
    private LocalDate day;
    private InsertSlot slot;
    private DailyCapacityScheduleGenerator generator;
    private UrgentInsertScheduleService service;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url == null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]*test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only owned loopback *_test PostgreSQL is allowed");
        }
        registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    private void tx(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> action.run());
    }

    @BeforeEach
    void committedFixture() {
        factory = "RO-" + UUID.randomUUID().toString().substring(0, 8);
        product = UUID.randomUUID().toString();
        line = UUID.randomUUID().toString();
        day = LocalDate.now().plusDays(3);
        tx(() -> {
            Factory f = new Factory();
            f.setId(factory);
            f.setName(factory);
            em.persist(f);
            User user = new User();
            user.setFactoryId(factory);
            user.setUsername("ro-" + UUID.randomUUID());
            user.setPasswordHash("unused-test-fixture");
            user.setRoleCode("factory_super_admin");
            em.persist(user);
            em.flush();
            actor = user.getId();
            ProductType p = new ProductType();
            p.setId(product);
            p.setFactoryId(factory);
            p.setCode(product);
            p.setName("reopen output");
            p.setUnit("kg");
            em.persist(p);
            ProductionLine l = new ProductionLine();
            l.setId(line);
            l.setFactoryId(factory);
            l.setName("actual line");
            em.persist(l);
        });
        slot = InsertSlot.builder().id("S-" + UUID.randomUUID()).factoryId(factory).productionLineId(line)
                .startTime(day.atTime(9, 0)).endTime(day.atTime(11, 0)).status("selected").build();
        generator = mock(DailyCapacityScheduleGenerator.class);
        // 引擎桩: 把请求里排第一的 (钉住的) 插单计划排在当天, 每天 50
        when(generator.preview(eq(factory), any(SchedulePreviewRequest.class))).thenAnswer(inv -> {
            SchedulePreviewRequest req = inv.getArgument(1);
            String insertId = req.planIds().get(0);
            return new ConstrainedSchedulePreview("SCHEDULED", line, day, List.of(), List.of(
                    new PlanResult(insertId, insertId, "SCHEDULED",
                            List.of(new Day(day, new BigDecimal("50"), BigDecimal.ONE)), List.of(), List.of(), Map.of())),
                    List.of());
        });
        service = UrgentInsertTestSupport.construct(UrgentInsertScheduleService.class, Map.of(
                DailyCapacityScheduleGenerator.class, generator,
                ApsConstraintQueryRepository.class, constraints,
                ProductionPlanRepository.class, plans,
                LineScheduleRepository.class, lineSchedules,
                SchedulingPlanRepository.class, schedulingPlans,
                DeliveryRiskService.class, mock(DeliveryRiskService.class),
                PlatformTransactionManager.class, transactions));
    }

    // ------------------------------------------------------------------ fixtures

    private ProductionPlan newInsertPlan(String number) {
        ProductionPlan p = new ProductionPlan();
        p.setId("PLAN-" + UUID.randomUUID());
        p.setFactoryId(factory);
        p.setPlanNumber(number);
        p.setProductTypeId(product);
        p.setPlannedQuantity(new BigDecimal("50"));
        p.setPlannedUnit("kg");
        p.setPlannedDate(day);
        p.setBatchDate(day);
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setPlanType(ProductionPlanType.FROM_INVENTORY);
        p.setCreatedBy(actor);
        p.setPriority(9);
        return p;
    }

    private ProductionPlan savedInsertPlan(String number) {
        ProductionPlan p = newInsertPlan(number);
        tx(() -> plans.saveAndFlush(p));
        return p;
    }

    private String cancelledDay(LineSchedule.ScheduleStatus... rowStatuses) {
        String[] containerId = new String[1];
        tx(() -> {
            SchedulingPlan c = new SchedulingPlan();
            c.setFactoryId(factory);
            c.setPlanDate(day);
            c.setPlanName(day + " 原调度计划");
            c.setNotes("原备注");
            c.setCreatedBy(actor);
            c.setConfirmedBy(actor);
            c.setConfirmedAt(java.time.LocalDateTime.now().minusDays(1));
            c.setStatus(SchedulingPlan.PlanStatus.cancelled);
            schedulingPlans.saveAndFlush(c);
            containerId[0] = c.getId();
            int i = 0;
            for (LineSchedule.ScheduleStatus st : rowStatuses) {
                LineSchedule ls = new LineSchedule();
                ls.setPlan(c);
                ls.setProductionLineId(line);
                ls.setProductionPlanId("OLD-" + i);
                ls.setSequenceOrder(i++);
                ls.setPlannedStartTime(day.atStartOfDay());
                ls.setPlannedEndTime(day.atTime(23, 59));
                ls.setPlannedQuantity(100);
                ls.setStatus(st);
                if (st == LineSchedule.ScheduleStatus.in_progress) {
                    ls.setActualStartTime(day.atTime(7, 0));
                }
                lineSchedules.saveAndFlush(ls);
            }
        });
        return containerId[0];
    }

    private String statusOfContainer(String id) {
        return jdbc.queryForObject("SELECT status FROM scheduling_plans WHERE id = ?", String.class, id);
    }

    private long containersOfTheDay() {
        return jdbc.queryForObject("SELECT count(*) FROM scheduling_plans WHERE factory_id = ? AND plan_date = ?",
                Long.class, factory, java.sql.Date.valueOf(day));
    }

    private List<String> rowStatusesOf(String containerId) {
        return jdbc.queryForList("SELECT status FROM line_schedules WHERE plan_id = ? ORDER BY sequence_order, id",
                String.class, containerId);
    }

    private long rowsFor(String productionPlanId, String containerId) {
        return jdbc.queryForObject("SELECT count(*) FROM line_schedules WHERE production_plan_id = ? AND plan_id = ?",
                Long.class, productionPlanId, containerId);
    }

    private static int occurrences(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ 重开 + 确认 (真 PG)

    @Test
    void previewThenConfirmReopensTheCancelledDayAndCommitsTheInsertUnderIt() {
        String containerId = cancelledDay(LineSchedule.ScheduleStatus.pending);
        ProductionPlan insert = newInsertPlan("URGENT-PG-1");

        // 预览: 独立事务建临时计划算完回滚, 一行不留; 取消的日调度计划不再堵死
        Proposal preview = service.dryRun(factory, slot, insert);
        assertThat(preview.ok()).as("预览: " + preview.message()).isTrue();
        assertThat(preview.warnings()).anyMatch(w -> w.contains("重开为草稿"));
        assertThat(statusOfContainer(containerId)).as("预览不改调度计划").isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production_plans WHERE id = ?", Long.class, insert.getId()))
                .as("预览的临时计划已回滚").isZero();

        // 确认: 计划已建 (插单自己的事务), 重算指纹与预览一致, 重开并落库
        tx(() -> plans.saveAndFlush(insert));
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                service.apply(factory, slot, insert, preview.fingerprint(), actor));

        assertThat(containersOfTheDay()).as("同一天仍只有一个调度计划 (唯一约束)").isEqualTo(1);
        assertThat(statusOfContainer(containerId)).isEqualTo("draft");
        String notes = jdbc.queryForObject("SELECT notes FROM scheduling_plans WHERE id = ?", String.class, containerId);
        assertThat(notes).startsWith("原备注\n[重开 ").contains("紧急插单 URGENT-PG-1").contains("操作人 " + actor)
                .contains("作废没开工的旧排程 1 条");
        assertThat(rowStatusesOf(containerId)).as("旧的未开工排程保持取消, 插单排程新建在同一个容器下")
                .containsExactlyInAnyOrder("cancelled", "pending");
        assertThat(rowsFor(insert.getId(), containerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total_batches FROM scheduling_plans WHERE id = ?", Integer.class, containerId))
                .as("重开后按名下排程重算: 作废的旧排程不算, 插单那条算").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT confirmed_by FROM scheduling_plans WHERE id = ?", Long.class, containerId))
                .as("重开为草稿: 旧确认人不留").isNull();
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM scheduling_plans WHERE id = ?", java.sql.Timestamp.class, containerId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT adjustment_reason FROM line_schedules WHERE plan_id = ? AND status = 'cancelled'",
                String.class, containerId)).contains("调度计划取消后重开").contains("URGENT-PG-1");
    }

    /**
     * 缺口 18 后续: 插单重开已取消的那天后, 批次数 / 派工人数按名下剩下的排程现算 (旧代码一直停在取消前的数)。
     * 作废的旧排程和它上面派的人都不算; 新挂上的插单排程算一条。
     */
    @Test
    void reopeningForAnInsertRecountsTheDayPlansBatchesAndWorkers() {
        String containerId = cancelledDay(LineSchedule.ScheduleStatus.pending, LineSchedule.ScheduleStatus.pending);
        tx(() -> {
            for (String rowId : jdbc.queryForList("SELECT id FROM line_schedules WHERE plan_id = ?", String.class,
                    containerId)) {
                WorkerAssignment a = new WorkerAssignment();
                a.setSchedule(em.find(LineSchedule.class, rowId));
                a.setUserId(actor);
                em.persist(a);
            }
            em.flush();
            jdbc.update("UPDATE scheduling_plans SET total_batches = 2, total_workers = 1 WHERE id = ?", containerId);
        });
        ProductionPlan insert = savedInsertPlan("URGENT-PG-T");
        Proposal preview = service.dryRun(factory, slot, insert);
        assertThat(preview.ok()).as("预览: " + preview.message()).isTrue();

        new TransactionTemplate(transactions).executeWithoutResult(status ->
                service.apply(factory, slot, insert, preview.fingerprint(), actor));

        Map<String, Object> totals = jdbc.queryForMap(
                "SELECT total_batches, total_workers FROM scheduling_plans WHERE id = ?", containerId);
        System.out.println("INSERT_REOPEN_TOTALS " + totals + " rows=" + rowStatusesOf(containerId));
        assertThat(rowStatusesOf(containerId)).containsExactlyInAnyOrder("cancelled", "cancelled", "pending");
        assertThat(((Number) totals.get("total_batches")).intValue()).as("只剩插单那一条").isEqualTo(1);
        assertThat(((Number) totals.get("total_workers")).intValue()).as("作废排程上派的人不算").isZero();
    }

    @Test
    void aCancelledDayStillHoldingAStartedRowIsNotReopenedAndNothingIsWritten() {
        String containerId = cancelledDay(LineSchedule.ScheduleStatus.in_progress);
        ProductionPlan insert = savedInsertPlan("URGENT-PG-2");

        Proposal preview = service.dryRun(factory, slot, newInsertPlan("URGENT-PG-2T"));
        assertThat(preview.status()).isEqualTo(UrgentInsertScheduleService.BLOCKED);
        assertThat(preview.message()).contains("已取消").contains("已开工");

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                service.apply(factory, slot, insert, null, actor)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_BLOCKED"));

        assertThat(statusOfContainer(containerId)).isEqualTo("cancelled");
        assertThat(rowStatusesOf(containerId)).containsExactly("in_progress");
        assertThat(rowsFor(insert.getId(), containerId)).isZero();
    }

    @Test
    void twoInsertsReopeningTheSameCancelledDayAtTheSameTimeReopenItExactlyOnce() throws Exception {
        String containerId = cancelledDay(LineSchedule.ScheduleStatus.pending);
        ProductionPlan first = savedInsertPlan("URGENT-PG-A");
        ProductionPlan second = savedInsertPlan("URGENT-PG-B");
        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean sawSomeoneWaitingOnALock = new java.util.concurrent.atomic.AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                service.apply(factory, slot, first, null, actor);
                firstHoldsTheLock.countDown();
                try {
                    // 等第二个插单真的开始、并在数据库里卡在对第一个事务的锁上 (pg_stat_activity 读数, 不靠睡眠猜), 再提交
                    secondStarted.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < 300 && !sawSomeoneWaitingOnALock.get(); i++) {
                        Long waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                                + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Long.class);
                        if (waiting != null && waiting > 0) {
                            sawSomeoneWaitingOnALock.set(true);
                        } else {
                            Thread.sleep(100);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            Future<?> b = pool.submit(() -> {
                try {
                    firstHoldsTheLock.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                secondStarted.countDown();
                new TransactionTemplate(transactions).executeWithoutResult(status ->
                        service.apply(factory, slot, second, null, actor));
            });
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(sawSomeoneWaitingOnALock.get()).as("第二个插单确实被第一个事务的锁挡住过 (否则这条测试什么也没证明)").isTrue();
        assertThat(containersOfTheDay()).isEqualTo(1);
        assertThat(statusOfContainer(containerId)).isEqualTo("draft");
        String notes = jdbc.queryForObject("SELECT notes FROM scheduling_plans WHERE id = ?", String.class, containerId);
        assertThat(occurrences(notes, "[重开 ")).as("只重开一次, 第二个插单看到的是已重开的草稿: " + notes).isEqualTo(1);
        // 谁先拿到行锁谁重开; 无锁时第二个也会重开并把第一个留的痕迹覆盖掉 (丢失更新)
        assertThat(notes).as("痕迹是先拿到锁的那个插单留的, 没被后来的覆盖").contains("URGENT-PG-A").doesNotContain("URGENT-PG-B");
        assertThat(jdbc.queryForObject("SELECT adjustment_reason FROM line_schedules WHERE plan_id = ? AND status = 'cancelled'",
                String.class, containerId)).contains("URGENT-PG-A");
        assertThat(rowsFor(first.getId(), containerId)).isEqualTo(1);
        assertThat(rowsFor(second.getId(), containerId)).isEqualTo(1);
        assertThat(rowStatusesOf(containerId)).containsExactlyInAnyOrder("cancelled", "pending", "pending");
        assertThat(jdbc.queryForObject("SELECT total_batches FROM scheduling_plans WHERE id = ?", Integer.class, containerId))
                .as("两个插单先后挂上, 后一个重算时看得到前一个已提交的那条").isEqualTo(2);
    }
}
