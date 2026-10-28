package com.cretas.aims.engine;

import com.cretas.aims.integration.DisposablePostgresTargetGuard;
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
        AutonomousWriteSwitch.class, AutonomousScheduledWriteIsolatedPostgresContractTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutonomousScheduledWriteIsolatedPostgresContractTest {

    private static final String TOOL = "user_disable";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = safeUrl();
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
    @Autowired AutonomousWriteSwitch writeSwitch;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    private DynamicSchedulerService instanceB;
    private Long operator;
    private Long grantor;
    private Long target;
    private Long bystander;
    private Long configId;

    @BeforeEach void fixtures() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS autonomous_write_switches");
        try (var connection = dataSource.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource(
                            "db/flyway/V20261103_110__autonomous_write_switches.sql"));
        }
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
            grantor = user("iso-admin", "factory_super_admin");
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
        String url = safeUrl();
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

    @Test void aFailureAfterTheDeductionInsideTheConsumingTransactionRollsAllOfItBack() throws Exception {
        FactorySchedulerConfig config = scheduleSoon();
        grant(config, 3);
        // Deterministic fault at the last step of the consuming transaction: marking the config RUNNING.
        // By then the grant row is locked, its budget decremented and the run inserted in the same
        // transaction; all of it must roll back with the failure.
        String trigger = "s6_fail_running_" + System.nanoTime();
        try {
            tx.executeWithoutResult(status -> {
                entityManager.createNativeQuery("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN RAISE EXCEPTION 's6 injected fault after consumption'; END $$").executeUpdate();
                entityManager.createNativeQuery("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON factory_scheduler_configs"
                        + " FOR EACH ROW WHEN (NEW.last_execution_status = 'RUNNING') EXECUTE FUNCTION " + trigger + "()")
                        .executeUpdate();
            });
            instanceA.scheduleTask(config);

            // The real fire reaches the fault, and the scheduler records the failed run.
            await(() -> "FAILED".equals(status()), 75);

            assertThat(text("SELECT last_execution_error FROM factory_scheduler_configs WHERE id = " + configId))
                    .contains("s6 injected fault after consumption");
            assertThat(count("autonomous_tool_grants WHERE used_runs = 0")).isEqualTo(1);
            assertThat(count("autonomous_tool_grant_runs")).isZero();
            assertThat(count("intent_preview_tokens")).isZero();
            assertThat(count("tool_execution_idempotency")).isZero();
            assertThat(count("tool_execution_audit_events")).isZero();
            assertThat(active(target)).isTrue();
        } finally {
            instanceA.cancelTask(config.getFactoryId(), config.getTaskCode());
            tx.executeWithoutResult(status -> {
                entityManager.createNativeQuery("DROP TRIGGER IF EXISTS " + trigger + " ON factory_scheduler_configs")
                        .executeUpdate();
                entityManager.createNativeQuery("DROP FUNCTION IF EXISTS " + trigger + "()").executeUpdate();
            });
        }
    }

    @Test void aRealFireStoppedAfterConsumptionShowsRunningIsReportedAndAReplayNeverExecutesOrDowngrades()
            throws Exception {
        FactorySchedulerConfig config = scheduleSoon();
        grant(config, 3);
        String url = safeUrl();
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String runKey;
        try (java.sql.Connection holder = java.sql.DriverManager.getConnection(url, user, password)) {
            // The business write stalls on the target's row: the state between consumption and outcome
            // that a crash would leave behind, observed through the real cron fire on two instances.
            holder.setAutoCommit(false);
            try (java.sql.Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM users WHERE id = " + target + " FOR UPDATE").close();
            }
            instanceA.scheduleTask(config);
            instanceB.scheduleTask(config);

            await(() -> count("autonomous_tool_grant_runs") == 1 && "RUNNING".equals(status()), 75);
            Thread.sleep(3000); // the other instance's fire of the same slot only replays
            runKey = text("SELECT run_key FROM autonomous_tool_grant_runs");

            assertThat(status()).isEqualTo("RUNNING");
            assertThat(count("autonomous_tool_grant_runs WHERE outcome IS NULL")).isEqualTo(1);
            assertThat(count("autonomous_tool_grants WHERE used_runs = 1")).isEqualTo(1);
            assertThat(active(target)).isTrue();
            var report = new ExecutionReconciliationReporter(entityManager, 0).report(LocalDateTime.now().plusMinutes(1));
            assertThat(report.unresolvedGrantRuns()).isEqualTo(1L);
            assertThat(report.samples()).anyMatch(sample -> sample.endsWith(":grant-run:" + runKey));
            holder.rollback();
        }
        // Released: the one execution finishes and records its outcome once.
        await(() -> "SUCCESS".equals(status()) && !active(target), 30);
        assertThat(text("SELECT outcome FROM autonomous_tool_grant_runs")).isEqualTo("SUCCEEDED");
        assertThat(count("tool_execution_audit_events WHERE outcome_status = 'SUCCEEDED'")).isEqualTo(1);

        // A late fire of the same planned slot replays: no second write, no RUNNING over the SUCCESS.
        instanceA.cancelTask(config.getFactoryId(), config.getTaskCode());
        instanceB.cancelTask(config.getFactoryId(), config.getTaskCode());
        instanceB.fire(configId, config.getCronExpression(), "late-replay",
                java.time.Instant.parse(runKey.substring("slot:".length())));
        assertThat(status()).isEqualTo("SUCCESS");
        assertThat(count("autonomous_tool_grants WHERE used_runs = 1")).isEqualTo(1);
        assertThat(count("tool_execution_audit_events WHERE outcome_status = 'SUCCEEDED'")).isEqualTo(1);
    }

    @Test void aConfigDeletedOrRetooledOrReassignedAfterTheFireReadItIsNotConsumed() throws Exception {
        FactorySchedulerConfig fired = scheduleSoon();
        String grantId = grant(fired, 3);
        var fireBinding = SchedulerGrantBinding.of(fired, toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(),
                objectMapper);
        // Each change happens after the fire read the config and before consumption.
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE factory_scheduler_configs SET tool_or_method = 'user_enable' WHERE id = :id")
                .setParameter("id", configId).executeUpdate());
        assertThat(runFired(fired, fireBinding, "slot:2026-10-24T01:00:00Z")).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE factory_scheduler_configs SET tool_or_method = :tool, run_as_user_id = :other WHERE id = :id")
                .setParameter("tool", TOOL).setParameter("other", bystander).setParameter("id", configId).executeUpdate());
        assertThat(runFired(fired, fireBinding, "slot:2026-10-24T01:01:00Z")).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "DELETE FROM factory_scheduler_configs WHERE id = :id").setParameter("id", configId).executeUpdate());
        assertThat(runFired(fired, fireBinding, "slot:2026-10-24T01:02:00Z")).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        configId = null;

        assertThat(count("autonomous_tool_grants WHERE id = '" + grantId + "' AND used_runs = 0")).isEqualTo(1);
        assertThat(count("autonomous_tool_grant_runs")).isZero();
        assertThat(count("tool_execution_audit_events")).isZero();
        assertThat(active(target)).isTrue();
    }

    @Test void aFireThatReadTheOldConfigCannotSpendAGrantIssuedForTheEditedOne() throws Exception {
        FactorySchedulerConfig fired = scheduleSoon();
        String oldGrant = grant(fired, 3);
        var fireBinding = SchedulerGrantBinding.of(fired, toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(),
                objectMapper);
        // After this fire read the config, it is edited to another target and a grant is issued for
        // the edited config. The fire still carries the old parameters: it must not spend the new grant.
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE factory_scheduler_configs SET params = '{\"username\":\"iso-bystander\"}' WHERE id = :id")
                .setParameter("id", configId).executeUpdate());
        assertThat(grants.revoke("F-ISO", oldGrant, operator, LocalDateTime.now())).isPresent();
        String newGrant = grant(schedules.findById(configId).orElseThrow(), 3);

        assertThat(runFired(fired, fireBinding, "slot:2026-10-24T04:00:00Z")).isEqualTo(AutonomousToolRunner.GRANT_DENIED);

        assertThat(count("autonomous_tool_grants WHERE id = '" + newGrant + "' AND used_runs = 0")).isEqualTo(1);
        assertThat(count("autonomous_tool_grant_runs")).isZero();
        assertThat(count("tool_execution_audit_events")).isZero();
        assertThat(active(target)).isTrue();
        assertThat(active(bystander)).isTrue();
    }

    @Test void anExpiredOrDeletedGrantIsNotConsumed() throws Exception {
        FactorySchedulerConfig fired = scheduleSoon();
        String grantId = grant(fired, 3);
        var fireBinding = SchedulerGrantBinding.of(fired, toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(),
                objectMapper);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE autonomous_tool_grants SET expires_at = :past WHERE id = :id")
                .setParameter("past", LocalDateTime.now().minusMinutes(1)).setParameter("id", grantId).executeUpdate());
        assertThat(runFired(fired, fireBinding, "slot:2026-10-24T02:00:00Z")).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        assertThat(count("autonomous_tool_grants WHERE used_runs = 0")).isEqualTo(1);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "DELETE FROM autonomous_tool_grants WHERE id = :id").setParameter("id", grantId).executeUpdate());
        assertThat(runFired(fired, fireBinding, "slot:2026-10-24T02:01:00Z")).isEqualTo(AutonomousToolRunner.GRANT_DENIED);

        assertThat(count("autonomous_tool_grant_runs")).isZero();
        assertThat(count("tool_execution_audit_events")).isZero();
        assertThat(active(target)).isTrue();
    }

    @Test void aRevokeHoldingTheGrantRowMakesConsumptionWaitAndThenRefuse() throws Exception {
        FactorySchedulerConfig fired = scheduleSoon();
        String grantId = grant(fired, 3);
        var fireBinding = SchedulerGrantBinding.of(fired, toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(),
                objectMapper);
        String url = safeUrl();
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (java.sql.Connection revoke = java.sql.DriverManager.getConnection(url, user, password)) {
            revoke.setAutoCommit(false);
            try (java.sql.Statement statement = revoke.createStatement()) {
                statement.executeUpdate("UPDATE autonomous_tool_grants SET revoked_at = now(), revoked_by = " + operator
                        + " WHERE id = '" + grantId + "'");
            }
            // Lock order config then grant: consumption takes the config row and waits on the grant row.
            java.util.concurrent.CompletableFuture<String> run = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> runFired(fired, fireBinding, "slot:2026-10-24T03:00:00Z"));
            try {
                run.get(2, java.util.concurrent.TimeUnit.SECONDS);
                throw new AssertionError("consumption should wait on the grant row the revoke holds");
            } catch (java.util.concurrent.TimeoutException expected) {
                // waiting on the revoke's row lock
            }
            revoke.commit();
            assertThat(run.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        }
        assertThat(count("autonomous_tool_grants WHERE used_runs = 0")).isEqualTo(1);
        assertThat(count("autonomous_tool_grant_runs")).isZero();
        assertThat(active(target)).isTrue();
    }

    @Test void aCronChangedElsewhereOnlyReschedulesAndNeverRunsTheOldBinding() throws Exception {
        // Two configs share the next fire second. The control's write proves the fire window passed;
        // the changed config must only reschedule on this instance, never run under its old schedule.
        FactorySchedulerConfig changed = scheduleSoon();
        Long changedId = configId;
        grant(changed, 3);
        FactorySchedulerConfig control = schedules.saveAndFlush(FactorySchedulerConfig.builder().factoryId("F-ISO")
                .taskCode("control-" + System.nanoTime()).cronExpression(changed.getCronExpression()).enabled(true)
                .toolOrMethod(TOOL).params(Map.of("username", "iso-bystander")).runAsUserId(operator).build());
        grant(control, 3);
        instanceA.scheduleTask(changed);
        instanceA.scheduleTask(control);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE factory_scheduler_configs SET cron_expression = '0 0 3 * * ?' WHERE id = :id")
                .setParameter("id", changedId).executeUpdate());
        try {
            await(() -> !active(bystander), 75);
            Thread.sleep(3000);

            assertThat(active(target)).isTrue();
            assertThat(count("autonomous_tool_grant_runs")).isEqualTo(1);
            assertThat(count("autonomous_tool_grant_runs r JOIN autonomous_tool_grants g ON g.id = r.grant_id"
                    + " WHERE g.source_ref = '" + changedId + "'")).isZero();
            assertThat(count("factory_scheduler_configs WHERE id = " + changedId + " AND last_execution_status IS NULL"))
                    .isEqualTo(1);
        } finally {
            instanceA.cancelTask(control.getFactoryId(), control.getTaskCode());
        }
    }

    private String runFired(FactorySchedulerConfig fired, AutonomousGrantService.Binding fireBinding, String runKey) {
        return runner.run("F-ISO", operator, TOOL, fired.getParams(), ToolExecutionSource.SCHEDULER, "c2-" + runKey,
                fireBinding, runKey).status();
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

    @Test void aGrantorNoLongerSuperAdminOrDeactivatedStopsTheGrantAtConsumption() throws Exception {
        FactorySchedulerConfig fired = scheduleSoon();
        String grantId = grant(fired, 3);
        var fireBinding = SchedulerGrantBinding.of(fired, toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(),
                objectMapper);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE users SET role_code = 'hr_admin' WHERE id = :id").setParameter("id", grantor).executeUpdate());
        assertThat(runFired(fired, fireBinding, "slot:2026-10-24T04:00:00Z")).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE users SET role_code = 'factory_super_admin', is_active = false WHERE id = :id")
                .setParameter("id", grantor).executeUpdate());
        assertThat(runFired(fired, fireBinding, "slot:2026-10-24T04:01:00Z")).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        assertThat(count("autonomous_tool_grants WHERE used_runs = 0 AND id = '" + grantId + "'")).isEqualTo(1);
        assertThat(count("autonomous_tool_grant_runs")).isZero();
        assertThat(count("tool_execution_audit_events")).isZero();
        assertThat(active(target)).isTrue();

        // Positive control: the same grant spends again once the grantor is restored.
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE users SET is_active = true WHERE id = :id").setParameter("id", grantor).executeUpdate());
        assertThat(runFired(fired, fireBinding, "slot:2026-10-24T04:02:00Z")).isEqualTo("SUCCESS");
        assertThat(count("autonomous_tool_grant_runs")).isEqualTo(1);
        assertThat(active(target)).isFalse();
    }

    @Test void theFactoryKillSwitchStopsTheNextDelegatedWriteBeforeAnyGrantIsSpent() throws Exception {
        FactorySchedulerConfig fired = scheduleSoon();
        String grantId = grant(fired, 3);
        var fireBinding = SchedulerGrantBinding.of(fired, toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(),
                objectMapper);
        writeSwitch.set("F-ISO", false, "盘点期间暂停自动写入", grantor, LocalDateTime.now());

        assertThat(runFired(fired, fireBinding, "slot:2026-10-25T04:00:00Z"))
                .isEqualTo(AutonomousToolRunner.WRITES_SUSPENDED);
        assertThat(count("autonomous_tool_grants WHERE used_runs = 0 AND id = '" + grantId + "'")).isEqualTo(1);
        assertThat(count("autonomous_tool_grant_runs")).isZero();
        assertThat(count("tool_execution_audit_events")).isZero();
        assertThat(active(target)).isTrue();
        // Another factory's switch does not stop this one, and switching back on lets the same grant run.
        writeSwitch.set("F-OTHER", false, null, grantor, LocalDateTime.now());
        writeSwitch.set("F-ISO", true, null, grantor, LocalDateTime.now());
        assertThat(runFired(fired, fireBinding, "slot:2026-10-25T04:01:00Z")).isEqualTo("SUCCESS");
        assertThat(count("autonomous_tool_grant_runs")).isEqualTo(1);
        assertThat(count("tool_execution_audit_events WHERE outcome_status = 'SUCCEEDED' AND principal_id = '"
                + operator + "'")).isEqualTo(1);
        assertThat(active(target)).isFalse();
        // The audit keeps who granted it, who it ran as, the tool, the parameters' binding and the result.
        assertThat(text("SELECT g.granted_by || '|' || g.run_as_user_id || '|' || g.tool_name || '|' || r.outcome"
                + " FROM autonomous_tool_grant_runs r JOIN autonomous_tool_grants g ON g.id = r.grant_id"
                + " JOIN tool_execution_audit_events e ON e.id = r.audit_event_id"))
                .isEqualTo(grantor + "|" + operator + "|" + TOOL + "|SUCCEEDED");
        assertThat(text("SELECT parameters_digest FROM autonomous_tool_grants")).isEqualTo(
                fireBinding.parametersDigest());
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
                now.plusDays(1), grantor, now).getId();
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

    /** Only a paired, disposable loopback target: URL and VERIFY=true together, checked before any connection. */
    private static String safeUrl() {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url == null || url.isBlank() || !"true".equals(System.getenv("CRETAS_WORKFLOW_PG_VERIFY"))) {
            throw new IllegalStateException("CRETAS_WORKFLOW_PG_URL and CRETAS_WORKFLOW_PG_VERIFY=true must be set together");
        }
        return DisposablePostgresTargetGuard.requireSafeUrl(url);
    }
}
