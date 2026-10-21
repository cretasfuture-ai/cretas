package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.DefaultToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.TestRuntimePolicies;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolConfirmationLease;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry;
import com.cretas.aims.ai.tool.impl.user.UserDisableTool;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.UserService;
import com.cretas.aims.service.governance.ToolSimilarityService;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Delegated autonomous writes end to end on PostgreSQL: a persisted cron config with a write tool
 * ({@link UserDisableTool}) runs through the real runner, the real grant consumption, the real
 * preview-token confirmation and the real {@link DefaultToolExecutionGateway} (principal, policy,
 * idempotency ledger, audit). Only the module permission snapshot and the user-service effect under
 * the tool are stubbed; the stub counts effects. The runtime policy of user_disable is extended with
 * SCHEDULER for this test only (shipped policies admit no autonomous write).
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({DefaultToolExecutionGateway.class, ToolPrincipalPolicy.class,
        ToolExecutionLedgerService.class, ToolConfirmationLease.class, LegacyToolMigrationRegistry.class,
        AuthenticatedToolPrincipalFactory.class, ToolRegistry.class, UserDisableTool.class,
        PreviewTokenServiceImpl.class, AutonomousToolRunner.class, AutonomousGrantService.class,
        DynamicSchedulerService.class, AutonomousGrantWriteEndToEndPostgresContractTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutonomousGrantWriteEndToEndPostgresContractTest {

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

        @Bean ToolRuntimeRegistry toolRuntimeRegistry(ToolRegistry tools, FactoryToolConfigRepository configs) {
            return TestRuntimePolicies.runtimeWithSources(tools, configs, TOOL, Set.of(ToolExecutionSource.SCHEDULER));
        }
    }

    @MockBean PermissionService permissionService;
    @MockBean UserService userService;
    @MockBean ToolSimilarityService toolSimilarityService;

    @Autowired DynamicSchedulerService scheduler;
    @Autowired AutonomousToolRunner runner;
    @Autowired AutonomousGrantService grants;
    @Autowired FactorySchedulerConfigRepository schedules;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;

    private Long operator;
    private Long target;
    private Long otherFactoryUser;
    private final List<Long> disabled = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach void fixtures() {
        tx.executeWithoutResult(status -> {
            for (String table : List.of("autonomous_tool_grant_runs", "autonomous_tool_grants",
                    "tool_execution_idempotency", "tool_execution_audit_events", "intent_preview_tokens",
                    "factory_scheduler_configs", "users")) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM factories WHERE id IN ('F-E2E', 'F-E2E-OTHER')").executeUpdate();
            factory("F-E2E");
            factory("F-E2E-OTHER");
            operator = user("F-E2E", "e2e-hr");
            target = user("F-E2E", "e2e-target");
            otherFactoryUser = user("F-E2E-OTHER", "e2e-other");
        });
        disabled.clear();
        when(permissionService.resolveModuleLevelsForAuthorization(any())).thenReturn(Map.of("hr", "rw"));
        doAnswer(call -> disabled.add(call.getArgument(1))).when(userService).deactivateUser(eq("F-E2E"), anyLong());
    }

    @Test void scheduledWriteWithAGrantRunsOnceThroughTheGatewayAsTheRunAsUser() throws Exception {
        Long id = schedule(operator);
        AutonomousToolGrant grant = grant("F-E2E", String.valueOf(id), operator, params(), 1);
        fire(id);

        assertThat(disabled).containsExactly(target);
        assertThat(status(id)).isEqualTo("SUCCESS");
        ToolExecutionAuditEvent audit = onlyAudit();
        assertThat(audit.getToolName()).isEqualTo(TOOL);
        assertThat(audit.getSource()).isEqualTo(ToolExecutionSource.SCHEDULER);
        assertThat(audit.getPrincipalId()).isEqualTo(String.valueOf(operator));
        assertThat(audit.getTenantId()).isEqualTo("F-E2E");
        assertThat(audit.getOutcomeStatus()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(usedRuns(grant.getId())).isEqualTo(1);
        assertThat(count("autonomous_tool_grant_runs")).isEqualTo(1);
        assertThat(count("tool_execution_idempotency")).isEqualTo(1);
        assertThat(single("SELECT status FROM intent_preview_tokens")).isEqualTo("CONFIRMED");
    }

    @Test void writeWithoutAGrantNeverReachesTheGatewayOrTheAuditLog() throws Exception {
        Long id = schedule(operator);
        fire(id);
        assertThat(status(id)).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        assertThat(disabled).isEmpty();
        assertThat(count("tool_execution_audit_events")).isZero();
        assertThat(count("intent_preview_tokens")).isZero();
    }

    @Test void sameRunKeyAgainReplaysTheRecordedResultWithoutASecondEffect() {
        grant("F-E2E", "78", operator, params(), 5);
        AutonomousToolRunner.Outcome first = run("78", operator, "fire:2026-10-21T07:00");
        AutonomousToolRunner.Outcome again = run("78", operator, "fire:2026-10-21T07:00");

        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(again.status()).isEqualTo("SUCCESS");
        assertThat(disabled).containsExactly(target);
        assertThat(count("tool_execution_audit_events")).isEqualTo(1);
        assertThat(sum("SELECT sum(used_runs) FROM autonomous_tool_grants")).isEqualTo(1);
    }

    @Test void runConsumedButNeverExecutedIsNotRetriedOnReplay() {
        AutonomousToolGrant grant = grant("F-E2E", "78", operator, params(), 5);
        // A first attempt that consumed the grant and stopped before the gateway (crash in between).
        assertThat(grants.consume("F-E2E", "SCHEDULER", "78", TOOL, operator, digest(params()), "fire:lost",
                LocalDateTime.now())).isPresent();

        AutonomousToolRunner.Outcome replay = run("78", operator, "fire:lost");
        assertThat(replay.status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(disabled).isEmpty();
        assertThat(count("tool_execution_audit_events")).isZero();
        assertThat(usedRuns(grant.getId())).isEqualTo(1);
    }

    @Test void budgetCapsDistinctRuns() {
        AutonomousToolGrant grant = grant("F-E2E", "78", operator, params(), 2);
        List<String> statuses = new ArrayList<>();
        for (String key : List.of("fire:a", "fire:b", "fire:c")) {
            statuses.add(run("78", operator, key).status());
        }
        assertThat(statuses).containsExactly("SUCCESS", "SUCCESS", AutonomousToolRunner.GRANT_DENIED);
        assertThat(disabled).hasSize(2);
        assertThat(usedRuns(grant.getId())).isEqualTo(2);
    }

    @Test void revokedOrExpiredGrantStopsTheRun() {
        AutonomousToolGrant revoked = grant("F-E2E", "78", operator, params(), 5);
        assertThat(grants.revoke("F-E2E", revoked.getId(), operator, LocalDateTime.now())).isPresent();
        assertThat(run("78", operator, "fire:a").status()).isEqualTo(AutonomousToolRunner.GRANT_DENIED);

        AutonomousToolGrant expired = grant("F-E2E", "79", operator, params(), 5);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE autonomous_tool_grants SET expires_at = :past WHERE id = :id")
                .setParameter("past", LocalDateTime.now().minusMinutes(1)).setParameter("id", expired.getId())
                .executeUpdate());
        assertThat(run("79", operator, "fire:a").status()).isEqualTo(AutonomousToolRunner.GRANT_DENIED);

        assertThat(disabled).isEmpty();
        assertThat(sum("SELECT sum(used_runs) FROM autonomous_tool_grants")).isZero();
        assertThat(count("tool_execution_audit_events")).isZero();
    }

    @Test void grantForOtherParametersSourceUserOrFactoryDoesNotApply() {
        grant("F-E2E", "78", operator, Map.of("username", "someone-else"), 5);
        grant("F-E2E", "79", operator, params(), 5);
        grant("F-E2E", "78", target, params(), 5);
        grant("F-E2E-OTHER", "78", operator, params(), 5);

        assertThat(run("78", operator, "fire:a").status()).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        assertThat(disabled).isEmpty();
        assertThat(sum("SELECT sum(used_runs) FROM autonomous_tool_grants")).isZero();
    }

    @Test void runAsUserFromAnotherFactoryIsDeniedByTheGateway() {
        // The grant endpoint never issues this (grantor = the requesting user of this factory); the
        // gateway still refuses it because it rehydrates the user against the factory on every run.
        grant("F-E2E", "78", otherFactoryUser, params(), 5);
        assertThat(run("78", otherFactoryUser, "fire:a").status()).isEqualTo("GATEWAY_DENIED");
        assertThat(disabled).isEmpty();
        assertThat(onlyAudit().getOutcomeStatus()).isEqualTo(ToolExecutionStatus.DENIED);
    }

    @Test void permissionRevokedAfterGrantingIsDeniedByTheGatewayAndAudited() {
        when(permissionService.resolveModuleLevelsForAuthorization(any())).thenReturn(Map.of("hr", "r"));
        AutonomousToolGrant grant = grant("F-E2E", "78", operator, params(), 5);
        assertThat(run("78", operator, "fire:a").status()).isEqualTo("GATEWAY_DENIED");
        assertThat(disabled).isEmpty();
        assertThat(onlyAudit().getOutcomeStatus()).isEqualTo(ToolExecutionStatus.DENIED);
        // Consumption is the authorization commit point: the denied run still used one run of the budget.
        assertThat(usedRuns(grant.getId())).isEqualTo(1);
    }

    @Test void concurrentRunsWithTheSameKeyHaveExactlyOneEffect() throws Exception {
        grant("F-E2E", "78", operator, params(), 5);
        List<String> statuses = concurrently(8, i -> "fire:same");
        assertThat(disabled).containsExactly(target);
        assertThat(sum("SELECT sum(used_runs) FROM autonomous_tool_grants")).isEqualTo(1);
        assertThat(count("autonomous_tool_grant_runs")).isEqualTo(1);
        assertThat(statuses).doesNotContain(AutonomousToolRunner.GRANT_DENIED, "GATEWAY_DENIED", "FAILED");
    }

    @Test void concurrentDistinctRunsNeverExceedTheBudget() throws Exception {
        AutonomousToolGrant grant = grant("F-E2E", "78", operator, params(), 3);
        List<String> statuses = concurrently(8, i -> "fire:" + i);
        assertThat(disabled).as("statuses %s", statuses).hasSize(3);
        assertThat(usedRuns(grant.getId())).isEqualTo(3);
        assertThat(statuses.stream().filter("SUCCESS"::equals)).hasSize(3);
        assertThat(statuses.stream().filter(AutonomousToolRunner.GRANT_DENIED::equals)).hasSize(5);
    }

    private interface Keys { String key(int i); }

    private List<String> concurrently(int threads, Keys keys) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String key = keys.key(i);
                Callable<String> call = () -> {
                    start.await();
                    return run("78", operator, key).status();
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<String> statuses = new ArrayList<>();
            for (Future<String> future : futures) statuses.add(future.get());
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private AutonomousToolRunner.Outcome run(String sourceRef, Long runAs, String runKey) {
        return runner.run("F-E2E", runAs, TOOL, params(), ToolExecutionSource.SCHEDULER, "test-" + runKey,
                sourceRef, runKey);
    }

    private void fire(Long id) throws Exception {
        FactorySchedulerConfig config = schedules.findById(id).orElseThrow();
        Method execute = DynamicSchedulerService.class.getDeclaredMethod("executeTask", FactorySchedulerConfig.class);
        execute.setAccessible(true);
        execute.invoke(org.springframework.test.util.AopTestUtils.getTargetObject(scheduler), config);
    }

    private static Map<String, Object> params() {
        return Map.of("username", "e2e-target");
    }

    private String digest(Map<String, Object> params) {
        return ToolCommandDigest.parametersHash(objectMapper.valueToTree(params));
    }

    private AutonomousToolGrant grant(String factoryId, String sourceRef, Long runAs, Map<String, Object> params,
            int maxRuns) {
        LocalDateTime now = LocalDateTime.now();
        return grants.issue(factoryId, "SCHEDULER", sourceRef, TOOL, runAs, digest(params), maxRuns,
                now.plusDays(1), runAs, now);
    }

    private Long schedule(Long runAs) {
        return schedules.saveAndFlush(FactorySchedulerConfig.builder().factoryId("F-E2E")
                .taskCode("disable-" + System.nanoTime()).cronExpression("0 0 7 * * ?").enabled(true)
                .toolOrMethod(TOOL).params(params()).runAsUserId(runAs).build()).getId();
    }

    private String status(Long id) {
        return (String) entityManager.createNativeQuery(
                "SELECT last_execution_status FROM factory_scheduler_configs WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private int usedRuns(String grantId) {
        return ((Number) entityManager.createNativeQuery("SELECT used_runs FROM autonomous_tool_grants WHERE id = :id")
                .setParameter("id", grantId).getSingleResult()).intValue();
    }

    private ToolExecutionAuditEvent onlyAudit() {
        List<ToolExecutionAuditEvent> rows = entityManager.createQuery(
                "select e from ToolExecutionAuditEvent e", ToolExecutionAuditEvent.class).getResultList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private Object single(String sql) {
        return entityManager.createNativeQuery(sql).getSingleResult();
    }

    private long count(String table) {
        return ((Number) single("SELECT count(*) FROM " + table)).longValue();
    }

    private long sum(String sql) {
        Object value = single(sql);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private void factory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName("e2e " + id);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
    }

    private Long user(String factoryId, String username) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user.setRoleCode("hr_admin");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }
}
