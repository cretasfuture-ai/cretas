package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.DefaultToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.ToolConfirmationLease;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry;
import com.cretas.aims.ai.tool.impl.material.MaterialExpiredQueryTool;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.governance.ToolSimilarityService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.utils.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * End to end on PostgreSQL: a persisted cron config is run by the real scheduler through the real
 * runner, the real {@link DefaultToolExecutionGateway} (principal rehydrated from the users and
 * factories tables, real runtime policy, real audit ledger) and the real
 * {@link MaterialExpiredQueryTool}. Only {@link PermissionService} (the module-level snapshot per
 * user) and the inventory service under the tool are stubbed; the stub records which identity the
 * tool ran as. Runs only against a disposable loopback PostgreSQL (CRETAS_WORKFLOW_PG_URL).
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({DefaultToolExecutionGateway.class, ToolPrincipalPolicy.class, ToolRuntimeRegistry.class,
        ToolExecutionLedgerService.class, ToolConfirmationLease.class, LegacyToolMigrationRegistry.class,
        AuthenticatedToolPrincipalFactory.class, ToolRegistry.class, MaterialExpiredQueryTool.class,
        AutonomousToolRunner.class, DynamicSchedulerService.class,
        AutonomousRunAsEndToEndPostgresContractTest.Json.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutonomousRunAsEndToEndPostgresContractTest {

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

    @MockBean PermissionService permissionService;
    @MockBean MaterialBatchService materialBatchService;
    @MockBean ToolSimilarityService toolSimilarityService;
    @MockBean PreviewTokenService previewTokenService;

    @Autowired DynamicSchedulerService scheduler;
    @Autowired FactorySchedulerConfigRepository schedules;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private Long warehouseUser;
    private Long otherFactoryUser;
    private final List<Long> seenAsUser = new ArrayList<>();
    private final List<Object> seenRequest = new ArrayList<>();

    @BeforeEach void fixtures() {
        tx.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM tool_execution_audit_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM factory_scheduler_configs").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM factories WHERE id IN ('F-E2E', 'F-E2E-OTHER')").executeUpdate();
            factory("F-E2E");
            factory("F-E2E-OTHER");
            warehouseUser = user("F-E2E", "e2e-warehouse", true);
            otherFactoryUser = user("F-E2E-OTHER", "e2e-other", true);
        });
        seenAsUser.clear();
        seenRequest.clear();
        when(permissionService.resolveModuleLevelsForAuthorization(any())).thenReturn(Map.of("warehouse", "r"));
        when(materialBatchService.getExpiredBatches("F-E2E")).thenAnswer(call -> {
            seenAsUser.add(SecurityUtils.currentUserId());
            seenRequest.add(RequestContextHolder.getRequestAttributes());
            return List.of();
        });
    }

    @Test void scheduledToolRunsThroughTheRealGatewayAsTheRunAsUserAndIsAudited() throws Exception {
        Long id = schedule(warehouseUser);
        run(id);

        assertThat(seenAsUser).containsExactly(warehouseUser);
        assertThat(seenRequest).containsExactly((Object) null);
        assertThat(status(id)).isEqualTo("SUCCESS");
        ToolExecutionAuditEvent audit = onlyAudit();
        assertThat(audit.getToolName()).isEqualTo("material_expired_query");
        assertThat(audit.getSource()).isEqualTo(ToolExecutionSource.SCHEDULER);
        assertThat(audit.getPrincipalId()).isEqualTo(String.valueOf(warehouseUser));
        assertThat(audit.getTenantId()).isEqualTo("F-E2E");
        assertThat(audit.getOutcomeStatus()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
    }

    @Test void permissionRevokedAfterEnablingIsDeniedOnTheNextRunAndAudited() throws Exception {
        when(permissionService.resolveModuleLevelsForAuthorization(any())).thenReturn(Map.of("sales", "r"));
        Long id = schedule(warehouseUser);
        run(id);

        assertThat(seenAsUser).isEmpty();
        assertThat(status(id)).isEqualTo("GATEWAY_DENIED");
        assertThat(onlyAudit().getOutcomeStatus()).isEqualTo(ToolExecutionStatus.DENIED);
    }

    @Test void deactivatedRunAsUserOrAnotherFactorysUserIsDenied() throws Exception {
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE users SET is_active = false WHERE id = :id").setParameter("id", warehouseUser).executeUpdate());
        Long deactivated = schedule(warehouseUser);
        run(deactivated);
        Long foreign = schedule(otherFactoryUser);
        run(foreign);

        verify(materialBatchService, never()).getExpiredBatches(any());
        assertThat(status(deactivated)).isEqualTo("GATEWAY_DENIED");
        assertThat(status(foreign)).isEqualTo("GATEWAY_DENIED");
    }

    @Test void noRunAsUserNeverReachesTheGatewayOrTheAuditLog() throws Exception {
        Long id = schedule(null);
        run(id);
        assertThat(status(id)).isEqualTo("RUN_AS_MISSING");
        assertThat(count("tool_execution_audit_events")).isZero();
        verify(materialBatchService, never()).getExpiredBatches(any());
    }

    private void run(Long id) throws Exception {
        FactorySchedulerConfig config = schedules.findById(id).orElseThrow();
        Method execute = DynamicSchedulerService.class.getDeclaredMethod("executeTask", FactorySchedulerConfig.class);
        execute.setAccessible(true);
        // executeTask is private: invoke it on the target, metadata still persists through the proxy (self).
        execute.invoke(org.springframework.test.util.AopTestUtils.getTargetObject(scheduler), config);
    }

    private Long schedule(Long runAs) {
        return schedules.saveAndFlush(FactorySchedulerConfig.builder().factoryId("F-E2E")
                .taskCode("expiry-" + System.nanoTime()).cronExpression("0 0 7 * * ?").enabled(true)
                .toolOrMethod("material_expired_query").params(Map.of()).runAsUserId(runAs).build()).getId();
    }

    private String status(Long id) {
        return (String) entityManager.createNativeQuery(
                "SELECT last_execution_status FROM factory_scheduler_configs WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private ToolExecutionAuditEvent onlyAudit() {
        List<ToolExecutionAuditEvent> rows = entityManager.createQuery(
                "select e from ToolExecutionAuditEvent e", ToolExecutionAuditEvent.class).getResultList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private long count(String table) {
        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue();
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

    private Long user(String factoryId, String username, boolean active) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user.setRoleCode("warehouse_manager");
        user.setIsActive(active);
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }
}
