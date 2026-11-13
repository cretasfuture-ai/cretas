package com.cretas.aims.integration;

import com.cretas.aims.dto.scheduling.GenerateScheduleRequest;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.dto.scheduling.UpdateScheduleRequest;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.scheduling.CancelledPlanScheduleRepository;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真 JPA Context + 真 PostgreSQL: {@code line_schedules} 加了 {@code @Version} 之后, 三条不经过实体的批量作废 JPQL
 * (重开日计划 ×2、取消生产计划的级联) 能被 Hibernate 解析执行, 并且在语句里把版本号 +1。
 *
 * <p>不 +1 的后果: 页面上拿着作废前版本号的人再改这条排程, 版本校验照样通过, 把已作废的排程改回去 (复活)。
 * 每条都用「拿旧版本号再改 → 冲突」收尾, 守的是这个行为, 不只是数字。
 */
@SpringBootTest(classes = LineScheduleVersionRepositoryQueryValidationTest.Runtime.class,
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
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class LineScheduleVersionRepositoryQueryValidationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 22);

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
    @Autowired LineScheduleServiceImpl schedules;
    @Autowired LineScheduleRepository lineSchedules;
    @Autowired SchedulingPlanRepository dayPlans;
    @Autowired CancelledPlanScheduleRepository cancelledPlanSchedules;

    private TransactionTemplate tx;
    private String factory;
    private String dayPlanId;
    private String row;

    @BeforeEach
    void oneGeneratedRow() {
        tx = new TransactionTemplate(transactionManager);
        factory = "LSQ-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(s -> {
            Factory f = new Factory();
            f.setId(factory);
            f.setName(factory);
            em.persist(f);
            User u = new User();
            u.setFactoryId(factory);
            u.setUsername("lsq-" + UUID.randomUUID());
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
            ProductType p = new ProductType();
            p.setId(factory + "-PT");
            p.setFactoryId(factory);
            p.setCode(factory + "-C");
            p.setName("产品");
            p.setUnit("袋");
            p.setDailyCapacity(new BigDecimal("100"));
            p.setDailyCapacityUnit("袋");
            em.persist(p);
            ProductionPlan plan = new ProductionPlan();
            plan.setId(planId());
            plan.setFactoryId(factory);
            plan.setPlanNumber(factory + "-PLAN");
            plan.setProductTypeId(p.getId());
            plan.setPlannedQuantity(new BigDecimal("50"));
            plan.setPlannedUnit("袋");
            plan.setStatus(ProductionPlanStatus.PENDING);
            plan.setSourceType(PlanSourceType.MANUAL);
            plan.setCreatedBy(u.getId());
            plan.setIsLocked(false);
            plan.setSkipProcessReporting(false);
            em.persist(plan);
        });
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(DAY);
        req.setPlanIds(new ArrayList<>(List.of(planId())));
        SchedulingPlanDTO day = generator.generate(factory, req, 1L);
        dayPlanId = day.getId();
        row = jdbc.queryForObject("select id from line_schedules where plan_id = ?", String.class, dayPlanId);
        assertThat(version()).isZero();
    }

    @Test
    void reopenVoidNotStartedRowsBumpsTheVersion() {
        int voided = tx.execute(s -> lineSchedules.voidNotStartedRows(dayPlanId, LineSchedule.NOT_STARTED_STATUSES,
                LineSchedule.ScheduleStatus.cancelled, "QA 重开作废", LocalDateTime.now()));
        assertVoidedAndStaleEditConflicts(voided);
    }

    @Test
    void reopenVoidPendingRowsBumpsTheVersion() {
        int voided = tx.execute(s -> dayPlans.voidPendingRowsOnReopen(dayPlanId, "QA 重开作废"));
        assertVoidedAndStaleEditConflicts(voided);
    }

    @Test
    void cancelledPlanCascadeBumpsTheVersion() {
        jdbc.update("update production_plans set status = 'CANCELLED' where id = ?", planId());
        int voided = tx.execute(s -> cancelledPlanSchedules.cancelNotStartedRows(factory, planId(),
                LineSchedule.NOT_STARTED_STATUSES, LineSchedule.ScheduleStatus.cancelled,
                ProductionPlanStatus.CANCELLED, "QA 计划取消", LocalDateTime.now()));
        assertVoidedAndStaleEditConflicts(voided);
    }

    private void assertVoidedAndStaleEditConflicts(int voided) {
        System.out.println("LSQ_VERSION voided=" + voided + " version=" + version());
        assertThat(voided).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from line_schedules where id = ?", String.class, row))
                .isEqualTo("cancelled");
        assertThat(version()).as("批量作废也要 +1").isEqualTo(1L);

        UpdateScheduleRequest stale = new UpdateScheduleRequest();
        stale.setPlannedQuantity(99);
        stale.setVersion(0L);
        assertThatThrownBy(() -> schedules.updateSchedule(factory, row, stale))
                .as("拿作废前的版本号改 → 冲突, 不能把作废的排程改回去")
                .matches(GlobalExceptionHandler::isLineScheduleConflict);
        assertThat(jdbc.queryForObject("select planned_quantity from line_schedules where id = ?", Integer.class, row))
                .isNotEqualTo(99);
    }

    private long version() {
        return jdbc.queryForObject("select version from line_schedules where id = ?", Long.class, row);
    }

    private String planId() {
        return factory + "-PP";
    }
}
