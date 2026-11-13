package com.cretas.aims.integration;

import com.cretas.aims.dto.scheduling.CreateSchedulingPlanRequest;
import com.cretas.aims.dto.scheduling.GenerateScheduleRequest;
import com.cretas.aims.dto.scheduling.LineScheduleDTO;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.dto.scheduling.UpdateScheduleRequest;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import com.cretas.aims.service.scheduling.daily.DailyCapacityScheduleGenerator;
import com.cretas.aims.service.scheduling.daily.ExperienceCapacityService;
import com.cretas.aims.service.scheduling.daily.ProductDailyCapacityService;
import com.cretas.aims.service.scheduling.daily.ScheduleConstraintReader;
import com.cretas.aims.service.scheduling.impl.LineScheduleServiceImpl;
import com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl;
import jakarta.persistence.EntityManager;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排程一致性 (首任 organizer 清单两条), 真 PostgreSQL + 真 Hibernate:
 * <ol>
 *   <li>{@code line_schedules} 乐观锁: 两个事务同时改同一条排程, 后提交的拿到冲突 (409「排程已被别人修改，请刷新后再改」),
 *       库里留的是先提交的那份, 不是后写覆盖先写; 客户端回传过期版本号同样被拒。</li>
 *   <li>生成排程锁生产计划: 自动 / AI 生成 (DailyCapacityScheduleGenerator) 与手工新建 (createPlan) 两个入口,
 *       在读完计划之后、写排程之前计划被并发取消 —— 生成方确实卡在计划行锁上 (pg_stat_activity),
 *       取消提交后锁内复核读到已取消, 拒绝 (409 SCHEDULE_PLAN_CLOSED), 一行排程都不写。</li>
 * </ol>
 * 每个并发用例都断言「等待方确实卡在库锁上」, 否则测的不是并发。开关与车道同 {@link CancelledDayPlanReopenPostgresContractTest}。
 */
@SpringBootTest(classes = LineScheduleConsistencyPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        // 与生产 (application-pg-prod.properties / application-pg.properties) 同一套 JDBC 批量写配置: 批量路径上
        // Hibernate 抛的是不带实体名的 StaleStateException, 冲突识别必须在这条路径上验 (独立审查 B1)
        "spring.jpa.properties.hibernate.jdbc.batch_size=100",
        "spring.jpa.properties.hibernate.order_inserts=true",
        "spring.jpa.properties.hibernate.order_updates=true",
        "spring.jpa.properties.hibernate.jdbc.batch_versioned_data=true",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class LineScheduleConsistencyPostgresContractTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 21);
    private static final String CONFLICT = "CONFLICT";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({DailyCapacityScheduleGenerator.class, ExperienceCapacityService.class, ProductDailyCapacityService.class,
            ScheduleConstraintReader.class, SchedulingPlanCrudServiceImpl.class, SchedulingResourceWriteLock.class,
            LineScheduleServiceImpl.class})
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
    @Autowired DailyCapacityScheduleGenerator generator;
    @Autowired SchedulingPlanCrudServiceImpl crud;
    @Autowired LineScheduleServiceImpl schedules;

    private TransactionTemplate tx;
    private String factory;
    private String lineId;
    private Long batchOfPlanA;

    @BeforeEach
    void committedFixture() {
        tx = new TransactionTemplate(transactionManager);
        factory = "LSV-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(s -> {
            Factory f = new Factory();
            f.setId(factory);
            f.setName(factory);
            em.persist(f);
            User u = new User();
            u.setFactoryId(factory);
            u.setUsername("lsv-" + UUID.randomUUID());
            u.setPasswordHash("unused-test-fixture");
            u.setRoleCode("factory_super_admin");
            em.persist(u);
            em.flush();
            ProductionLine line = new ProductionLine();
            line.setFactoryId(factory);
            line.setName("一号线");
            line.setStatus(ProductionLine.LineStatus.active);
            line.setHourlyCapacity(new BigDecimal("999"));
            em.persist(line);
            em.flush();
            lineId = line.getId();
            for (String code : List.of("A", "B")) {
                ProductType p = new ProductType();
                p.setId(factory + "-PT-" + code);
                p.setFactoryId(factory);
                p.setCode(factory + "-C-" + code);
                p.setName("产品" + code);
                p.setUnit("袋");
                p.setDailyCapacity(new BigDecimal("100"));
                p.setDailyCapacityUnit("袋");
                em.persist(p);
                ProductionPlan plan = new ProductionPlan();
                plan.setId(planId(code));
                plan.setFactoryId(factory);
                plan.setPlanNumber(factory + "-PLAN-" + code);
                plan.setProductTypeId(p.getId());
                plan.setPlannedQuantity(new BigDecimal("50"));
                plan.setPlannedUnit("袋");
                plan.setStatus(ProductionPlanStatus.PENDING);
                plan.setSourceType(PlanSourceType.MANUAL);
                plan.setCreatedBy(u.getId());
                plan.setIsLocked(false);
                plan.setSkipProcessReporting(false);
                em.persist(plan);
            }
            ProductionBatch b = new ProductionBatch();
            b.setFactoryId(factory);
            b.setBatchNumber("LSV-" + UUID.randomUUID().toString().substring(0, 8));
            b.setProductTypeId(factory + "-PT-A");
            b.setQuantity(new BigDecimal("50"));
            b.setUnit("袋");
            b.setStatus(ProductionBatchStatus.PLANNED);
            b.setProductionPlanId(planId("A"));
            em.persist(b);
            em.flush();
            batchOfPlanA = b.getId();
        });
    }

    // ==================== 1. line_schedules 乐观锁 ====================

    @Test
    void twoConcurrentEditsOfOneScheduleTheLaterCommitGetsAConflictNotALostUpdate() throws Exception {
        String row = oneGeneratedRow();
        assertThat(version(row)).as("新排程版本号从 0 起").isZero();

        List<String> outcome = holdThenRace(
                () -> schedules.updateSchedule(factory, row, quantity(11, null)).getId(),
                () -> schedules.updateSchedule(factory, row, quantity(22, null)).getId());

        System.out.println("LSV_PG_CONCURRENT_EDIT holder=" + outcome.get(0) + " waiter=" + outcome.get(1)
                + " qty=" + plannedQuantity(row) + " version=" + version(row));
        assertThat(outcome.get(0)).isEqualTo(row);
        assertThat(outcome.get(1)).as("后提交的拿到排程冲突 (→ 409「排程已被别人修改」)").isEqualTo(CONFLICT);
        assertThat(plannedQuantity(row)).as("库里是先提交的那份, 没被后写覆盖").isEqualTo(11);
        assertThat(version(row)).isEqualTo(1L);
    }

    @Test
    void anEditCarryingAStaleVersionIsRejectedAndTheFreshOneGoesThrough() {
        String row = oneGeneratedRow();
        LineScheduleDTO first = schedules.updateSchedule(factory, row, quantity(11, 0L));
        assertThat(first.getVersion()).as("返回的是刷库后的新版本号, 前端下次回传它").isEqualTo(1L);

        String stale = run(() -> schedules.updateSchedule(factory, row, quantity(22, 0L)).getId());
        System.out.println("LSV_PG_STALE_VERSION outcome=" + stale + " qty=" + plannedQuantity(row));
        assertThat(stale).isEqualTo(CONFLICT);
        assertThat(plannedQuantity(row)).isEqualTo(11);

        LineScheduleDTO fresh = schedules.updateSchedule(factory, row, quantity(33, first.getVersion()));
        assertThat(fresh.getVersion()).isEqualTo(2L);
        assertThat(plannedQuantity(row)).isEqualTo(33);
    }

    // ==================== 2. 生成排程锁生产计划 ====================

    @Test
    void autoGenerateWhilePlanIsBeingCancelledWaitsOnThePlanLockAndIsRejected() throws Exception {
        List<String> outcome = holdThenRace(this::cancelPlanA,
                () -> generator.generate(factory, generateFor("A"), 2L).getId());

        System.out.println("LSV_PG_AUTO_GENERATE_VS_CANCEL holder=" + outcome.get(0) + " waiter=" + outcome.get(1)
                + " rows=" + rowsOf(planId("A")));
        assertThat(outcome.get(0)).isEqualTo("CANCELLED");
        assertThat(outcome.get(1)).as("锁内复核读到已取消 → 拒绝").isEqualTo("409");
        assertThat(rowsOf(planId("A"))).as("一行排程都没写").isZero();
    }

    @Test
    void manualCreateWhileTheBatchPlanIsBeingCancelledWaitsOnThePlanLockAndIsRejected() throws Exception {
        List<String> outcome = holdThenRace(this::cancelPlanA,
                () -> crud.createPlan(factory, manualFor(batchOfPlanA), 3L).getId());

        System.out.println("LSV_PG_MANUAL_CREATE_VS_CANCEL holder=" + outcome.get(0) + " waiter=" + outcome.get(1)
                + " rows=" + rowsOfBatch(batchOfPlanA));
        assertThat(outcome.get(0)).isEqualTo("CANCELLED");
        assertThat(outcome.get(1)).as("手工入口与自动入口同一个加锁复核").isEqualTo("409");
        assertThat(rowsOfBatch(batchOfPlanA)).as("一行排程都没写").isZero();
    }

    @Test
    void positiveControlBothEntriesWriteRowsWhenNobodyCancels() {
        SchedulingPlanDTO manual = crud.createPlan(factory, manualFor(batchOfPlanA), 3L);
        assertThat(rowsOfBatch(batchOfPlanA)).isEqualTo(1);
        crud.cancelPlan(factory, manual.getId(), "阳性对照: 腾出这一天");
        generator.generate(factory, generateFor("B"), 2L);
        assertThat(rowsOf(planId("B"))).isEqualTo(1);
    }

    // ==================== helpers ====================

    private String oneGeneratedRow() {
        SchedulingPlanDTO plan = generator.generate(factory, generateFor("A"), 2L);
        return jdbc.queryForObject("select id from line_schedules where plan_id = ?", String.class, plan.getId());
    }

    private String cancelPlanA() {
        jdbc.update("update production_plans set status = 'CANCELLED' where id = ? and factory_id = ?",
                planId("A"), factory);
        return "CANCELLED";
    }

    private String planId(String code) {
        return factory + "-PP-" + code;
    }

    private GenerateScheduleRequest generateFor(String code) {
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(DAY);
        req.setPlanIds(new ArrayList<>(List.of(planId(code))));
        return req;
    }

    private CreateSchedulingPlanRequest manualFor(Long batchId) {
        CreateSchedulingPlanRequest.ScheduleItem item = new CreateSchedulingPlanRequest.ScheduleItem();
        item.setProductionLineId(lineId);
        item.setBatchId(batchId);
        item.setPlannedStartTime(DAY.atTime(8, 0));
        item.setPlannedEndTime(DAY.atTime(12, 0));
        item.setPlannedQuantity(50);
        CreateSchedulingPlanRequest req = new CreateSchedulingPlanRequest();
        req.setPlanDate(DAY);
        req.setSchedules(new ArrayList<>(List.of(item)));
        return req;
    }

    private static UpdateScheduleRequest quantity(int qty, Long version) {
        UpdateScheduleRequest req = new UpdateScheduleRequest();
        req.setPlannedQuantity(qty);
        req.setVersion(version);
        return req;
    }

    private long version(String row) {
        return jdbc.queryForObject("select version from line_schedules where id = ?", Long.class, row);
    }

    private int plannedQuantity(String row) {
        return jdbc.queryForObject("select planned_quantity from line_schedules where id = ?", Integer.class, row);
    }

    private int rowsOf(String productionPlanId) {
        return jdbc.queryForObject("select count(*) from line_schedules where production_plan_id = ?",
                Integer.class, productionPlanId);
    }

    private int rowsOfBatch(Long batchId) {
        return jdbc.queryForObject("select count(*) from line_schedules where batch_id = ? and status <> 'cancelled'",
                Integer.class, batchId);
    }

    /**
     * 持锁方在事务里跑完 holder 但先不提交; 证明等待方卡在库锁上之后再放行。
     * 返回 [持锁方结果, 等待方结果]; BusinessException 记成状态码, 排程版本冲突记成 CONFLICT。
     */
    private List<String> holdThenRace(Supplier<String> holder, Supplier<String> waiter) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            int waitersBefore = lockWaiters();
            Future<String> h = pool.submit(() -> run(() -> tx.execute(s -> {
                String id = holder.get();
                held.countDown();
                await(release);
                return id;
            })));
            assertThat(held.await(60, TimeUnit.SECONDS)).as("持锁方跑完了").isTrue();
            Future<String> w = pool.submit(() -> run(() -> tx.execute(s -> waiter.get())));
            long deadline = System.currentTimeMillis() + 30_000;
            while (lockWaiters() <= waitersBefore && !w.isDone() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(lockWaiters()).as("等待方确实卡在库锁上 (否则测的不是并发)").isGreaterThan(waitersBefore);
            release.countDown();
            return List.of(h.get(60, TimeUnit.SECONDS), w.get(60, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static String run(Supplier<String> body) {
        try {
            return body.get();
        } catch (BusinessException e) {
            return String.valueOf(e.getCode());
        } catch (RuntimeException e) {
            if (GlobalExceptionHandler.isLineScheduleConflict(e)) {
                // 真实抛出的异常交给真实的全局处理器: 用户看到的是 409 + 中文一句, 不是 500 / 通用提示
                var response = new GlobalExceptionHandler().handleOptimisticLockException(e);
                if (Integer.valueOf(409).equals(response.getCode())
                        && GlobalExceptionHandler.LINE_SCHEDULE_CONFLICT_MESSAGE.equals(response.getMessage())) {
                    return CONFLICT;
                }
                return "WRONG_RESPONSE " + response.getCode() + " " + response.getMessage();
            }
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
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
