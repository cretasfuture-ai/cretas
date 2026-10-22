package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.engine.AutonomousGrantService;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Issue through the real endpoint, persist on PostgreSQL (jsonb params with numbers, nesting and
 * arrays), then build the run-time binding from the reloaded config exactly as the scheduler will:
 * the issued grant must be consumable by it, and stop applying once the config is edited. Real
 * users table and repositories; the permission snapshot and the tool are stubbed.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({AutonomousGrantController.class, AutonomousGrantService.class, AutonomousGrantIssuePostgresContractTest.Json.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutonomousGrantIssuePostgresContractTest {

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
    static class Json {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @MockBean ToolRegistry toolRegistry;
    @MockBean PermissionService permissionService;

    @Autowired AutonomousGrantController controller;
    @Autowired AutonomousGrantService grants;
    @Autowired FactorySchedulerConfigRepository schedules;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private final ToolExecutor tool = mock(ToolExecutor.class);
    private Long admin;

    @BeforeEach void fixtures() {
        tx.executeWithoutResult(status -> {
            for (String table : List.of("autonomous_tool_grant_runs", "autonomous_tool_grants",
                    "factory_scheduler_configs", "users")) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM factories WHERE id = 'F-ISS'").executeUpdate();
            Factory factory = new Factory();
            factory.setId("F-ISS");
            factory.setName("issue test");
            factory.setType(FactoryType.FACTORY);
            factory.setLevel(0);
            factory.setIsActive(true);
            factory.setManuallyVerified(false);
            factory.setAiWeeklyQuota(20);
            entityManager.persist(factory);
            User user = new User();
            user.setFactoryId("F-ISS");
            user.setUsername("iss-admin");
            user.setPasswordHash("test-only");
            user.setRoleCode("factory_super_admin");
            user.setIsActive(true);
            entityManager.persist(user);
            entityManager.flush();
            admin = user.getId();
        });
        when(tool.getAccessMode()).thenReturn(AccessMode.WRITE);
        when(tool.getVersion()).thenReturn("2.0.0");
        when(toolRegistry.getExecutor("user_disable")).thenReturn(Optional.of(tool));
        when(permissionService.hasAllPermissions(any(User.class), eq("system:read_write"))).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, admin, "F-ISS", "factory_super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach void clearActor() { RequestContextHolder.resetRequestAttributes(); }

    @Test void issuedGrantIsConsumableByTheRunTimeBindingAndStopsApplyingOnceTheConfigIsEdited() {
        Map<String, Object> params = Map.of("username", "iss-target", "limit", 5,
                "names", List.of("a", "b"), "nested", Map.of("ratio", 1.5, "on", true));
        Long configId = schedules.saveAndFlush(FactorySchedulerConfig.builder().factoryId("F-ISS")
                .taskCode("disable-nightly").cronExpression("0 0 7 * * ?").enabled(true)
                .toolOrMethod("user_disable").params(params).runAsUserId(admin).build()).getId();

        String grantId;
        try (MockedStatic<AutonomousToolPolicy> policy = mockStatic(AutonomousToolPolicy.class, CALLS_REAL_METHODS)) {
            policy.when(() -> AutonomousToolPolicy.requireSourceAllowed("user_disable", ToolExecutionSource.SCHEDULER))
                    .then(call -> null);
            grantId = controller.issue("F-ISS", new AutonomousGrantController.GrantRequest("SCHEDULER",
                    String.valueOf(configId), "user_disable", 2, LocalDateTime.now().plusDays(1))).getData().id();
        }
        assertThat(single("SELECT granted_by FROM autonomous_tool_grants WHERE id = '" + grantId + "'"))
                .isEqualTo(admin);

        // What the scheduler will see: the config reloaded from PostgreSQL after the jsonb round trip.
        entityManager.clear();
        FactorySchedulerConfig fired = schedules.findById(configId).orElseThrow();
        assertThat(grants.consume(controller.binding(fired, tool), "fire:1", LocalDateTime.now()))
                .hasValueSatisfying(consumption -> assertThat(consumption.grantId()).isEqualTo(grantId));

        fired.setCronExpression("0 0 8 * * ?");
        schedules.saveAndFlush(fired);
        entityManager.clear();
        FactorySchedulerConfig edited = schedules.findById(configId).orElseThrow();
        assertThat(grants.consume(controller.binding(edited, tool), "fire:2", LocalDateTime.now())).isEmpty();
        assertThat(single("SELECT used_runs FROM autonomous_tool_grants WHERE id = '" + grantId + "'"))
                .isEqualTo(1L);
    }

    private Long single(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
