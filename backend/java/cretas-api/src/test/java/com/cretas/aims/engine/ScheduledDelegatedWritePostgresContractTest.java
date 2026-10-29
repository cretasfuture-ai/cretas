package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.DefaultToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.ToolConfirmationLease;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry;
import com.cretas.aims.ai.tool.impl.alerts.AlertRuleToggleTool;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.governance.ToolSimilarityService;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * S6-04 end to end on PostgreSQL with the <b>shipped</b> runtime policies (nothing widened for the
 * test) and the connection pool's auto-commit switched off, as in production:
 * a factory super admin schedules {@code alert_rule_toggle} (the first write tool the shipped policy
 * opens to SCHEDULER), issues a delegated grant, and the real scheduler runs it through the real
 * grant consumption, preview-token confirmation, gateway (principal re-read, audit, idempotency) and
 * tool, whose effect is the alert rule row. The factory kill switch, set from an empty table, stops
 * the next run before any grant is spent; revoking the grant stops it too.
 * Disposable loopback PostgreSQL only (CRETAS_WORKFLOW_PG_URL).
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({DefaultToolExecutionGateway.class, ToolPrincipalPolicy.class, ToolRuntimeRegistry.class,
        ToolExecutionLedgerService.class, ToolConfirmationLease.class, LegacyToolMigrationRegistry.class,
        AuthenticatedToolPrincipalFactory.class, ToolRegistry.class, AlertRuleToggleTool.class,
        PreviewTokenServiceImpl.class, AutonomousToolRunner.class, AutonomousGrantService.class,
        AutonomousGrantIssuer.class, DynamicSchedulerService.class, AutonomousWriteSwitch.class,
        ScheduledDelegatedWritePostgresContractTest.Json.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduledDelegatedWritePostgresContractTest {

    private static final String TOOL = "alert_rule_toggle";

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
        // As in production (application-pg-prod.properties): a write outside a transaction is lost.
        registry.add("spring.datasource.hikari.auto-commit", () -> "false");
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class Json {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @MockBean PermissionService permissionService;
    @MockBean ToolSimilarityService toolSimilarityService;

    @Autowired DynamicSchedulerService scheduler;
    @Autowired FactorySchedulerConfigRepository schedules;
    @Autowired AutonomousGrantIssuer issuer;
    @Autowired AutonomousWriteSwitch writeSwitch;
    @Autowired ToolRegistry toolRegistry;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource dataSource;

    private Long admin;
    private Long operator;
    private java.util.UUID ruleId;
    private java.util.UUID otherRuleId;

    @BeforeEach void fixtures() throws Exception {
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS autonomous_write_switches");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261103_110__autonomous_write_switches.sql"));
            connection.commit();
        }
        tx.executeWithoutResult(status -> {
            for (String table : List.of("autonomous_tool_grant_runs", "autonomous_tool_grants",
                    "tool_execution_idempotency", "tool_execution_audit_events", "intent_preview_tokens",
                    "factory_scheduler_configs", "alert_rules", "users")) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM factories WHERE id = 'F-SW'").executeUpdate();
            Factory factory = new Factory();
            factory.setId("F-SW");
            factory.setName("scheduled write");
            factory.setType(FactoryType.FACTORY);
            factory.setLevel(0);
            factory.setIsActive(true);
            factory.setManuallyVerified(false);
            factory.setAiWeeklyQuota(20);
            entityManager.persist(factory);
            admin = user("sw-admin", "factory_super_admin");
            operator = user("sw-operator", "operator");
            ruleId = rule("夜间温度告警");
            otherRuleId = rule("其他规则");
        });
        when(permissionService.resolveModuleLevelsForAuthorization(any())).thenReturn(Map.of());
    }

    @Test void theShippedPolicyOpensAlertRuleToggleToSchedulesOnly() {
        AutonomousToolPolicy.requireSourceAllowed(TOOL, ToolExecutionSource.SCHEDULER);
        assertThatThrownBy(() -> AutonomousToolPolicy.requireSourceAllowed(TOOL, ToolExecutionSource.TRIGGER))
                .isInstanceOf(BusinessException.class);
    }

    @Test void aGrantedScheduledWriteRunsOnceThroughTheGatewayAndTheKillSwitchAndRevokeStopTheNext()
            throws Exception {
        FactorySchedulerConfig config = schedule(admin, Map.of("ruleId", ruleId.toString(), "enabled", false));
        String grantId = tx.execute(status -> issuer.issue("F-SW", admin, config.getId(), TOOL,
                toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(), 5, LocalDateTime.now().plusDays(1),
                LocalDateTime.now()).getId());

        fire(config.getId(), "slot:2026-10-29T22:00:00Z");

        assertThat(status(config.getId())).isEqualTo("SUCCESS");
        assertThat(enabled(ruleId)).isFalse();
        assertThat(enabled(otherRuleId)).isTrue();
        assertThat(text("SELECT g.granted_by || '|' || g.run_as_user_id || '|' || g.tool_name || '|' || r.outcome"
                + " FROM autonomous_tool_grant_runs r JOIN autonomous_tool_grants g ON g.id = r.grant_id"
                + " JOIN tool_execution_audit_events e ON e.id = r.audit_event_id"
                + " WHERE e.source = 'SCHEDULER' AND e.outcome_status = 'SUCCEEDED'"))
                .isEqualTo(admin + "|" + admin + "|" + TOOL + "|SUCCEEDED");
        assertThat(text("SELECT status FROM intent_preview_tokens")).isEqualTo("CONFIRMED");
        assertThat(count("autonomous_tool_grants WHERE used_runs = 1")).isEqualTo(1);

        // Kill switch from an empty table: persisted, and the next fire stops before the grant is spent.
        assertThat(count("autonomous_write_switches")).isZero();
        writeSwitch.set("F-SW", false, "盘点期间暂停", admin, LocalDateTime.now());
        assertThat(count("autonomous_write_switches WHERE writes_enabled = false")).isEqualTo(1);
        setEnabled(ruleId, true);
        fire(config.getId(), "slot:2026-10-30T22:00:00Z");
        assertThat(status(config.getId())).isEqualTo(AutonomousToolRunner.WRITES_SUSPENDED);
        assertThat(enabled(ruleId)).isTrue();
        assertThat(count("autonomous_tool_grants WHERE used_runs = 1")).isEqualTo(1);
        assertThat(count("tool_execution_audit_events")).isEqualTo(1);

        // Switched back on: the same grant runs the next slot.
        writeSwitch.set("F-SW", true, null, admin, LocalDateTime.now());
        fire(config.getId(), "slot:2026-10-31T22:00:00Z");
        assertThat(status(config.getId())).isEqualTo("SUCCESS");
        assertThat(enabled(ruleId)).isFalse();

        // Revoked: the next fire is refused and writes nothing.
        tx.executeWithoutResult(status -> issuer.revoke("F-SW", admin, grantId, LocalDateTime.now()));
        setEnabled(ruleId, true);
        fire(config.getId(), "slot:2026-11-01T22:00:00Z");
        assertThat(status(config.getId())).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        assertThat(enabled(ruleId)).isTrue();
        assertThat(count("autonomous_tool_grant_runs")).isEqualTo(2);
    }

    @Test void onlyAFactorySuperAdminCanGrantAndAWriteWithoutAGrantNeverRuns() throws Exception {
        FactorySchedulerConfig byOperator = schedule(operator, Map.of("ruleId", ruleId.toString(), "enabled", false));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> issuer.issue("F-SW", operator, byOperator.getId(),
                TOOL, toolRegistry.getExecutor(TOOL).orElseThrow().getVersion(), 5,
                LocalDateTime.now().plusDays(1), LocalDateTime.now())))
                .isInstanceOf(BusinessException.class);

        FactorySchedulerConfig ungranted = schedule(admin, Map.of("ruleId", otherRuleId.toString(), "enabled", false));
        fire(ungranted.getId(), "slot:2026-10-29T23:00:00Z");
        assertThat(status(ungranted.getId())).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        assertThat(enabled(otherRuleId)).isTrue();
        assertThat(count("tool_execution_audit_events")).isZero();
    }

    private void fire(Long configId, String plannedFireKey) throws Exception {
        FactorySchedulerConfig config = schedules.findById(configId).orElseThrow();
        Method execute = DynamicSchedulerService.class.getDeclaredMethod("executeTask",
                FactorySchedulerConfig.class, String.class);
        execute.setAccessible(true);
        execute.invoke(AopTestUtils.getTargetObject(scheduler), config, plannedFireKey);
    }

    private FactorySchedulerConfig schedule(Long runAs, Map<String, Object> params) {
        return tx.execute(status -> schedules.saveAndFlush(FactorySchedulerConfig.builder().factoryId("F-SW")
                .taskCode("rule-night-" + System.nanoTime()).cronExpression("0 0 22 * * ?").enabled(true)
                .toolOrMethod(TOOL).params(params).runAsUserId(runAs).build()));
    }

    private java.util.UUID rule(String name) {
        AlertRule rule = AlertRule.builder().factoryId("F-SW").alertType(AlertType.INVENTORY_LOW)
                .ruleName(name).enabled(true).build();
        entityManager.persist(rule);
        entityManager.flush();
        return rule.getId();
    }

    private void setEnabled(java.util.UUID id, boolean enabled) {
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE alert_rules SET enabled = :enabled WHERE id = :id")
                .setParameter("enabled", enabled).setParameter("id", id).executeUpdate());
    }

    private boolean enabled(java.util.UUID id) {
        return Boolean.TRUE.equals(tx.execute(status -> entityManager.createNativeQuery(
                "SELECT enabled FROM alert_rules WHERE id = :id").setParameter("id", id).getSingleResult()));
    }

    private String status(Long configId) {
        return text("SELECT last_execution_status FROM factory_scheduler_configs WHERE id = " + configId);
    }

    private long count(String tableAndFilter) {
        return tx.execute(status -> ((Number) entityManager.createNativeQuery("SELECT count(*) FROM " + tableAndFilter)
                .getSingleResult()).longValue());
    }

    private String text(String sql) {
        return tx.execute(status -> {
            List<?> rows = entityManager.createNativeQuery(sql).getResultList();
            return rows.isEmpty() || rows.get(0) == null ? null : String.valueOf(rows.get(0));
        });
    }

    private Long user(String username, String role) {
        User user = new User();
        user.setFactoryId("F-SW");
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
