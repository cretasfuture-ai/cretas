package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleActivationCommandRequest;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleCommandReceipt;
import com.cretas.aims.blueprint.interview.InterviewModuleConfigCatalog;
import com.cretas.aims.engine.DDLExecutor;
import com.cretas.aims.engine.DynamicFieldService;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.config.ModuleSchema;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraft;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraftItem;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.config.ConfigChangeLogRepository;
import com.cretas.aims.repository.config.FactoryConfigurationRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.config.ModuleSchemaRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftItemRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.config.impl.FactoryConfigServiceImpl;
import com.cretas.aims.service.module.ModuleSwitchService;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ERP-S2-FORMAL-MODULE-SCHEMA-001: a platform-built module draft uses the customer-facing module ids
 * (production / finance ...). The catalog expands them to backend gate codes, and some of those codes
 * (production, finance, smartbi, operating_reports, logistics) have no module_schemas row in prod.
 * Execution used to call saveModuleConfig for each code and died on "ModuleSchema not found" with zero
 * writes. A code without a schema has no factory-level switch at runtime (getEffectiveConfig answers
 * LEGACY enabled), so the switch skips it and the readback does not expect it to turn off.
 *
 * Drives the real formal command, apply service, module switch and canvas config service over real
 * PostgreSQL JPA (module_schemas is jsonb, which H2 cannot load), so it runs only with
 * CRETAS_WORKFLOW_PG_URL + CRETAS_WORKFLOW_PG_VERIFY=true; the Java PR gate runs it that way.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ModuleConfigurationFormalActivationPostgresContractTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        boolean hasUrl = url != null && !url.isBlank();
        boolean verify = "true".equals(System.getenv("CRETAS_WORKFLOW_PG_VERIFY"));
        if (hasUrl != verify) {
            throw new IllegalStateException("CRETAS_WORKFLOW_PG_URL and CRETAS_WORKFLOW_PG_VERIFY=true must be set together");
        }
        if (hasUrl) {
            String bound = com.cretas.aims.integration.DisposablePostgresTargetGuard.requireSafeUrl(url)
                    + "?stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    /** module_schemas.module_code as read from prod on 2026-10-25 (QATEST_1021_001 failure). */
    private static final List<String> PROD_SCHEMA_CODES = List.of("bom", "customer", "equipment", "finance_ap",
            "finance_ar", "hr_employee", "inbound", "inventory", "material_batch", "outbound", "production_plan",
            "production_report", "purchase_order", "quality_inspection", "restaurant", "sales_order", "scheduling",
            "supplier", "traceability", "transfer", "warehouse");

    @Autowired TestEntityManager entityManager;
    @Autowired ModuleSchemaRepository schemas;
    @Autowired FactoryConfigurationRepository configurations;
    @Autowired FactoryModuleConfigRepository moduleConfigs;
    @Autowired ConfigChangeLogRepository changeLogs;
    @Autowired FactoryFeatureConfigRepository features;
    @Autowired ModuleConfigDraftRepository draftRows;
    @Autowired ModuleConfigDraftItemRepository draftItems;

    @Test
    @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
    void aDraftWithFrontendModuleIdsWhoseGateCodesLackSchemasActivatesAndReadsBackActive() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "FS-" + suffix;
        for (String code : PROD_SCHEMA_CODES) {
            if (schemas.findByModuleCode(code).isEmpty()) schemas.save(schema(code));
        }
        // Precondition of the defect: the catalog names gate codes the schema table does not have.
        assertThat(schemas.findByModuleCode("production")).isEmpty();
        assertThat(schemas.findByModuleCode("finance")).isEmpty();
        assertThat(InterviewModuleConfigCatalog.find("production").gateCodes()).contains("production", "production_plan");

        // production off (has an unschema'd code), warehouse on (all codes backend-coded),
        // finance off (mixed), trace off (no gate codes at all).
        Map<String, Boolean> desired = new LinkedHashMap<>();
        desired.put("production", false);
        desired.put("warehouse", true);
        desired.put("finance", false);
        desired.put("trace", false);
        ModuleConfigDraft draft = persistDraft(factory, desired);
        entityManager.flush();
        entityManager.clear();

        AssemblyActivationActorResolver actors = mock(AssemblyActivationActorResolver.class);
        FactoryConfigServiceImpl runtime = new FactoryConfigServiceImpl(schemas, configurations, moduleConfigs,
                changeLogs, new ObjectMapper());
        ReflectionTestUtils.setField(runtime, "actorResolver", actors);
        ReflectionTestUtils.setField(runtime, "ddlExecutor", mock(DDLExecutor.class));
        ReflectionTestUtils.setField(runtime, "dynamicFieldService", mock(DynamicFieldService.class));
        var featureWriteLock = new com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock();
        ReflectionTestUtils.setField(featureWriteLock, "entityManager", entityManager.getEntityManager());
        ModuleSwitchService moduleSwitch = new ModuleSwitchService(runtime, features, featureWriteLock);
        BlueprintModuleConfigurationApplyService apply =
                new BlueprintModuleConfigurationApplyService(draftRows, draftItems, runtime, moduleSwitch);
        ModuleConfigDraftCommand drafts = mock(ModuleConfigDraftCommand.class);
        apply.setDraftCommand(drafts);
        ModuleConfigurationFormalActivationCommand command = new ModuleConfigurationFormalActivationCommand(
                actors, drafts, draftRows, draftItems, apply, runtime, features);

        ActorRef platform = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7L);
        var current = command.currentDraft(factory, draft.getId(), platform);
        ModuleCommandReceipt receipt = command.activate(new ModuleActivationCommandRequest(factory, "plan-" + suffix,
                "modules", draft.getId(), current.revision(), current.digest(), "idem-" + suffix, 7L, platform));
        entityManager.flush();
        entityManager.clear();

        // 1 publication + 4 customer decisions, and the runtime reads back ACTIVE.
        assertThat(receipt.businessWriteCount()).isEqualTo(5);
        assertThat(command.readBack(factory, receipt).runtimeState()).isEqualTo("ACTIVE");
        assertThat(draftRows.findByIdAndFactoryId(draft.getId(), factory)).get()
                .extracting(ModuleConfigDraft::getStatus).isEqualTo("APPLIED");

        int published = configurations.findLatestPublished(factory).orElseThrow().getConfigVersion();
        Map<String, Boolean> gates = moduleConfigs.findByFactoryIdAndConfigVersion(factory, published).stream()
                .collect(Collectors.toMap(FactoryModuleConfig::getModuleCode, FactoryModuleConfig::getEnabled));
        // Backend-coded gates were written; codes without a schema were not invented.
        assertThat(gates).containsEntry("production_plan", false).containsEntry("production_report", false)
                .containsEntry("bom", false).containsEntry("warehouse", true).containsEntry("inventory", true)
                .containsEntry("inbound", true).containsEntry("transfer", true)
                .containsEntry("finance_ar", false).containsEntry("finance_ap", false)
                .doesNotContainKeys("production", "finance");
        assertThat(runtime.isModuleEnabled(factory, "production_plan")).isFalse();
        assertThat(runtime.isModuleEnabled(factory, "warehouse")).isTrue();
        // Frontend visibility layer carries every customer-facing decision, including gate-less ones.
        for (var entry : desired.entrySet()) {
            assertThat(features.findByFactoryIdAndModuleIdAndDeletedAtIsNull(factory, entry.getKey()))
                    .get().extracting(f -> f.getEnabled()).isEqualTo(entry.getValue());
        }
        // Customer-facing state: a disabled finance reads as backend-closed, not as "API still open".
        var states = moduleSwitch.describe(factory).stream()
                .collect(Collectors.toMap(row -> (String) row.get("moduleId"), row -> row));
        assertThat(states.get("finance").get("backendEnabled")).isEqualTo(false);
        assertThat(states.get("production").get("backendEnabled")).isEqualTo(false);
        assertThat(states.get("warehouse").get("backendEnabled")).isEqualTo(true);
    }

    private ModuleConfigDraft persistDraft(String factory, Map<String, Boolean> desired) {
        String draftId = UUID.randomUUID().toString();
        int gateTotal = desired.keySet().stream()
                .mapToInt(id -> InterviewModuleConfigCatalog.find(id).gateCodes().size()).sum();
        ModuleConfigDraft draft = ModuleConfigDraft.builder()
                .id(draftId).factoryId(factory).blueprintId("bp-" + factory).blueprintVersion(1L)
                .sourceDigest(hex('a')).catalogDigest(hex('b')).sourceSnapshotDigest(hex('c'))
                .desiredDigest(hex('d')).previewDigest(hex('e')).requestDigest(hex('f'))
                .idempotencyKey("draft-" + draftId).moduleCount(desired.size()).gateCodeCount(gateTotal)
                .dependencyBasis("CATALOG").dependencyClosureComplete(true).historicalReadPolicy("NONE")
                .status("DRAFT").build();
        entityManager.persist(draft);
        int ordinal = 0;
        for (var entry : desired.entrySet()) {
            var catalog = InterviewModuleConfigCatalog.find(entry.getKey());
            entityManager.persist(ModuleConfigDraftItem.builder()
                    .id(UUID.randomUUID().toString()).draftId(draftId).factoryId(factory).ordinal(ordinal++)
                    .moduleId(catalog.moduleId()).moduleLabel(catalog.label()).moduleDetail(catalog.detail())
                    .carrier(catalog.carrier()).desiredEnabled(entry.getValue()).rationale("blueprint")
                    .currentFrontendVisible(true).currentBackendEnabled(true).currentState("ENABLED")
                    .backendGated(!catalog.gateCodes().isEmpty()).gateCodeCount(catalog.gateCodes().size())
                    .historicalReadPolicy("NONE").build());
        }
        return draft;
    }

    private static ModuleSchema schema(String code) {
        return ModuleSchema.builder().moduleCode(code).moduleName(code).moduleCategory("core")
                .fieldSchema(Map.of()).defaultConfig(Map.of()).build();
    }

    private static String hex(char c) {
        return String.valueOf(c).repeat(64);
    }
}
