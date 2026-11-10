package com.cretas.aims.integration;

import com.cretas.aims.dto.scheduling.GenerateScheduleRequest;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import com.cretas.aims.service.scheduling.daily.DailyCapacityScheduleGenerator;
import com.cretas.aims.service.scheduling.daily.ExperienceCapacityService;
import com.cretas.aims.service.scheduling.daily.ProductDailyCapacityService;
import com.cretas.aims.service.scheduling.daily.ScheduleConstraintReader;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺口 18 后续, 真 PostgreSQL + 真 Hibernate: 同一天两个「自动排程」同时提交。
 *
 * <p>唯一约束 (factory_id, plan_date) 在库里; 取消只改状态, 所以已取消的那天要靠重开。
 * 每个用例让一方生成后不提交 (持锁), 证明另一方确实卡在库锁上 (pg_stat_activity), 再放行:
 * 先到的成功, 后到的得到同一句 409 (不是 500, 也不是第二次重开), 库里只有一份活排程。
 *
 * <p>开关同 {@link SemiFinishedLockRefreshPostgresContractTest}, 跑在 postgres-reversal-concurrency 车道。
 */
@SpringBootTest(classes = CancelledDayPlanReopenPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class CancelledDayPlanReopenPostgresContractTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 20);

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({DailyCapacityScheduleGenerator.class, ExperienceCapacityService.class, ProductDailyCapacityService.class,
            ScheduleConstraintReader.class, SchedulingPlanCrudServiceImpl.class, SchedulingResourceWriteLock.class})
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

    private TransactionTemplate tx;
    private String factory;

    @BeforeEach
    void committedFixture() {
        tx = new TransactionTemplate(transactionManager);
        factory = "RO-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(s -> {
            Factory f = new Factory();
            f.setId(factory);
            f.setName(factory);
            em.persist(f);
            User u = new User();
            u.setFactoryId(factory);
            u.setUsername("ro-" + UUID.randomUUID());
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
        });
    }

    private String planId(String code) {
        return factory + "-PP-" + code;
    }

    private GenerateScheduleRequest request(String code) {
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(DAY);
        req.setPlanIds(new ArrayList<>(List.of(planId(code))));
        return req;
    }

    @Test
    void twoGeneratesOnACancelledDayReopenItOnceAndTheLaterOneGets409() throws Exception {
        SchedulingPlanDTO first = generator.generate(factory, request("A"), 1L);
        crud.cancelPlan(factory, first.getId(), "统一验收复现");
        String oldRow = jdbc.queryForObject("select id from line_schedules where plan_id = ?", String.class, first.getId());
        // 旧排程上派过一个人, 汇总数停在取消前 (1 条 / 1 人): 重开后要按剩下的排程现算 (缺口 18 后续)
        jdbc.update("insert into worker_assignments (id, schedule_id, user_id, status, is_temporary) "
                + "values (?, ?, 101, 'assigned', false)", java.util.UUID.randomUUID().toString(), oldRow);
        jdbc.update("update scheduling_plans set total_batches = 1, total_workers = 1 where id = ?", first.getId());

        List<String> outcome = holdThenRace(() -> generator.generate(factory, request("A"), 2L).getId(),
                () -> generator.generate(factory, request("B"), 3L).getId());

        System.out.println("REOPEN_PG_CANCELLED holder=" + outcome.get(0) + " waiter=" + outcome.get(1));
        assertThat(outcome.get(0)).isEqualTo(first.getId());
        assertThat(outcome.get(1)).isEqualTo("409");
        assertDay(first.getId(), planId("A"));
        assertThat(jdbc.queryForObject("select status from line_schedules where id = ?", String.class, oldRow))
                .isEqualTo("cancelled");
        String notes = jdbc.queryForObject("select notes from scheduling_plans where id = ?", String.class, first.getId());
        assertThat(notes.split("\\[重开", -1)).as("只重开一次: " + notes).hasSize(2);
        java.util.Map<String, Object> totals = jdbc.queryForMap(
                "select total_batches, total_workers from scheduling_plans where id = ?", first.getId());
        System.out.println("REOPEN_PG_TOTALS " + totals);
        assertThat(((Number) totals.get("total_batches")).intValue()).as("新排的一条").isEqualTo(1);
        assertThat(((Number) totals.get("total_workers")).intValue()).as("作废的旧排程上派的人不算").isZero();
    }

    @Test
    void twoGeneratesOnAFreshDayCreateItOnceAndTheLaterOneGets409NotA500() throws Exception {
        List<String> outcome = holdThenRace(() -> generator.generate(factory, request("A"), 2L).getId(),
                () -> generator.generate(factory, request("B"), 3L).getId());

        System.out.println("REOPEN_PG_FRESH holder=" + outcome.get(0) + " waiter=" + outcome.get(1));
        assertThat(outcome.get(1)).isEqualTo("409");
        assertDay(outcome.get(0), planId("A"));
    }

    /** 这一天恰好一个调度计划 (就是 planId), 草稿; 活的排程恰好一条, 是 livePlan 的。 */
    private void assertDay(String schedulingPlanId, String livePlan) {
        List<Map<String, Object>> plans = jdbc.queryForList(
                "select id, status from scheduling_plans where factory_id = ? and plan_date = ?", factory, DAY);
        assertThat(plans).singleElement().satisfies(p -> {
            assertThat(p.get("id")).isEqualTo(schedulingPlanId);
            assertThat(p.get("status")).isEqualTo("draft");
        });
        assertThat(jdbc.queryForList("select production_plan_id from line_schedules where plan_id = ? "
                + "and status <> 'cancelled'", String.class, schedulingPlanId)).containsExactly(livePlan);
    }

    /**
     * 持锁方在事务里跑完 holder 但先不提交; 证明等待方卡在库锁上之后再放行。
     * 返回 [持锁方结果, 等待方结果]; BusinessException 记成它的状态码。
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
            assertThat(held.await(60, TimeUnit.SECONDS)).as("持锁方跑完了生成").isTrue();
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
