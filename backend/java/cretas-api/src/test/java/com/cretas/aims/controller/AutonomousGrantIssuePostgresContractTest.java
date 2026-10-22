package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.engine.AutonomousGrantIssuer;
import com.cretas.aims.engine.AutonomousGrantService;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.engine.SchedulerGrantBinding;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Issuing, refusing and revoking grants on PostgreSQL through the real controller, issuer, service
 * and users/config rows: the issued grant is consumable by the run-time binding of the reloaded
 * config; a second active grant is refused; an edit or a disable revokes, and restoring the content
 * does not revive; an account deactivated, demoted or moved, a disabled config or a grantor who is
 * not the run-as user gets no grant; and the issue transaction is serialized against a concurrent
 * role change on the grantor's row (both orders, two connections, no pre-applied change).
 * Only the tool (a write tool at version 2.0.0) and the SCHEDULER policy admission are stubbed.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({AutonomousGrantController.class, AutonomousGrantService.class, AutonomousGrantIssuer.class,
        AutonomousGrantIssuePostgresContractTest.Json.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutonomousGrantIssuePostgresContractTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AutonomousGrantIssuePostgresContractTest::url);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", AutonomousGrantIssuePostgresContractTest::user);
        registry.add("spring.datasource.password", AutonomousGrantIssuePostgresContractTest::password);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    private static String url() {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        return url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
    }

    private static String user() { return System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"); }

    private static String password() { return System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""); }

    @org.springframework.boot.test.context.TestConfiguration
    static class Json {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @MockBean ToolRegistry toolRegistry;

    @Autowired AutonomousGrantController controller;
    @Autowired AutonomousGrantService grants;
    @Autowired AutonomousGrantIssuer issuer;
    @Autowired FactorySchedulerConfigRepository schedules;
    @Autowired ObjectMapper objectMapper;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private final ToolExecutor tool = mock(ToolExecutor.class);
    private MockedStatic<AutonomousToolPolicy> policy;
    private Long admin;
    private Long otherAdmin;
    private Long configId;

    @BeforeEach void fixtures() {
        tx.executeWithoutResult(status -> {
            for (String table : List.of("autonomous_tool_grant_runs", "autonomous_tool_grants",
                    "factory_scheduler_configs", "users")) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM factories WHERE id IN ('F-ISS', 'F-ISS-2')").executeUpdate();
            factory("F-ISS");
            factory("F-ISS-2");
            admin = user("F-ISS", "iss-admin", "factory_super_admin");
            otherAdmin = user("F-ISS", "iss-admin-2", "factory_super_admin");
        });
        when(tool.getAccessMode()).thenReturn(AccessMode.WRITE);
        when(tool.getVersion()).thenReturn("2.0.0");
        when(toolRegistry.getExecutor("user_disable")).thenReturn(Optional.of(tool));
        // A reviewed policy admitting SCHEDULER for user_disable; shipped policies admit none.
        policy = mockStatic(AutonomousToolPolicy.class, CALLS_REAL_METHODS);
        policy.when(() -> AutonomousToolPolicy.requireSourceAllowed("user_disable", ToolExecutionSource.SCHEDULER))
                .then(call -> null);
        actor(admin);
        configId = schedules.saveAndFlush(FactorySchedulerConfig.builder().factoryId("F-ISS")
                .taskCode("disable-nightly").cronExpression("0 0 7 * * ?").enabled(true).toolOrMethod("user_disable")
                .params(Map.of("username", "iss-target", "limit", 5, "names", List.of("a", "b"),
                        "nested", Map.of("ratio", 1.5, "on", true)))
                .runAsUserId(admin).build()).getId();
    }

    @AfterEach void cleanup() {
        policy.close();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test void issuedGrantIsConsumableByTheRunTimeBindingOfTheReloadedConfig() {
        String grantId = issue().id();
        assertThat(single("SELECT granted_by FROM autonomous_tool_grants WHERE id = '" + grantId + "'")).isEqualTo(admin);
        entityManager.clear();
        FactorySchedulerConfig fired = schedules.findById(configId).orElseThrow();
        assertThat(grants.consume(SchedulerGrantBinding.of(fired, "2.0.0", objectMapper), "slot:1", LocalDateTime.now()))
                .hasValueSatisfying(consumption -> assertThat(consumption.grantId()).isEqualTo(grantId));
    }

    @Test void aSecondActiveGrantForTheSameConfigIsRefusedUntilTheFirstIsRevoked() {
        String first = issue().id();
        assertThatThrownBy(this::issue).isInstanceOf(BusinessException.class).hasMessageContaining("不能重复签发")
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(409);
        assertThat(count("autonomous_tool_grants")).isEqualTo(1);
        // Any current super admin of the factory can stop it, then a new one can be issued.
        actor(otherAdmin);
        assertThat(controller.revoke("F-ISS", first).getData().revokedBy()).isEqualTo(otherAdmin);
        actor(admin);
        assertThat(issue().state()).isEqualTo("ACTIVE");
        assertThat(count("autonomous_tool_grants WHERE revoked_at IS NULL")).isEqualTo(1);
    }

    @Test void editingOrDisablingTheConfigRevokesItsGrantsAndRestoringDoesNotReviveThem() {
        issue();
        // A save that changes nothing the grant is bound to keeps the grant.
        save(config -> config.setDescription("only a description"));
        assertThat(count("autonomous_tool_grants WHERE revoked_at IS NULL")).isEqualTo(1);
        // Editing the cron revokes it, recorded as revoked by the editor.
        actor(otherAdmin);
        save(config -> config.setCronExpression("0 0 8 * * ?"));
        assertThat(count("autonomous_tool_grants WHERE revoked_at IS NULL")).isZero();
        assertThat(single("SELECT revoked_by FROM autonomous_tool_grants")).isEqualTo(otherAdmin);
        // Restoring the old content does not bring the revoked grant back.
        save(config -> config.setCronExpression("0 0 7 * * ?"));
        entityManager.clear();
        FactorySchedulerConfig restored = schedules.findById(configId).orElseThrow();
        assertThat(grants.consume(SchedulerGrantBinding.of(restored, "2.0.0", objectMapper), "slot:1",
                LocalDateTime.now())).isEmpty();

        // Disabling revokes a fresh grant as well.
        actor(admin);
        issue();
        save(config -> config.setEnabled(false));
        assertThat(count("autonomous_tool_grants WHERE revoked_at IS NULL")).isZero();
    }

    @Test void theDatabaseNotTheTokenDecidesWhoMayIssue() {
        // Each case: the request's actor is still the old token's factory super admin.
        for (String change : List.of("UPDATE users SET is_active = false WHERE id = " + "%d",
                "UPDATE users SET role_code = 'warehouse_manager' WHERE id = %d",
                "UPDATE users SET factory_id = 'F-ISS-2' WHERE id = %d")) {
            exec(change.formatted(admin));
            assertThatThrownBy(this::issue).isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(403);
            assertThatThrownBy(() -> controller.list("F-ISS")).isInstanceOf(BusinessException.class);
            exec("UPDATE users SET is_active = true, role_code = 'factory_super_admin', factory_id = 'F-ISS' WHERE id = "
                    + admin);
        }
        // Not the config's run-as user, or a disabled config.
        actor(otherAdmin);
        assertThatThrownBy(this::issue).hasMessageContaining("执行人是你");
        actor(admin);
        exec("UPDATE factory_scheduler_configs SET enabled = false WHERE id = " + configId);
        assertThatThrownBy(this::issue).hasMessageContaining("已停用");
        assertThat(count("autonomous_tool_grants")).isZero();
    }

    @Test void aRoleChangeCommittedWhileIssuingWaitsOrIsSeen() throws Exception {
        // Order 1: the demotion holds the grantor's row; issuing waits for it and then sees it.
        try (Connection demote = DriverManager.getConnection(url(), user(), password())) {
            demote.setAutoCommit(false);
            try (Statement statement = demote.createStatement()) {
                statement.executeUpdate("UPDATE users SET role_code = 'warehouse_manager' WHERE id = " + admin);
            }
            CompletableFuture<Object> issuing = CompletableFuture.supplyAsync(this::issueOnThisThread);
            assertBlocked(issuing);
            demote.commit();
            assertThat(issuing.get(30, TimeUnit.SECONDS)).isInstanceOf(BusinessException.class)
                    .extracting(refused -> ((BusinessException) refused).getMessage()).asString()
                    .contains("超级管理员");
        }
        assertThat(count("autonomous_tool_grants")).isZero();
        exec("UPDATE users SET role_code = 'factory_super_admin' WHERE id = " + admin);

        // Order 2: the issue transaction holds the grantor's row; the demotion waits until the grant
        // is committed (it then stops the grant's runs at the gateway, which re-reads the user).
        CompletableFuture<Void> demotion = new CompletableFuture<>();
        Object issued = tx.execute(status -> {
            Object result = issueOnThisThread();
            CompletableFuture.runAsync(() -> {
                try (Connection demote = DriverManager.getConnection(url(), user(), password());
                     Statement statement = demote.createStatement()) {
                    statement.executeUpdate("UPDATE users SET role_code = 'warehouse_manager' WHERE id = " + admin);
                    demotion.complete(null);
                } catch (Exception failure) {
                    demotion.completeExceptionally(failure);
                }
            });
            assertBlocked(demotion);
            return result;
        });
        assertThat(issued).isInstanceOf(com.cretas.aims.entity.ai.AutonomousToolGrant.class);
        demotion.get(30, TimeUnit.SECONDS);
        assertThat(count("autonomous_tool_grants")).isEqualTo(1);
        assertThat(single("SELECT count(*) FROM users WHERE role_code = 'warehouse_manager' AND id = " + admin))
                .isEqualTo(1L);
    }

    /**
     * The issuer itself, callable from any thread (the static policy stub is thread-local, so the
     * concurrent cases go below the controller's policy check, straight to the locked decision).
     */
    private Object issueOnThisThread() {
        try {
            return issuer.issue("F-ISS", admin, configId, "user_disable", "2.0.0", 2,
                    LocalDateTime.now().plusDays(1), LocalDateTime.now());
        } catch (BusinessException refused) {
            return refused;
        }
    }

    private static void assertBlocked(CompletableFuture<?> future) {
        try {
            future.get(2, TimeUnit.SECONDS);
            throw new AssertionError("expected to wait on the other transaction's row lock, but it finished");
        } catch (TimeoutException expected) {
            // still waiting on the lock
        } catch (Exception other) {
            throw new AssertionError("expected to wait on the lock, but it failed", other);
        }
    }

    private AutonomousGrantController.GrantView issue() {
        return controller.issue("F-ISS", new AutonomousGrantController.GrantRequest("SCHEDULER",
                String.valueOf(configId), "user_disable", 2, LocalDateTime.now().plusDays(1))).getData();
    }

    private void save(java.util.function.Consumer<FactorySchedulerConfig> change) {
        entityManager.clear();
        FactorySchedulerConfig config = schedules.findById(configId).orElseThrow();
        String before = issuer.revisionOf(config);
        change.accept(config);
        issuer.saveConfig(config, before, AutonomousToolPolicy.currentActorIdOrNull(), LocalDateTime.now());
    }

    private void exec(String sql) {
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(sql).executeUpdate());
    }

    private long count(String tableAndFilter) {
        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM " + tableAndFilter)
                .getSingleResult()).longValue();
    }

    private Long single(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private void factory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName("issue " + id);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
    }

    private Long user(String factoryId, String username, String role) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user.setRoleCode(role);
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private static void actor(Long id) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, "F-ISS", "factory_super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
