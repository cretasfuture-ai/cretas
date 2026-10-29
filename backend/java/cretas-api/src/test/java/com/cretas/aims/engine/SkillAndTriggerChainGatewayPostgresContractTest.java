package com.cretas.aims.engine;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.DefaultToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.TestRuntimePolicies;
import com.cretas.aims.ai.tool.gateway.ToolConfirmationLease;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry;
import com.cretas.aims.ai.tool.impl.material.MaterialExpiredQueryTool;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.execution.ToolPermissionChain;
import com.cretas.aims.service.governance.ToolSimilarityService;
import com.cretas.aims.service.skill.contract.FactoryScopedReferenceChecker;
import com.cretas.aims.service.skill.contract.SavedSkillService;
import com.cretas.aims.service.skill.contract.SkillParameterContractValidator;
import com.cretas.aims.service.skill.contract.SkillRunStore;
import com.cretas.aims.service.skill.impl.SkillExecutorImpl;
import com.cretas.aims.utils.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S6-02 on PostgreSQL through the real {@link DefaultToolExecutionGateway} (principal re-read from the
 * users and factories tables, the shipped runtime policy, the real audit ledger) and the real
 * {@link MaterialExpiredQueryTool}:
 * <ul>
 *   <li>a Skill's governed tool runs through the gateway as the Skill's user and leaves an audit
 *       event and a run receipt; a user without the tool's permission is denied and the tool never runs;</li>
 *   <li>a successful run saved as a reusable Skill reruns with new, contract-valid parameters as a new
 *       receipt, refuses out-of-bounds, undeclared, identity and other-factory parameters before any
 *       tool runs, and cannot do more than the user running it may do;</li>
 *   <li>an event chain step runs through the gateway as the chain's run-as user (source TRIGGER).</li>
 * </ul>
 * Only the module-level permission snapshot and the inventory service under the tool are stubbed;
 * the stub records which identity the tool ran as. The shipped policy of material_expired_query is
 * widened to TRIGGER for this test only (shipped policies admit no event chain).
 * Disposable loopback PostgreSQL only (CRETAS_WORKFLOW_PG_URL).
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({DefaultToolExecutionGateway.class, ToolPrincipalPolicy.class, ToolExecutionLedgerService.class,
        ToolConfirmationLease.class, LegacyToolMigrationRegistry.class, AuthenticatedToolPrincipalFactory.class,
        ToolRegistry.class, MaterialExpiredQueryTool.class,
        com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool.class, AutonomousToolRunner.class, AutonomousGrantService.class,
        AutonomousGrantIssuer.class, TriggerChainExecutor.class, SkillExecutorImpl.class,
        SkillParameterContractValidator.class, FactoryScopedReferenceChecker.class, SkillRunStore.class,
        SavedSkillService.class, com.cretas.aims.ai.tool.ToolRbacGuard.class,
        SkillAndTriggerChainGatewayPostgresContractTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SkillAndTriggerChainGatewayPostgresContractTest {

    private static final String TOOL = "material_expired_query";

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
        // As in production (application-pg-prod.properties): a write outside a transaction is lost,
        // so a receipt that is not written transactionally would never be seen.
        registry.add("spring.datasource.hikari.auto-commit", () -> "false");
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }

        @Bean ToolRuntimeRegistry toolRuntimeRegistry(ToolRegistry tools, FactoryToolConfigRepository configs) {
            return TestRuntimePolicies.runtimeWithSources(tools, configs, TOOL, Set.of(ToolExecutionSource.TRIGGER));
        }
    }

    @MockBean PermissionService permissionService;
    @MockBean MaterialBatchService materialBatchService;
    @MockBean ToolSimilarityService toolSimilarityService;
    @MockBean PreviewTokenService previewTokenService;
    @MockBean DashScopeClient dashScopeClient;
    // Legacy (ungoverned) lane only; the governed tool here must never reach either of them.
    @MockBean WriteGuardService writeGuardService;
    @MockBean ToolPermissionChain toolPermissionChain;
    @MockBean com.cretas.aims.service.skill.SkillRouterService skillRouter;
    @MockBean com.cretas.aims.service.skill.SkillRegistry skillRegistry;

    @Autowired SkillExecutorImpl skills;
    @Autowired SavedSkillService savedSkills;
    @Autowired TriggerChainExecutor chains;
    @Autowired FactoryTriggerChainRepository chainRepository;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private Long warehouseUser;
    private Long salesUser;
    private final List<Long> seenAsUser = new ArrayList<>();

    @BeforeEach void fixtures() throws Exception {
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS factory_saved_skills, skill_run_records CASCADE");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261103_109__skill_runs_and_saved_skills.sql"));
            connection.commit();
        }
        tx.executeWithoutResult(status -> {
            for (String table : List.of("tool_execution_audit_events", "factory_trigger_chains", "suppliers", "users")) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM factories WHERE id IN ('F-SK', 'F-SK-OTHER')").executeUpdate();
            factory("F-SK");
            factory("F-SK-OTHER");
            warehouseUser = user("F-SK", "sk-warehouse");
            salesUser = user("F-SK", "sk-sales");
        });
        tx.executeWithoutResult(status -> {
            supplier("SUP-OWN", "F-SK");
            supplier("SUP-FOREIGN", "F-SK-OTHER");
        });
        seenAsUser.clear();
        when(permissionService.resolveModuleLevelsForAuthorization(any())).thenAnswer(call -> {
            User user = call.getArgument(0);
            return user.getId().equals(warehouseUser) ? Map.of("warehouse", "r") : Map.of("sales", "r");
        });
        when(materialBatchService.getExpiredBatches("F-SK")).thenAnswer(call -> {
            seenAsUser.add(SecurityUtils.currentUserId());
            return List.of();
        });
    }

    @Test void skillGovernedToolRunsThroughTheRealGatewayAsTheSkillUserWithAReceipt() {
        SkillResult result = skills.execute(skill(), context(warehouseUser, Map.of("limit", 5)));

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        assertThat(seenAsUser).containsExactly(warehouseUser);
        assertThat(count("tool_execution_audit_events WHERE source = 'SKILL_WORKFLOW' AND outcome_status = 'SUCCEEDED'"
                + " AND tool_name = '" + TOOL + "' AND principal_id = '" + warehouseUser + "' AND tenant_id = 'F-SK'"))
                .isEqualTo(1);
        assertThat(result.getRunId()).isNotNull();
        assertThat(text("SELECT status FROM skill_run_records WHERE id = '" + result.getRunId() + "'")).isEqualTo("SUCCEEDED");
        assertThat(text("SELECT steps->0->>'auditEventId' FROM skill_run_records WHERE id = '" + result.getRunId() + "'"))
                .isEqualTo(text("SELECT id FROM tool_execution_audit_events"));
        // Identity never travels as a parameter: the gateway binds it.
        assertThat(text("SELECT steps->0->'params' FROM skill_run_records")).doesNotContain("factoryId").doesNotContain("userId");
        verify(toolPermissionChain, never()).denyReason(any(), any(), any(), any(), any());
    }

    @Test void skillUserWithoutTheToolPermissionIsDeniedByTheGatewayAndTheToolNeverRuns() {
        SkillResult result = skills.execute(skill(), context(salesUser, Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(seenAsUser).isEmpty();
        verify(materialBatchService, never()).getExpiredBatches(any());
        assertThat(count("tool_execution_audit_events WHERE source = 'SKILL_WORKFLOW' AND outcome_status = 'DENIED'"
                + " AND principal_id = '" + salesUser + "'")).isEqualTo(1);
        assertThat(text("SELECT status FROM skill_run_records WHERE id = '" + result.getRunId() + "'")).isEqualTo("DENIED");
        // A denied run cannot become a reusable Skill.
        assertThatThrownBy(() -> savedSkills.save("F-SK", salesUser,
                new SavedSkillService.SaveRequest(result.getRunId(), "denied-run", null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("经工具网关执行成功");
    }

    @Test void savedSkillRerunsWithNewParametersAsANewReceiptAndRefusesOutOfContractParameters() throws Exception {
        SkillResult source = skills.execute(skill(), context(warehouseUser, Map.of("limit", 5, "supplierId", "SUP-OWN")));
        assertThat(source.isSuccess()).as(source.getMessage()).isTrue();
        var contract = new ObjectMapper().readTree("{\"params\":["
                + "{\"name\":\"limit\",\"type\":\"INTEGER\",\"required\":true,\"min\":1,\"max\":100},"
                + "{\"name\":\"supplierId\",\"type\":\"STRING\",\"factoryScopedRef\":\"SUPPLIER\"}]}");

        // Saving cannot add a parameter the run did not have.
        var widened = new ObjectMapper().readTree("{\"params\":[{\"name\":\"warehouseCode\",\"type\":\"STRING\"}]}");
        assertThatThrownBy(() -> savedSkills.save("F-SK", warehouseUser,
                new SavedSkillService.SaveRequest(source.getRunId(), "widened", null, widened, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能增加参数");
        // Another user cannot save someone else's run.
        assertThatThrownBy(() -> savedSkills.save("F-SK", salesUser,
                new SavedSkillService.SaveRequest(source.getRunId(), "not-mine", null, contract, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("自己执行成功");

        SkillRunStore.SavedSkill saved = savedSkills.save("F-SK", warehouseUser,
                new SavedSkillService.SaveRequest(source.getRunId(), "expired-check", "过期检查", contract, null));
        assertThat(saved.sourceRunId()).isEqualTo(source.getRunId());
        long auditsBefore = count("tool_execution_audit_events");

        SavedSkillService.RunResult rerun = savedSkills.runSaved("F-SK", warehouseUser, "warehouse_manager",
                saved.id(), Map.of("limit", 7), "s1");
        assertThat(rerun.result().isSuccess()).as(rerun.result().getMessage()).isTrue();
        assertThat(rerun.result().getRunId()).isNotEqualTo(source.getRunId());
        assertThat(text("SELECT saved_skill_id FROM skill_run_records WHERE id = '" + rerun.result().getRunId() + "'"))
                .isEqualTo(saved.id());
        assertThat(text("SELECT steps->0->'params'->>'limit' FROM skill_run_records WHERE id = '"
                + rerun.result().getRunId() + "'")).isEqualTo("7");
        // The original definition and its source run are unchanged and still traceable.
        assertThat(text("SELECT steps->0->'params'->>'limit' FROM skill_run_records WHERE id = '"
                + source.getRunId() + "'")).isEqualTo("5");
        assertThat(count("tool_execution_audit_events")).isEqualTo(auditsBefore + 1);

        // Refused before any tool runs: no audit event, no tool call, the reason names the parameter.
        int callsBefore = seenAsUser.size();
        long auditsAfterRerun = count("tool_execution_audit_events");
        assertThat(savedSkills.runSaved("F-SK", warehouseUser, "warehouse_manager", saved.id(),
                Map.of("limit", 500), "s2").result().getMessage()).contains("limit").contains("不能大于 100");
        assertThat(savedSkills.runSaved("F-SK", warehouseUser, "warehouse_manager", saved.id(),
                Map.of("limit", 5, "factoryId", "F-SK-OTHER"), "s3").result().getMessage()).contains("factoryId");
        assertThat(savedSkills.runSaved("F-SK", warehouseUser, "warehouse_manager", saved.id(),
                Map.of("limit", 5, "warehouseCode", "W9"), "s4").result().getMessage()).contains("warehouseCode");
        assertThat(savedSkills.runSaved("F-SK", warehouseUser, "warehouse_manager", saved.id(),
                Map.of("limit", 5, "supplierId", "SUP-FOREIGN"), "s5").result().getMessage())
                .contains("supplierId").contains("不属于本工厂");
        assertThat(savedSkills.runSaved("F-SK", warehouseUser, "warehouse_manager", saved.id(),
                Map.of(), "s6").result().getMessage()).contains("缺少必填参数：limit");
        assertThat(seenAsUser).hasSize(callsBefore);
        assertThat(count("tool_execution_audit_events")).isEqualTo(auditsAfterRerun);

        // Positive control for the scope check: this factory's own supplier is accepted.
        assertThat(savedSkills.runSaved("F-SK", warehouseUser, "warehouse_manager", saved.id(),
                Map.of("limit", 5, "supplierId", "SUP-OWN"), "s7").result().isSuccess()).isTrue();

        // Reusing never widens authority: a user without the tool's permission is denied by the gateway.
        SavedSkillService.RunResult foreign = savedSkills.runSaved("F-SK", salesUser, "sales_manager", saved.id(),
                Map.of("limit", 5), "s8");
        assertThat(foreign.result().isSuccess()).isFalse();
        assertThat(count("tool_execution_audit_events WHERE outcome_status = 'DENIED' AND principal_id = '"
                + salesUser + "'")).isEqualTo(1);
    }

    @Test void theRegisteredSkillEntryRunsAsTheCallerAndLeavesASavableReceipt() {
        when(skillRegistry.getSkill("expired-materials")).thenReturn(java.util.Optional.of(skill()));
        when(skillRouter.executeSkill(org.mockito.ArgumentMatchers.eq("expired-materials"), any()))
                .thenAnswer(call -> skills.execute(skill(), call.getArgument(1)));

        SkillResult result = savedSkills.runRegistered("F-SK", warehouseUser, "warehouse_manager",
                "expired-materials", Map.of("limit", 3), null, "entry-1");

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        assertThat(seenAsUser).containsExactly(warehouseUser);
        assertThat(savedSkills.recentRuns("F-SK", warehouseUser)).extracting(SkillRunStore.Run::id)
                .containsExactly(result.getRunId());
        assertThat(savedSkills.recentRuns("F-SK", salesUser)).isEmpty();
        SkillRunStore.SavedSkill saved = savedSkills.save("F-SK", warehouseUser,
                new SavedSkillService.SaveRequest(result.getRunId(), "from-entry", null, null, null));
        assertThat(saved.steps()).extracting(SkillRunStore.SavedStep::tool).containsExactly(TOOL);
        // Identity cannot be smuggled through the entry either.
        assertThatThrownBy(() -> savedSkills.runRegistered("F-SK", warehouseUser, "warehouse_manager",
                "expired-materials", Map.of("factoryId", "F-SK-OTHER"), null, "entry-2"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("factoryId");
    }

    @Test void theBuiltInMaterialBatchLookupSkillRunsSavesAndRerunsWithNewParameters() {
        // The real built-in definition, as the application registers it (not a test-made one).
        SkillDefinition lookup = com.cretas.aims.ai.tool.GatedDataToolAiPathPermissionTest.defaultSkills()
                .getSkill("material-batch-lookup").orElseThrow();
        when(skillRegistry.getSkill("material-batch-lookup")).thenReturn(java.util.Optional.of(lookup));
        when(skillRouter.executeSkill(org.mockito.ArgumentMatchers.eq("material-batch-lookup"), any()))
                .thenAnswer(call -> skills.execute(lookup, call.getArgument(1)));
        List<com.cretas.aims.dto.common.PageRequest> queries = new ArrayList<>();
        when(materialBatchService.getMaterialBatchList(org.mockito.ArgumentMatchers.eq("F-SK"), any()))
                .thenAnswer(call -> {
                    queries.add(call.getArgument(1));
                    seenAsUser.add(SecurityUtils.currentUserId());
                    return com.cretas.aims.dto.common.PageResponse.of(List.of(), 1, 10, 0L);
                });

        SkillResult first = savedSkills.runRegistered("F-SK", warehouseUser, "warehouse_manager",
                "material-batch-lookup", Map.of("status", "AVAILABLE", "size", 5), null, "lookup-1");
        assertThat(first.isSuccess()).as(first.getMessage()).isTrue();
        assertThat(first.getRunId()).isNotNull();
        assertThat(text("SELECT steps->0->>'auditEventId' FROM skill_run_records WHERE id = '" + first.getRunId() + "'"))
                .isNotNull();

        // Saved with the contract inferred from the run (status text, size integer), bounds edited.
        var contract = new ObjectMapper().createObjectNode();
        var params = contract.putArray("params");
        params.addObject().put("name", "status").put("type", "ENUM").put("required", true)
                .putArray("allowedValues").add("AVAILABLE").add("EXPIRED").add("DEPLETED");
        params.addObject().put("name", "size").put("type", "INTEGER").put("min", 1).put("max", 50);
        SkillRunStore.SavedSkill saved = savedSkills.save("F-SK", warehouseUser,
                new SavedSkillService.SaveRequest(first.getRunId(), "batch-by-status", "按状态查批次", contract, null));

        SavedSkillService.RunResult rerun = savedSkills.runSaved("F-SK", warehouseUser, "warehouse_manager",
                saved.id(), Map.of("status", "EXPIRED", "size", 3), "lookup-2");
        assertThat(rerun.result().isSuccess()).as(rerun.result().getMessage()).isTrue();
        assertThat(queries).hasSize(2);
        assertThat(queries.get(0).getStatus()).isEqualTo("AVAILABLE");
        assertThat(queries.get(0).getSize()).isEqualTo(5);
        assertThat(queries.get(1).getStatus()).isEqualTo("EXPIRED");
        assertThat(queries.get(1).getSize()).isEqualTo(3);
        assertThat(seenAsUser).containsExactly(warehouseUser, warehouseUser);
        assertThat(text("SELECT saved_skill_id FROM skill_run_records WHERE id = '" + rerun.result().getRunId() + "'"))
                .isEqualTo(saved.id());

        // Out of contract: refused before the tool runs.
        assertThat(savedSkills.runSaved("F-SK", warehouseUser, "warehouse_manager", saved.id(),
                Map.of("status", "ALL", "size", 3), "lookup-3").result().getMessage()).contains("status");
        assertThat(queries).hasSize(2);
    }

    @Test void theRegisteredSkillEntryEnforcesTheSkillsOwnRequiredPermission() {
        SkillDefinition guarded = SkillDefinition.builder().name("guarded-materials").displayName("受限原料")
                .tools(List.of(TOOL)).enabled(true).requiredPermission("inventory:read, warehouse:read").build();
        when(skillRegistry.getSkill("guarded-materials")).thenReturn(java.util.Optional.of(guarded));
        when(skillRouter.executeSkill(org.mockito.ArgumentMatchers.eq("guarded-materials"), any()))
                .thenAnswer(call -> skills.execute(guarded, call.getArgument(1)));
        when(permissionService.hasAnyPermission(any(User.class), any(String[].class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            return user.getId().equals(warehouseUser);
        });

        assertThatThrownBy(() -> savedSkills.runRegistered("F-SK", salesUser, "sales_manager",
                "guarded-materials", Map.of(), null, "guard-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("inventory:read");
        assertThat(seenAsUser).isEmpty();
        assertThat(count("tool_execution_audit_events")).isZero();

        // Positive control: the permitted user runs it.
        assertThat(savedSkills.runRegistered("F-SK", warehouseUser, "warehouse_manager", "guarded-materials",
                Map.of(), null, "guard-2").isSuccess()).isTrue();
        assertThat(seenAsUser).containsExactly(warehouseUser);
        assertThatThrownBy(() -> savedSkills.runRegistered("F-SK", warehouseUser, "warehouse_manager",
                "no-such-skill", Map.of(), null, "guard-3"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有找到");
    }

    @Test void triggerChainStepRunsThroughTheRealGatewayAsTheRunAsUser() throws Exception {
        FactoryTriggerChain chain = chain(warehouseUser);
        runChain(chain);

        assertThat(seenAsUser).containsExactly(warehouseUser);
        assertThat(count("tool_execution_audit_events WHERE source = 'TRIGGER' AND outcome_status = 'SUCCEEDED'"
                + " AND principal_id = '" + warehouseUser + "' AND tenant_id = 'F-SK'")).isEqualTo(1);
        assertThat(chainStatus(chain)).isEqualTo("SUCCESS");
    }

    @Test void triggerChainRunAsUserWithoutPermissionIsDeniedAndAuditedAndNoRunAsNeverReachesTheGateway()
            throws Exception {
        FactoryTriggerChain denied = chain(salesUser);
        runChain(denied);
        assertThat(seenAsUser).isEmpty();
        assertThat(count("tool_execution_audit_events WHERE source = 'TRIGGER' AND outcome_status = 'DENIED'"))
                .isEqualTo(1);
        assertThat(chainStatus(denied)).isEqualTo("FAILED");

        FactoryTriggerChain noRunAs = chain(null);
        runChain(noRunAs);
        assertThat(count("tool_execution_audit_events")).isEqualTo(1);
        assertThat(chainStatus(noRunAs)).isEqualTo("FAILED");
        verify(materialBatchService, never()).getExpiredBatches(any());
    }

    private SkillDefinition skill() {
        return SkillDefinition.builder().name("expired-materials").displayName("过期原料")
                .tools(List.of(TOOL)).enabled(true).build();
    }

    private SkillContext context(Long userId, Map<String, Object> params) {
        return SkillContext.builder().factoryId("F-SK").userId(String.valueOf(userId)).userRole("warehouse_manager")
                .sessionId("sess-" + userId).userQuery("看看过期原料")
                .extractedParams(new java.util.HashMap<>(params)).build();
    }

    private FactoryTriggerChain chain(Long runAs) {
        FactoryTriggerChain chain = new FactoryTriggerChain();
        chain.setFactoryId("F-SK");
        chain.setChainCode("CH-" + System.nanoTime());
        chain.setEventType("MaterialReceivedEvent");
        chain.setEnabled(true);
        chain.setErrorStrategy("CONTINUE");
        chain.setSteps(List.of(Map.of("order", 1, "tool", TOOL, "params", Map.of())));
        chain.setRunAsUserId(runAs);
        return chainRepository.saveAndFlush(chain);
    }

    private void runChain(FactoryTriggerChain chain) throws Exception {
        Method run = TriggerChainExecutor.class.getDeclaredMethod("executeChain",
                FactoryTriggerChain.class, String.class, ApplicationEvent.class);
        run.setAccessible(true);
        run.invoke(AopTestUtils.getTargetObject(chains), chain, "F-SK", new ApplicationEvent("test") { });
    }

    private String chainStatus(FactoryTriggerChain chain) {
        return text("SELECT last_execution_status FROM factory_trigger_chains WHERE id = " + chain.getId());
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

    private void factory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName("skill " + id);
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
        user.setRoleCode("warehouse_manager");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private void supplier(String id, String factoryId) {
        jdbc.update("INSERT INTO suppliers (id, factory_id, code, supplier_code, name, is_active, created_by, version,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, true, ?, 0, now(), now())",
                id, factoryId, id, id, "supplier " + id, warehouseUser);
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
