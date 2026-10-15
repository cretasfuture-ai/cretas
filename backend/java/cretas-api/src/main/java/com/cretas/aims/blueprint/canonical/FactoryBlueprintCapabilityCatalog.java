package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityCatalogView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityDefinitionView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.PlannedChange;
import com.cretas.aims.blueprint.interview.FactoryAssemblyDraftIntent;
import com.cretas.aims.entity.config.FactoryCapabilityDefinition;
import com.cretas.aims.entity.config.ModuleSchema;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactoryCapabilityDefinitionRepository;
import com.cretas.aims.repository.config.ModuleSchemaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned server authority for cross-module Factory Blueprint capabilities.
 * Definitions are append-only database rows. A blueprint stores the version,
 * digest, and derived snapshot so later catalogs cannot rewrite its meaning.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
@RequiredArgsConstructor
public class FactoryBlueprintCapabilityCatalog {

    public static final String V1_VERSION = "factory-capability-catalog/v1";
    public static final String CURRENT_VERSION = "factory-capability-catalog/v2";
    public static final String SOURCE = "SERVER_VERSIONED_CAPABILITY_CATALOG";

    private static final Set<String> V1_CAPABILITY_IDS = Set.of(
            "factory", "products", "materials", "processes", "routes", "bom",
            "warehouses", "packaging", "accounts", "modules", "alerts", "forms", "workflow");
    private static final Set<String> V2_CAPABILITY_IDS = Set.of(
            "factory", "products", "materials", "supplier-receiving", "processes", "routes", "bom",
            "hazard-analysis", "critical-controls", "allergen-label", "sanitation-cip",
            "environmental-monitoring", "hygienic-zones", "warehouses", "packaging",
            "equipment-calibration", "quality-release", "deviation-capa", "trace-recall",
            "accounts", "modules", "forms", "alerts", "contract-material", "emergency-defense",
            "workflow");
    private static final Set<String> V2_SECTOR_PROFILES = Set.of(
            "ALL_FOOD", "CHILLED_RTE_MEAT", "BAKERY", "BEVERAGE_SAUCE",
            "FROZEN_PREPARED_CENTRAL_KITCHEN");
    private static final Set<String> V2_AUTHORITY_REFS = Set.of(
            "S01_GB14881_2013",
            "S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27",
            "S03_PRC_IMPLEMENTATION_REGULATION_RESEARCHED_2026_08_27",
            "S04_GB8957_2016", "S04_GB12695_2016", "S04_GB31646_2018",
            "S05_GB19295_2011", "S06_CXC1_1969_REV2022", "S07_US_21CFR117_CROSSCHECK");
    private static final Set<String> V2_REQUIREMENT_CODES = Set.of(
            "factory-scope-sector", "product-sku-packaging", "material-specifications",
            "supplier-incoming", "formula-bom-rework", "process-flow-segregation",
            "hazard-analysis", "critical-limits-monitoring", "deviation-capa",
            "allergen-label-control", "sanitation-cip", "environmental-pathogen-monitoring",
            "hygienic-zones-flows", "warehouse-cold-chain", "equipment-maintenance-calibration",
            "quality-sampling-release", "traceability-recall", "roles-training-health-approval",
            "records-retention-audit", "alerts-escalation", "contract-customer-material",
            "emergency-defense-utility", "workflow-human-publication");
    private static final Set<String> DERIVATION_STRATEGIES = Set.of(
            "FACTORY", "PLANNED_CHANGE", "ROUTE", "BOM", "WORKFLOW");

    private final FactoryCapabilityDefinitionRepository repository;
    private final ModuleSchemaRepository moduleSchemaRepository;

    public record CatalogSnapshot(String version, String digest,
                                  List<FactoryCapabilityDefinition> definitions,
                                  LocalDateTime loadedAt) {
    }

    public record FactoryTypeCatalogRef(String version, String digest) {
    }

    @Transactional(readOnly = true)
    public CatalogSnapshot current() {
        return load(CURRENT_VERSION);
    }

    @Transactional(readOnly = true)
    public CatalogSnapshot load(String catalogVersion) {
        if (catalogVersion == null || catalogVersion.isBlank()) {
            throw unavailable("蓝图没有锁定能力目录版本，禁止继续派生或执行");
        }
        String requestedVersion = catalogVersion.trim();
        List<FactoryCapabilityDefinition> definitions = repository
                .findByCatalogVersionOrderByDisplayOrderAsc(requestedVersion);
        if (definitions.isEmpty()) {
            throw unavailable("能力目录 " + requestedVersion + " 不存在或尚未部署");
        }
        validate(requestedVersion, definitions);
        List<FactoryCapabilityDefinition> immutableDefinitions = List.copyOf(definitions);
        return new CatalogSnapshot(requestedVersion, digest(requestedVersion, immutableDefinitions),
                immutableDefinitions, LocalDateTime.now());
    }

    public void verifyLockedCatalog(FactoryTypeCatalogRef ref, CatalogSnapshot snapshot) {
        if (ref.version() == null || ref.digest() == null) {
            throw unavailable("蓝图缺少能力目录版本或摘要，必须重新保存草稿");
        }
        if (!ref.version().equals(snapshot.version()) || !ref.digest().equals(snapshot.digest())) {
            throw invalid("蓝图锁定的能力目录摘要与服务端定义不一致，禁止继续操作");
        }
    }

    public CapabilityCatalogView toView(CatalogSnapshot snapshot) {
        return CapabilityCatalogView.builder()
                .catalogVersion(snapshot.version())
                .digest(snapshot.digest())
                .source(SOURCE)
                .capabilityCount(snapshot.definitions().size())
                .definitions(snapshot.definitions().stream().map(this::definitionView).toList())
                .loadedAt(snapshot.loadedAt())
                .build();
    }

    public List<CapabilityView> derive(CatalogSnapshot snapshot, List<PlannedChange> changes,
                                       ApplyAIInitRequest payload) {
        FactoryAssemblyDraftIntent.requireValid(
                payload == null ? null : payload.getDraftCapabilityIntents());
        List<PlannedChange> safeChanges = changes == null ? List.of() : changes;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (FactoryCapabilityDefinition definition : snapshot.definitions()) {
            int matchedCount = (int) safeChanges.stream()
                    .filter(change -> matches(definition, safe(change.getGroup())))
                    .count();
            counts.put(definition.getCapabilityId(), derivedCount(definition, matchedCount, payload));
        }

        Map<String, CapabilityView> built = new LinkedHashMap<>();
        for (FactoryCapabilityDefinition definition : snapshot.definitions()) {
            String capabilityId = definition.getCapabilityId();
            int count = counts.getOrDefault(capabilityId, 0);
            boolean included = count > 0;
            List<String> missing = safeList(definition.getDependsOn()).stream()
                    .filter(dependency -> dependencyRequired(capabilityId, dependency, payload))
                    .filter(dependency -> {
                        CapabilityView dependencyView = built.get(dependency);
                        return dependencyView == null || !"READY".equals(dependencyView.getStatus());
                    })
                    .toList();
            String status = !included ? "NOT_STARTED" : missing.isEmpty() ? "READY" : "BLOCKED";
            CapabilityView view = CapabilityView.builder()
                    .id(capabilityId)
                    .label(definition.getLabel())
                    .description(definition.getDescription())
                    .displayOrder(definition.getDisplayOrder())
                    .dependsOn(safeList(definition.getDependsOn()))
                    .configurationTypes(safeList(definition.getConfigurationTypes()))
                    .moduleSchemaCodes(safeList(definition.getModuleSchemaCodes()))
                    .sectorProfiles(safeList(definition.getSectorProfiles()))
                    .authorityRefs(safeList(definition.getAuthorityRefs()))
                    .requirementCodes(safeList(definition.getRequirementCodes()))
                    .draftIntentEligible(FactoryAssemblyDraftIntent.isDraftIntentEligible(capabilityId))
                    .configurationRefs(configurationRefs(definition, safeChanges))
                    .plannedCount(count)
                    .included(included)
                    .status(status)
                    .missingDependencies(missing)
                    .source(CanonicalFactoryBlueprintModels.SOURCE)
                    .confirmationState("PENDING")
                    .blueprintPublishState("NOT_PUBLISHED")
                    .configurationApplyState("NOT_APPLIED")
                    .workflowPublishState("workflow".equals(capabilityId) ? "NOT_PUBLISHED" : "NOT_APPLICABLE")
                    .acceptanceEligible(false)
                    .acceptanceState(included ? "NOT_READY" : "NOT_APPLICABLE")
                    .validationIssues(List.of())
                    .configurationReceipts(List.of())
                    .acceptanceReceipts(List.of())
                    .nextStep("BLOCKED".equals(status)
                            ? "先补齐依赖，再进入逐项确认"
                            : "READY".equals(status) ? "可进入逐项确认" : "本轮还没有提出配置")
                    .build();
            built.put(capabilityId, view);
        }
        return new ArrayList<>(built.values());
    }

    private boolean dependencyRequired(String capabilityId, String dependency,
                                       ApplyAIInitRequest payload) {
        if (!"workflow".equals(capabilityId) || !"critical-controls".equals(dependency)) {
            return true;
        }
        return criticalControlsRequirement(payload) != CriticalControlsRequirement.NOT_REQUIRED;
    }

    public enum CriticalControlsRequirement { REQUIRED, NOT_REQUIRED, UNKNOWN }

    /** The source, rather than the caller's selection, determines this dependency. */
    public static CriticalControlsRequirement criticalControlsRequirement(ApplyAIInitRequest payload) {
        if (payload == null || payload.getSuggestedData() == null) return CriticalControlsRequirement.UNKNOWN;
        var checkpoints = payload.getSuggestedData().getHaccpCheckpoints();
        return checkpoints == null || checkpoints.isEmpty()
                ? CriticalControlsRequirement.NOT_REQUIRED : CriticalControlsRequirement.REQUIRED;
    }

    public String capabilityId(CatalogSnapshot snapshot, PlannedChange change) {
        String group = safe(change == null ? null : change.getGroup());
        return snapshot.definitions().stream()
                .filter(definition -> matches(definition, group))
                .map(FactoryCapabilityDefinition::getCapabilityId)
                .findFirst()
                .orElse("unclassified");
    }

    private CapabilityDefinitionView definitionView(FactoryCapabilityDefinition definition) {
        return CapabilityDefinitionView.builder()
                .id(definition.getCapabilityId())
                .label(definition.getLabel())
                .description(definition.getDescription())
                .displayOrder(definition.getDisplayOrder())
                .dependsOn(safeList(definition.getDependsOn()))
                .configurationTypes(safeList(definition.getConfigurationTypes()))
                .moduleSchemaCodes(safeList(definition.getModuleSchemaCodes()))
                .sectorProfiles(safeList(definition.getSectorProfiles()))
                .authorityRefs(safeList(definition.getAuthorityRefs()))
                .requirementCodes(safeList(definition.getRequirementCodes()))
                .draftIntentEligible(FactoryAssemblyDraftIntent.isDraftIntentEligible(
                        definition.getCapabilityId()))
                .build();
    }

    private int derivedCount(FactoryCapabilityDefinition definition, int matchedCount,
                             ApplyAIInitRequest payload) {
        return switch (definition.getDerivationStrategy()) {
            case "FACTORY" -> 1;
            case "ROUTE" -> matchedCount + routeCount(payload);
            case "BOM" -> matchedCount + recipeCount(payload);
            case "WORKFLOW" -> matchedCount + routeCount(payload);
            case "PLANNED_CHANGE" -> "packaging".equals(definition.getCapabilityId())
                    ? Math.max(matchedCount, packagingFactCount(payload)) : matchedCount;
            default -> throw invalid("未知能力派生策略：" + definition.getDerivationStrategy());
        };
    }

    private List<String> configurationRefs(FactoryCapabilityDefinition definition,
                                           List<PlannedChange> changes) {
        if ("FACTORY".equals(definition.getDerivationStrategy())) {
            return List.of("sourceSnapshot");
        }
        return changes.stream()
                .filter(change -> matches(definition, safe(change.getGroup())))
                .map(change -> safe(change.getGroup()) + ":" + safe(change.getKey()))
                .filter(reference -> !reference.equals(":"))
                .distinct()
                .toList();
    }

    private boolean matches(FactoryCapabilityDefinition definition, String group) {
        if ("processes".equals(definition.getCapabilityId()) && group.contains("工序链")) {
            return false;
        }
        return safeList(definition.getMatchKeywords()).stream().anyMatch(group::contains);
    }

    private int routeCount(ApplyAIInitRequest payload) {
        return payload != null && payload.getSuggestedData() != null
                && payload.getSuggestedData().getProductProcesses() != null
                ? payload.getSuggestedData().getProductProcesses().size() : 0;
    }

    private int recipeCount(ApplyAIInitRequest payload) {
        return payload != null && payload.getSuggestedData() != null
                && payload.getSuggestedData().getWorkProcesses() != null
                ? (int) payload.getSuggestedData().getWorkProcesses().stream()
                        .filter(item -> (item.getAuxMaterials() != null && !item.getAuxMaterials().isEmpty())
                                || (item.getPackMaterials() != null && !item.getPackMaterials().isEmpty()))
                        .count()
                : 0;
    }

    /**
     * Packaging is also an explicit fact on a process recipe.  It is deliberately counted only
     * from {@code packMaterials}; a process name, conversion rate, or missing case coefficient is
     * not packaging evidence.
     */
    private int packagingFactCount(ApplyAIInitRequest payload) {
        return payload != null && payload.getSuggestedData() != null
                && payload.getSuggestedData().getWorkProcesses() != null
                ? (int) payload.getSuggestedData().getWorkProcesses().stream()
                        .filter(item -> item != null && item.getPackMaterials() != null
                                && item.getPackMaterials().stream().anyMatch(material -> material != null))
                        .count()
                : 0;
    }

    private void validate(String catalogVersion, List<FactoryCapabilityDefinition> definitions) {
        Set<String> ids = new LinkedHashSet<>();
        Set<Integer> orders = new HashSet<>();
        Map<String, FactoryCapabilityDefinition> byId = new LinkedHashMap<>();
        for (FactoryCapabilityDefinition definition : definitions) {
            if (!catalogVersion.equals(definition.getCatalogVersion())
                    || definition.getCapabilityId() == null || definition.getCapabilityId().isBlank()
                    || definition.getLabel() == null || definition.getLabel().isBlank()
                    || definition.getDescription() == null || definition.getDescription().isBlank()
                    || definition.getDisplayOrder() == null
                    || !DERIVATION_STRATEGIES.contains(definition.getDerivationStrategy())) {
                throw invalid("能力目录包含缺失或跨版本定义");
            }
            validateValues(definition.getCapabilityId(), "dependsOn", definition.getDependsOn(), true);
            validateValues(definition.getCapabilityId(), "matchKeywords", definition.getMatchKeywords(),
                    "FACTORY".equals(definition.getDerivationStrategy()));
            validateValues(definition.getCapabilityId(), "configurationTypes",
                    definition.getConfigurationTypes(), false);
            validateValues(definition.getCapabilityId(), "moduleSchemaCodes",
                    definition.getModuleSchemaCodes(), true);
            validateVersionedResearchMetadata(catalogVersion, definition);
            if (!ids.add(definition.getCapabilityId())) {
                throw invalid("能力目录包含重复 ID：" + definition.getCapabilityId());
            }
            if (!orders.add(definition.getDisplayOrder())) {
                throw invalid("能力目录包含重复显示顺序：" + definition.getDisplayOrder());
            }
            byId.put(definition.getCapabilityId(), definition);
        }
        if (V1_VERSION.equals(catalogVersion) && !ids.equals(V1_CAPABILITY_IDS)) {
            throw invalid("v1 能力目录必须完整包含 13 个权威能力定义");
        }
        if (CURRENT_VERSION.equals(catalogVersion) && !ids.equals(V2_CAPABILITY_IDS)) {
            throw invalid("v2 能力目录必须完整包含 26 个权威能力定义");
        }
        if (!V1_VERSION.equals(catalogVersion) && !CURRENT_VERSION.equals(catalogVersion)) {
            throw invalid("服务端不支持能力目录版本：" + catalogVersion);
        }
        if (CURRENT_VERSION.equals(catalogVersion)) {
            Set<String> coveredSectors = definitions.stream()
                    .flatMap(definition -> safeList(definition.getSectorProfiles()).stream())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!coveredSectors.equals(V2_SECTOR_PROFILES)) {
                throw invalid("v2 能力目录没有完整覆盖食品工厂通用基线和四个行业原型");
            }
            Set<String> coveredAuthorities = definitions.stream()
                    .flatMap(definition -> safeList(definition.getAuthorityRefs()).stream())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!coveredAuthorities.equals(V2_AUTHORITY_REFS)) {
                throw invalid("v2 能力目录没有完整覆盖 Stage A 权威依据台账");
            }
            Set<String> coveredRequirements = definitions.stream()
                    .flatMap(definition -> safeList(definition.getRequirementCodes()).stream())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!coveredRequirements.equals(V2_REQUIREMENT_CODES)) {
                throw invalid("v2 能力目录没有完整覆盖 23 项食品安全研究需求");
            }
        }
        validateModuleSchemaReferences(definitions);
        FactoryCapabilityDefinition root = byId.get("factory");
        if (root == null || !"FACTORY".equals(root.getDerivationStrategy())) {
            throw invalid("能力目录缺少 factory 根能力");
        }
        for (FactoryCapabilityDefinition definition : definitions) {
            for (String dependency : safeList(definition.getDependsOn())) {
                FactoryCapabilityDefinition dependencyDefinition = byId.get(dependency);
                if (dependencyDefinition == null) {
                    throw invalid(definition.getCapabilityId() + " 引用了不存在的依赖 " + dependency);
                }
                if (dependencyDefinition.getDisplayOrder() >= definition.getDisplayOrder()) {
                    throw invalid(definition.getCapabilityId() + " 的依赖顺序无效：" + dependency);
                }
            }
        }
        detectCycles(byId);
    }

    private void validateVersionedResearchMetadata(String catalogVersion,
                                                    FactoryCapabilityDefinition definition) {
        boolean v2 = CURRENT_VERSION.equals(catalogVersion);
        validateValues(definition.getCapabilityId(), "sectorProfiles",
                definition.getSectorProfiles(), !v2);
        validateValues(definition.getCapabilityId(), "authorityRefs",
                definition.getAuthorityRefs(), !v2);
        validateValues(definition.getCapabilityId(), "requirementCodes",
                definition.getRequirementCodes(), !v2);
        if (!v2) {
            return;
        }
        validateKnownValues(definition.getCapabilityId(), "sectorProfiles",
                definition.getSectorProfiles(), V2_SECTOR_PROFILES);
        validateKnownValues(definition.getCapabilityId(), "authorityRefs",
                definition.getAuthorityRefs(), V2_AUTHORITY_REFS);
        validateKnownValues(definition.getCapabilityId(), "requirementCodes",
                definition.getRequirementCodes(), V2_REQUIREMENT_CODES);
    }

    private void validateKnownValues(String capabilityId, String field, List<String> values,
                                     Set<String> allowed) {
        List<String> unknown = safeList(values).stream()
                .filter(value -> !allowed.contains(value))
                .toList();
        if (!unknown.isEmpty()) {
            throw invalid(capabilityId + " 的 " + field + " 包含未知值：" + String.join(",", unknown));
        }
    }

    private void validateModuleSchemaReferences(List<FactoryCapabilityDefinition> definitions) {
        Set<String> moduleCodes = definitions.stream()
                .flatMap(definition -> safeList(definition.getModuleSchemaCodes()).stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String moduleCode : moduleCodes) {
            ModuleSchema schema = moduleSchemaRepository.findByModuleCode(moduleCode)
                    .orElseThrow(() -> invalid("能力目录引用了不存在的模块 Schema：" + moduleCode));
            if (!Boolean.TRUE.equals(schema.getIsActive()) || schema.getDeletedAt() != null) {
                throw invalid("能力目录引用了未启用的模块 Schema：" + moduleCode);
            }
        }
    }

    private void validateValues(String capabilityId, String field, List<String> values,
                                boolean emptyAllowed) {
        if (values == null || (!emptyAllowed && values.isEmpty())
                || values.stream().anyMatch(value -> value == null || value.isBlank())
                || new HashSet<>(values).size() != values.size()) {
            throw invalid(capabilityId + " 的 " + field + " 定义无效");
        }
    }

    private void detectCycles(Map<String, FactoryCapabilityDefinition> definitions) {
        Map<String, Integer> states = new HashMap<>();
        for (String capabilityId : definitions.keySet()) {
            visit(capabilityId, definitions, states);
        }
    }

    private void visit(String capabilityId, Map<String, FactoryCapabilityDefinition> definitions,
                       Map<String, Integer> states) {
        int state = states.getOrDefault(capabilityId, 0);
        if (state == 1) {
            throw invalid("能力目录依赖存在环：" + capabilityId);
        }
        if (state == 2) {
            return;
        }
        states.put(capabilityId, 1);
        for (String dependency : safeList(definitions.get(capabilityId).getDependsOn())) {
            visit(dependency, definitions, states);
        }
        states.put(capabilityId, 2);
    }

    private String digest(String version, List<FactoryCapabilityDefinition> definitions) {
        StringBuilder canonical = new StringBuilder(version).append('\u001e');
        for (FactoryCapabilityDefinition definition : definitions) {
            append(canonical, definition.getCapabilityId());
            append(canonical, definition.getLabel());
            append(canonical, definition.getDescription());
            append(canonical, String.valueOf(definition.getDisplayOrder()));
            append(canonical, definition.getDerivationStrategy());
            append(canonical, String.join("\u001d", safeList(definition.getDependsOn())));
            append(canonical, String.join("\u001d", safeList(definition.getMatchKeywords())));
            append(canonical, String.join("\u001d", safeList(definition.getConfigurationTypes())));
            append(canonical, String.join("\u001d", safeList(definition.getModuleSchemaCodes())));
            if (CURRENT_VERSION.equals(version)) {
                append(canonical, String.join("\u001d", safeList(definition.getSectorProfiles())));
                append(canonical, String.join("\u001d", safeList(definition.getAuthorityRefs())));
                append(canonical, String.join("\u001d", safeList(definition.getRequirementCodes())));
            }
            canonical.append('\u001e');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private void append(StringBuilder canonical, String value) {
        String safeValue = safe(value);
        canonical.append(safeValue.length()).append(':').append(safeValue).append('\u001f');
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(503, message)
                .withCode("BLUEPRINT_CAPABILITY_CATALOG_UNAVAILABLE")
                .withHint("确认对应目录版本已完成迁移后重试")
                .withSeverity("BLOCKING");
    }

    private BusinessException invalid(String message) {
        return new BusinessException(500, message)
                .withCode("BLUEPRINT_CAPABILITY_CATALOG_INVALID")
                .withHint("修复目录定义或恢复已锁定版本后重试")
                .withSeverity("BLOCKING");
    }
}
