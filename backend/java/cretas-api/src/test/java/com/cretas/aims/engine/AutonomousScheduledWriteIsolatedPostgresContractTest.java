package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.DefaultToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.TestRuntimePolicies;
import com.cretas.aims.ai.tool.gateway.ToolConfirmationLease;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry;
import com.cretas.aims.ai.tool.impl.user.UserDisableTool;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.service.governance.ToolSimilarityService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.cretas.aims.service.impl.UserServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolated real-time check of scheduled delegated writes on PostgreSQL. Two scheduler instances
 * (the Spring bean and a second one sharing the database, as two blue/green slots would) schedule
 * the same config with a real CronTrigger and wait for the actual fire. Everything below the
 * scheduler is real: grant consumption, preview-token confirmation, the gateway (principal re-read
 * from users/factories, current permissions from the real PermissionServiceImpl, idempotency ledger,
 * audit) and the real UserDisableTool over the real UserServiceImpl, whose effect is a users row.
 * Only the runtime policy of user_disable is widened to SCHEDULER for this test (shipped policies
 * admit no autonomous write). Disposable loopback PostgreSQL only (CRETAS_WORKFLOW_PG_URL).
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({DefaultToolExecutionGateway.class, ToolPrincipalPolicy.class, ToolExecutionLedgerService.class,
        ToolConfirmationLease.class, LegacyToolMigrationRegistry.class, AuthenticatedToolPrincipalFactory.class,
        ToolRegistry.class, UserDisableTool.class, UserServiceImpl.class, UserMapper.class, ExcelUtil.class,
        PermissionServiceImpl.class, PreviewTokenServiceImpl.class, AutonomousToolRunner.class,
        AutonomousGrantService.class, AutonomousGrantIssuer.class, DynamicSchedulerService.class,
        AutonomousScheduledWriteIsolatedPostgresContractTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutonomousScheduledWriteIsolatedPostgresContractTest {

    private static final String TOOL = "user_disable";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
        registry.add("spring.datasource.url", () -> bound);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }

        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

        @Bean ToolRuntimeRegistry toolRuntimeRegistry(ToolRegistry tools, FactoryToolConfigRepository configs) {
            return TestRuntimePolicies.runtimeWithSources(tools, configs, TOOL, Set.of(ToolExecutionSource.SCHEDULER));
        }
    }

    @MockBean ToolSimilarityService toolSimilarityService;

    @Autowired DynamicSchedulerService instanceA;
    @Autowired AutonomousToolRunner runner;
    @Autowired AutonomousGrantService grants;
    @Autowired AutonomousGrantIssuer issuer;
    @Autowired ToolRegistry toolRegistry;
    @Autowired FactorySchedulerConfigRepository schedules;
    @Autowired ObjectMapper objectMapper;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private DynamicSchedulerService instanceB;
    private Long operator;
    private Long target;
    private Long bystander;
    private Long configId;

    @BeforeEach void fixtures() {
        tx.executeWithoutResult(status -> {
            for (String table : List.of("autonomous_tool_grant_runs", "autonomous_tool_grants",
                    "tool_execution_idempotency", "tool_execution_audit_events", "intent_preview_tokens",
                    "factory_scheduler_configs", "users")) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM factories WHERE id = 'F-ISO'").executeUpdate();
            Factory factory = new Factory();
            factory.setId("F-ISO");
            factory.setName("isolated cron");
            factory.setType(FactoryType.FACTORY);
            factory.setLevel(0);
            factory.setIsActive(true);
            factory.setManuallyVerified(false);
            factory.setAiWeeklyQuota(20);
            entityManager.persist(factory);
            operator = user("iso-hr", "hr_admin");
            target = user("iso-target", "operator");
            bystander = user("iso-bystander", "operator");
        });
        instanceB = new DynamicSchedulerService(schedules, toolRegistry, runner, objectMapper);
    }

    @AfterEach void stopSchedules() {
        if (configId != null) {
            FactorySchedulerConfig config = schedules.findById(configId).orElse(null);
            if (config != null) {
                instanceA.cancelTask(config.getFactoryId(), config.getTaskCode());
                instanceB.cancelTask(config.getFactoryId(), config.getTaskCode());
            }
        }
    }

    @Test void realCronFireOnTwoInstancesWritesOnceThroughTheGatewayAsTheRunAsUser() throws Exception {
        FactorySchedulerConfig config = scheduleSoon();
        grant(config, 3);
        instanceA.scheduleTask(config);
        instanceB.scheduleTask(config);

        await(() -> count("autonomous_tool_grant_runs") == 1 && !active(target), 75);
        Thread.sleep(5000); // let the second instance's fire for the same slot finish too

        assertThat(active(target)).isFalse();
        assertThat(active(bystander)).isTrue();
        assertThat(active(operator)).isTrue();
        assertThat(count("autonomous_tool_grant_runs")).isEqualTo(1);
        assertThat(text("SELECT run_key FROM autonomous_tool_grant_runs")).startsWith("slot:");
        assertThat(count("tool_execution_idempotency")).isEqualTo(1);
        assertThat(count("tool_execution_audit_events WHERE tool_name = 'user_disable' AND outcome_status = 'SUCCEEDED'"))
                .isEqualTo(1);
        assertThat(text("SELECT principal_id FROM tool_execution_audit_events WHERE outcome_status = 'SUCCEEDED'"))
                .isEqualTo(String.valueOf(operator));
        assertThat(count("autonomous_tool_grants WHERE used_runs = 1")).isEqualTo(1);
        assertThat(text("SELECT status FROM intent_preview_tokens")).isEqualTo("CONFIRMED");
        // The instance that only replayed the slot did not overwrite the executing instance's record.
        assertThat(status()).isEqualTo("SUCCESS");
        // The run keeps its outcome and the gateway audit it came from.
        assertThat(text("SELECT outcome FROM autonomous_tool_grant_runs")).isEqualTo("SUCCEEDED");
        assertThat(text("SELECT audit_event_id FROM autonomous_tool_grant_runs")).isEqualTo(
                text("SELECT id FROM tool_execution_audit_events WHERE outcome_status = 'SUCCEEDED'"));
        // Written once: a later record for the same run (a replay, a late duplicate) changes nothing.
        assertThat(issuer.recordOutcome(text("SELECT grant_id FROM autonomous_tool_grant_runs"),
                text("SELECT run_key FROM autonomous_tool_grant_runs"), "FAILED", null, LocalDateTime.now())).isZero();
        assertThat(text("SELECT outcome FROM autonomous_tool_grant_runs")).isEqualTo("SUCCEEDED");
        assertThat(count("factory_scheduler_configs WHERE execution_count = 1")).isEqualTo(1);
    }

    @Test void adjacentRealSlotsWriteTwiceAndALateArrivalOfAnEarlierSlotOnlyReplays() throws Exception {
        FactorySchedulerConfig config = scheduleSoon();
        grant(config, 3);
        instanceA.scheduleTask(config);

        // Two adjacent real planned fires on instance A: two keys, one consumption and one write each.
        await(() -> count("autonomous_tool_grant_runs") == 2, 135);
        List<?> keys = entityManager.createNativeQuery(
                "SELECT run_key FROM autonomous_tool_grant_runs ORDER BY consumed_at").getResultList();
        assertThat(keys).hasSize(2).doesNotHaveDuplicates()
                .allSatisfy(key -> assertThat(String.valueOf(key)).startsWith("slot:"));
        java.time.Instant first = java.time.Instant.parse(String.valueOf(keys.get(0)).substring("slot:".length()));
        java.time.Instant second = java.time.Instant.parse(String.valueOf(keys.get(1)).substring("slot:".length()));
        assertThat(java.time.Duration.between(first, second)).isEqualTo(java.time.Duration.ofMinutes(1));
        await(() -> count("tool_execution_audit_events WHERE outcome_status = 'SUCCEEDED'") == 2, 15);

        // Instance B now runs the first planned fire: well after the next planned point and, for its key,
        // as late as it gets. Same planned fire, same key: it replays and writes nothing.
        instanceB.fire(configId, config.getCronExpression(), "late-arrival", first);
        assertThat(count("autonomous_tool_grant_runs")).isEqualTo(2);
        assertThat(count("tool_execution_idempotency")).isEqualTo(2);
        assertThat(count("tool_execution_audit_events WHERE outcome_status = 'SUCCEEDED'")).isEqualTo(2);
        assertThat(count("autonomous_tool_grants WHERE used_runs = 2")).isEqualTo(1);
    }

    @Test void aConfigEditedOrDisabledAfterTheFireReadItIsNotConsumed() throws Exception {
        FactorySchedulerConfig fired = scheduleSoon();
        grant(fired, 3);
        var fireBinding = SchedulerGrantBinding.of(fired, toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(),
                objectMapper);
        // The fire read the config; then, before consumption, someone edits it.
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE factory_scheduler_configs SET params = '{\"username\":\"iso-bystander\"}' WHERE id = :id")
                .setParameter("id", configId).executeUpdate());
        assertThat(runner.run("F-ISO", operator, TOOL, fired.getParams(), ToolExecutionSource.SCHEDULER, "c2-edit",
                fireBinding, "slot:2026-10-22T01:00:00Z").status()).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        // Restored, but now disabled before consumption.
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE factory_scheduler_configs SET params = '{\"username\":\"iso-target\"}', enabled = false"
                        + " WHERE id = :id").setParameter("id", configId).executeUpdate());
        assertThat(runner.run("F-ISO", operator, TOOL, fired.getParams(), ToolExecutionSource.SCHEDULER, "c2-disable",
                fireBinding, "slot:2026-10-22T01:01:00Z").status()).isEqualTo(AutonomousToolRunner.GRANT_DENIED);

        assertThat(count("autonomous_tool_grant_runs")).isZero();
        assertThat(count("tool_execution_audit_events")).isZero();
        assertThat(active(target)).isTrue();
        assertThat(active(bystander)).isTrue();
    }

    @Test void anEditHoldingTheConfigRowMakesConsumptionWaitAndThenRefuse() throws Exception {
        FactorySchedulerConfig fired = scheduleSoon();
        grant(fired, 3);
        var fireBinding = SchedulerGrantBinding.of(fired, toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(),
                objectMapper);
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (java.sql.Connection edit = java.sql.DriverManager.getConnection(url, user, password)) {
            edit.setAutoCommit(false);
            try (java.sql.Statement statement = edit.createStatement()) {
                statement.executeUpdate("UPDATE factory_scheduler_configs SET cron_expression = '0 0 3 * * ?' WHERE id = "
                        + configId);
            }
            java.util.concurrent.CompletableFuture<String> run = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> runner.run("F-ISO", operator, TOOL, fired.getParams(), ToolExecutionSource.SCHEDULER,
                            "c2-latch", fireBinding, "slot:2026-10-22T02:00:00Z").status());
            try {
                run.get(2, java.util.concurrent.TimeUnit.SECONDS);
                throw new AssertionError("consumption should wait on the config row the edit holds");
            } catch (java.util.concurrent.TimeoutException expected) {
                // waiting on the edit's row lock
            }
            edit.commit();
            assertThat(run.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        }
        assertThat(count("autonomous_tool_grant_runs")).isZero();
        assertThat(active(target)).isTrue();
    }

    @Test void aRunConsumedButNeverResolvedIsReportedAndNotRetried() throws Exception {
        FactorySchedulerConfig fired = scheduleSoon();
        grant(fired, 3);
        var fireBinding = SchedulerGrantBinding.of(fired, toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(),
                objectMapper);
        // The first attempt consumed the grant and stopped before the gateway (crash in between).
        assertThat(issuer.consumeForScheduledFire(fireBinding, "slot:2026-10-22T03:00:00Z", LocalDateTime.now()))
                .isPresent();

        var report = new ExecutionReconciliationReporter(entityManager, 0).report(LocalDateTime.now().plusMinutes(1));
        assertThat(report.status()).isEqualTo("FOUND");
        assertThat(report.unresolvedGrantRuns()).isEqualTo(1L);
        // Replaying the same slot never executes; the run stays unresolved for a person to look up.
        assertThat(runner.run("F-ISO", operator, TOOL, fired.getParams(), ToolExecutionSource.SCHEDULER, "c3",
                fireBinding, "slot:2026-10-22T03:00:00Z").status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(count("tool_execution_audit_events")).isZero();
        assertThat(active(target)).isTrue();
        assertThat(count("autonomous_tool_grant_runs WHERE outcome IS NULL")).isEqualTo(1);
    }

    @Test void revokedGrantAtFireTimeIsRefusedWithZeroBusinessWrites() throws Exception {
        FactorySchedulerConfig config = scheduleSoon();
        String grantId = grant(config, 3);
        assertThat(grants.revoke("F-ISO", grantId, operator, LocalDateTime.now())).isPresent();
        instanceA.scheduleTask(config);

        await(() -> AutonomousToolRunner.GRANT_DENIED.equals(status()), 75);

        assertThat(active(target)).isTrue();
        assertThat(count("autonomous_tool_grant_runs")).isZero();
        assertThat(count("tool_execution_audit_events")).isZero();
        assertThat(text("SELECT last_execution_error FROM factory_scheduler_configs")).contains("no usable grant");
    }

    @Test void runAsUserWhosePermissionWasRevokedIsDeniedByTheGatewayAndAudited() throws Exception {
        FactorySchedulerConfig config = scheduleSoon();
        grant(config, 3);
        // Real revocation: the run-as user's role no longer carries hr write in the permission matrix.
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE users SET role_code = 'operator' WHERE id = :id").setParameter("id", operator).executeUpdate());
        instanceA.scheduleTask(config);

        await(() -> "GATEWAY_DENIED".equals(status()), 75);

        assertThat(active(target)).isTrue();
        assertThat(count("tool_execution_audit_events WHERE outcome_status = 'DENIED'")).isEqualTo(1);
        assertThat(count("tool_execution_audit_events WHERE outcome_status = 'SUCCEEDED'")).isZero();
    }

    /** A cron firing a few seconds from now, once a minute (only a literal second is schedulable). */
    private FactorySchedulerConfig scheduleSoon() {
        int second = (LocalTime.now().getSecond() + 6) % 60;
        FactorySchedulerConfig config = schedules.saveAndFlush(FactorySchedulerConfig.builder().factoryId("F-ISO")
                .taskCode("disable-" + System.nanoTime()).cronExpression(second + " * * * * ?").enabled(true)
                .toolOrMethod(TOOL).params(Map.of("username", "iso-target")).runAsUserId(operator).build());
        configId = config.getId();
        entityManager.clear();
        return schedules.findById(configId).orElseThrow();
    }

    private String grant(FactorySchedulerConfig config, int maxRuns) {
        ToolExecutor tool = toolRegistry.getExecutor(TOOL).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        return grants.issue(SchedulerGrantBinding.of(config, tool.getVersion(), objectMapper), maxRuns,
                now.plusDays(1), operator, now).getId();
    }

    private static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(500);
        }
        throw new AssertionError("condition not met within " + seconds + "s (the cron fire did not happen or did not finish)");
    }

    private String status() {
        return text("SELECT last_execution_status FROM factory_scheduler_configs WHERE id = " + configId);
    }

    private boolean active(Long userId) {
        return Boolean.TRUE.equals(entityManager.createNativeQuery("SELECT is_active FROM users WHERE id = :id")
                .setParameter("id", userId).getSingleResult());
    }

    private long count(String tableAndFilter) {
        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM " + tableAndFilter)
                .getSingleResult()).longValue();
    }

    private String text(String sql) {
        List<?> rows = entityManager.createNativeQuery(sql).getResultList();
        return rows.isEmpty() || rows.get(0) == null ? null : String.valueOf(rows.get(0));
    }

    private Long user(String username, String role) {
        User user = new User();
        user.setFactoryId("F-ISO");
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user.setRoleCode(role);
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }
}
