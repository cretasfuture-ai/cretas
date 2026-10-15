package com.cretas.aims.service.criticalcontroldraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraft;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraftEvidence;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraftItem;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraft;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraftEvidence;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraftItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftEvidenceRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftItemRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftRepository;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftEvidenceRepository;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftItemRepository;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftRepository;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.ControlClassification;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.ControlItem;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.CriterionType;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.DecisionAnswers;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.DraftIssue;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.DraftPreview;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.DraftReadback;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.DraftReceipt;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.DraftRequest;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.FrequencyUnit;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.HazardPin;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.LimitKind;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.ParameterType;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.RecoveryPlan;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.Schedule;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftCommand;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic, module-owned critical-controls DRAFT command.
 *
 * <p>It consumes a current readback-ready hazard-analysis DRAFT and emits only typed
 * Blueprint-owned rows. It never creates or updates a live HACCP checkpoint, monitoring
 * record, deviation/CAPA, inventory, batch, production, quality result, alert, notification
 * or device command.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class CriticalControlDraftCommand {
    public static final String CAPABILITY_ID = "critical-controls";
    public static final String REQUIRED_PERMISSION = "haccp:ccp:draft";
    private static final int MAX_ITEMS = 500;
    private static final int MAX_EVIDENCE_PER_ITEM = 20;

    private final CriticalControlDraftRepository drafts;
    private final CriticalControlDraftItemRepository draftItems;
    private final CriticalControlDraftEvidenceRepository draftEvidence;
    private final HazardAnalysisDraftRepository hazardDrafts;
    private final HazardAnalysisDraftItemRepository hazardItems;
    private final HazardAnalysisDraftEvidenceRepository hazardEvidence;
    private final HazardAnalysisDraftCommand hazardCommand;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UnitContractService units;
    private final HaccpCheckpointRepository formalCheckpoints;
    private final UserRepository users;

    public CriticalControlDraftCommand(
            CriticalControlDraftRepository drafts,
            CriticalControlDraftItemRepository draftItems,
            CriticalControlDraftEvidenceRepository draftEvidence,
            HazardAnalysisDraftRepository hazardDrafts,
            HazardAnalysisDraftItemRepository hazardItems,
            HazardAnalysisDraftEvidenceRepository hazardEvidence,
            HazardAnalysisDraftCommand hazardCommand,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UnitContractService units,
            HaccpCheckpointRepository formalCheckpoints,
            UserRepository users) {
        this.drafts = drafts;
        this.draftItems = draftItems;
        this.draftEvidence = draftEvidence;
        this.hazardDrafts = hazardDrafts;
        this.hazardItems = hazardItems;
        this.hazardEvidence = hazardEvidence;
        this.hazardCommand = hazardCommand;
        this.canonicalBlueprints = canonicalBlueprints;
        this.units = units;
        this.formalCheckpoints = formalCheckpoints;
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
        Optional<CriticalControlDraft> prior = drafts.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同关键控制草稿请求",
                        "CRITICAL_CONTROL_DRAFT_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview preview = evaluation.preview();
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "关键控制草稿预览被阻断", "CRITICAL_CONTROL_DRAFT_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "关键控制草稿预览摘要已变化",
                    "CRITICAL_CONTROL_DRAFT_PREVIEW_DIGEST_CONFLICT");
        }

        int evidenceCount = request.items().stream()
                .mapToInt(item -> item.authorityCodes().size()).sum();
        HazardAnalysisDraft hazard = evaluation.hazard().parent();
        CriticalControlDraft parent = CriticalControlDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .hazardDraftId(request.hazard().draftId())
                .hazardDesiredDigest(request.hazard().desiredDigest())
                .hazardSourceSnapshotDigest(hazard.getSourceSnapshotDigest())
                .hazardRouteSnapshotDigest(hazard.getRouteSnapshotDigest())
                .hazardBomSnapshotDigest(hazard.getBomSnapshotDigest())
                .hazardAuthoritySnapshotDigest(hazard.getAuthoritySnapshotDigest())
                .hazardPreviewDigest(hazard.getPreviewDigest())
                .hazardSnapshotDigest(preview.hazardSnapshotDigest())
                .hazardRiskAlgorithmVersion(hazard.getRiskAlgorithmVersion())
                .capabilityCatalogVersion(evaluation.blueprint().catalogVersion())
                .capabilityCatalogDigest(evaluation.blueprint().catalogDigest())
                .authoritySnapshotDigest(preview.authoritySnapshotDigest())
                .formalCheckpointSnapshotDigest(preview.formalCheckpointSnapshotDigest())
                .decisionAlgorithmVersion(CriticalControlDraftModels.DECISION_ALGORITHM_VERSION)
                .desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest())
                .requestDigest(requestDigest)
                .itemCount(request.items().size())
                .evidenceCount(evidenceCount)
                .dependencyBasis(CriticalControlDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .status("DRAFT")
                .build();

        CriticalControlDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "关键控制草稿幂等键发生并发竞争",
                    "CRITICAL_CONTROL_DRAFT_IDEMPOTENCY_RACE");
        }

        List<CriticalControlDraftItem> itemRows = new ArrayList<>();
        List<CriticalControlDraftEvidence> evidenceRows = new ArrayList<>();
        for (int index = 0; index < evaluation.items().size(); index++) {
            ControlResolution resolved = evaluation.items().get(index);
            CriticalControlDraftItem itemRow = toRow(saved.getId(), request.factoryId(),
                    request.hazard().draftId(), index, resolved);
            itemRows.add(itemRow);
            for (String authorityCode : sortedDistinct(resolved.desired().authorityCodes())) {
                evidenceRows.add(CriticalControlDraftEvidence.builder()
                        .id(UUID.randomUUID().toString())
                        .itemId(itemRow.getId())
                        .draftId(saved.getId())
                        .factoryId(request.factoryId())
                        .evidenceType("CATALOG_AUTHORITY")
                        .authorityCode(authorityCode)
                        .authoritySnapshotDigest(authorityEvidenceDigest(
                                evaluation.blueprint().catalogVersion(),
                                evaluation.blueprint().catalogDigest(), authorityCode))
                        .build());
            }
        }
        draftItems.saveAllAndFlush(itemRows);
        draftEvidence.saveAllAndFlush(evidenceRows);

        DraftReadback readback = readBack(saved.getFactoryId(), saved.getId());
        if (!preview.desiredDigest().equals(desiredDigest(readback.desired()))) {
            throw invalid(409, "关键控制草稿逐字段回读不一致",
                    "CRITICAL_CONTROL_DRAFT_READBACK_MISMATCH");
        }
        DraftPreview current = evaluate(request).preview();
        if (!"READY".equals(current.status())
                || !preview.sourceSnapshotDigest().equals(current.sourceSnapshotDigest())
                || !preview.hazardSnapshotDigest().equals(current.hazardSnapshotDigest())
                || !preview.authoritySnapshotDigest().equals(current.authoritySnapshotDigest())
                || !preview.formalCheckpointSnapshotDigest().equals(
                        current.formalCheckpointSnapshotDigest())
                || !preview.previewDigest().equals(current.previewDigest())) {
            throw invalid(409, "关键控制权威在草稿写入期间发生变化",
                    "CRITICAL_CONTROL_DRAFT_AUTHORITY_DRIFT_DURING_WRITE");
        }
        return receipt(saved, false, 1, 1 + itemRows.size() + evidenceRows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        CriticalControlDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "关键控制草稿不存在",
                        "CRITICAL_CONTROL_DRAFT_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !CriticalControlDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !CriticalControlDraftModels.DECISION_ALGORITHM_VERSION.equals(
                        parent.getDecisionAlgorithmVersion())
                || !HazardAnalysisDraftModels.RISK_ALGORITHM_VERSION.equals(
                        parent.getHazardRiskAlgorithmVersion())) {
            throw invalid(409, "关键控制草稿依赖闭包或状态无效",
                    "CRITICAL_CONTROL_DRAFT_READBACK_INVARIANT_BROKEN");
        }
        List<CriticalControlDraftItem> rows = draftItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<CriticalControlDraftEvidence> evidenceRows = draftEvidence
                .findByDraftIdAndFactoryIdOrderByItemIdAscAuthorityCodeAsc(draftId, factoryId);
        Map<String, List<String>> evidenceByItem = new HashMap<>();
        for (CriticalControlDraftEvidence row : evidenceRows) {
            if (!"CATALOG_AUTHORITY".equals(row.getEvidenceType())
                    || !authorityEvidenceDigest(parent.getCapabilityCatalogVersion(),
                            parent.getCapabilityCatalogDigest(), row.getAuthorityCode())
                            .equals(row.getAuthoritySnapshotDigest())) {
                throw invalid(409, "关键控制依据回读不一致",
                        "CRITICAL_CONTROL_DRAFT_EVIDENCE_READBACK_MISMATCH");
            }
            evidenceByItem.computeIfAbsent(row.getItemId(), ignored -> new ArrayList<>())
                    .add(row.getAuthorityCode());
        }

        List<ControlItem> items = new ArrayList<>();
        for (CriticalControlDraftItem row : rows) {
            DecisionAnswers decision = new DecisionAnswers(
                    Boolean.TRUE.equals(row.getControlMeasureAtStep()),
                    Boolean.TRUE.equals(row.getStepDesignedToControl()),
                    Boolean.TRUE.equals(row.getHazardMayIncrease()),
                    Boolean.TRUE.equals(row.getLaterStepControls()),
                    Boolean.TRUE.equals(row.getOperationalPrerequisiteAdequate()));
            ControlClassification classification = CriticalControlDraftModels.classify(decision);
            CriterionType criterion = CriticalControlDraftModels.criterionType(classification);
            String unitSnapshot = unitSnapshot(row.getUnitCode(), row.getUnitDimension(),
                    row.getUnitBaseCode(), row.getUnitFactorToBase(), row.getUnitDisplayName(),
                    row.getUnitDisplayScale(), row.getUnitConversionFamily());
            if (!classification.name().equals(row.getControlClassification())
                    || !criterion.name().equals(row.getCriterionType())
                    || !CriticalControlDraftModels.DECISION_ALGORITHM_VERSION.equals(
                            row.getDecisionAlgorithmVersion())
                    || !controlKey(row.getHazardItemId(), row.getParameterCode())
                            .equals(row.getControlKey())
                    || !com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(unitSnapshot, row.getUnitSnapshotDigest())) {
                throw invalid(409, "关键控制算法或单位快照回读不一致",
                        "CRITICAL_CONTROL_DRAFT_ITEM_READBACK_MISMATCH");
            }
            items.add(new ControlItem(row.getHazardItemId(), row.getParameterCode(),
                    row.getParameterName(), decision, ParameterType.valueOf(row.getParameterType()),
                    LimitKind.valueOf(row.getLimitKind()), row.getLowerLimit(), row.getUpperLimit(),
                    Boolean.TRUE.equals(row.getLowerInclusive()), Boolean.TRUE.equals(row.getUpperInclusive()),
                    row.getUnitCode(), CriticalControlDraftModels.MonitoringMethod.valueOf(
                            row.getMonitoringMethod()),
                    new Schedule(row.getMonitoringFrequencyInterval(), FrequencyUnit.valueOf(
                            row.getMonitoringFrequencyUnit())),
                    CriticalControlDraftModels.ResponsibleRole.valueOf(row.getResponsibleRole()),
                    row.getMonitoringProcedure(),
                    CriticalControlDraftModels.DeviationDisposition.valueOf(
                            row.getDeviationDisposition()),
                    row.getCorrectiveAction(),
                    CriticalControlDraftModels.VerificationMethod.valueOf(row.getVerificationMethod()),
                    new Schedule(row.getVerificationFrequencyInterval(), FrequencyUnit.valueOf(
                            row.getVerificationFrequencyUnit())),
                    row.getVerificationProcedure(), row.getRecordRetentionDays(),
                    evidenceByItem.getOrDefault(row.getId(), List.of()), row.getChangeReason()));
        }
        Long readbackOperatorId = parent.getOperatorId() == null ? parent.getActorId() : parent.getOperatorId();
        if (readbackOperatorId == null) throw invalid(409, "草稿缺少 typed actor 身份，无法安全回读", "DRAFT_ACTOR_IDENTITY_MISSING");
        DraftRequest desired = new DraftRequest(parent.getFactoryId(), parent.getBlueprintId(),
                parent.getBlueprintVersion(), parent.getSourceDigest(),
                new HazardPin(parent.getHazardDraftId(), parent.getHazardDesiredDigest()), items,
                parent.getPreviewDigest(), parent.getIdempotencyKey(), readbackOperatorId);
        if (rows.size() != parent.getItemCount() || evidenceRows.size() != parent.getEvidenceCount()
                || !desiredDigest(desired).equals(parent.getDesiredDigest())) {
            throw invalid(409, "关键控制草稿回读不完整",
                    "CRITICAL_CONTROL_DRAFT_READBACK_MISMATCH");
        }
        DraftPreview current = evaluate(desired).preview();
        if (!"READY".equals(current.status())
                || !parent.getSourceSnapshotDigest().equals(current.sourceSnapshotDigest())
                || !parent.getHazardSnapshotDigest().equals(current.hazardSnapshotDigest())
                || !parent.getAuthoritySnapshotDigest().equals(current.authoritySnapshotDigest())
                || !parent.getFormalCheckpointSnapshotDigest().equals(
                        current.formalCheckpointSnapshotDigest())
                || !parent.getDesiredDigest().equals(current.desiredDigest())
                || !parent.getPreviewDigest().equals(current.previewDigest())) {
            throw invalid(409, "关键控制草稿当前依赖已漂移，不能作为可执行权威回读",
                    "CRITICAL_CONTROL_DRAFT_AUTHORITY_DRIFT");
        }
        return new DraftReadback(CriticalControlDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getStatus(), desired, 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        CriticalControlDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "关键控制草稿不存在",
                        "CRITICAL_CONTROL_DRAFT_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus())) {
            throw invalid(409, "只有未发布关键控制草稿可恢复",
                    "CRITICAL_CONTROL_DRAFT_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(CriticalControlDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getDesiredDigest(), "ARCHIVE_DRAFT_AFTER_FOOD_SAFETY_OWNER_CONFIRMATION",
                "只归档关键控制草稿；不改变正式 HACCP/CCP、监控、质量、偏差、批次、库存、生产、告警或通知",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintResolution blueprint = resolveBlueprint(request, issues);
        HazardResolution hazard = resolveHazard(request, issues);
        String formalSnapshot = formalCheckpointSnapshot(request.factoryId());

        List<ControlResolution> resolutions = new ArrayList<>();
        Set<String> controlKeys = new HashSet<>();
        Set<String> coveredHazards = new HashSet<>();
        for (int index = 0; index < request.items().size(); index++) {
            ControlItem item = request.items().get(index);
            String target = "items[" + index + "]";
            validateItemShape(item, target, issues);
            if (item == null) {
                resolutions.add(ControlResolution.missing());
                continue;
            }
            HazardAnalysisDraftItem hazardItem = hazard.items().get(item.hazardItemId());
            if (hazardItem == null) {
                issues.add(issue("CRITICAL_CONTROL_HAZARD_ITEM_NOT_FOUND",
                        target + ".hazardItemId", "控制项引用的危害条目不存在",
                        "从当前 hazard-analysis DRAFT 重新选择危害"));
            } else if (!Boolean.TRUE.equals(hazardItem.getSignificant())) {
                issues.add(issue("CRITICAL_CONTROL_HAZARD_NOT_SIGNIFICANT",
                        target + ".hazardItemId", "critical-controls 只接受显著危害",
                        "在 hazard-analysis 中确认风险，或移除此控制项"));
            } else {
                coveredHazards.add(hazardItem.getId());
            }
            CanonicalUnit unit = resolveUnit(request.factoryId(), item, target, issues);
            String key = controlKey(item.hazardItemId(), item.parameterCode());
            if (!controlKeys.add(key)) {
                issues.add(issue("CRITICAL_CONTROL_ITEM_DUPLICATE", target,
                        "同一危害和参数只能有一个控制项", "合并重复控制项"));
            }
            for (String code : sortedDistinct(item.authorityCodes())) {
                if (!blueprint.authorityCodes().contains(code)) {
                    issues.add(issue("CRITICAL_CONTROL_AUTHORITY_NOT_RESOLVED",
                            target + ".authorityCodes", "依据不属于当前服务端关键控制能力目录",
                            "只选择平台提供的 critical-controls 法规/标准依据"));
                }
            }
            ControlClassification classification = CriticalControlDraftModels.classify(item.decision());
            resolutions.add(new ControlResolution(item, hazardItem, unit, classification,
                    CriticalControlDraftModels.criterionType(classification), key));
        }

        Set<String> significantHazards = new LinkedHashSet<>();
        hazard.items().values().stream().filter(row -> Boolean.TRUE.equals(row.getSignificant()))
                .sorted(Comparator.comparing(HazardAnalysisDraftItem::getId))
                .forEach(row -> significantHazards.add(row.getId()));
        for (String missing : significantHazards) {
            if (!coveredHazards.contains(missing)) {
                issues.add(issue("CRITICAL_CONTROL_SIGNIFICANT_HAZARD_UNCOVERED", "hazardItem:" + missing,
                        "每个显著危害至少需要一个 typed CCP/OPRP/PRP 决策和完整计划",
                        "为该危害补充控制项，或先修订 hazard-analysis DRAFT"));
            }
        }

        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String sourceSnapshotDigest = digest(request.sourceDigest(), blueprint.snapshotDigest(),
                hazard.snapshotDigest(), blueprint.authoritySnapshotDigest(), formalSnapshot,
                CriticalControlDraftModels.DECISION_ALGORITHM_VERSION);
        String desiredDigest = desiredDigest(request);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest(CriticalControlDraftModels.SCHEMA_VERSION,
                request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), sourceSnapshotDigest, hazard.snapshotDigest(),
                blueprint.authoritySnapshotDigest(), formalSnapshot,
                CriticalControlDraftModels.DECISION_ALGORITHM_VERSION, desiredDigest, status);
        DraftPreview preview = new DraftPreview(CriticalControlDraftModels.SCHEMA_VERSION, status,
                request.factoryId(), List.of(), request, sourceSnapshotDigest, hazard.snapshotDigest(),
                blueprint.authoritySnapshotDigest(), formalSnapshot, desiredDigest, previewDigest,
                issues, 0, 0, 0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, blueprint, hazard, List.copyOf(resolutions));
    }

    private BlueprintResolution resolveBlueprint(DraftRequest request, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(request.factoryId());
        if (response == null || !request.blueprintId().equals(response.getId())
                || !Objects.equals(Math.toIntExact(request.blueprintVersion()), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                    || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("CRITICAL_CONTROL_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
                    "当前工厂 Blueprint DRAFT/VALIDATED 版本与请求不一致",
                    "刷新当前 Blueprint 后重新预览"));
            return BlueprintResolution.missing();
        }
        CapabilityView capability = response.getCapabilities() == null ? null
                : response.getCapabilities().stream().filter(item -> CAPABILITY_ID.equals(item.getId()))
                .findFirst().orElse(null);
        Set<String> dependencies = capability == null || capability.getDependsOn() == null
                ? Set.of() : new HashSet<>(capability.getDependsOn());
        if (capability == null || !capability.isIncluded() || !"READY".equals(capability.getStatus())
                || !dependencies.equals(Set.copyOf(FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID)))) {
            issues.add(issue("CRITICAL_CONTROL_CAPABILITY_NOT_READY", "capability",
                    "critical-controls 能力或 hazard-analysis 依赖尚未就绪",
                    "先补齐危害分析草稿后重新预览"));
        }
        Set<String> authorityCodes = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest())) {
            issues.add(issue("CRITICAL_CONTROL_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本或摘要无效", "部署有效的版本化能力目录后重新预览"));
        }
        if (authorityCodes.isEmpty()) {
            issues.add(issue("CRITICAL_CONTROL_AUTHORITY_CATALOG_EMPTY", "authorityCodes",
                    "服务端能力目录没有可验证的关键控制依据",
                    "部署带 authority refs 的版本化能力目录"));
        }
        return new BlueprintResolution(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), authorityCodes,
                authoritySnapshot(response, authorityCodes), digestBlueprint(response));
    }

    private HazardResolution resolveHazard(DraftRequest request, List<DraftIssue> issues) {
        HazardPin pin = request.hazard();
        HazardAnalysisDraft parent = pin == null ? null
                : hazardDrafts.findByIdAndFactoryId(pin.draftId(), request.factoryId()).orElse(null);
        if (parent == null || !"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !HazardAnalysisDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !HazardAnalysisDraftModels.RISK_ALGORITHM_VERSION.equals(
                        parent.getRiskAlgorithmVersion())
                || !request.blueprintId().equals(parent.getBlueprintId())
                || request.blueprintVersion() != parent.getBlueprintVersion()
                || !request.sourceDigest().equals(parent.getSourceDigest())
                || !pin.desiredDigest().equals(parent.getDesiredDigest())) {
            issues.add(issue("CRITICAL_CONTROL_HAZARD_DRAFT_DRIFT", "hazard",
                    "hazard-analysis DRAFT 不存在、lineage 不一致或摘要已变化",
                    "选择同 Blueprint 的当前 hazard-analysis DRAFT"));
            return HazardResolution.missing();
        }
        try {
            var readback = hazardCommand.readBack(request.factoryId(), parent.getId());
            var current = hazardCommand.preview(readback.desired());
            if (!"READY".equals(current.status())
                    || !parent.getDesiredDigest().equals(current.desiredDigest())
                    || !parent.getSourceSnapshotDigest().equals(current.sourceSnapshotDigest())
                    || !parent.getRouteSnapshotDigest().equals(current.routeSnapshotDigest())
                    || !parent.getBomSnapshotDigest().equals(current.bomSnapshotDigest())
                    || !parent.getAuthoritySnapshotDigest().equals(current.authoritySnapshotDigest())
                    || !parent.getPreviewDigest().equals(current.previewDigest())) {
                issues.add(issue("CRITICAL_CONTROL_HAZARD_AUTHORITY_BLOCKED", "hazard",
                        "hazard-analysis DRAFT 当前依赖已漂移或不能逐字段回读",
                        "先重新生成 readback-ready hazard-analysis DRAFT"));
            }
        } catch (BusinessException exception) {
            issues.add(issue("CRITICAL_CONTROL_HAZARD_AUTHORITY_BLOCKED", "hazard",
                    "hazard-analysis DRAFT 当前不能逐字段回读",
                    "先修复或重新生成 hazard-analysis DRAFT"));
        }
        List<HazardAnalysisDraftItem> rows = hazardItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(parent.getId(), request.factoryId());
        List<HazardAnalysisDraftEvidence> evidence = hazardEvidence
                .findByDraftIdAndFactoryIdOrderByItemIdAscAuthorityCodeAsc(
                        parent.getId(), request.factoryId());
        Map<String, HazardAnalysisDraftItem> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.putIfAbsent(row.getId(), row));
        return new HazardResolution(parent, Map.copyOf(byId), hazardSnapshot(parent, rows, evidence));
    }

    private void validateItemShape(ControlItem item, String target, List<DraftIssue> issues) {
        if (item == null) {
            issues.add(issue("CRITICAL_CONTROL_ITEM_REQUIRED", target,
                    "控制项不能为空", "补充 typed 控制项"));
            return;
        }
        if (isBlankOrTooLong(item.hazardItemId(), 36)
                || item.decision() == null || item.parameterType() == null || item.limitKind() == null
                || item.monitoringMethod() == null || item.monitoringFrequency() == null
                || item.responsibleRole() == null || item.deviationDisposition() == null
                || item.verificationMethod() == null || item.verificationFrequency() == null
                || !item.parameterCode().matches("[A-Z][A-Z0-9_]{1,63}")
                || isBlankOrTooLong(item.parameterName(), 200)
                || isBlankOrTooLong(item.unit(), 64)
                || isBlankOrTooLong(item.monitoringProcedure(), 1000)
                || isBlankOrTooLong(item.correctiveAction(), 1000)
                || isBlankOrTooLong(item.verificationProcedure(), 1000)
                || isBlankOrTooLong(item.changeReason(), 500)
                || item.recordRetentionDays() < 1 || item.recordRetentionDays() > 36500) {
            issues.add(issue("CRITICAL_CONTROL_ITEM_FIELDS_REQUIRED", target,
                    "危害、决策、参数、限值、监控、责任、纠偏、验证、保存期和变更原因均须 typed 且完整",
                    "补全关键控制草稿字段"));
        }
        validateLimit(item, target, issues);
        validateSchedule(item.monitoringFrequency(), target + ".monitoringFrequency", true, issues);
        validateSchedule(item.verificationFrequency(), target + ".verificationFrequency", false, issues);
        if (item.authorityCodes().isEmpty() || item.authorityCodes().size() > MAX_EVIDENCE_PER_ITEM
                || item.authorityCodes().stream().anyMatch(code -> isBlankOrTooLong(code, 100))) {
            issues.add(issue("CRITICAL_CONTROL_AUTHORITY_REQUIRED", target + ".authorityCodes",
                    "每个控制项必须引用 1 至 20 个平台法规/标准依据",
                    "选择平台提供的 authority code"));
        }
        if (new HashSet<>(item.authorityCodes()).size() != item.authorityCodes().size()) {
            issues.add(issue("CRITICAL_CONTROL_AUTHORITY_DUPLICATE", target + ".authorityCodes",
                    "同一控制项不能重复引用同一依据", "移除重复 authority code"));
        }
    }

    private void validateLimit(ControlItem item, String target, List<DraftIssue> issues) {
        if (item.limitKind() == null) {
            issues.add(issue("CRITICAL_CONTROL_LIMIT_INVALID", target + ".limit",
                    "MINIMUM/MAXIMUM/RANGE/EXACT 的限值组合或精度无效",
                    "使用最多 19 位有效数字、6 位小数的 typed 限值"));
            return;
        }
        BigDecimal lower = item.lowerLimit();
        BigDecimal upper = item.upperLimit();
        boolean valid = switch (item.limitKind()) {
            case MINIMUM -> lower != null && upper == null;
            case MAXIMUM -> lower == null && upper != null;
            case RANGE -> lower != null && upper != null && lower.compareTo(upper) <= 0;
            case EXACT -> lower != null && upper != null && lower.compareTo(upper) == 0;
        };
        if (!valid || !validDecimal(lower) || !validDecimal(upper)) {
            issues.add(issue("CRITICAL_CONTROL_LIMIT_INVALID", target + ".limit",
                    "MINIMUM/MAXIMUM/RANGE/EXACT 的限值组合或精度无效",
                    "使用最多 19 位有效数字、6 位小数的 typed 限值"));
        }
    }

    private void validateSchedule(Schedule schedule, String target, boolean allowContinuous,
                                  List<DraftIssue> issues) {
        if (schedule == null || schedule.unit() == null || schedule.interval() < 1
                || schedule.interval() > 10000
                || (!allowContinuous && schedule.unit() == FrequencyUnit.CONTINUOUS)
                || (Set.of(FrequencyUnit.CONTINUOUS, FrequencyUnit.EVERY_BATCH,
                        FrequencyUnit.EVENT_DRIVEN).contains(schedule.unit())
                    && schedule.interval() != 1)) {
            issues.add(issue("CRITICAL_CONTROL_SCHEDULE_INVALID", target,
                    "频率必须使用封闭单位和合法间隔；连续/每批/事件触发的间隔固定为 1",
                    "修正 typed 频率"));
        }
    }

    private CanonicalUnit resolveUnit(String factoryId, ControlItem item, String target,
                                      List<DraftIssue> issues) {
        if (item == null || item.parameterType() == null) return null;
        CanonicalUnit unit = units.describe(factoryId, item.unit()).orElse(null);
        UnitDimension expected = expectedDimension(item.parameterType());
        if (unit == null || !unit.active() || !com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(item.unit(), unit.code())
                || unit.dimension() != expected) {
            issues.add(issue("CRITICAL_CONTROL_UNIT_NOT_SUPPORTED", target + ".unit",
                    "限值单位必须是当前工厂已启用的精确 canonical code，且量纲与参数类型一致",
                    "从单位目录选择 " + expected + " 单位"));
            return null;
        }
        return unit;
    }

    private UnitDimension expectedDimension(ParameterType type) {
        return switch (type) {
            case TEMPERATURE -> UnitDimension.TEMPERATURE;
            case TIME -> UnitDimension.TIME;
            case MASS -> UnitDimension.MASS;
            case VOLUME -> UnitDimension.VOLUME;
            case COUNT -> UnitDimension.COUNT;
            case CONCENTRATION, ACIDITY_PH -> UnitDimension.RATIO;
        };
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "CRITICAL_CONTROL_DRAFT_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少关键控制草稿权限",
                    "CRITICAL_CONTROL_DRAFT_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(), operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员",
                    "CRITICAL_CONTROL_DRAFT_OPERATOR_MEMBERSHIP_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || request.blueprintVersion() > Integer.MAX_VALUE
                || !isDigest(request.sourceDigest()) || request.hazard() == null
                || request.hazard().draftId().isBlank() || request.hazard().draftId().length() > 36
                || !isDigest(request.hazard().desiredDigest()) || request.items().size() > MAX_ITEMS) {
            throw invalid(400, "Blueprint envelope、hazard pin 或控制项范围无效",
                    "CRITICAL_CONTROL_DRAFT_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128 || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "CRITICAL_CONTROL_DRAFT_OPERATOR_REQUIRED");
        }
    }

    private CriticalControlDraftItem toRow(String draftId, String factoryId, String hazardDraftId,
                                           int ordinal, ControlResolution resolved) {
        ControlItem item = resolved.desired();
        HazardAnalysisDraftItem hazard = resolved.hazard();
        CanonicalUnit unit = resolved.unit();
        return CriticalControlDraftItem.builder()
                .id(UUID.randomUUID().toString()).draftId(draftId).factoryId(factoryId)
                .hazardDraftId(hazardDraftId).hazardItemId(hazard.getId()).ordinal(ordinal)
                .controlKey(resolved.controlKey()).routeProductCode(hazard.getRouteProductCode())
                .routeNodeId(hazard.getRouteNodeId()).routeNodeLabel(hazard.getRouteNodeLabel())
                .bomDraftId(hazard.getBomDraftId()).bomItemId(hazard.getBomItemId())
                .materialRefType(hazard.getMaterialRefType()).materialRefId(hazard.getMaterialRefId())
                .materialSnapshotDigest(hazard.getMaterialSnapshotDigest())
                .hazardKey(hazard.getHazardKey()).hazardType(hazard.getHazardType())
                .riskScore(hazard.getRiskScore()).riskBand(hazard.getRiskBand())
                .significant(hazard.getSignificant())
                .controlClassification(resolved.classification().name())
                .criterionType(resolved.criterionType().name())
                .decisionAlgorithmVersion(CriticalControlDraftModels.DECISION_ALGORITHM_VERSION)
                .controlMeasureAtStep(item.decision().controlMeasureAtStep())
                .stepDesignedToControl(item.decision().stepDesignedToEliminateOrReduce())
                .hazardMayIncrease(item.decision().hazardCouldOccurOrIncrease())
                .laterStepControls(item.decision().laterStepEliminatesOrReduces())
                .operationalPrerequisiteAdequate(item.decision().operationalPrerequisiteAdequate())
                .parameterCode(item.parameterCode()).parameterName(item.parameterName())
                .parameterType(item.parameterType().name()).limitKind(item.limitKind().name())
                .lowerLimit(item.lowerLimit()).upperLimit(item.upperLimit())
                .lowerInclusive(item.lowerInclusive()).upperInclusive(item.upperInclusive())
                .unitCode(unit.code()).unitDimension(unit.dimension().name())
                .unitBaseCode(unit.baseCode()).unitFactorToBase(unit.factorToBase())
                .unitDisplayName(unit.displayName()).unitDisplayScale(unit.displayScale())
                .unitConversionFamily(unit.conversionFamily())
                .unitSnapshotDigest(unitSnapshot(unit))
                .monitoringMethod(item.monitoringMethod().name())
                .monitoringFrequencyInterval(item.monitoringFrequency().interval())
                .monitoringFrequencyUnit(item.monitoringFrequency().unit().name())
                .responsibleRole(item.responsibleRole().name())
                .monitoringProcedure(item.monitoringProcedure())
                .deviationDisposition(item.deviationDisposition().name())
                .correctiveAction(item.correctiveAction())
                .verificationMethod(item.verificationMethod().name())
                .verificationFrequencyInterval(item.verificationFrequency().interval())
                .verificationFrequencyUnit(item.verificationFrequency().unit().name())
                .verificationProcedure(item.verificationProcedure())
                .recordRetentionDays(item.recordRetentionDays()).changeReason(item.changeReason())
                .build();
    }

    private DraftReceipt receipt(CriticalControlDraft parent, boolean replayed, int writes, int rows) {
        return new DraftReceipt(CriticalControlDraftModels.SCHEMA_VERSION, parent.getId(),
                parent.getFactoryId(), parent.getId(), parent.getStatus(),
                parent.getSourceSnapshotDigest(), parent.getHazardSnapshotDigest(),
                parent.getAuthoritySnapshotDigest(), parent.getFormalCheckpointSnapshotDigest(),
                parent.getDesiredDigest(), parent.getPreviewDigest(), parent.getRequestDigest(),
                parent.getItemCount(), parent.getEvidenceCount(),
                Objects.requireNonNullElse(parent.getLockVersion(), 0L), replayed, writes,
                replayed ? 0 : 1, rows, 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED",
                "ARCHIVE_DRAFT_AFTER_FOOD_SAFETY_OWNER_CONFIRMATION");
    }

    private String digestBlueprint(BlueprintResponse response) {
        return digest(response.getId(), response.getFactoryId(), String.valueOf(response.getVersion()),
                String.valueOf(response.getRowVersion()), response.getLifecycleState(),
                response.getSchemaVersion(), response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), String.valueOf(response.getUpdatedAt()));
    }

    private String authoritySnapshot(BlueprintResponse response, Set<String> authorityCodes) {
        List<String> values = new ArrayList<>();
        values.add(response.getCapabilityCatalogVersion());
        values.add(response.getCapabilityCatalogDigest());
        authorityCodes.stream().sorted().forEach(values::add);
        return digest(values.toArray(String[]::new));
    }

    private String authorityEvidenceDigest(String catalogVersion, String catalogDigest, String code) {
        return digest("CATALOG_AUTHORITY", catalogVersion, catalogDigest, code);
    }

    private String hazardSnapshot(HazardAnalysisDraft parent, List<HazardAnalysisDraftItem> items,
                                  List<HazardAnalysisDraftEvidence> evidence) {
        List<String> values = new ArrayList<>();
        values.add(parent.getId()); values.add(parent.getFactoryId()); values.add(parent.getBlueprintId());
        values.add(String.valueOf(parent.getBlueprintVersion())); values.add(parent.getSourceDigest());
        values.add(parent.getSourceSnapshotDigest()); values.add(parent.getRouteProductCode());
        values.add(parent.getRouteSnapshotDigest()); values.add(parent.getBomDraftId());
        values.add(parent.getBomDesiredDigest()); values.add(parent.getBomSnapshotDigest());
        values.add(parent.getCapabilityCatalogVersion()); values.add(parent.getCapabilityCatalogDigest());
        values.add(parent.getAuthoritySnapshotDigest()); values.add(parent.getRiskAlgorithmVersion());
        values.add(parent.getDesiredDigest()); values.add(parent.getPreviewDigest());
        values.add(String.valueOf(parent.getLockVersion())); values.add(String.valueOf(parent.getUpdatedAt()));
        for (HazardAnalysisDraftItem item : items) {
            values.add(item.getId()); values.add(String.valueOf(item.getOrdinal())); values.add(item.getHazardKey());
            values.add(item.getRouteProductCode()); values.add(item.getRouteNodeId()); values.add(item.getRouteNodeLabel());
            values.add(item.getBomDraftId()); values.add(item.getBomItemId()); values.add(item.getMaterialRefType());
            values.add(item.getMaterialRefId()); values.add(item.getMaterialSnapshotDigest());
            values.add(item.getHazardType()); values.add(item.getHazardDescription()); values.add(item.getCauseSummary());
            values.add(item.getSeverity()); values.add(item.getLikelihood()); values.add(String.valueOf(item.getRiskScore()));
            values.add(item.getRiskBand()); values.add(String.valueOf(item.getSignificant()));
            values.add(item.getControlMeasureType()); values.add(item.getPreventiveMeasure());
            values.add(item.getChangeReason());
        }
        evidence.stream().sorted(Comparator.comparing(HazardAnalysisDraftEvidence::getItemId)
                        .thenComparing(HazardAnalysisDraftEvidence::getAuthorityCode))
                .forEach(row -> { values.add(row.getItemId()); values.add(row.getEvidenceType());
                    values.add(row.getAuthorityCode()); values.add(row.getAuthoritySnapshotDigest()); });
        return digest(values.toArray(String[]::new));
    }

    private String formalCheckpointSnapshot(String factoryId) {
        List<HaccpCheckpoint> rows = new ArrayList<>(Objects.requireNonNullElse(
                formalCheckpoints.findByFactoryIdAndActiveTrue(factoryId), List.of()));
        rows.sort(Comparator.comparing(HaccpCheckpoint::getId, Comparator.nullsFirst(Long::compareTo)));
        List<String> values = new ArrayList<>();
        values.add(factoryId);
        for (HaccpCheckpoint row : rows) {
            values.add(String.valueOf(row.getId())); values.add(row.getCheckpointCode());
            values.add(row.getName()); values.add(row.getHazardType()); values.add(row.getDescription());
            values.add(decimal(row.getCriticalLimitMin())); values.add(decimal(row.getCriticalLimitMax()));
            values.add(row.getUnit()); values.add(row.getMonitoringProcedure());
            values.add(row.getCorrectiveAction()); values.add(row.getVerificationProcedure());
            values.add(row.getRecordKeeping()); values.add(String.valueOf(row.isActive()));
            values.add(String.valueOf(row.getVersion())); values.add(String.valueOf(row.getUpdatedAt()));
        }
        return digest(values.toArray(String[]::new));
    }

    private String unitSnapshot(CanonicalUnit unit) {
        return unitSnapshot(unit.code(), unit.dimension().name(), unit.baseCode(), unit.factorToBase(),
                unit.displayName(), unit.displayScale(), unit.conversionFamily());
    }

    private String unitSnapshot(String code, String dimension, String baseCode, BigDecimal factor,
                                String displayName, Integer displayScale, String conversionFamily) {
        return digest("CANONICAL_UNIT", code, dimension, baseCode, decimal(factor), displayName,
                String.valueOf(displayScale), conversionFamily);
    }

    private String controlKey(String hazardItemId, String parameterCode) {
        return digest("CONTROL", hazardItemId, parameterCode);
    }

    private String desiredDigest(DraftRequest request) {
        List<String> values = new ArrayList<>();
        values.add(CriticalControlDraftModels.SCHEMA_VERSION);
        values.add(CriticalControlDraftModels.DECISION_ALGORITHM_VERSION);
        values.add(request.factoryId()); values.add(request.blueprintId());
        values.add(String.valueOf(request.blueprintVersion())); values.add(request.sourceDigest());
        values.add(request.hazard().draftId()); values.add(request.hazard().desiredDigest());
        for (ControlItem item : request.items()) {
            if (item == null) { values.add("NULL_ITEM"); continue; }
            values.add(item.hazardItemId()); values.add(item.parameterCode()); values.add(item.parameterName());
            DecisionAnswers d = item.decision();
            values.add(d == null ? "NULL_DECISION" : String.valueOf(d.controlMeasureAtStep()));
            if (d != null) {
                values.add(String.valueOf(d.stepDesignedToEliminateOrReduce()));
                values.add(String.valueOf(d.hazardCouldOccurOrIncrease()));
                values.add(String.valueOf(d.laterStepEliminatesOrReduces()));
                values.add(String.valueOf(d.operationalPrerequisiteAdequate()));
                values.add(CriticalControlDraftModels.classify(d).name());
            }
            values.add(String.valueOf(item.parameterType())); values.add(String.valueOf(item.limitKind()));
            values.add(decimal(item.lowerLimit())); values.add(decimal(item.upperLimit()));
            values.add(String.valueOf(item.lowerInclusive())); values.add(String.valueOf(item.upperInclusive()));
            values.add(item.unit()); values.add(String.valueOf(item.monitoringMethod()));
            addSchedule(values, item.monitoringFrequency()); values.add(String.valueOf(item.responsibleRole()));
            values.add(item.monitoringProcedure()); values.add(String.valueOf(item.deviationDisposition()));
            values.add(item.correctiveAction()); values.add(String.valueOf(item.verificationMethod()));
            addSchedule(values, item.verificationFrequency()); values.add(item.verificationProcedure());
            values.add(String.valueOf(item.recordRetentionDays()));
            sortedDistinct(item.authorityCodes()).forEach(values::add); values.add(item.changeReason());
        }
        return digest(values.toArray(String[]::new));
    }

    private void addSchedule(List<String> values, Schedule schedule) {
        values.add(schedule == null ? "NULL_SCHEDULE" : String.valueOf(schedule.interval()));
        values.add(schedule == null ? "NULL_SCHEDULE_UNIT" : String.valueOf(schedule.unit()));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        return digest(request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), desiredDigest(request));
    }

    private boolean validDecimal(BigDecimal value) {
        return value == null || (value.precision() <= 19 && value.scale() <= 6);
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private List<String> sortedDistinct(List<String> values) {
        return values == null ? List.of() : values.stream().distinct().sorted().toList();
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_critical_control_draft_idempotency")) return true;
        }
        return false;
    }

    private String digest(String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                md.update((byte) (bytes.length >>> 24)); md.update((byte) (bytes.length >>> 16));
                md.update((byte) (bytes.length >>> 8)); md.update((byte) bytes.length); md.update(bytes);
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

    private record Evaluation(DraftPreview preview, BlueprintResolution blueprint,
                              HazardResolution hazard, List<ControlResolution> items) {}

    private record BlueprintResolution(String catalogVersion, String catalogDigest,
                                       Set<String> authorityCodes, String authoritySnapshotDigest,
                                       String snapshotDigest) {
        static BlueprintResolution missing() {
            return new BlueprintResolution("", "", Set.of(), digestStatic("MISSING_AUTHORITY"),
                    digestStatic("MISSING_BLUEPRINT"));
        }
    }

    private record HazardResolution(HazardAnalysisDraft parent,
                                    Map<String, HazardAnalysisDraftItem> items,
                                    String snapshotDigest) {
        static HazardResolution missing() {
            return new HazardResolution(null, Map.of(), digestStatic("MISSING_HAZARD"));
        }
    }

    private record ControlResolution(ControlItem desired, HazardAnalysisDraftItem hazard,
                                     CanonicalUnit unit, ControlClassification classification,
                                     CriterionType criterionType, String controlKey) {
        static ControlResolution missing() {
            return new ControlResolution(null, null, null, ControlClassification.PRP,
                    CriterionType.PRP_CRITERION, digestStatic("MISSING_CONTROL"));
        }
    }

    private static String digestStatic(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}



