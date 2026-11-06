package com.cretas.aims.integration;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ApprovalPolicy;
import com.cretas.aims.ai.tool.gateway.ConfirmationPolicy;
import com.cretas.aims.ai.tool.gateway.DataClassification;
import com.cretas.aims.ai.tool.gateway.DefaultToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.IdempotencyPolicy;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolDescriptor;
import com.cretas.aims.ai.tool.gateway.ToolEgressPolicy;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasApplyTemplateTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasToggleModuleTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasToggleSkillTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasToggleToolTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasUpdateFieldTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasUpdateTriggerChainTool;
import com.cretas.aims.controller.CanvasAIController;
import com.cretas.aims.controller.DynamicFieldController;
import com.cretas.aims.controller.TriggerChainController;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.config.ConfigChangeLog;
import com.cretas.aims.entity.config.FactoryConfiguration;
import com.cretas.aims.entity.config.FactoryDefaultValue;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.config.FactorySkillConfig;
import com.cretas.aims.entity.config.FactoryTemplate;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.entity.config.ModuleSchema;
import com.cretas.aims.entity.config.CanvasDynamicField;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.engine.DDLExecutor;
import com.cretas.aims.engine.DynamicFieldService;
import com.cretas.aims.engine.DynamicTableService;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ai.ToolExecutionAuditEventRepository;
import com.cretas.aims.repository.ai.ToolExecutionIdempotencyRepository;
import com.cretas.aims.repository.config.ConfigChangeLogRepository;
import com.cretas.aims.repository.config.FactoryConfigurationRepository;
import com.cretas.aims.repository.config.FactoryDefaultValueRepository;
import com.cretas.aims.repository.config.CanvasDynamicFieldRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import com.cretas.aims.repository.config.FactoryTemplateRepository;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.cretas.aims.repository.config.ModuleSchemaRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.config.CanvasConfigCommandService;
import com.cretas.aims.service.config.CanvasConfigPlanBindingService;
import com.cretas.aims.service.config.CanvasConfigStateReader;
import com.cretas.aims.service.config.impl.FactoryConfigServiceImpl;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Opt-in PostgreSQL/JPA proof for all six Canvas configuration writers.
 * The test exercises the real plan binding, lock, writer transaction and committed readback.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.datasource.hikari.maximum-pool-size=8",
        "spring.datasource.hikari.minimum-idle=1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@Import({CanvasConfigStateReader.class, CanvasConfigPlanBindingService.class,
        CanvasConfigCommandService.class, FactoryConfigServiceImpl.class,
        FactoryFeatureMenuWriteLock.class, PreviewTokenServiceImpl.class,
        ToolExecutionLedgerService.class, com.cretas.aims.ai.tool.gateway.ToolConfirmationLease.class,
        DefaultToolExecutionGateway.class, CanvasAIController.class,
        DynamicFieldController.class,
        TriggerChainController.class,
        CanvasToggleModuleTool.class, CanvasUpdateFieldTool.class, CanvasApplyTemplateTool.class,
        CanvasToggleToolTool.class, CanvasToggleSkillTool.class, CanvasUpdateTriggerChainTool.class,
        CanvasConfigPlanBindingPostgresContractTest.CanvasSchemaFilterConfiguration.class})
class CanvasConfigPlanBindingPostgresContractTest {
    private static final String PRIVATE_SCHEMA =
            "canvas_plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String APPLICATION_NAME = "cretas_canvas_plan_binding_test";
    private static final long USER_ID = 77001L;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String rawUrl = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        createPrivateSchema(rawUrl);
        registry.add("spring.datasource.url", () -> appendQuery(rawUrl,
                "currentSchema=" + PRIVATE_SCHEMA + "&stringtype=unspecified&ApplicationName=" + APPLICATION_NAME));
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @AfterAll
    static void dropPrivateSchema() throws Exception {
        String rawUrl = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (Connection connection = DriverManager.getConnection(rawUrl, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + PRIVATE_SCHEMA + "\" CASCADE");
        }
    }

    private static void createPrivateSchema(String rawUrl) {
        try (Connection connection = DriverManager.getConnection(rawUrl, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + PRIVATE_SCHEMA + "\"");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create private Canvas PostgreSQL schema", exception);
        }
    }

    private static String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    @Autowired private DataSource dataSource;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CanvasConfigPlanBindingService bindings;
    @Autowired private CanvasConfigCommandService executor;
    @Autowired private FactoryConfigurationRepository configurations;
    @Autowired private FactoryModuleConfigRepository modules;
    @Autowired private FactoryToolConfigRepository tools;
    @Autowired private FactorySkillConfigRepository skills;
    @Autowired private FactoryTriggerChainRepository triggers;
    @Autowired private FactoryTemplateRepository templates;
    @Autowired private ModuleSchemaRepository schemas;
    @Autowired private FactoryRepository factories;
    @Autowired private PreviewTokenService previewTokens;
    @Autowired private CanvasAIController controller;
    @Autowired private DynamicFieldController dynamicFieldController;
    @Autowired private TriggerChainController directController;
    @SpyBean private CanvasToggleModuleTool toggleModuleTool;
    @SpyBean private CanvasUpdateFieldTool updateFieldTool;
    @SpyBean private CanvasApplyTemplateTool applyTemplateTool;
    @SpyBean private CanvasToggleToolTool toggleToolTool;
    @SpyBean private CanvasToggleSkillTool toggleSkillTool;
    @SpyBean private CanvasUpdateTriggerChainTool updateTriggerChainTool;
    @SpyBean private FactoryFeatureMenuWriteLock featureWriteLock;
    @Autowired private IntentPreviewTokenRepository previewTokenRepository;
    @Autowired private ToolExecutionIdempotencyRepository idempotencyRepository;
    @Autowired private ToolExecutionAuditEventRepository auditRepository;
    @Autowired private FactoryDefaultValueRepository defaultValues;
    @Autowired private CanvasDynamicFieldRepository dynamicFields;
    @SpyBean private CanvasConfigStateReader stateReader;
    @MockBean private DDLExecutor ddlExecutor;
    @MockBean private DynamicFieldService dynamicFieldService;
    @MockBean private DynamicTableService dynamicTableService;
    @MockBean private DashScopeClient dashScopeClient;
    @MockBean private MobileService mobileService;
    @MockBean private ToolRegistry toolRegistry;
    @MockBean private ToolPrincipalPolicy principalPolicy;
    @MockBean private ToolRuntimeRegistry runtimeRegistry;
    @MockBean private LegacyToolMigrationRegistry legacyToolMigrationRegistry;
    @MockBean private LlmEgressGuard llmEgressGuard;

    private String factoryId;
    private String moduleCode;

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void seed() {
        factoryId = "CPB-PG-" + UUID.randomUUID().toString().replace("-", "");
        moduleCode = "inventory_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        transactionTemplate().executeWithoutResult(status -> {
            Factory factory = new Factory();
            factory.setId(factoryId);
            factory.setName("Canvas binding " + factoryId);
            factory.setType(FactoryType.FACTORY);
            factory.setLevel(0);
            factory.setIsActive(true);
            factory.setManuallyVerified(false);
            factory.setAiWeeklyQuota(20);
            factories.save(factory);

            schemas.save(ModuleSchema.builder()
                    .moduleCode(moduleCode)
                    .moduleName("库存")
                    .moduleCategory("FACTORY")
                    .fieldSchema(Map.of("fields", List.of(Map.of("code", "lot"))))
                    .defaultConfig(Map.of())
                    .isActive(true)
                    .build());
            tools.save(FactoryToolConfig.builder().factoryId(factoryId).toolName("sample_tool")
                    .enabled(true).paramOverrides(Map.of()).build());
            skills.save(FactorySkillConfig.builder().factoryId(factoryId).skillName("sample_skill")
                    .enabled(true).customDag(Map.of()).customTriggers(List.of()).build());
            triggers.save(FactoryTriggerChain.builder().factoryId(factoryId).chainCode("sample_chain")
                    .eventType("CANVAS").enabled(false)
                    .steps(List.of(new HashMap<>(Map.of("order", 1, "enabled", true))))
                    .errorStrategy("CONTINUE").executionCount(0L).build());
            templates.save(FactoryTemplate.builder().templateCode(
                            ("CANVAS_TEMPLATE_" + factoryId).toLowerCase(Locale.ROOT))
                    .templateName("Canvas template").industryType("FOOD")
                    .createdBy(USER_ID)
                    .baseConfig(Map.of(
                            "moduleConfigs", Map.of("enabledModules", List.of(moduleCode),
                                    "disabledModules", List.of()),
                            "defaultOverrides", Map.of("defaultValues", Map.of(
                                     moduleCode, Map.of("lot", "LOT")),
                                    "disabledTools", List.of("canvas_blocked_tool")),
                            "seedDynamicFields", List.of(Map.of(
                                    "moduleCode", moduleCode,
                                    "fieldCode", "custom_lot",
                                    "fieldType", "TEXT",
                                    "label", "模板批次",
                                    "config", Map.of(),
                                    "sortOrder", 1))))
                    .usageCount(0).isActive(true).build());
        });
        entityManager.clear();
        wireActualGateway();
    }

    private void wireActualGateway() {
        when(toolRegistry.getAllToolNames()).thenReturn(List.of("canvas_blocked_tool"));
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        user.setFactoryId(factoryId);
        user.setFactoryType("FACTORY");
        user.setRoleCode(FactoryUserRole.permission_admin);
        when(mobileService.getUserFromToken("canvas-pg-user")).thenReturn(user);

        when(principalPolicy.rehydrate(any(ExecutionPrincipal.class))).thenAnswer(invocation -> {
            ExecutionPrincipal asserted = invocation.getArgument(0);
            return Optional.of(new ToolPrincipalPolicy.RehydratedPrincipal(
                    asserted,
                    Map.of("factoryId", factoryId, "tenantId", factoryId,
                            "businessType", "FACTORY", "userId", USER_ID,
                            "userRole", "permission_admin", "roleCode", "permission_admin",
                            "permissions", Set.of(), "canViewPrices", false,
                            "canViewPurchasePrices", false,
                            "canViewPurchaseOrFinancePrices", false)));
        });
        when(runtimeRegistry.resolve(any(), any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0,
                    com.cretas.aims.ai.tool.gateway.ToolExecutionCommand.class);
            return Optional.of(new ResolvedTool(descriptor(command.toolName()), actualTool(command.toolName())));
        });
        lenient().when(legacyToolMigrationRegistry.resolve(any(), any())).thenReturn(Optional.empty());
    }

    private ToolExecutor actualTool(String toolName) {
        return switch (toolName) {
            case "canvas_toggle_module" -> toggleModuleTool;
            case "canvas_update_field" -> updateFieldTool;
            case "canvas_apply_template" -> applyTemplateTool;
            case "canvas_toggle_tool" -> toggleToolTool;
            case "canvas_toggle_skill" -> toggleSkillTool;
            case "canvas_update_trigger_chain" -> updateTriggerChainTool;
            default -> throw new IllegalArgumentException("Unexpected Canvas tool: " + toolName);
        };
    }

    private ToolDescriptor descriptor(String toolName) {
        return new ToolDescriptor(
                toolName, ToolExecutor.ActionType.UPDATE, ToolExecutor.RiskLevel.LOW,
                Set.of(), Set.of("factory_super_admin", "permission_admin"),
                Set.of(FactoryType.FACTORY, FactoryType.CENTRAL_KITCHEN), Set.of("canvas"),
                "2.0.0", false, ConfirmationPolicy.REQUIRED_FOR_EXECUTION,
                ApprovalPolicy.NOT_REQUIRED, IdempotencyPolicy.REQUIRED_FOR_EXECUTION,
                DataClassification.INTERNAL, Set.of(ToolExecutionSource.HTTP_CONTROLLER),
                ToolEgressPolicy.denyAll(), DescriptorProvenance.EXPLICIT);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void allSixWritersUseOneBoundSnapshotAndCommittedFreshReadback() throws Exception {
        execute("canvas_toggle_module", Map.of("moduleCode", moduleCode, "enabled", false));
        assertThat(modules.findByFactoryIdAndConfigVersion(factoryId, 1)).singleElement()
                .extracting(FactoryModuleConfig::getEnabled).isEqualTo(false);

        execute("canvas_update_field", Map.of("moduleCode", moduleCode, "fieldCode", "lot",
                "property", "label", "value", "批次"));
        assertThat(modules.findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, moduleCode, 1))
                .get().extracting(row -> ((Map<?, ?>) row.getFieldConfig().get("fields"))
                        .get("lot")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("label", "批次");

        execute("canvas_toggle_tool", Map.of("toolName", "sample_tool", "enabled", false));
        assertThat(tools.findByFactoryIdAndToolName(factoryId, "sample_tool")).get()
                .extracting(FactoryToolConfig::getEnabled).isEqualTo(false);
        verify(toggleToolTool, times(1)).execute(any(), any());

        execute("canvas_toggle_skill", Map.of("skillName", "sample_skill", "enabled", false));
        assertThat(skills.findByFactoryIdAndSkillName(factoryId, "sample_skill")).get()
                .extracting(FactorySkillConfig::getEnabled).isEqualTo(false);
        verify(toggleSkillTool, times(1)).execute(any(), any());

        execute("canvas_update_trigger_chain", Map.of("chainCode", "sample_chain", "stepOrder", 1,
                "stepEnabled", false));
        assertThat(triggers.findByFactoryIdAndChainCode(factoryId, "sample_chain")).get()
                .extracting(FactoryTriggerChain::getSteps).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .first().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("enabled", false);

        String template = ("CANVAS_TEMPLATE_" + factoryId).toLowerCase(Locale.ROOT);
        execute("canvas_apply_template", Map.of("templateCode", template));
        assertThat(configurations.findDraft(factoryId)).isPresent();
        assertThat(templates.findByTemplateCodeIgnoreCase(template)).get()
                .extracting(FactoryTemplate::getUsageCount).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "4,5"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void draftVersionUsesOneWhenHistoryIsEmptyOrNextAfterArchivedHistory(int historyVersion,
                                                                           int expectedVersion) {
        if (historyVersion > 0) {
            transactionTemplate().executeWithoutResult(status -> configurations.save(
                    FactoryConfiguration.builder().factoryId(factoryId).configVersion(historyVersion)
                            .status("ARCHIVED").createdBy(USER_ID).build()));
        }
        execute("canvas_toggle_module", Map.of("moduleCode", moduleCode, "enabled", false));
        assertThat(configurations.findDraft(factoryId)).get()
                .extracting(FactoryConfiguration::getConfigVersion).isEqualTo(expectedVersion);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void archivedHistoryAfterPublishedVersionStillGetsNextDraftVersion() {
        transactionTemplate().executeWithoutResult(status -> {
            configurations.save(FactoryConfiguration.builder().factoryId(factoryId).configVersion(1)
                    .status("PUBLISHED").createdBy(USER_ID).build());
            configurations.save(FactoryConfiguration.builder().factoryId(factoryId).configVersion(5)
                    .status("ARCHIVED").createdBy(USER_ID).build());
        });
        execute("canvas_toggle_module", Map.of("moduleCode", moduleCode, "enabled", false));
        assertThat(configurations.findDraft(factoryId)).get()
                .extracting(FactoryConfiguration::getConfigVersion).isEqualTo(6);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void legacyModuleVersionOneWithoutConfigurationStartsDraftAtTwoAndPreservesAllLegacyFields() {
        Map<String, Object> legacyFieldConfig = Map.of("fields", Map.of("lot", Map.of("label", "legacy")));
        Map<String, Object> legacyWorkflow = Map.of("mode", "legacy");
        Map<String, Object> legacyValidation = Map.of("required", true);
        Map<String, Object> legacyPermission = Map.of("permission_admin", "rw");
        Map<String, Map<String, String>> legacyRole = Map.of("permission_admin", Map.of(moduleCode, "rw"));
        Map<String, Object> legacyLayout = Map.of("order", 7);
        Map<String, Object> legacyLabels = Map.of("lot", "Legacy lot");
        Map<String, Object> legacyComputed = Map.of("summary", "lot");
        transactionTemplate().executeWithoutResult(status -> modules.save(FactoryModuleConfig.builder()
                .factoryId(factoryId).moduleCode(moduleCode).configVersion(1).enabled(true)
                .fieldConfig(legacyFieldConfig).workflowConfig(legacyWorkflow)
                .validationConfig(legacyValidation).permissionConfig(legacyPermission)
                .roleModuleOverride(legacyRole).layoutConfig(legacyLayout)
                .customLabels(legacyLabels).computedFields(legacyComputed)
                .renderingMode("CANVAS").build()));

        execute("canvas_toggle_module", Map.of("moduleCode", moduleCode, "enabled", false));

        assertThat(configurations.findDraft(factoryId)).get()
                .extracting(FactoryConfiguration::getConfigVersion).isEqualTo(2);
        FactoryModuleConfig legacy = modules.findByFactoryIdAndModuleCodeAndConfigVersion(
                factoryId, moduleCode, 1).orElseThrow();
        assertThat(legacy.getEnabled()).isTrue();
        assertThat(legacy.getFieldConfig()).isEqualTo(legacyFieldConfig);
        assertThat(legacy.getWorkflowConfig()).isEqualTo(legacyWorkflow);
        assertThat(legacy.getValidationConfig()).isEqualTo(legacyValidation);
        assertThat(legacy.getPermissionConfig()).isEqualTo(legacyPermission);
        assertThat(legacy.getRoleModuleOverride()).isEqualTo(legacyRole);
        assertThat(legacy.getLayoutConfig()).isEqualTo(legacyLayout);
        assertThat(legacy.getCustomLabels()).isEqualTo(legacyLabels);
        assertThat(legacy.getComputedFields()).isEqualTo(legacyComputed);
        assertThat(legacy.getRenderingMode()).isEqualTo("CANVAS");
        FactoryModuleConfig draft = modules.findByFactoryIdAndModuleCodeAndConfigVersion(
                factoryId, moduleCode, 2).orElseThrow();
        assertThat(draft.getEnabled()).isFalse();
        // A legacy v1 row is preserved as-is, but is never promoted into the
        // new draft.  The draft starts empty and only the requested mutation
        // is allowed to populate it.
        assertThat(draft.getFieldConfig()).isEmpty();
        assertThat(draft.getWorkflowConfig()).isEmpty();
        assertThat(draft.getValidationConfig()).isEmpty();
        assertThat(draft.getPermissionConfig()).isEmpty();
        assertThat(draft.getRoleModuleOverride()).isEmpty();
        assertThat(draft.getLayoutConfig()).isEmpty();
        assertThat(draft.getCustomLabels()).isEmpty();
        assertThat(draft.getComputedFields()).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void changedTargetIsRejectedAfterPreviewWithoutASecondWrite() {
        Map<String, Object> params = Map.of("toolName", "sample_tool", "enabled", false);
        Map<String, Object> internal = bindings.issue(factoryId, "canvas_toggle_tool", params);
        transactionTemplate().executeWithoutResult(status -> tools.findByFactoryIdAndToolName(factoryId, "sample_tool")
                .orElseThrow().setEnabled(false));
        assertThatThrownBy(() -> executor.execute(factoryId, "canvas_toggle_tool", internal,
                Map.of("userId", 77L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
        assertThat(tools.findByFactoryIdAndToolName(factoryId, "sample_tool")).get()
                .extracting(FactoryToolConfig::getEnabled).isEqualTo(false);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSamePreviewHasOneCommitAndOneStaleResult() throws Exception {
        Map<String, Object> params = Map.of("toolName", "sample_tool", "enabled", false);
        Map<String, Object> internal = bindings.issue(factoryId, "canvas_toggle_tool", params);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        executor.execute(factoryId, "canvas_toggle_tool", internal, Map.of("userId", 77L));
                        return true;
                    } catch (IllegalStateException stale) {
                        return false;
                    }
                }));
            }
            assertThat(futures.stream().map(this::get).filter(Boolean.TRUE::equals).count()).isEqualTo(1);
            assertThat(futures.stream().map(this::get).filter(Boolean.FALSE::equals).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(tools.findByFactoryIdAndToolName(factoryId, "sample_tool")).get()
                .extracting(FactoryToolConfig::getEnabled).isEqualTo(false);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualControllerGatewayStalePlanFailsOnceAndFailedReplayDoesNotWrite() throws Exception {
        String tool = "canvas_toggle_tool";
        Map<String, Object> params = Map.of("toolName", "sample_tool", "enabled", false);
        IntentPreviewToken token = issueProof(tool, params);

        transactionTemplate().executeWithoutResult(status -> tools.findByFactoryIdAndToolName(
                        factoryId, "sample_tool").orElseThrow().setEnabled(false));

        CanvasAIController.ApplyDiffsResult first = applyProof(token, tool, params);
        assertThat(first.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        assertThat(tokenStatus(token.getToken())).isEqualTo(IntentPreviewToken.TokenStatus.FAILED.name());
        assertThat(latestLedger("state")).isEqualTo(ToolExecutionIdempotencyRecord.State.FAILED.name());
        assertThat(latestLedger("outcome_status")).isEqualTo(ToolExecutionStatus.FAILED.name());
        assertThat(latestLedger("result_code")).isEqualTo("TOOL_NEEDS_INFO");
        assertThat(auditCount(tool, ToolExecutionStatus.FAILED.name())).isEqualTo(1);
        assertThat(tools.findByFactoryIdAndToolName(factoryId, "sample_tool")).get()
                .extracting(FactoryToolConfig::getEnabled).isEqualTo(false);
        verify(toggleToolTool, times(1)).execute(any(), any());

        CanvasAIController.ApplyDiffsResult replay = applyProof(token, tool, params);
        assertThat(replay.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        assertThat(countLedgerRows()).isEqualTo(1);
        assertThat(auditCount(tool, ToolExecutionStatus.IDEMPOTENT_REPLAY.name())).isEqualTo(1);
        assertThat(tools.findByFactoryIdAndToolName(factoryId, "sample_tool")).get()
                .extracting(FactoryToolConfig::getEnabled).isEqualTo(false);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualGatewayPostCommitReadbackFailureIsUnknownAndDoesNotReplayWrite() throws Exception {
        String tool = "canvas_toggle_skill";
        Map<String, Object> params = Map.of("skillName", "sample_skill", "enabled", false);
        IntentPreviewToken token = issueProof(tool, params);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
            if (reads.incrementAndGet() == 4) {
                throw new IllegalStateException("fixture committed readback unavailable");
            }
            return invocation.callRealMethod();
        }).when(stateReader).read(any(String.class), any(String.class), any(Map.class));

        CanvasAIController.ApplyDiffsResult first = applyProof(token, tool, params);
        assertThat(first.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("UNKNOWN");
        assertThat(tokenStatus(token.getToken())).isEqualTo(IntentPreviewToken.TokenStatus.FAILED.name());
        assertThat(latestLedger("state")).isEqualTo(ToolExecutionIdempotencyRecord.State.IN_DOUBT.name());
        assertThat(latestLedger("outcome_status")).isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN.name());
        assertThat(auditCount(tool, ToolExecutionStatus.OUTCOME_UNKNOWN.name())).isEqualTo(1);
        assertThat(tools.findByFactoryIdAndToolName(factoryId, "sample_tool")).isPresent();
        assertThat(skills.findByFactoryIdAndSkillName(factoryId, "sample_skill")).get()
                .extracting(FactorySkillConfig::getEnabled).isEqualTo(false);
        verify(toggleSkillTool, times(1)).execute(any(), any());

        CanvasAIController.ApplyDiffsResult replay = applyProof(token, tool, params);
        assertThat(replay.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("UNKNOWN");
        assertThat(countLedgerRows()).isEqualTo(1);
        assertThat(auditCount(tool, ToolExecutionStatus.OUTCOME_UNKNOWN.name())).isEqualTo(2);
        assertThat(auditCount(tool, ToolExecutionStatus.IDEMPOTENT_REPLAY.name())).isZero();
        verify(toggleSkillTool, times(1)).execute(any(), any());
        assertThat(skills.findByFactoryIdAndSkillName(factoryId, "sample_skill")).get()
                .extracting(FactorySkillConfig::getEnabled).isEqualTo(false);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualTemplateGatewayPersistsFullImpactAndSuccessfulReplayIsReused() throws Exception {
        String tool = "canvas_apply_template";
        Map<String, Object> params = Map.of("templateCode",
                ("CANVAS_TEMPLATE_" + factoryId).toLowerCase(Locale.ROOT));
        seedPublishedTemplateSource();
        IntentPreviewToken token = issueProof(tool, params);

        CanvasAIController.ApplyDiffsResult first = applyProof(token, tool, params);
        assertThat(first.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("SUCCESS");
        assertThat(tokenStatus(token.getToken())).isEqualTo(IntentPreviewToken.TokenStatus.CONFIRMED.name());
        assertThat(latestLedger("state")).isEqualTo(ToolExecutionIdempotencyRecord.State.SUCCEEDED.name());
        assertThat(latestLedger("outcome_status")).isEqualTo(ToolExecutionStatus.SUCCEEDED.name());
        assertThat(configurations.findDraft(factoryId)).get()
                .extracting(FactoryConfiguration::getConfigVersion).isEqualTo(2);
        assertThat(modules.findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, moduleCode, 2))
                .get().extracting(FactoryModuleConfig::getRoleModuleOverride)
                .isEqualTo(Map.of("permission_admin", Map.of(moduleCode, "rw")));
        assertThat(modules.findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, moduleCode, 2))
                .get().extracting(row -> ((Map<?, ?>) row.getFieldConfig().get("fields"))
                        .get("lot")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("label", "旧标签");
        assertThat(defaultValues.findByFactoryIdAndModuleCodeAndFieldCode(factoryId, moduleCode, "lot"))
                .singleElement().extracting(FactoryDefaultValue::getDefaultValue)
                .isEqualTo(Map.of("value", "LOT"));
        assertThat(tools.findByFactoryIdAndToolName(factoryId, "canvas_blocked_tool"))
                .get().extracting(FactoryToolConfig::getEnabled).isEqualTo(false);
        assertThat(dynamicFields.findByFactoryIdAndModuleCodeAndFieldCode(factoryId, moduleCode, "custom_lot"))
                .get().satisfies(field -> {
                    assertThat(field.getStatus()).isEqualTo("PENDING_DDL");
                    assertThat(field.getFieldType()).isEqualTo("TEXT");
                    assertThat(field.getLabel()).isEqualTo("模板批次");
                    assertThat(field.getColumnName()).isEqualTo("cf_custom_lot");
                });
        verify(applyTemplateTool, times(1)).execute(any(), any());
        assertThat(templates.findByTemplateCodeIgnoreCase(params.get("templateCode").toString())).get()
                .extracting(FactoryTemplate::getUsageCount).isEqualTo(1);
        assertThat(auditCount(tool, ToolExecutionStatus.SUCCEEDED.name())).isEqualTo(1);

        CanvasAIController.ApplyDiffsResult replay = applyProof(token, tool, params);
        assertThat(replay.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("REUSED");
        assertThat(countLedgerRows()).isEqualTo(1);
        assertThat(auditCount(tool, ToolExecutionStatus.IDEMPOTENT_REPLAY.name())).isEqualTo(1);
        assertThat(templates.findByTemplateCodeIgnoreCase(params.get("templateCode").toString())).get()
                .extracting(FactoryTemplate::getUsageCount).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void directDynamicFieldCreatedBeforeFreshTemplatePreviewIsPreserved() throws Exception {
        createDirectDynamicField("custom_lot", "直写批次");
        seedPublishedTemplateSource();
        IntentPreviewToken token = issueProof("canvas_apply_template", Map.of(
                "templateCode", ("CANVAS_TEMPLATE_" + factoryId).toLowerCase(Locale.ROOT)));

        CanvasAIController.ApplyDiffsResult result = applyProof(token, "canvas_apply_template", Map.of(
                "templateCode", ("CANVAS_TEMPLATE_" + factoryId).toLowerCase(Locale.ROOT)));
        assertThat(result.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("SUCCESS");
        assertThat(dynamicFields.findByFactoryIdAndModuleCodeAndFieldCode(factoryId, moduleCode, "custom_lot"))
                .get().satisfies(field -> {
                    assertThat(field.getLabel()).isEqualTo("直写批次");
                    assertThat(field.getFieldType()).isEqualTo("TEXT");
                });
        assertThat(auditCount("canvas_apply_template", ToolExecutionStatus.SUCCEEDED.name())).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void templateLockSerializesDynamicFieldCreateAndReturnsDuplicateAfterCommit() throws Exception {
        seedPublishedTemplateSource();
        IntentPreviewToken token = issueProof("canvas_apply_template", Map.of(
                "templateCode", ("CANVAS_TEMPLATE_" + factoryId).toLowerCase(Locale.ROOT)));
        AtomicReference<BusinessException> directFailure = new AtomicReference<>();

        runAiWhileDirectWriterWaits(
                () -> applyProof(token, "canvas_apply_template", Map.of(
                        "templateCode", ("CANVAS_TEMPLATE_" + factoryId).toLowerCase(Locale.ROOT))),
                () -> {
                    try {
                        return dynamicFieldController.createDynamicField(factoryId, Map.of(
                                "moduleCode", moduleCode,
                                "fieldCode", "custom_lot",
                                "fieldType", "TEXT",
                                "label", "direct-race",
                                "config", Map.of(),
                                "sortOrder", 1));
                    } catch (BusinessException failure) {
                        directFailure.set(failure);
                        return null;
                    }
                });

        assertThat(directFailure.get()).isNotNull().satisfies(failure -> {
            assertThat(failure.getCode()).isEqualTo(409);
            assertThat(failure.getMessage()).contains("同名");
        });
        assertThat(dynamicFields.findByFactoryIdAndModuleCodeAndFieldCode(factoryId, moduleCode, "custom_lot"))
                .get().extracting(CanvasDynamicField::getLabel).isEqualTo("模板批次");
        assertThat(auditCount("canvas_apply_template", ToolExecutionStatus.SUCCEEDED.name())).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualBindingRejectsTamperCrossFactoryAndPermissionWithoutClaimOrTool() throws Exception {
        String tool = "canvas_toggle_tool";
        Map<String, Object> params = Map.of("toolName", "sample_tool", "enabled", false);
        IntentPreviewToken token = issueProof(tool, params);

        CanvasAIController.ApplyDiffsResult tampered = applyProof(token, tool,
                Map.of("toolName", "sample_tool", "enabled", true));
        assertThat(tampered.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        assertThat(countLedgerRows()).isZero();
        verify(toggleToolTool, never()).execute(any(), any());

        var crossFactoryResponse = controller.applyDiffs(
                "OTHER-FACTORY", "Bearer canvas-pg-user", List.of(Map.of(
                        "tool", tool,
                        "params", params,
                        "proof", Map.of("token", token.getToken(),
                                "commandDigest", token.getCommandDigest(), "descriptorVersion", "2.0.0"))))
                ;
        assertThat(crossFactoryResponse.getSuccess()).isFalse();
        assertThat(crossFactoryResponse.getData()).isNull();
        assertThat(countLedgerRows()).isZero();
        verify(toggleToolTool, never()).execute(any(), any());

        reset(principalPolicy);
        when(principalPolicy.rehydrate(any())).thenReturn(Optional.empty());
        CanvasAIController.ApplyDiffsResult denied = applyProof(token, tool, params);
        assertThat(denied.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        assertThat(countLedgerRows()).isZero();
        verify(toggleToolTool, never()).execute(any(), any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void directToolPutAndAiToolWritePreserveParamOverrides() throws Exception {
        IntentPreviewToken token = issueProof("canvas_toggle_tool",
                Map.of("toolName", "sample_tool", "enabled", false));
        directController.setToolConfig(factoryId, "sample_tool",
                Map.of("paramOverrides", Map.of("source", "direct-http")));
        CanvasAIController.ApplyDiffsResult result = applyProof(token, "canvas_toggle_tool",
                Map.of("toolName", "sample_tool", "enabled", false));
        assertThat(result.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        FactoryToolConfig row = tools.findByFactoryIdAndToolName(factoryId, "sample_tool").orElseThrow();
        assertThat(row.getEnabled()).isTrue();
        assertThat(row.getParamOverrides()).containsEntry("source", "direct-http");
        verify(toggleToolTool, times(1)).execute(any(), any());
        assertThat(latestLedger("state")).isEqualTo(ToolExecutionIdempotencyRecord.State.FAILED.name());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void directSkillPutAndAiSkillWritePreserveCustomDag() throws Exception {
        IntentPreviewToken token = issueProof("canvas_toggle_skill",
                Map.of("skillName", "sample_skill", "enabled", false));
        directController.setSkillConfig(factoryId, "sample_skill",
                Map.of("customDag", Map.of("source", "direct-http", "nodes", List.of())));
        CanvasAIController.ApplyDiffsResult result = applyProof(token, "canvas_toggle_skill",
                Map.of("skillName", "sample_skill", "enabled", false));
        assertThat(result.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        FactorySkillConfig row = skills.findByFactoryIdAndSkillName(factoryId, "sample_skill").orElseThrow();
        assertThat(row.getEnabled()).isTrue();
        assertThat(row.getCustomDag()).containsEntry("source", "direct-http");
        verify(toggleSkillTool, times(1)).execute(any(), any());
        assertThat(latestLedger("state")).isEqualTo(ToolExecutionIdempotencyRecord.State.FAILED.name());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void directTriggerPutAndAiTriggerWritePreserveUnrelatedAttributes() throws Exception {
        IntentPreviewToken token = issueProof("canvas_update_trigger_chain",
                Map.of("chainCode", "sample_chain", "stepOrder", 1, "stepEnabled", false));
        FactoryTriggerChain directBody = new FactoryTriggerChain();
        directBody.setEnabled(false);
        directBody.setDescription("direct-http");
        directBody.setSteps(null);
        directController.setTriggerChain(factoryId, "sample_chain", directBody);
        CanvasAIController.ApplyDiffsResult result = applyProof(token, "canvas_update_trigger_chain",
                Map.of("chainCode", "sample_chain", "stepOrder", 1, "stepEnabled", false));
        assertThat(result.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        FactoryTriggerChain row = triggers.findByFactoryIdAndChainCode(factoryId, "sample_chain").orElseThrow();
        assertThat(row.getEnabled()).isFalse();
        assertThat(row.getDescription()).isEqualTo("direct-http");
        assertThat(row.getSteps()).first().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("enabled", true);
        verify(updateTriggerChainTool, times(1)).execute(any(), any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void factoryLockSerializesAiAndDirectToolWrites() throws Exception {
        IntentPreviewToken token = issueProof("canvas_toggle_tool",
                Map.of("toolName", "sample_tool", "enabled", false));
        runAiWhileDirectWriterWaits(
                () -> applyProof(token, "canvas_toggle_tool",
                        Map.of("toolName", "sample_tool", "enabled", false)),
                () -> directController.setToolConfig(factoryId, "sample_tool",
                        Map.of("paramOverrides", Map.of("source", "direct-http"))));

        FactoryToolConfig row = tools.findByFactoryIdAndToolName(factoryId, "sample_tool").orElseThrow();
        assertThat(row.getEnabled()).isFalse();
        assertThat(row.getParamOverrides()).containsEntry("source", "direct-http");
        verify(toggleToolTool, times(1)).execute(any(), any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void factoryLockSerializesAiAndDirectSkillWrites() throws Exception {
        IntentPreviewToken token = issueProof("canvas_toggle_skill",
                Map.of("skillName", "sample_skill", "enabled", false));
        runAiWhileDirectWriterWaits(
                () -> applyProof(token, "canvas_toggle_skill",
                        Map.of("skillName", "sample_skill", "enabled", false)),
                () -> directController.setSkillConfig(factoryId, "sample_skill",
                        Map.of("customDag", Map.of("source", "direct-http", "nodes", List.of()))));

        FactorySkillConfig row = skills.findByFactoryIdAndSkillName(factoryId, "sample_skill").orElseThrow();
        assertThat(row.getEnabled()).isFalse();
        assertThat(row.getCustomDag()).containsEntry("source", "direct-http");
        verify(toggleSkillTool, times(1)).execute(any(), any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void factoryLockSerializesAiAndDirectTriggerWrites() throws Exception {
        IntentPreviewToken token = issueProof("canvas_update_trigger_chain",
                Map.of("chainCode", "sample_chain", "stepOrder", 1, "stepEnabled", false));
        runAiWhileDirectWriterWaits(
                () -> applyProof(token, "canvas_update_trigger_chain",
                        Map.of("chainCode", "sample_chain", "stepOrder", 1, "stepEnabled", false)),
                () -> {
                    FactoryTriggerChain body = new FactoryTriggerChain();
                    body.setEnabled(false);
                    body.setDescription("direct-http");
                    body.setSteps(null);
                    directController.setTriggerChain(factoryId, "sample_chain", body);
                    return null;
                });

        FactoryTriggerChain row = triggers.findByFactoryIdAndChainCode(factoryId, "sample_chain").orElseThrow();
        assertThat(row.getEnabled()).isFalse();
        assertThat(row.getDescription()).isEqualTo("direct-http");
        assertThat(row.getSteps()).first().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("enabled", false);
        verify(updateTriggerChainTool, times(1)).execute(any(), any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualTriggerEnableRebindsRunAsToCurrentTypedFactoryActor() {
        transactionTemplate().executeWithoutResult(status -> {
            FactoryTriggerChain chain = triggers.findByFactoryIdAndChainCode(factoryId, "sample_chain").orElseThrow();
            chain.setRunAsUserId(99L);
            chain.setSteps(List.of(new HashMap<>(Map.of("order", 1, "enabled", false))));
        });
        IntentPreviewToken token = issueProof("canvas_update_trigger_chain",
                Map.of("chainCode", "sample_chain", "enabled", true));
        CanvasAIController.ApplyDiffsResult result = withActor(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER_ID, factoryId,
                        "permission_admin"),
                () -> applyProof(token, "canvas_update_trigger_chain",
                        Map.of("chainCode", "sample_chain", "enabled", true)));
        assertThat(result.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("SUCCESS");
        assertThat(triggers.findByFactoryIdAndChainCode(factoryId, "sample_chain")).get()
                .extracting(FactoryTriggerChain::getRunAsUserId).isEqualTo(USER_ID);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualTriggerEnableWithoutTypedActorIsRejectedWithoutWrite() throws Exception {
        assertTriggerEnableRejected(null);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualTriggerEnableWithPlatformActorIsRejectedWithoutWrite() throws Exception {
        assertTriggerEnableRejected(new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN,
                USER_ID, factoryId, "platform_admin"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualTriggerEnableWithForeignFactoryActorIsRejectedWithoutWrite() throws Exception {
        assertTriggerEnableRejected(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,
                USER_ID, "OTHER-FACTORY", "permission_admin"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualTriggerWriteStepIsRejectedBeforePersistentChange() throws Exception {
        when(toolRegistry.getExecutor("canvas_toggle_tool")).thenReturn(Optional.of(toggleToolTool));
        assertTriggerStepRejected(Map.of("tool", "canvas_toggle_tool", "params", Map.of()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualTriggerIdentityParameterIsRejectedBeforePersistentChange() throws Exception {
        when(toolRegistry.getExecutor("canvas_toggle_tool")).thenReturn(Optional.of(toggleToolTool));
        assertTriggerStepRejected(Map.of("tool", "canvas_toggle_tool",
                "params", Map.of("factoryId", "OTHER-FACTORY")));
    }

    private void seedPublishedTemplateSource() {
        transactionTemplate().executeWithoutResult(status -> {
            FactoryConfiguration published = FactoryConfiguration.builder()
                    .factoryId(factoryId)
                    .configVersion(1)
                    .status("PUBLISHED")
                    .publishedAt(LocalDateTime.now())
                    .publishedBy(USER_ID)
                    .createdBy(USER_ID)
                    .build();
            configurations.save(published);
            modules.save(FactoryModuleConfig.builder()
                    .factoryId(factoryId)
                    .moduleCode(moduleCode)
                    .configVersion(1)
                    .enabled(true)
                    .fieldConfig(Map.of("fields", Map.of("lot", Map.of("label", "旧标签"))))
                    .roleModuleOverride(Map.of("permission_admin", Map.of(moduleCode, "rw")))
                    .build());
        });
        entityManager.clear();
    }

    private void createDirectDynamicField(String fieldCode, String label) {
        dynamicFieldController.createDynamicField(factoryId, Map.of(
                "moduleCode", moduleCode,
                "fieldCode", fieldCode,
                "fieldType", "TEXT",
                "label", label,
                "config", Map.of(),
                "sortOrder", 1));
        entityManager.clear();
    }

    private void assertTriggerEnableRejected(AuthenticatedActor actor) throws Exception {
        transactionTemplate().executeWithoutResult(status -> {
            FactoryTriggerChain chain = triggers.findByFactoryIdAndChainCode(factoryId, "sample_chain").orElseThrow();
            chain.setRunAsUserId(99L);
            chain.setSteps(List.of(new HashMap<>(Map.of("order", 1, "enabled", false))));
        });
        IntentPreviewToken token = issueProof("canvas_update_trigger_chain",
                Map.of("chainCode", "sample_chain", "enabled", true));
        CanvasAIController.ApplyDiffsResult result = withActor(actor,
                () -> applyProof(token, "canvas_update_trigger_chain",
                        Map.of("chainCode", "sample_chain", "enabled", true)));
        assertThat(result.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isNotEqualTo("SUCCESS");
        FactoryTriggerChain unchanged = triggers.findByFactoryIdAndChainCode(factoryId, "sample_chain").orElseThrow();
        assertThat(unchanged.getEnabled()).isFalse();
        assertThat(unchanged.getRunAsUserId()).isEqualTo(99L);
        verify(updateTriggerChainTool, times(1)).execute(any(), any());
    }

    private void assertTriggerStepRejected(Map<String, Object> step) throws Exception {
        transactionTemplate().executeWithoutResult(status -> {
            FactoryTriggerChain chain = triggers.findByFactoryIdAndChainCode(factoryId, "sample_chain").orElseThrow();
            chain.setSteps(List.of(new HashMap<>(Map.of("order", 1, "enabled", true,
                    "tool", step.get("tool"), "params", step.get("params")))));
            chain.setEnabled(false);
            chain.setRunAsUserId(99L);
        });
        IntentPreviewToken token = issueProof("canvas_update_trigger_chain",
                Map.of("chainCode", "sample_chain", "enabled", true));
        CanvasAIController.ApplyDiffsResult result = withActor(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER_ID, factoryId,
                        "permission_admin"),
                () -> applyProof(token, "canvas_update_trigger_chain",
                        Map.of("chainCode", "sample_chain", "enabled", true)));
        assertThat(result.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isNotEqualTo("SUCCESS");
        FactoryTriggerChain unchanged = triggers.findByFactoryIdAndChainCode(factoryId, "sample_chain").orElseThrow();
        assertThat(unchanged.getEnabled()).isFalse();
        assertThat(unchanged.getRunAsUserId()).isEqualTo(99L);
        assertThat(unchanged.getSteps()).singleElement().isEqualTo(stepWithOrder(step));
        verify(updateTriggerChainTool, times(1)).execute(any(), any());
    }

    private Map<String, Object> stepWithOrder(Map<String, Object> step) {
        return Map.of("order", 1, "enabled", true,
                "tool", step.get("tool"), "params", step.get("params"));
    }

    private <T> T withActor(AuthenticatedActor actor, Callable<T> action) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (actor != null) request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return action.call();
        } catch (Exception failure) {
            throw new AssertionError("typed actor Canvas execution failed", failure);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private IntentPreviewToken issueProof(String tool, Map<String, Object> publicParams) {
        Map<String, Object> persisted = bindings.issue(factoryId, tool, publicParams);
        return previewTokens.createBoundToken(new PreviewTokenService.BoundTokenRequest(
                factoryId, USER_ID, "canvas-pg-user", "CANVAS_DIFF_" + UUID.randomUUID(),
                "Canvas configuration diff", tool, "2.0.0",
                com.cretas.aims.ai.tool.gateway.ToolExecutionMode.EXECUTE,
                "CANVAS_DIFF", tool, "UPDATE", persisted, Map.of(), publicParams, 300));
    }

    private CanvasAIController.ApplyDiffsResult applyProof(
            IntentPreviewToken token, String tool, Map<String, Object> publicParams) {
        return controller.applyDiffs(factoryId, "Bearer canvas-pg-user", List.of(Map.of(
                "tool", tool,
                "params", publicParams,
                "proof", Map.of("token", token.getToken(),
                        "commandDigest", token.getCommandDigest(), "descriptorVersion", "2.0.0")))).getData();
    }

    private String tokenStatus(String token) {
        return scalar("SELECT status FROM intent_preview_tokens WHERE token=:token", Map.of("token", token));
    }

    private String latestLedger(String column) {
        return scalar("SELECT " + column + " FROM tool_execution_idempotency "
                + "WHERE tenant_id=:factory AND principal_id=:user "
                + "ORDER BY created_at DESC LIMIT 1",
                Map.of("factory", factoryId, "user", Long.toString(USER_ID)));
    }

    private long countLedgerRows() {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM tool_execution_idempotency WHERE tenant_id=:factory "
                        + "AND principal_id=:user")
                .setParameter("factory", factoryId)
                .setParameter("user", Long.toString(USER_ID))
                .getSingleResult()).longValue();
    }

    private long auditCount(String tool, String outcome) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM tool_execution_audit_events WHERE tenant_id=:factory "
                        + "AND principal_id=:user AND tool_name=:tool AND outcome_status=:outcome")
                .setParameter("factory", factoryId)
                .setParameter("user", Long.toString(USER_ID))
                .setParameter("tool", tool)
                .setParameter("outcome", outcome)
                .getSingleResult()).longValue();
    }

    private String scalar(String sql, Map<String, Object> parameters) {
        var query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        List<?> rows = query.getResultList();
        return rows.isEmpty() || rows.get(0) == null ? null : String.valueOf(rows.get(0));
    }

    private void execute(String tool, Map<String, Object> params) {
        IntentPreviewToken proof = issueProof(tool, params);
        assertThat(applyProof(proof, tool, params).items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("SUCCESS");
        entityManager.clear();
    }

    /**
     * Hold the real parent-factory row lock after the AI transaction acquires
     * it, then prove the direct HTTP writer has actually attempted the same
     * lock and cannot complete until the AI transaction releases it.
     */
    private void runAiWhileDirectWriterWaits(Callable<?> aiWriter, Callable<?> directWriter) throws Exception {
        CountDownLatch aiLocked = new CountDownLatch(1);
        CountDownLatch directAttempted = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        CountDownLatch directCompleted = new CountDownLatch(1);
        AtomicReference<Thread> aiThread = new AtomicReference<>();
        AtomicInteger invocations = new AtomicInteger();
        FactoryFeatureMenuWriteLock target =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(featureWriteLock);
        doAnswer(invocation -> {
            boolean ai = Thread.currentThread() == aiThread.get();
            if (!ai) directAttempted.countDown();
            Object result = invocation.callRealMethod();
            if (ai && invocations.incrementAndGet() == 1) {
                aiLocked.countDown();
                if (!releaseAi.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("AI factory-lock barrier was not released");
                }
            }
            return result;
        }).when(target).lock(factoryId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> ai = pool.submit(() -> {
                aiThread.set(Thread.currentThread());
                return new TransactionTemplate(transactionManager).execute(status -> {
                    try {
                        return aiWriter.call();
                    } catch (Exception failure) {
                        throw new IllegalStateException("AI writer failed", failure);
                    }
                });
            });
            assertThat(aiLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> direct = pool.submit(() -> {
                try {
                    // The direct HTTP controllers are transactional proxies in
                    // the real Spring context.  Do not wrap them in a second
                    // transaction: a deliberate duplicate-field 409 must be
                    // allowed to roll back its own proxy transaction without
                    // turning the barrier fixture into an outer rollback-only
                    // commit failure.
                    return directWriter.call();
                } finally {
                    directCompleted.countDown();
                }
            });
            assertThat(directAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(directCompleted.await(300, TimeUnit.MILLISECONDS)).isFalse();
            releaseAi.countDown();
            ai.get(20, TimeUnit.SECONDS);
            direct.get(20, TimeUnit.SECONDS);
        } finally {
            releaseAi.countDown();
            pool.shutdownNow();
        }
    }

    private Boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent Canvas writer interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw new AssertionError("concurrent Canvas writer failed", failure.getCause());
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    /** Keep the PG fixture's DDL limited to the config aggregate under test. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CanvasSchemaFilterConfiguration implements HibernatePropertiesCustomizer {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        private static final java.util.Set<String> TABLES = java.util.Set.of(
                "factories", "module_schemas", "factory_module_configs", "factory_default_values",
                "factory_tool_configs", "factory_skill_configs", "factory_trigger_chains",
                "factory_templates", "factory_configurations", "config_change_log", "canvas_dynamic_field",
                "canvas_ddl_log",
                "intent_preview_tokens", "tool_execution_idempotency", "tool_execution_audit_events");

        @Override
        public void customize(Map<String, Object> properties) {
            properties.put("hibernate.hbm2ddl.schema_filter_provider", new org.hibernate.tool.schema.spi.SchemaFilterProvider() {
                private final org.hibernate.tool.schema.spi.SchemaFilter filter = new org.hibernate.tool.schema.spi.SchemaFilter() {
                    @Override public boolean includeNamespace(org.hibernate.boot.model.relational.Namespace namespace) { return true; }
                    @Override public boolean includeTable(org.hibernate.mapping.Table table) { return TABLES.contains(table.getName()); }
                    @Override public boolean includeSequence(org.hibernate.boot.model.relational.Sequence sequence) { return true; }
                };
                @Override public org.hibernate.tool.schema.spi.SchemaFilter getCreateFilter() { return filter; }
                @Override public org.hibernate.tool.schema.spi.SchemaFilter getDropFilter() { return filter; }
                @Override public org.hibernate.tool.schema.spi.SchemaFilter getTruncatorFilter() { return filter; }
                @Override public org.hibernate.tool.schema.spi.SchemaFilter getMigrateFilter() { return filter; }
                @Override public org.hibernate.tool.schema.spi.SchemaFilter getValidateFilter() { return filter; }
            });
        }
    }
}
