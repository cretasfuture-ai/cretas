package com.cretas.aims.service.sanitationcipdraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteNode;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.equipmentplan.EquipmentCalibrationPlanDraft;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.entity.sanitationcipdraft.SanitationCipTemplateDraft;
import com.cretas.aims.entity.sanitationcipdraft.SanitationCipTemplateDraftEvidence;
import com.cretas.aims.entity.sanitationcipdraft.SanitationCipTemplateDraftItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.equipmentplan.EquipmentCalibrationPlanDraftRepository;
import com.cretas.aims.repository.foodsafety.SsopProcedureRepository;
import com.cretas.aims.repository.sanitationcipdraft.SanitationCipTemplateDraftEvidenceRepository;
import com.cretas.aims.repository.sanitationcipdraft.SanitationCipTemplateDraftItemRepository;
import com.cretas.aims.repository.sanitationcipdraft.SanitationCipTemplateDraftRepository;
import com.cretas.aims.service.equipmentplandraft.EquipmentCalibrationPlanDraftCommand;
import com.cretas.aims.service.equipmentplandraft.EquipmentCalibrationPlanDraftModels;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.ChemicalType;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.ConcentrationUnit;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.DerivedTemplate;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.DraftIssue;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.DraftPreview;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.DraftReadback;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.DraftReceipt;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.DraftRequest;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.EquipmentPlanPin;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.EvidenceType;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.RecoveryPlan;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.SanitationMethod;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.TargetType;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.TemplateInput;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.VerificationMethod;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module-owned sanitation/CIP TEMPLATE/DRAFT command.
 *
 * <p>The formal SSOP service and its AI tools are intentionally unreachable. Existing SSOP rows
 * are only a same-factory drift baseline; this command writes only its three draft tables and never
 * creates cleaning executions, production gates, equipment commands, environmental results,
 * alerts or notifications.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class SanitationCipDraftCommand {
    public static final String CAPABILITY_ID = "sanitation-cip";
    public static final String REQUIRED_PERMISSION = "sanitation:template:draft";
    private static final String AUTHORITY_CODE = "SANITATION_CIP_CATALOG_AUTHORITY";
    private static final List<String> DEPENDENCIES =
            FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID);
    private static final int MAX_TEMPLATES = 500;

    private final SanitationCipTemplateDraftRepository drafts;
    private final SanitationCipTemplateDraftItemRepository items;
    private final SanitationCipTemplateDraftEvidenceRepository evidence;
    private final EquipmentCalibrationPlanDraftRepository equipmentPlans;
    private final EquipmentCalibrationPlanDraftCommand equipmentPlanCommand;
    private final SsopProcedureRepository ssopProcedures;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UserRepository users;

    public SanitationCipDraftCommand(
            SanitationCipTemplateDraftRepository drafts,
            SanitationCipTemplateDraftItemRepository items,
            SanitationCipTemplateDraftEvidenceRepository evidence,
            EquipmentCalibrationPlanDraftRepository equipmentPlans,
            EquipmentCalibrationPlanDraftCommand equipmentPlanCommand,
            SsopProcedureRepository ssopProcedures,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UserRepository users) {
        this.drafts = drafts;
        this.items = items;
        this.evidence = evidence;
        this.equipmentPlans = equipmentPlans;
        this.equipmentPlanCommand = equipmentPlanCommand;
        this.ssopProcedures = ssopProcedures;
        this.canonicalBlueprints = canonicalBlueprints;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        return evaluate(request).preview();
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, AuthenticatedOperator operator) {
        validateEnvelope(request, true);
        authorize(request, operator);
        String requestDigest = requestDigest(request, operator);
        Optional<SanitationCipTemplateDraft> prior = drafts
                .findByFactoryIdAndIdempotencyKey(request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同清洗消毒模板请求",
                        "SANITATION_CIP_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview plan = evaluation.preview();
        if (!"READY".equals(plan.status())) {
            throw invalid(409, "清洗消毒模板预览被阻断", "SANITATION_CIP_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(plan.previewDigest())) {
            throw invalid(409, "清洗消毒模板确认摘要已漂移", "SANITATION_CIP_PREVIEW_DRIFT");
        }

        BlueprintAuthority authority = evaluation.authority();
        EquipmentAuthority equipment = evaluation.equipment();
        SanitationCipTemplateDraft parent = SanitationCipTemplateDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(plan.sourceSnapshotDigest())
                .routeSnapshotDigest(plan.routeSnapshotDigest())
                .equipmentPlanDraftId(equipment.draftId())
                .equipmentPlanDesiredDigest(equipment.desiredDigest())
                .equipmentPlanSnapshotDigest(equipment.snapshotDigest())
                .ssopBaselineDigest(plan.ssopBaselineDigest())
                .capabilityCatalogVersion(authority.catalogVersion())
                .capabilityCatalogDigest(authority.catalogDigest())
                .authoritySnapshotDigest(authority.authoritySnapshotDigest())
                .desiredDigest(plan.desiredDigest())
                .previewDigest(plan.previewDigest())
                .requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .templateCount(plan.templates().size())
                .evidenceCount(1)
                .dependencyBasis(SanitationCipDraftModels.DEPENDENCY_BASIS)
                .algorithmVersion(SanitationCipDraftModels.ALGORITHM_VERSION)
                .dependencyClosureComplete(true)
                .status("DRAFT")
                .build();
        SanitationCipTemplateDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "清洗消毒模板幂等键发生并发竞争",
                    "SANITATION_CIP_IDEMPOTENCY_RACE");
        }

        List<SanitationCipTemplateDraftItem> rows = plan.templates().stream()
                .map(template -> toItem(saved, template)).toList();
        items.saveAllAndFlush(rows);
        evidence.saveAndFlush(SanitationCipTemplateDraftEvidence.builder()
                .id(UUID.randomUUID().toString())
                .draftId(saved.getId())
                .factoryId(saved.getFactoryId())
                .authorityCode(AUTHORITY_CODE)
                .catalogVersion(authority.catalogVersion())
                .catalogDigest(authority.catalogDigest())
                .authoritySnapshotDigest(authorityEvidenceDigest(authority))
                .build());

        DraftReadback readback = readBack(saved.getFactoryId(), saved.getId());
        if (!plan.desiredDigest().equals(readback.desiredDigest())) {
            throw invalid(409, "清洗消毒模板回读摘要不一致",
                    "SANITATION_CIP_READBACK_MISMATCH");
        }
        return receipt(saved, false, 1, 2 + rows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        SanitationCipTemplateDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该清洗消毒模板草稿",
                        "SANITATION_CIP_NOT_FOUND"));
        List<SanitationCipTemplateDraftItem> rows = items
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<SanitationCipTemplateDraftEvidence> evidenceRows = evidence
                .findByDraftIdAndFactoryIdOrderByAuthorityCode(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !SanitationCipDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !SanitationCipDraftModels.ALGORITHM_VERSION.equals(parent.getAlgorithmVersion())
                || parent.getTemplateCount() == null || parent.getTemplateCount() != rows.size()
                || parent.getEvidenceCount() == null || parent.getEvidenceCount() != 1
                || evidenceRows.size() != 1) {
            throw invalid(409, "清洗消毒模板父子行或依赖闭包不完整",
                    "SANITATION_CIP_READBACK_INVARIANT_BROKEN");
        }

        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(factoryId, parent.getBlueprintId(),
                parent.getBlueprintVersion(), issues);
        EquipmentAuthority equipment = resolveStoredEquipment(parent, issues);
        String currentSsop = ssopBaselineDigest(factoryId);
        if (!issues.isEmpty()
                || !parent.getRouteSnapshotDigest().equals(authority.routeSnapshotDigest())
                || !parent.getAuthoritySnapshotDigest().equals(authority.authoritySnapshotDigest())
                || !parent.getCapabilityCatalogVersion().equals(authority.catalogVersion())
                || !parent.getCapabilityCatalogDigest().equals(authority.catalogDigest())
                || !parent.getEquipmentPlanSnapshotDigest().equals(equipment.snapshotDigest())
                || !parent.getSsopBaselineDigest().equals(currentSsop)) {
            throw invalid(409, "清洗消毒模板上游 Blueprint、设备计划或 SSOP 基线已漂移",
                    "SANITATION_CIP_UPSTREAM_DRIFT");
        }
        String currentSourceSnapshot = digest("SANITATION_CIP_SOURCE",
                authority.blueprintSnapshotDigest(), authority.routeSnapshotDigest(),
                equipment.snapshotDigest(), currentSsop, authority.authoritySnapshotDigest());
        if (!currentSourceSnapshot.equals(parent.getSourceSnapshotDigest())) {
            throw invalid(409, "清洗消毒模板源快照摘要已漂移",
                    "SANITATION_CIP_SOURCE_SNAPSHOT_DRIFT");
        }
        if (!sameEvidence(evidenceRows.get(0), authority)) {
            throw invalid(409, "清洗消毒模板目录依据摘要不一致",
                    "SANITATION_CIP_EVIDENCE_MISMATCH");
        }

        Map<String, RouteTarget> routeTargets = routeTargets(authority.routeGraphs());
        List<DerivedTemplate> templates = new ArrayList<>();
        for (SanitationCipTemplateDraftItem row : rows) {
            DerivedTemplate template = fromItem(row);
            RouteTarget route = routeTargets.get(routeKey(template.productCode(), template.routeNodeId()));
            EquipmentEntry equipmentEntry = template.targetType() == TargetType.EQUIPMENT_CIP
                    ? equipment.entries().get(template.equipmentId()) : null;
            String expectedTarget = targetSnapshot(route, equipmentEntry);
            if (route == null || !Objects.equals(route.label(), template.routeNodeLabel())
                    || !expectedTarget.equals(template.targetSnapshotDigest())
                    || (template.targetType() == TargetType.EQUIPMENT_CIP
                        && (equipmentEntry == null
                            || !Objects.equals(equipmentEntry.ordinal(),
                                    template.equipmentPlanEntryOrdinal())))) {
                throw invalid(409, "清洗消毒对象的路线或设备快照已漂移",
                        "SANITATION_CIP_TARGET_DRIFT");
            }
            templates.add(template);
        }
        if (!desiredDigest(templates).equals(parent.getDesiredDigest())) {
            throw invalid(409, "清洗消毒模板逐字段回读摘要不一致",
                    "SANITATION_CIP_READBACK_MISMATCH");
        }
        return new DraftReadback(SanitationCipDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getStatus(), templates, parent.getSourceSnapshotDigest(),
                parent.getDesiredDigest(), 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId,
                                     String expectedDesiredDigest) {
        SanitationCipTemplateDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该清洗消毒模板草稿",
                        "SANITATION_CIP_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus())
                || !isDigest(expectedDesiredDigest)
                || !expectedDesiredDigest.equals(parent.getDesiredDigest())) {
            throw invalid(409, "只能恢复摘要精确匹配的未发布清洗消毒草稿",
                    "SANITATION_CIP_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(SanitationCipDraftModels.SCHEMA_VERSION, factoryId, draftId,
                expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "只归档未发布模板；不创建 SSOP、清洗记录、生产门、设备命令、环境结果、告警或通知",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(request.factoryId(), request.blueprintId(),
                request.blueprintVersion(), issues);
        EquipmentAuthority equipment = resolveEquipment(request, issues);
        Map<String, RouteTarget> targets = routeTargets(authority.routeGraphs());
        List<DerivedTemplate> derived = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (int ordinal = 0; ordinal < request.templates().size(); ordinal++) {
            TemplateInput input = request.templates().get(ordinal);
            String target = "templates[" + ordinal + "]";
            validateTemplate(input, target, issues);
            RouteTarget route = input == null ? null
                    : targets.get(routeKey(input.productCode(), input.routeNodeId()));
            if (route == null) {
                issues.add(issue("SANITATION_CIP_ROUTE_TARGET_MISSING", target,
                        "清洗对象必须引用当前 Blueprint 的确定路线节点",
                        "重新选择当前产品路线节点"));
                continue;
            }
            if (input == null || input.targetType() == null || input.method() == null
                    || input.chemicalType() == null || input.frequency() == null
                    || input.responsibleRole() == null) {
                continue;
            }
            EquipmentEntry equipmentEntry = input.targetType() == TargetType.EQUIPMENT_CIP
                    ? equipment.entries().get(input.equipmentId()) : null;
            if (input.targetType() == TargetType.EQUIPMENT_CIP && equipmentEntry == null) {
                issues.add(issue("SANITATION_CIP_EQUIPMENT_PLAN_MISSING", target,
                        "CIP 设备必须存在于同厂 readback-ready 的设备计划草稿",
                        "选择 B2 设备计划中已有的设备"));
                continue;
            }
            String identity = routeKey(input.productCode(), input.routeNodeId()) + "|"
                    + input.targetType() + "|" + String.valueOf(input.equipmentId());
            if (!identities.add(identity)) {
                issues.add(issue("SANITATION_CIP_DUPLICATE_TARGET", target,
                        "同一产品路线、对象类型和设备不能重复", "合并重复模板"));
                continue;
            }
            derived.add(derive(ordinal, input, route, equipmentEntry));
        }
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String ssopBaseline = ssopBaselineDigest(request.factoryId());
        String desired = desiredDigest(derived);
        String sourceSnapshot = digest("SANITATION_CIP_SOURCE",
                authority.blueprintSnapshotDigest(), authority.routeSnapshotDigest(),
                equipment.snapshotDigest(), ssopBaseline, authority.authoritySnapshotDigest());
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("SANITATION_CIP_PREVIEW",
                SanitationCipDraftModels.SCHEMA_VERSION,
                SanitationCipDraftModels.ALGORITHM_VERSION, status, sourceSnapshot,
                authority.routeSnapshotDigest(), equipment.snapshotDigest(), ssopBaseline,
                authority.authoritySnapshotDigest(), desired, issueDigest(issues));
        DraftPreview preview = new DraftPreview(SanitationCipDraftModels.SCHEMA_VERSION,
                SanitationCipDraftModels.ALGORITHM_VERSION, status, request.factoryId(),
                DEPENDENCIES, request, derived, sourceSnapshot, authority.routeSnapshotDigest(),
                equipment.snapshotDigest(), ssopBaseline, authority.authoritySnapshotDigest(),
                desired, previewDigest, issues, 0, 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, authority, equipment);
    }

    private BlueprintAuthority resolveBlueprint(String factoryId, String blueprintId,
                                                long blueprintVersion,
                                                List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(factoryId);
        if (response == null || !blueprintId.equals(response.getId())
                || !Objects.equals(Math.toIntExact(blueprintVersion), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                     || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("SANITATION_CIP_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
                    "当前工厂 Blueprint DRAFT/VALIDATED 版本与请求不一致",
                    "刷新当前 Blueprint 后重新预览"));
            return BlueprintAuthority.missing();
        }
        CapabilityView capability = response.getCapabilities() == null ? null
                : response.getCapabilities().stream()
                .filter(item -> CAPABILITY_ID.equals(item.getId())).findFirst().orElse(null);
        Set<String> dependencies = capability == null || capability.getDependsOn() == null
                ? Set.of() : Set.copyOf(capability.getDependsOn());
        if (capability == null || !capability.isIncluded() || !"READY".equals(capability.getStatus())
                || !dependencies.equals(Set.copyOf(DEPENDENCIES))) {
            issues.add(issue("SANITATION_CIP_CAPABILITY_NOT_READY", "capability",
                    "sanitation-cip 或 routes 目录依赖尚未就绪",
                    "补齐当前路线证据后重新编译 Blueprint"));
        }
        Set<String> refs = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || refs.isEmpty()) {
            issues.add(issue("SANITATION_CIP_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本、摘要或卫生依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        List<RouteGraph> graphs = response.getRouteGraphs() == null
                ? List.of() : response.getRouteGraphs();
        if (graphs.isEmpty() || graphs.stream().anyMatch(graph -> graph == null
                || graph.validation() == null || !graph.validation().valid()
                || graph.nodes().isEmpty())) {
            issues.add(issue("SANITATION_CIP_ROUTE_GRAPH_INVALID", "routes",
                    "当前 Blueprint 没有完整可验证的路线图", "修复路线节点和拓扑"));
        }
        String routeSnapshot = routeSnapshot(graphs);
        String authoritySnapshot = digest("SANITATION_CIP_AUTHORITY",
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                refs.stream().sorted().reduce("", (left, right) -> left + "|" + right));
        return new BlueprintAuthority(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), refs, authoritySnapshot, routeSnapshot,
                digestBlueprint(response), List.copyOf(graphs));
    }

    private EquipmentAuthority resolveEquipment(DraftRequest request, List<DraftIssue> issues) {
        boolean required = request.templates().stream().filter(Objects::nonNull)
                .anyMatch(template -> template.targetType() == TargetType.EQUIPMENT_CIP);
        EquipmentPlanPin pin = request.equipmentPlan();
        if (pin == null) {
            if (required) {
                issues.add(issue("SANITATION_CIP_EQUIPMENT_PLAN_REQUIRED", "equipmentPlan",
                        "设备 CIP 模板必须锁定 B2 设备计划草稿",
                        "选择同一 Blueprint 的设备计划草稿"));
            }
            return EquipmentAuthority.none();
        }
        if (pin.draftId().isBlank() || !isDigest(pin.desiredDigest())) {
            issues.add(issue("SANITATION_CIP_EQUIPMENT_PLAN_PIN_INVALID", "equipmentPlan",
                    "设备计划 ID 或摘要格式无效", "刷新设备计划 pin"));
            return EquipmentAuthority.none();
        }
        EquipmentCalibrationPlanDraft parent = equipmentPlans
                .findByIdAndFactoryId(pin.draftId(), request.factoryId()).orElse(null);
        if (parent == null || !"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !request.blueprintId().equals(parent.getBlueprintId())
                || !Objects.equals(request.blueprintVersion(), parent.getBlueprintVersion())
                || !request.sourceDigest().equals(parent.getSourceDigest())
                || !pin.desiredDigest().equals(parent.getDesiredDigest())) {
            issues.add(issue("SANITATION_CIP_EQUIPMENT_PLAN_DRIFT", "equipmentPlan",
                    "B2 设备计划不属于当前 Blueprint 或摘要已变化",
                    "重新读取并选择当前设备计划"));
            return EquipmentAuthority.none();
        }
        try {
            EquipmentCalibrationPlanDraftModels.DraftReadback readback =
                    equipmentPlanCommand.readBack(request.factoryId(), pin.draftId());
            return equipmentAuthority(parent, readback);
        } catch (RuntimeException exception) {
            issues.add(issue("SANITATION_CIP_EQUIPMENT_PLAN_READBACK_FAILED", "equipmentPlan",
                    "B2 设备计划逐字段回读失败", "修复设备计划后重新预览"));
            return EquipmentAuthority.none();
        }
    }

    private EquipmentAuthority resolveStoredEquipment(SanitationCipTemplateDraft parent,
                                                       List<DraftIssue> issues) {
        if (parent.getEquipmentPlanDraftId() == null) return EquipmentAuthority.none();
        EquipmentCalibrationPlanDraft plan = equipmentPlans.findByIdAndFactoryId(
                parent.getEquipmentPlanDraftId(), parent.getFactoryId()).orElse(null);
        if (plan == null || !Objects.equals(parent.getEquipmentPlanDesiredDigest(),
                plan.getDesiredDigest()) || !parent.getBlueprintId().equals(plan.getBlueprintId())
                || !Objects.equals(parent.getBlueprintVersion(), plan.getBlueprintVersion())
                || !parent.getSourceDigest().equals(plan.getSourceDigest())) {
            issues.add(issue("SANITATION_CIP_EQUIPMENT_PLAN_DRIFT", "equipmentPlan",
                    "已保存的 B2 设备计划 pin 已漂移", "重新生成卫生模板草稿"));
            return EquipmentAuthority.none();
        }
        try {
            return equipmentAuthority(plan,
                    equipmentPlanCommand.readBack(parent.getFactoryId(), plan.getId()));
        } catch (RuntimeException exception) {
            issues.add(issue("SANITATION_CIP_EQUIPMENT_PLAN_READBACK_FAILED", "equipmentPlan",
                    "已保存的 B2 设备计划逐字段回读失败", "修复设备计划后重新生成草稿"));
            return EquipmentAuthority.none();
        }
    }

    private EquipmentAuthority equipmentAuthority(
            EquipmentCalibrationPlanDraft parent,
            EquipmentCalibrationPlanDraftModels.DraftReadback readback) {
        Map<Long, EquipmentEntry> entries = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < readback.plans().size(); ordinal++) {
            EquipmentCalibrationPlanDraftModels.PlanEntry plan = readback.plans().get(ordinal);
            entries.putIfAbsent(plan.equipmentId(),
                    new EquipmentEntry(ordinal, plan.equipmentSnapshotDigest()));
        }
        return new EquipmentAuthority(parent.getId(), parent.getDesiredDigest(),
                digestEquipmentPlan(readback), Map.copyOf(entries));
    }

    private void validateTemplate(TemplateInput input, String target, List<DraftIssue> issues) {
        if (input == null || isBlankOrTooLong(input.productCode(), 100)
                || isBlankOrTooLong(input.routeNodeId(), 100) || input.targetType() == null
                || input.method() == null || input.chemicalType() == null
                || input.frequency() == null || input.responsibleRole() == null
                || isBlankOrTooLong(input.changeReason(), 500)) {
            issues.add(issue("SANITATION_CIP_TEMPLATE_FIELDS_REQUIRED", target,
                    "产品路线、对象、方法、频率、责任和变更原因均必填",
                    "补齐 typed 模板字段"));
            return;
        }
        boolean equipment = input.targetType() == TargetType.EQUIPMENT_CIP;
        if (equipment != (input.equipmentId() != null && input.equipmentId() > 0)
                || (equipment && input.method() != SanitationMethod.CLEAN_IN_PLACE)) {
            issues.add(issue("SANITATION_CIP_TARGET_SHAPE_INVALID", target,
                    "只有设备 CIP 对象可带设备 ID，且方法必须为 CLEAN_IN_PLACE",
                    "修正对象类型、设备和方法"));
        }
        validateChemical(input, target, issues);
        if (!validDecimal(input.temperatureMinC(), 8, 3)
                || !validDecimal(input.temperatureTargetC(), 8, 3)
                || !validDecimal(input.temperatureMaxC(), 8, 3)
                || input.temperatureMinC().compareTo(BigDecimal.ZERO) < 0
                || input.temperatureMaxC().compareTo(new BigDecimal("150")) > 0
                || input.temperatureMinC().compareTo(input.temperatureTargetC()) > 0
                || input.temperatureTargetC().compareTo(input.temperatureMaxC()) > 0) {
            issues.add(issue("SANITATION_CIP_TEMPERATURE_INVALID", target,
                    "温度必须是 0–150°C 的有序 typed 范围", "修正温度下限、目标和上限"));
        }
        if (input.contactTimeSeconds() == null || input.contactTimeSeconds() < 1
                || input.contactTimeSeconds() > 86400
                || input.preRinseSeconds() == null || input.preRinseSeconds() < 0
                || input.preRinseSeconds() > 7200
                || input.finalRinseSeconds() == null || input.finalRinseSeconds() < 0
                || input.finalRinseSeconds() > 7200) {
            issues.add(issue("SANITATION_CIP_TIME_INVALID", target,
                    "接触和冲洗时长超出 typed 安全范围", "修正秒数"));
        }
        if (input.method() == SanitationMethod.CLEAN_IN_PLACE) {
            if (!validDecimal(input.flowRateLitersPerMinute(), 12, 4)
                    || input.flowRateLitersPerMinute().signum() <= 0
                    || input.preRinseSeconds() == null || input.preRinseSeconds() <= 0
                    || input.finalRinseSeconds() == null || input.finalRinseSeconds() <= 0) {
                issues.add(issue("SANITATION_CIP_FLOW_RINSE_REQUIRED", target,
                        "CIP 必须有正流量及前后冲洗时长", "补齐流量与冲洗参数"));
            }
        } else if (input.flowRateLitersPerMinute() != null) {
            issues.add(issue("SANITATION_CIP_FLOW_FORBIDDEN", target,
                    "非 CIP 方法不能携带管路流量", "删除流量或改为 CLEAN_IN_PLACE"));
        }
        if (input.verificationMethods().isEmpty() || input.verificationMethods().size() > 7
                || new HashSet<>(input.verificationMethods()).size()
                   != input.verificationMethods().size()
                || input.requiredEvidence().isEmpty() || input.requiredEvidence().size() > 4
                || new HashSet<>(input.requiredEvidence()).size() != input.requiredEvidence().size()) {
            issues.add(issue("SANITATION_CIP_VERIFICATION_EVIDENCE_INVALID", target,
                    "验证方法和留证类型必须为非空、不重复的封闭枚举",
                    "选择有效验证与留证组合"));
        }
        if (input.holdUntilVerified()
                && input.verificationMethods().stream().allMatch(v -> v == VerificationMethod.VISUAL)) {
            issues.add(issue("SANITATION_CIP_HOLD_NEEDS_OBJECTIVE_VERIFICATION", target,
                    "清场 hold 不能只依赖目视", "增加 ATP、蛋白、pH、温度或微生物验证"));
        }
    }

    private void validateChemical(TemplateInput input, String target, List<DraftIssue> issues) {
        boolean noConcentration = Set.of(ChemicalType.NONE, ChemicalType.HOT_WATER,
                ChemicalType.STEAM).contains(input.chemicalType());
        boolean allNull = input.concentrationMin() == null
                && input.concentrationTarget() == null && input.concentrationMax() == null
                && input.concentrationUnit() == null;
        if (noConcentration) {
            if (!allNull) {
                issues.add(issue("SANITATION_CIP_CONCENTRATION_FORBIDDEN", target,
                        "水、蒸汽或无化学品方法不能携带化学浓度", "删除浓度字段"));
            }
            return;
        }
        if (!validDecimal(input.concentrationMin(), 12, 4)
                || !validDecimal(input.concentrationTarget(), 12, 4)
                || !validDecimal(input.concentrationMax(), 12, 4)
                || input.concentrationUnit() == null || input.concentrationMin().signum() <= 0
                || input.concentrationMin().compareTo(input.concentrationTarget()) > 0
                || input.concentrationTarget().compareTo(input.concentrationMax()) > 0
                || (input.concentrationUnit() == ConcentrationUnit.PERCENT
                    && input.concentrationMax().compareTo(new BigDecimal("100")) > 0)) {
            issues.add(issue("SANITATION_CIP_CONCENTRATION_INVALID", target,
                    "化学品浓度必须是正数、有序且使用 PPM/PERCENT typed 单位",
                    "修正浓度下限、目标、上限和单位"));
        }
    }

    private DerivedTemplate derive(int ordinal, TemplateInput input, RouteTarget route,
                                   EquipmentEntry equipment) {
        List<VerificationMethod> verification = input.verificationMethods().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        List<EvidenceType> requiredEvidence = input.requiredEvidence().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        String targetSnapshot = targetSnapshot(route, equipment);
        String code = "SAN-" + digest(input.productCode(), input.routeNodeId(),
                input.targetType().name(), String.valueOf(input.equipmentId())).substring(0, 16)
                .toUpperCase(Locale.ROOT);
        return new DerivedTemplate(ordinal, code, input.productCode(), input.routeNodeId(),
                route.label(), input.targetType(), input.equipmentId(),
                equipment == null ? null : equipment.ordinal(), targetSnapshot, input.method(),
                input.chemicalType(), input.concentrationMin(), input.concentrationTarget(),
                input.concentrationMax(), input.concentrationUnit(), input.temperatureMinC(),
                input.temperatureTargetC(), input.temperatureMaxC(), input.contactTimeSeconds(),
                input.flowRateLitersPerMinute(), input.preRinseSeconds(),
                input.finalRinseSeconds(), input.frequency(), verification,
                input.responsibleRole(), requiredEvidence, input.holdUntilVerified(),
                input.changeReason());
    }

    private SanitationCipTemplateDraftItem toItem(SanitationCipTemplateDraft parent,
                                                   DerivedTemplate value) {
        boolean equipment = value.targetType() == TargetType.EQUIPMENT_CIP;
        return SanitationCipTemplateDraftItem.builder()
                .id(UUID.randomUUID().toString()).draftId(parent.getId())
                .factoryId(parent.getFactoryId()).ordinal(value.ordinal())
                .templateCode(value.templateCode()).productCode(value.productCode())
                .routeNodeId(value.routeNodeId()).routeNodeLabel(value.routeNodeLabel())
                .targetType(value.targetType().name())
                .equipmentPlanDraftId(equipment ? parent.getEquipmentPlanDraftId() : null)
                .equipmentId(value.equipmentId())
                .equipmentPlanEntryOrdinal(value.equipmentPlanEntryOrdinal())
                .targetSnapshotDigest(value.targetSnapshotDigest())
                .sanitationMethod(value.method().name()).chemicalType(value.chemicalType().name())
                .concentrationMin(value.concentrationMin())
                .concentrationTarget(value.concentrationTarget())
                .concentrationMax(value.concentrationMax())
                .concentrationUnit(value.concentrationUnit() == null
                        ? null : value.concentrationUnit().name())
                .temperatureMinC(value.temperatureMinC())
                .temperatureTargetC(value.temperatureTargetC())
                .temperatureMaxC(value.temperatureMaxC())
                .contactTimeSeconds(value.contactTimeSeconds())
                .flowRateLitersPerMinute(value.flowRateLitersPerMinute())
                .preRinseSeconds(value.preRinseSeconds())
                .finalRinseSeconds(value.finalRinseSeconds())
                .frequency(value.frequency().name())
                .verificationMethods(joinEnums(value.verificationMethods()))
                .responsibleRole(value.responsibleRole().name())
                .requiredEvidence(joinEnums(value.requiredEvidence()))
                .holdUntilVerified(value.holdUntilVerified())
                .changeReason(value.changeReason()).build();
    }

    private DerivedTemplate fromItem(SanitationCipTemplateDraftItem row) {
        return new DerivedTemplate(row.getOrdinal(), row.getTemplateCode(), row.getProductCode(),
                row.getRouteNodeId(), row.getRouteNodeLabel(), TargetType.valueOf(row.getTargetType()),
                row.getEquipmentId(), row.getEquipmentPlanEntryOrdinal(),
                row.getTargetSnapshotDigest(), SanitationMethod.valueOf(row.getSanitationMethod()),
                ChemicalType.valueOf(row.getChemicalType()), row.getConcentrationMin(),
                row.getConcentrationTarget(), row.getConcentrationMax(),
                row.getConcentrationUnit() == null ? null
                        : ConcentrationUnit.valueOf(row.getConcentrationUnit()),
                row.getTemperatureMinC(), row.getTemperatureTargetC(), row.getTemperatureMaxC(),
                row.getContactTimeSeconds(), row.getFlowRateLitersPerMinute(),
                row.getPreRinseSeconds(), row.getFinalRinseSeconds(),
                SanitationCipDraftModels.Frequency.valueOf(row.getFrequency()),
                parseEnums(row.getVerificationMethods(), VerificationMethod::valueOf),
                SanitationCipDraftModels.ResponsibleRole.valueOf(row.getResponsibleRole()),
                parseEnums(row.getRequiredEvidence(), EvidenceType::valueOf),
                Boolean.TRUE.equals(row.getHoldUntilVerified()), row.getChangeReason());
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "SANITATION_CIP_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少清洗消毒模板草稿权限",
                    "SANITATION_CIP_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(), operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员",
                    "SANITATION_CIP_MEMBER_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.templates().isEmpty() || request.templates().size() > MAX_TEMPLATES) {
            throw invalid(400, "Blueprint envelope 或清洗模板数量无效",
                    "SANITATION_CIP_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "SANITATION_CIP_MATERIALIZE_ENVELOPE_INVALID");
        }
    }

    private DraftReceipt receipt(SanitationCipTemplateDraft row, boolean replayed,
                                 int moduleWrites, int persistedRows) {
        return new DraftReceipt(SanitationCipDraftModels.SCHEMA_VERSION, row.getId(),
                row.getFactoryId(), row.getId(), row.getStatus(), row.getSourceSnapshotDigest(),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                row.getTemplateCount(), row.getEvidenceCount(),
                row.getLockVersion() == null ? 0L : row.getLockVersion(), replayed,
                moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED",
                "ARCHIVE_DRAFT_ONLY");
    }

    private boolean sameEvidence(SanitationCipTemplateDraftEvidence row,
                                 BlueprintAuthority authority) {
        return AUTHORITY_CODE.equals(row.getAuthorityCode())
                && authority.catalogVersion().equals(row.getCatalogVersion())
                && authority.catalogDigest().equals(row.getCatalogDigest())
                && authorityEvidenceDigest(authority).equals(row.getAuthoritySnapshotDigest());
    }

    private Map<String, RouteTarget> routeTargets(List<RouteGraph> graphs) {
        Map<String, RouteTarget> result = new HashMap<>();
        for (RouteGraph graph : graphs) {
            if (graph == null) continue;
            for (RouteNode node : graph.nodes()) {
                result.put(routeKey(clean(graph.productCode()), clean(node.id())),
                        new RouteTarget(clean(graph.productCode()), clean(node.id()), clean(node.label())));
            }
        }
        return result;
    }

    private String routeSnapshot(List<RouteGraph> graphs) {
        List<String> values = new ArrayList<>();
        graphs.stream().sorted(Comparator.comparing(graph -> clean(graph.productCode())))
                .forEach(graph -> {
                    values.add("G|" + clean(graph.productCode()) + "|" + clean(graph.productName()));
                    graph.nodes().stream().sorted(Comparator.comparing(RouteNode::id))
                            .forEach(node -> values.add("N|" + node.id() + "|" + node.label()));
                    graph.edges().stream().sorted(Comparator.comparing(edge -> edge.fromNodeId()
                                    + "|" + edge.toNodeId() + "|" + edge.type()))
                            .forEach(edge -> values.add("E|" + edge.fromNodeId() + "|"
                                    + edge.toNodeId() + "|" + edge.type()));
                });
        return digest(values.toArray(String[]::new));
    }

    private String ssopBaselineDigest(String factoryId) {
        List<String> values = ssopProcedures.findByFactoryIdAndActiveTrue(factoryId).stream()
                .sorted(Comparator.comparing(SsopProcedure::getId,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(row -> String.join("|", String.valueOf(row.getId()), row.getFactoryId(),
                        row.getProcedureCode(), row.getName(), row.getTarget(), row.getFrequency(),
                        clean(row.getSteps()), clean(row.getChemicalsUsed()), row.getInspectorRole(),
                        String.valueOf(row.getActive()), String.valueOf(row.getUpdatedAt())))
                .toList();
        return digest(values.toArray(String[]::new));
    }

    private String digestEquipmentPlan(EquipmentCalibrationPlanDraftModels.DraftReadback readback) {
        List<String> values = new ArrayList<>();
        values.add(readback.schemaVersion());
        values.add(readback.factoryId());
        values.add(readback.draftId());
        values.add(readback.status());
        for (int i = 0; i < readback.plans().size(); i++) {
            EquipmentCalibrationPlanDraftModels.PlanEntry plan = readback.plans().get(i);
            values.add(i + "|" + plan.equipmentId() + "|" + plan.equipmentSnapshotDigest()
                    + "|" + plan.planType() + "|" + plan.frequencyKind() + "|"
                    + plan.frequencyValue() + "|" + plan.duePolicy() + "|" + plan.method()
                    + "|" + decimal(plan.toleranceValue()) + "|" + plan.toleranceUnit()
                    + "|" + plan.responsibleRole() + "|" + plan.requiredEvidence()
                    + "|" + plan.verificationMethod() + "|" + plan.estimatedDurationMinutes()
                    + "|" + plan.changeReason());
            plan.processRefs().forEach(ref -> values.add("R|" + ref.processId() + "|"
                    + ref.processDraftId() + "|" + ref.processSnapshotDigest()));
        }
        return digest(values.toArray(String[]::new));
    }

    private String targetSnapshot(RouteTarget route, EquipmentEntry equipment) {
        if (route == null) return digest("MISSING_ROUTE_TARGET");
        return digest("SANITATION_TARGET", route.productCode(), route.nodeId(), route.label(),
                equipment == null ? "NO_EQUIPMENT" : String.valueOf(equipment.ordinal()),
                equipment == null ? "NO_EQUIPMENT" : equipment.equipmentSnapshotDigest());
    }

    private String desiredDigest(List<DerivedTemplate> templates) {
        List<String> values = new ArrayList<>(List.of(SanitationCipDraftModels.SCHEMA_VERSION,
                SanitationCipDraftModels.ALGORITHM_VERSION));
        templates.stream().sorted(Comparator.comparingInt(DerivedTemplate::ordinal))
                .forEach(value -> values.add(value.ordinal() + "|" + value.templateCode() + "|"
                        + value.productCode() + "|" + value.routeNodeId() + "|"
                        + value.routeNodeLabel() + "|" + value.targetType() + "|"
                        + value.equipmentId() + "|" + value.equipmentPlanEntryOrdinal() + "|"
                        + value.targetSnapshotDigest() + "|" + value.method() + "|"
                        + value.chemicalType() + "|" + decimal(value.concentrationMin()) + "|"
                        + decimal(value.concentrationTarget()) + "|"
                        + decimal(value.concentrationMax()) + "|" + value.concentrationUnit() + "|"
                        + decimal(value.temperatureMinC()) + "|"
                        + decimal(value.temperatureTargetC()) + "|"
                        + decimal(value.temperatureMaxC()) + "|" + value.contactTimeSeconds() + "|"
                        + decimal(value.flowRateLitersPerMinute()) + "|" + value.preRinseSeconds()
                        + "|" + value.finalRinseSeconds() + "|" + value.frequency() + "|"
                        + joinEnums(value.verificationMethods()) + "|" + value.responsibleRole()
                        + "|" + joinEnums(value.requiredEvidence()) + "|"
                        + value.holdUntilVerified() + "|" + value.changeReason()));
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        DraftPreview preview = evaluate(request).preview();
        return digest(request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), preview.desiredDigest());
    }

    private String digestBlueprint(BlueprintResponse response) {
        return digest("BLUEPRINT", response.getFactoryId(), response.getId(),
                String.valueOf(response.getVersion()), String.valueOf(response.getRowVersion()),
                response.getLifecycleState(), response.getSchemaVersion(),
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                String.valueOf(response.getUpdatedAt()));
    }

    private String authorityEvidenceDigest(BlueprintAuthority authority) {
        return digest("CATALOG_AUTHORITY", authority.catalogVersion(), authority.catalogDigest(),
                AUTHORITY_CODE, authority.authoritySnapshotDigest());
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream().map(issue -> issue.code() + "|" + issue.target() + "|"
                + issue.message() + "|" + issue.recovery()).toArray(String[]::new));
    }

    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_sanitation_cip_draft_idempotency")) return true;
        }
        return false;
    }

    private boolean validDecimal(BigDecimal value, int precision, int scale) {
        return value != null && value.precision() <= precision && value.scale() <= scale;
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String routeKey(String productCode, String nodeId) {
        return clean(productCode) + "\u0000" + clean(nodeId);
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String joinEnums(List<? extends Enum<?>> values) {
        return String.join("|", values.stream().map(Enum::name).toList());
    }

    private <E> List<E> parseEnums(String value, java.util.function.Function<String, E> parser) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split("\\|", -1)).stream().map(parser).toList();
    }

    private String digest(String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                md.update((byte) (bytes.length >>> 24));
                md.update((byte) (bytes.length >>> 16));
                md.update((byte) (bytes.length >>> 8));
                md.update((byte) bytes.length);
                md.update(bytes);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }

    private BusinessException invalid(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code);
    }

    private record Evaluation(DraftPreview preview, BlueprintAuthority authority,
                              EquipmentAuthority equipment) {
    }

    private record BlueprintAuthority(String catalogVersion, String catalogDigest,
                                      Set<String> authorityRefs, String authoritySnapshotDigest,
                                      String routeSnapshotDigest, String blueprintSnapshotDigest,
                                      List<RouteGraph> routeGraphs) {
        static BlueprintAuthority missing() {
            return new BlueprintAuthority("", digestStatic("MISSING_CATALOG"), Set.of(),
                    digestStatic("MISSING_AUTHORITY"), digestStatic("MISSING_ROUTES"),
                    digestStatic("MISSING_BLUEPRINT"), List.of());
        }
    }

    private record EquipmentAuthority(String draftId, String desiredDigest,
                                      String snapshotDigest, Map<Long, EquipmentEntry> entries) {
        static EquipmentAuthority none() {
            return new EquipmentAuthority(null, null, digestStatic("NO_EQUIPMENT_PLAN"), Map.of());
        }
    }

    private record EquipmentEntry(int ordinal, String equipmentSnapshotDigest) {
    }

    private record RouteTarget(String productCode, String nodeId, String label) {
    }

    private static String digestStatic(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
