package com.cretas.aims.service.qualityreleasedraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftItem;
import com.cretas.aims.entity.config.QualityCheckItem;
import com.cretas.aims.entity.config.QualityCheckItemBinding;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraft;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraftEvidence;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraftItem;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraft;
import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraft;
import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraftEvidence;
import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraftItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.QualityCheckItemBindingRepository;
import com.cretas.aims.repository.QualityCheckItemRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftItemRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftEvidenceRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftItemRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftRepository;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftRepository;
import com.cretas.aims.repository.qualityreleasedraft.QualityReleaseTemplateDraftEvidenceRepository;
import com.cretas.aims.repository.qualityreleasedraft.QualityReleaseTemplateDraftItemRepository;
import com.cretas.aims.repository.qualityreleasedraft.QualityReleaseTemplateDraftRepository;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftCommand;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.DraftIssue;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.DraftPreview;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.DraftReadback;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.DraftReceipt;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.DraftRequest;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.InspectionSpec;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.ReleaseEffect;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.ReleasePolicy;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.RecoveryPlan;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.SamplingStrategy;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.Severity;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.SubjectRefType;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.SubjectType;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.ValueType;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
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
 * Deterministic module-owned quality release TEMPLATE/DRAFT command.
 *
 * <p>The command consumes current B6/B5/B4 lineage and writes only its three typed
 * Blueprint draft tables. It never creates an inspection/result, changes batch or inventory
 * status, executes disposition, starts approval, or emits an alert/notification.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class QualityReleaseTemplateDraftCommand {
    public static final String CAPABILITY_ID = "quality-release";
    public static final String REQUIRED_PERMISSION = "quality:template:draft";
    private static final int MAX_ITEMS = 1000;
    private static final int MAX_EVIDENCE_PER_ITEM = 20;

    private final QualityReleaseTemplateDraftRepository drafts;
    private final QualityReleaseTemplateDraftItemRepository draftItems;
    private final QualityReleaseTemplateDraftEvidenceRepository draftEvidence;
    private final CriticalControlDraftRepository criticalDrafts;
    private final CriticalControlDraftItemRepository criticalItems;
    private final CriticalControlDraftEvidenceRepository criticalEvidence;
    private final CriticalControlDraftCommand criticalCommand;
    private final HazardAnalysisDraftRepository hazardDrafts;
    private final BomRecipeDraftRepository bomDrafts;
    private final BomRecipeDraftItemRepository bomItems;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UnitContractService units;
    private final QualityCheckItemRepository formalQualityItems;
    private final QualityCheckItemBindingRepository formalQualityBindings;
    private final UserRepository users;

    public QualityReleaseTemplateDraftCommand(
            QualityReleaseTemplateDraftRepository drafts,
            QualityReleaseTemplateDraftItemRepository draftItems,
            QualityReleaseTemplateDraftEvidenceRepository draftEvidence,
            CriticalControlDraftRepository criticalDrafts,
            CriticalControlDraftItemRepository criticalItems,
            CriticalControlDraftEvidenceRepository criticalEvidence,
            CriticalControlDraftCommand criticalCommand,
            HazardAnalysisDraftRepository hazardDrafts,
            BomRecipeDraftRepository bomDrafts,
            BomRecipeDraftItemRepository bomItems,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UnitContractService units,
            QualityCheckItemRepository formalQualityItems,
            QualityCheckItemBindingRepository formalQualityBindings,
            UserRepository users) {
        this.drafts = drafts;
        this.draftItems = draftItems;
        this.draftEvidence = draftEvidence;
        this.criticalDrafts = criticalDrafts;
        this.criticalItems = criticalItems;
        this.criticalEvidence = criticalEvidence;
        this.criticalCommand = criticalCommand;
        this.hazardDrafts = hazardDrafts;
        this.bomDrafts = bomDrafts;
        this.bomItems = bomItems;
        this.canonicalBlueprints = canonicalBlueprints;
        this.units = units;
        this.formalQualityItems = formalQualityItems;
        this.formalQualityBindings = formalQualityBindings;
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
        Optional<QualityReleaseTemplateDraft> prior = drafts.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同质量放行模板请求",
                        "QUALITY_RELEASE_DRAFT_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview preview = evaluation.preview();
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "质量放行模板预览被阻断", "QUALITY_RELEASE_DRAFT_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "质量放行模板预览摘要已变化",
                    "QUALITY_RELEASE_DRAFT_PREVIEW_DIGEST_CONFLICT");
        }

        int evidenceCount = request.items().stream()
                .mapToInt(item -> item.authorityCodes().size()).sum();
        UpstreamResolution upstream = evaluation.upstream();
        ReleasePolicy policy = request.releasePolicy();
        QualityReleaseTemplateDraft parent = QualityReleaseTemplateDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .criticalControlDraftId(request.criticalControl().draftId())
                .criticalControlDesiredDigest(request.criticalControl().desiredDigest())
                .criticalControlSnapshotDigest(preview.criticalControlSnapshotDigest())
                .bomDraftId(upstream.bom().getId())
                .bomDesiredDigest(upstream.bom().getDesiredDigest())
                .bomSnapshotDigest(preview.bomSnapshotDigest())
                .materialSnapshotDigest(preview.materialSnapshotDigest())
                .capabilityCatalogVersion(evaluation.blueprint().catalogVersion())
                .capabilityCatalogDigest(evaluation.blueprint().catalogDigest())
                .authoritySnapshotDigest(preview.authoritySnapshotDigest())
                .formalQualitySnapshotDigest(preview.formalQualitySnapshotDigest())
                .decisionAlgorithmVersion(QualityReleaseTemplateDraftModels.DECISION_ALGORITHM_VERSION)
                .maximumMajorFailuresForConditionalReview(
                        policy.maximumMajorFailuresForConditionalReview())
                .maximumMinorFailuresForConditionalReview(
                        policy.maximumMinorFailuresForConditionalReview())
                .approvalRole(policy.approvalRole().name())
                .policyRecordRetentionDays(policy.recordRetentionDays())
                .policyChangeReason(policy.changeReason())
                .desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest())
                .requestDigest(requestDigest)
                .itemCount(request.items().size())
                .evidenceCount(evidenceCount)
                .dependencyBasis(QualityReleaseTemplateDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .status("DRAFT")
                .build();

        QualityReleaseTemplateDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "质量放行模板幂等键发生并发竞争",
                    "QUALITY_RELEASE_DRAFT_IDEMPOTENCY_RACE");
        }

        List<QualityReleaseTemplateDraftItem> itemRows = new ArrayList<>();
        List<QualityReleaseTemplateDraftEvidence> evidenceRows = new ArrayList<>();
        for (int index = 0; index < evaluation.items().size(); index++) {
            ItemResolution resolved = evaluation.items().get(index);
            QualityReleaseTemplateDraftItem itemRow = toRow(saved.getId(), request.factoryId(),
                    request.criticalControl().draftId(), upstream.bom().getId(), index, resolved);
            itemRows.add(itemRow);
            for (String authorityCode : sortedDistinct(resolved.desired().authorityCodes())) {
                evidenceRows.add(QualityReleaseTemplateDraftEvidence.builder()
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
            throw invalid(409, "质量放行模板逐字段回读不一致",
                    "QUALITY_RELEASE_DRAFT_READBACK_MISMATCH");
        }
        DraftPreview current = evaluate(request).preview();
        if (!sameAuthority(preview, current)) {
            throw invalid(409, "质量放行模板写入期间依赖发生漂移",
                    "QUALITY_RELEASE_DRAFT_AUTHORITY_DRIFT");
        }
        return receipt(saved, false, 1, 1 + itemRows.size() + evidenceRows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        QualityReleaseTemplateDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该质量放行模板草稿",
                        "QUALITY_RELEASE_DRAFT_NOT_FOUND"));
        List<QualityReleaseTemplateDraftItem> rows = draftItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<QualityReleaseTemplateDraftEvidence> evidence = draftEvidence
                .findByDraftIdAndFactoryIdOrderByItemIdAscAuthorityCodeAsc(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !QualityReleaseTemplateDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !QualityReleaseTemplateDraftModels.DECISION_ALGORITHM_VERSION.equals(
                        parent.getDecisionAlgorithmVersion())
                || parent.getItemCount() != rows.size() || parent.getEvidenceCount() != evidence.size()) {
            throw invalid(409, "质量放行模板父子行、状态或算法不一致",
                    "QUALITY_RELEASE_DRAFT_READBACK_MISMATCH");
        }
        Map<String, List<String>> authorityByItem = new HashMap<>();
        for (QualityReleaseTemplateDraftEvidence row : evidence) {
            if (!"CATALOG_AUTHORITY".equals(row.getEvidenceType())
                    || !row.getAuthoritySnapshotDigest().equals(authorityEvidenceDigest(
                            parent.getCapabilityCatalogVersion(), parent.getCapabilityCatalogDigest(),
                            row.getAuthorityCode()))) {
                throw invalid(409, "质量放行模板依据摘要不一致",
                        "QUALITY_RELEASE_DRAFT_EVIDENCE_MISMATCH");
            }
            authorityByItem.computeIfAbsent(row.getItemId(), ignored -> new ArrayList<>())
                    .add(row.getAuthorityCode());
        }
        List<InspectionSpec> desiredItems = rows.stream()
                .map(row -> fromRow(row, authorityByItem.getOrDefault(row.getId(), List.of())))
                .toList();
        ReleasePolicy policy = new ReleasePolicy(
                parent.getMaximumMajorFailuresForConditionalReview(),
                parent.getMaximumMinorFailuresForConditionalReview(),
                QualityReleaseTemplateDraftModels.ResponsibleRole.valueOf(parent.getApprovalRole()),
                parent.getPolicyRecordRetentionDays(), parent.getPolicyChangeReason());
        Long readbackOperatorId = parent.getOperatorId() == null ? parent.getActorId() : parent.getOperatorId();
        if (readbackOperatorId == null) throw invalid(409, "草稿缺少 typed actor 身份，无法安全回读", "DRAFT_ACTOR_IDENTITY_MISSING");
        DraftRequest desired = new DraftRequest(parent.getFactoryId(), parent.getBlueprintId(),
                parent.getBlueprintVersion(), parent.getSourceDigest(),
                new QualityReleaseTemplateDraftModels.CriticalControlPin(
                        parent.getCriticalControlDraftId(), parent.getCriticalControlDesiredDigest()),
                desiredItems, policy, parent.getPreviewDigest(), parent.getIdempotencyKey(),
                readbackOperatorId);
        Evaluation current = evaluate(desired);
        if (!"READY".equals(current.preview().status())
                || !parent.getDesiredDigest().equals(desiredDigest(desired))
                || !parent.getSourceSnapshotDigest().equals(current.preview().sourceSnapshotDigest())
                || !parent.getCriticalControlSnapshotDigest().equals(
                        current.preview().criticalControlSnapshotDigest())
                || !parent.getBomSnapshotDigest().equals(current.preview().bomSnapshotDigest())
                || !parent.getMaterialSnapshotDigest().equals(current.preview().materialSnapshotDigest())
                || !parent.getAuthoritySnapshotDigest().equals(current.preview().authoritySnapshotDigest())
                || !parent.getFormalQualitySnapshotDigest().equals(
                        current.preview().formalQualitySnapshotDigest())
                || !parent.getPreviewDigest().equals(current.preview().previewDigest())
                || current.items().size() != rows.size()) {
            throw invalid(409, "质量放行模板当前依赖或逐字段回读已漂移",
                    "QUALITY_RELEASE_DRAFT_AUTHORITY_DRIFT");
        }
        for (int index = 0; index < rows.size(); index++) {
            if (!matches(rows.get(index), current.items().get(index), parent, index)) {
                throw invalid(409, "质量放行模板派生字段被篡改",
                        "QUALITY_RELEASE_DRAFT_READBACK_MISMATCH");
            }
        }
        return new DraftReadback(QualityReleaseTemplateDraftModels.SCHEMA_VERSION,
                factoryId, draftId, parent.getStatus(), desired,
                0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recovery(String factoryId, String draftId, String expectedDesiredDigest) {
        DraftReadback readback = readBack(factoryId, draftId);
        String actual = desiredDigest(readback.desired());
        if (!actual.equals(expectedDesiredDigest)) {
            throw invalid(409, "恢复前质量放行模板摘要不一致",
                    "QUALITY_RELEASE_DRAFT_RECOVERY_DIGEST_CONFLICT");
        }
        return new RecoveryPlan(QualityReleaseTemplateDraftModels.SCHEMA_VERSION,
                factoryId, draftId, expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "仅归档未发布模板草稿；不改变质检、批次、库存、审批或告警",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintResolution blueprint = resolveBlueprint(request, issues);
        UpstreamResolution upstream = resolveUpstream(request, issues);
        String formalSnapshot = formalQualitySnapshot(request.factoryId());
        List<ItemResolution> resolved = new ArrayList<>();
        Set<String> specKeys = new HashSet<>();
        for (int index = 0; index < request.items().size(); index++) {
            InspectionSpec item = request.items().get(index);
            String target = "items[" + index + "]";
            validateItemShape(item, target, issues);
            ItemResolution resolution = resolveItem(request.factoryId(), item, target,
                    upstream, blueprint, issues);
            if (!specKeys.add(resolution.specKey())) {
                issues.add(issue("QUALITY_RELEASE_SPEC_DUPLICATE", target,
                        "同一 subject 和 itemCode 不能重复", "合并重复检验规格"));
            }
            resolved.add(resolution);
        }
        validatePolicy(request.releasePolicy(), issues);
        validateCoverage(upstream, resolved, issues);

        String desiredDigest = desiredDigest(request);
        String sourceSnapshot = digest("QUALITY_RELEASE_SOURCE", blueprint.snapshotDigest(),
                upstream.criticalSnapshotDigest(), upstream.bomSnapshotDigest(),
                upstream.materialSnapshotDigest(), formalSnapshot);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("QUALITY_RELEASE_PREVIEW",
                QualityReleaseTemplateDraftModels.SCHEMA_VERSION,
                QualityReleaseTemplateDraftModels.DECISION_ALGORITHM_VERSION,
                status, sourceSnapshot, upstream.criticalSnapshotDigest(),
                upstream.bomSnapshotDigest(), upstream.materialSnapshotDigest(),
                blueprint.authoritySnapshotDigest(), formalSnapshot, desiredDigest,
                issueDigest(issues));
        DraftPreview preview = new DraftPreview(
                QualityReleaseTemplateDraftModels.SCHEMA_VERSION, status, request.factoryId(),
                List.of("critical-controls", "materials"), request, sourceSnapshot,
                upstream.criticalSnapshotDigest(), upstream.bomSnapshotDigest(),
                upstream.materialSnapshotDigest(), blueprint.authoritySnapshotDigest(),
                formalSnapshot, desiredDigest, previewDigest, issues,
                0, 0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, blueprint, upstream, List.copyOf(resolved));
    }

    private BlueprintResolution resolveBlueprint(DraftRequest request, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(request.factoryId());
        if (response == null || !request.blueprintId().equals(response.getId())
                || !Objects.equals(Math.toIntExact(request.blueprintVersion()), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                    || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("QUALITY_RELEASE_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
                    "当前工厂 Blueprint DRAFT/VALIDATED 版本与请求不一致",
                    "刷新当前 Blueprint 后重新预览"));
            return BlueprintResolution.missing();
        }
        CapabilityView capability = response.getCapabilities() == null ? null
                : response.getCapabilities().stream()
                .filter(item -> CAPABILITY_ID.equals(item.getId())).findFirst().orElse(null);
        Set<String> dependencies = capability == null || capability.getDependsOn() == null
                ? Set.of() : new HashSet<>(capability.getDependsOn());
        if (capability == null || !capability.isIncluded() || !"READY".equals(capability.getStatus())
                || !dependencies.equals(Set.copyOf(FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID)))) {
            issues.add(issue("QUALITY_RELEASE_CAPABILITY_NOT_READY", "capability",
                    "quality-release 能力或 critical-controls/materials 依赖尚未就绪",
                    "统一 immutable catalog 依赖并补齐当前草稿"));
        }
        Set<String> authorityCodes = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || authorityCodes.isEmpty()) {
            issues.add(issue("QUALITY_RELEASE_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本、摘要或质量依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        return new BlueprintResolution(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), authorityCodes,
                authoritySnapshot(response, authorityCodes), digestBlueprint(response));
    }

    private UpstreamResolution resolveUpstream(DraftRequest request, List<DraftIssue> issues) {
        CriticalControlDraft parent = request.criticalControl() == null ? null
                : criticalDrafts.findByIdAndFactoryId(request.criticalControl().draftId(),
                        request.factoryId()).orElse(null);
        if (parent == null || !"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !CriticalControlDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !CriticalControlDraftModels.DECISION_ALGORITHM_VERSION.equals(
                        parent.getDecisionAlgorithmVersion())
                || !request.blueprintId().equals(parent.getBlueprintId())
                || request.blueprintVersion() != parent.getBlueprintVersion()
                || !request.sourceDigest().equals(parent.getSourceDigest())
                || !request.criticalControl().desiredDigest().equals(parent.getDesiredDigest())) {
            issues.add(issue("QUALITY_RELEASE_CRITICAL_CONTROL_DRIFT", "criticalControl",
                    "critical-controls DRAFT 不存在、lineage 不一致或摘要已变化",
                    "选择同 Blueprint 的当前 critical-controls DRAFT"));
            return UpstreamResolution.missing();
        }
        try {
            var readback = criticalCommand.readBack(request.factoryId(), parent.getId());
            var current = criticalCommand.preview(readback.desired());
            if (!"READY".equals(current.status())
                    || !parent.getDesiredDigest().equals(current.desiredDigest())
                    || !parent.getSourceSnapshotDigest().equals(current.sourceSnapshotDigest())
                    || !parent.getHazardSnapshotDigest().equals(current.hazardSnapshotDigest())
                    || !parent.getAuthoritySnapshotDigest().equals(current.authoritySnapshotDigest())
                    || !parent.getFormalCheckpointSnapshotDigest().equals(
                            current.formalCheckpointSnapshotDigest())
                    || !parent.getPreviewDigest().equals(current.previewDigest())) {
                issues.add(issue("QUALITY_RELEASE_CRITICAL_CONTROL_AUTHORITY_BLOCKED",
                        "criticalControl", "critical-controls DRAFT 当前依赖已漂移或不能回读",
                        "先重新生成 readback-ready critical-controls DRAFT"));
            }
        } catch (BusinessException exception) {
            issues.add(issue("QUALITY_RELEASE_CRITICAL_CONTROL_AUTHORITY_BLOCKED",
                    "criticalControl", "critical-controls DRAFT 当前不能逐字段回读",
                    "先修复或重新生成 critical-controls DRAFT"));
        }
        List<CriticalControlDraftItem> controls = criticalItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(parent.getId(), request.factoryId());
        List<CriticalControlDraftEvidence> evidence = criticalEvidence
                .findByDraftIdAndFactoryIdOrderByItemIdAscAuthorityCodeAsc(
                        parent.getId(), request.factoryId());
        HazardAnalysisDraft hazard = hazardDrafts.findByIdAndFactoryId(
                parent.getHazardDraftId(), request.factoryId()).orElse(null);
        BomRecipeDraft bom = hazard == null ? null : bomDrafts.findByIdAndFactoryId(
                hazard.getBomDraftId(), request.factoryId()).orElse(null);
        List<BomRecipeDraftItem> materials = bom == null ? List.of() : bomItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(bom.getId(), request.factoryId());
        if (hazard == null || bom == null || !"DRAFT".equals(hazard.getStatus())
                || !"DRAFT".equals(bom.getStatus())
                || !Boolean.TRUE.equals(bom.getDependencyClosureComplete())
                || !request.blueprintId().equals(bom.getBlueprintId())
                || request.blueprintVersion() != bom.getBlueprintVersion()
                || !request.sourceDigest().equals(bom.getSourceDigest())
                || !parent.getHazardBomSnapshotDigest().equals(hazard.getBomSnapshotDigest())
                || !hazard.getBomDesiredDigest().equals(bom.getDesiredDigest())) {
            issues.add(issue("QUALITY_RELEASE_BOM_LINEAGE_BLOCKED", "materials",
                    "B6→B5→B4 的 BOM/material lineage 不完整或已漂移",
                    "重新生成同 Blueprint 的 BOM、危害和关键控制草稿"));
        }
        Map<String, CriticalControlDraftItem> byControl = new LinkedHashMap<>();
        controls.forEach(row -> byControl.putIfAbsent(row.getId(), row));
        Map<String, List<BomRecipeDraftItem>> byMaterial = new LinkedHashMap<>();
        for (BomRecipeDraftItem row : materials) {
            byMaterial.computeIfAbsent(materialKey(row.getMaterialRefType(), row.getMaterialRefId()),
                    ignored -> new ArrayList<>()).add(row);
        }
        return new UpstreamResolution(parent, Map.copyOf(byControl), bom,
                List.copyOf(materials), immutableLists(byMaterial),
                criticalSnapshot(parent, controls, evidence), bomSnapshot(bom, materials),
                materialSnapshot(materials));
    }

    private ItemResolution resolveItem(String factoryId, InspectionSpec item, String target,
                                       UpstreamResolution upstream, BlueprintResolution blueprint,
                                       List<DraftIssue> issues) {
        if (item == null || upstream.parent() == null || upstream.bom() == null) {
            return ItemResolution.missing();
        }
        SubjectResolution subject = resolveSubject(item, target, upstream, issues);
        CriticalControlDraftItem linked = item.linkedCriticalControlItemId().isBlank() ? null
                : upstream.controls().get(item.linkedCriticalControlItemId());
        if (!item.linkedCriticalControlItemId().isBlank() && linked == null) {
            issues.add(issue("QUALITY_RELEASE_CONTROL_LINK_INVALID",
                    target + ".linkedCriticalControlItemId",
                    "关联控制项不属于当前 critical-controls DRAFT",
                    "选择同一上游草稿的控制项"));
        }
        if (item.severity() == Severity.CRITICAL && linked == null) {
            issues.add(issue("QUALITY_RELEASE_CRITICAL_LINK_REQUIRED", target,
                    "CRITICAL 检验规格必须绑定当前关键控制项",
                    "补充 critical-control item pin"));
        }
        if (item.subjectType() == SubjectType.MATERIAL && linked != null
                && (!Objects.equals(linked.getMaterialRefId(), item.subjectRefId())
                    || !Objects.equals(linked.getMaterialRefType(), materialRefType(item.subjectRefType())))) {
            issues.add(issue("QUALITY_RELEASE_MATERIAL_CONTROL_MISMATCH", target,
                    "原料检验规格与关联控制项的物料不一致",
                    "选择同一 BOM 物料的控制项"));
        }
        CanonicalUnit unit = resolveUnit(factoryId, item, linked, target, issues);
        validateAuthority(item, target, blueprint, issues);
        String criterion = criterionKind(item);
        ReleaseEffect effect = item.severity() == null ? ReleaseEffect.ADVISORY
                : QualityReleaseTemplateDraftModels.releaseEffect(item.severity());
        return new ItemResolution(item, subject, linked, unit, criterion, effect,
                specKey(item.subjectType(), item.subjectRefId(), item.itemCode()));
    }

    private SubjectResolution resolveSubject(InspectionSpec item, String target,
                                             UpstreamResolution upstream,
                                             List<DraftIssue> issues) {
        if (item.subjectType() == null || item.subjectRefType() == null) {
            issues.add(issue("QUALITY_RELEASE_SUBJECT_REQUIRED", target + ".subject",
                    "检验 subject 类型和引用类型不能为空", "选择成品、BOM 原料或工序控制项"));
            return SubjectResolution.missing();
        }
        if (item.subjectType() == SubjectType.FINISHED_PRODUCT) {
            SubjectRefType expected = "LIVE_PRODUCT".equals(upstream.bom().getTargetProductRefType())
                    ? SubjectRefType.FORMAL_PRODUCT : SubjectRefType.PRODUCT_DRAFT;
            if (item.subjectRefType() != expected
                    || !item.subjectRefId().equals(upstream.bom().getTargetProductRefId())) {
                issues.add(issue("QUALITY_RELEASE_PRODUCT_SUBJECT_MISMATCH", target + ".subject",
                        "成品 subject 必须来自同一 B4 BOM target product",
                        "使用服务端派生的 BOM product pin"));
                return SubjectResolution.missing();
            }
            return new SubjectResolution(upstream.bom().getTargetProductSnapshotDigest(),
                    null, null, null);
        }
        if (item.subjectType() == SubjectType.MATERIAL) {
            String key = materialKey(materialRefType(item.subjectRefType()), item.subjectRefId());
            List<BomRecipeDraftItem> candidates = upstream.materialsByKey().getOrDefault(key, List.of());
            if (candidates.isEmpty()) {
                issues.add(issue("QUALITY_RELEASE_MATERIAL_SUBJECT_MISMATCH", target + ".subject",
                        "原料 subject 不属于同一 B4 BOM material snapshot",
                        "从服务端 BOM material pin 中选择"));
                return SubjectResolution.missing();
            }
            Set<String> snapshots = new HashSet<>();
            candidates.forEach(row -> snapshots.add(row.getMaterialSnapshotDigest()));
            if (snapshots.size() != 1) {
                issues.add(issue("QUALITY_RELEASE_MATERIAL_SUBJECT_AMBIGUOUS", target + ".subject",
                        "同一物料在 BOM 中携带冲突快照", "先修复 BOM material snapshot"));
                return SubjectResolution.missing();
            }
            BomRecipeDraftItem first = candidates.stream()
                    .min(Comparator.comparing(BomRecipeDraftItem::getOrdinal)).orElseThrow();
            return new SubjectResolution(first.getMaterialSnapshotDigest(), first.getId(),
                    first.getWorkflowProcessNodeId(), null);
        }
        CriticalControlDraftItem control = upstream.controls().get(item.subjectRefId());
        if (item.subjectRefType() != SubjectRefType.CRITICAL_CONTROL || control == null
                || !item.subjectRefId().equals(item.linkedCriticalControlItemId())) {
            issues.add(issue("QUALITY_RELEASE_PROCESS_SUBJECT_MISMATCH", target + ".subject",
                    "工序 subject 必须精确绑定当前 critical-control item",
                    "使用服务端派生的控制项 pin"));
            return SubjectResolution.missing();
        }
        return new SubjectResolution(controlSnapshot(control), null,
                control.getRouteNodeId(), control.getRouteNodeLabel());
    }

    private void validateItemShape(InspectionSpec item, String target, List<DraftIssue> issues) {
        if (item == null) {
            issues.add(issue("QUALITY_RELEASE_ITEM_REQUIRED", target,
                    "检验规格不能为空", "补充 typed 检验规格"));
            return;
        }
        if (item.subjectType() == null || item.subjectRefType() == null
                || isBlankOrTooLong(item.subjectRefId(), 191)
                || !item.itemCode().matches("[A-Z][A-Z0-9_]{1,63}")
                || isBlankOrTooLong(item.itemName(), 200) || item.category() == null
                || item.valueType() == null || item.samplingStrategy() == null
                || item.severity() == null || item.responsibleRole() == null
                || isBlankOrTooLong(item.standardReference(), 300)
                || isBlankOrTooLong(item.inspectionMethod(), 1000)
                || isBlankOrTooLong(item.changeReason(), 500)
                || item.recordRetentionDays() < 1 || item.recordRetentionDays() > 36500) {
            issues.add(issue("QUALITY_RELEASE_ITEM_FIELDS_REQUIRED", target,
                    "subject、规格、方法、抽样、严重度、责任、留存和变更原因均须 typed 且完整",
                    "补全质量放行模板字段"));
        }
        validateCriterion(item, target, issues);
        validateSampling(item, target, issues);
        if (item.authorityCodes().isEmpty() || item.authorityCodes().size() > MAX_EVIDENCE_PER_ITEM
                || item.authorityCodes().stream().anyMatch(code -> isBlankOrTooLong(code, 100))
                || new HashSet<>(item.authorityCodes()).size() != item.authorityCodes().size()) {
            issues.add(issue("QUALITY_RELEASE_AUTHORITY_REQUIRED", target + ".authorityCodes",
                    "每个检验规格必须引用 1 至 20 个不重复的平台依据",
                    "选择平台提供的 authority code"));
        }
    }

    private void validateCriterion(InspectionSpec item, String target, List<DraftIssue> issues) {
        boolean valid = false;
        if (item.valueType() == ValueType.NUMERIC) {
            valid = (item.lowerValue() != null && item.upperValue() == null)
                    || (item.lowerValue() == null && item.upperValue() != null)
                    || (item.lowerValue() != null && item.upperValue() != null
                        && item.lowerValue().compareTo(item.upperValue()) == 0);
            valid = valid && item.expectedText().isBlank() && item.expectedBoolean() == null
                    && !item.unit().isBlank();
        } else if (item.valueType() == ValueType.RANGE) {
            valid = item.lowerValue() != null && item.upperValue() != null
                    && item.lowerValue().compareTo(item.upperValue()) <= 0
                    && item.expectedText().isBlank() && item.expectedBoolean() == null
                    && !item.unit().isBlank();
        } else if (item.valueType() == ValueType.TEXT) {
            valid = item.lowerValue() == null && item.upperValue() == null
                    && !item.expectedText().isBlank() && item.expectedBoolean() == null
                    && item.unit().isBlank() && item.tolerance() == null;
        } else if (item.valueType() == ValueType.BOOLEAN) {
            valid = item.lowerValue() == null && item.upperValue() == null
                    && item.expectedText().isBlank() && item.expectedBoolean() != null
                    && item.unit().isBlank() && item.tolerance() == null;
        }
        if (!valid || !validDecimal(item.lowerValue()) || !validDecimal(item.upperValue())
                || !validDecimal(item.tolerance())
                || (item.tolerance() != null && item.tolerance().signum() < 0)) {
            issues.add(issue("QUALITY_RELEASE_CRITERION_INVALID", target + ".criterion",
                    "数值/范围/文本/布尔判定值、单位或公差组合无效",
                    "使用封闭 valueType 和最多 19 位、6 位小数的 typed 标准"));
        }
    }

    private void validateSampling(InspectionSpec item, String target, List<DraftIssue> issues) {
        BigDecimal ratio = item.samplingRatio();
        BigDecimal aql = item.aqlLevel();
        boolean valid = item.minimumSampleSize() >= 1 && item.minimumSampleSize() <= 100000;
        if (item.samplingStrategy() == SamplingStrategy.FULL_INSPECTION) {
            valid &= ratio != null && ratio.compareTo(new BigDecimal("100")) == 0 && aql == null;
        } else if (item.samplingStrategy() == SamplingStrategy.AQL) {
            valid &= ratio == null && aql != null && aql.signum() > 0
                    && aql.compareTo(new BigDecimal("100")) <= 0;
        } else {
            valid &= ratio != null && ratio.signum() > 0
                    && ratio.compareTo(new BigDecimal("100")) <= 0 && aql == null;
        }
        if (!valid || !validSamplingDecimal(ratio) || !validSamplingDecimal(aql)) {
            issues.add(issue("QUALITY_RELEASE_SAMPLING_INVALID", target + ".sampling",
                    "抽样策略、比例、最小样本或 AQL 组合无效", "修正 typed 抽样方案"));
        }
    }

    private void validateAuthority(InspectionSpec item, String target,
                                   BlueprintResolution blueprint, List<DraftIssue> issues) {
        if (!blueprint.authorityCodes().containsAll(item.authorityCodes())) {
            issues.add(issue("QUALITY_RELEASE_AUTHORITY_NOT_IN_CATALOG",
                    target + ".authorityCodes", "检验规格引用了目录外依据",
                    "只使用当前 quality-release capability authority refs"));
        }
    }

    private void validatePolicy(ReleasePolicy policy, List<DraftIssue> issues) {
        if (policy == null || policy.approvalRole() == null
                || policy.maximumMajorFailuresForConditionalReview() < 0
                || policy.maximumMajorFailuresForConditionalReview() > 10000
                || policy.maximumMinorFailuresForConditionalReview()
                    < policy.maximumMajorFailuresForConditionalReview()
                || policy.maximumMinorFailuresForConditionalReview() > 10000
                || policy.recordRetentionDays() < 1 || policy.recordRetentionDays() > 36500
                || isBlankOrTooLong(policy.changeReason(), 500)) {
            issues.add(issue("QUALITY_RELEASE_POLICY_INVALID", "releasePolicy",
                    "放行资格、条件复核阈值、责任、留存和变更原因不完整",
                    "补充 typed 放行模板策略"));
        }
    }

    private void validateCoverage(UpstreamResolution upstream, List<ItemResolution> items,
                                  List<DraftIssue> issues) {
        if (items.stream().noneMatch(item -> item.desired() != null
                && item.desired().subjectType() == SubjectType.FINISHED_PRODUCT)) {
            issues.add(issue("QUALITY_RELEASE_PRODUCT_SPEC_REQUIRED", "items",
                    "至少需要一个成品检验规格", "为 BOM target product 补充检验规格"));
        }
        Set<String> materialCoverage = new HashSet<>();
        Map<String, List<ItemResolution>> controlCoverage = new HashMap<>();
        for (ItemResolution item : items) {
            if (item.desired() == null) continue;
            if (item.desired().subjectType() == SubjectType.MATERIAL) {
                materialCoverage.add(materialKey(materialRefType(item.desired().subjectRefType()),
                        item.desired().subjectRefId()));
            }
            if (!item.desired().linkedCriticalControlItemId().isBlank()) {
                controlCoverage.computeIfAbsent(item.desired().linkedCriticalControlItemId(),
                        ignored -> new ArrayList<>()).add(item);
            }
        }
        upstream.materials().stream().filter(row -> !Boolean.TRUE.equals(row.getOptional()))
                .map(row -> materialKey(row.getMaterialRefType(), row.getMaterialRefId()))
                .distinct().filter(key -> !materialCoverage.contains(key)).forEach(key ->
                        issues.add(issue("QUALITY_RELEASE_MATERIAL_SPEC_REQUIRED", "items",
                                "必需 BOM 物料缺少检验规格: " + key,
                                "为每种必需物料补充来料检验规格")));
        for (CriticalControlDraftItem control : upstream.controls().values()) {
            if (!("CCP".equals(control.getControlClassification())
                    || "OPRP".equals(control.getControlClassification()))) continue;
            List<ItemResolution> linked = controlCoverage.getOrDefault(control.getId(), List.of());
            boolean covered = "CCP".equals(control.getControlClassification())
                    ? linked.stream().anyMatch(item -> item.desired().severity() == Severity.CRITICAL)
                    : linked.stream().anyMatch(item -> Set.of(Severity.CRITICAL, Severity.MAJOR)
                        .contains(item.desired().severity()));
            if (!covered) {
                issues.add(issue("QUALITY_RELEASE_CONTROL_SPEC_REQUIRED", "items",
                        control.getControlClassification() + " 控制项缺少相应放行检验规格: "
                                + control.getId(), "为该控制项补充 CRITICAL/MAJOR 检验规格"));
            }
        }
    }

    private CanonicalUnit resolveUnit(String factoryId, InspectionSpec item,
                                      CriticalControlDraftItem linked, String target,
                                      List<DraftIssue> issues) {
        if (item.valueType() == null
                || !(item.valueType() == ValueType.NUMERIC || item.valueType() == ValueType.RANGE)) {
            return null;
        }
        CanonicalUnit unit = units.describe(factoryId, item.unit()).orElse(null);
        if (unit == null || !unit.active() || !com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(item.unit(), unit.code())) {
            issues.add(issue("QUALITY_RELEASE_UNIT_NOT_SUPPORTED", target + ".unit",
                    "数值规格单位必须是当前工厂启用的精确 canonical code",
                    "从单位目录选择单位"));
            return null;
        }
        if (item.subjectType() == SubjectType.PROCESS_CONTROL && linked != null
                && !unit.code().equals(linked.getUnitCode())) {
            issues.add(issue("QUALITY_RELEASE_CONTROL_UNIT_MISMATCH", target + ".unit",
                    "工序检验规格单位与关键控制限值单位不一致",
                    "使用控制项的 canonical unit"));
        }
        return unit;
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "QUALITY_RELEASE_DRAFT_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少质量放行模板草稿权限",
                    "QUALITY_RELEASE_DRAFT_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(), operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员",
                    "QUALITY_RELEASE_DRAFT_OPERATOR_MEMBERSHIP_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || request.blueprintVersion() > Integer.MAX_VALUE
                || !isDigest(request.sourceDigest()) || request.criticalControl() == null
                || request.criticalControl().draftId().isBlank()
                || request.criticalControl().draftId().length() > 36
                || !isDigest(request.criticalControl().desiredDigest())
                || request.items().isEmpty() || request.items().size() > MAX_ITEMS) {
            throw invalid(400, "Blueprint envelope、critical-control pin 或检验规格范围无效",
                    "QUALITY_RELEASE_DRAFT_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "QUALITY_RELEASE_DRAFT_OPERATOR_REQUIRED");
        }
    }

    private QualityReleaseTemplateDraftItem toRow(
            String draftId, String factoryId, String criticalDraftId, String bomDraftId,
            int ordinal, ItemResolution resolved) {
        InspectionSpec item = resolved.desired();
        CanonicalUnit unit = resolved.unit();
        return QualityReleaseTemplateDraftItem.builder()
                .id(UUID.randomUUID().toString()).draftId(draftId).factoryId(factoryId)
                .ordinal(ordinal).specKey(resolved.specKey())
                .criticalControlDraftId(criticalDraftId)
                .criticalControlItemId(resolved.linkedControl() == null ? null
                        : resolved.linkedControl().getId())
                .bomDraftId(bomDraftId).bomItemId(resolved.subject().bomItemId())
                .subjectType(item.subjectType().name()).subjectRefType(item.subjectRefType().name())
                .subjectRefId(item.subjectRefId())
                .subjectSnapshotDigest(resolved.subject().snapshotDigest())
                .routeNodeId(resolved.subject().routeNodeId())
                .routeNodeLabel(resolved.subject().routeNodeLabel())
                .itemCode(item.itemCode()).itemName(item.itemName())
                .category(item.category().name()).valueType(item.valueType().name())
                .criterionKind(resolved.criterionKind())
                .lowerValue(item.lowerValue()).upperValue(item.upperValue())
                .expectedText(item.expectedText().isBlank() ? null : item.expectedText())
                .expectedBoolean(item.expectedBoolean())
                .unitCode(unit == null ? null : unit.code())
                .unitDimension(unit == null ? null : unit.dimension().name())
                .unitBaseCode(unit == null ? null : unit.baseCode())
                .unitFactorToBase(unit == null ? null : unit.factorToBase())
                .unitDisplayName(unit == null ? null : unit.displayName())
                .unitDisplayScale(unit == null ? null : unit.displayScale())
                .unitConversionFamily(unit == null ? null : unit.conversionFamily())
                .unitSnapshotDigest(unit == null ? null : unitSnapshot(unit))
                .tolerance(item.tolerance()).standardReference(item.standardReference())
                .inspectionMethod(item.inspectionMethod())
                .samplingStrategy(item.samplingStrategy().name())
                .samplingRatio(item.samplingRatio()).minimumSampleSize(item.minimumSampleSize())
                .aqlLevel(item.aqlLevel()).severity(item.severity().name())
                .releaseEffect(resolved.releaseEffect().name()).required(item.required())
                .photoRequiredOnFailure(item.photoRequiredOnFailure())
                .noteRequiredOnFailure(item.noteRequiredOnFailure())
                .responsibleRole(item.responsibleRole().name())
                .recordRetentionDays(item.recordRetentionDays()).changeReason(item.changeReason())
                .build();
    }

    private InspectionSpec fromRow(QualityReleaseTemplateDraftItem row, List<String> authorityCodes) {
        return new InspectionSpec(SubjectType.valueOf(row.getSubjectType()),
                SubjectRefType.valueOf(row.getSubjectRefType()), row.getSubjectRefId(),
                Objects.requireNonNullElse(row.getCriticalControlItemId(), ""),
                row.getItemCode(), row.getItemName(),
                QualityReleaseTemplateDraftModels.QualityCategory.valueOf(row.getCategory()),
                ValueType.valueOf(row.getValueType()), row.getLowerValue(), row.getUpperValue(),
                Objects.requireNonNullElse(row.getExpectedText(), ""), row.getExpectedBoolean(),
                Objects.requireNonNullElse(row.getUnitCode(), ""), row.getTolerance(),
                row.getStandardReference(), row.getInspectionMethod(),
                SamplingStrategy.valueOf(row.getSamplingStrategy()), row.getSamplingRatio(),
                row.getMinimumSampleSize(), row.getAqlLevel(), Severity.valueOf(row.getSeverity()),
                Boolean.TRUE.equals(row.getRequired()), Boolean.TRUE.equals(row.getPhotoRequiredOnFailure()),
                Boolean.TRUE.equals(row.getNoteRequiredOnFailure()),
                QualityReleaseTemplateDraftModels.ResponsibleRole.valueOf(row.getResponsibleRole()),
                row.getRecordRetentionDays(), authorityCodes.stream().sorted().toList(),
                row.getChangeReason());
    }

    private boolean matches(QualityReleaseTemplateDraftItem row, ItemResolution current,
                            QualityReleaseTemplateDraft parent, int expectedOrdinal) {
        InspectionSpec item = current.desired();
        CanonicalUnit unit = current.unit();
        return Objects.equals(row.getOrdinal(), expectedOrdinal)
                && row.getCriticalControlDraftId().equals(parent.getCriticalControlDraftId())
                && Objects.equals(row.getCriticalControlItemId(),
                    current.linkedControl() == null ? null : current.linkedControl().getId())
                && row.getBomDraftId().equals(parent.getBomDraftId())
                && row.getSpecKey().equals(current.specKey())
                && row.getSubjectType().equals(item.subjectType().name())
                && row.getSubjectRefType().equals(item.subjectRefType().name())
                && row.getSubjectRefId().equals(item.subjectRefId())
                && row.getSubjectSnapshotDigest().equals(current.subject().snapshotDigest())
                && Objects.equals(row.getBomItemId(), current.subject().bomItemId())
                && Objects.equals(row.getRouteNodeId(), current.subject().routeNodeId())
                && Objects.equals(row.getRouteNodeLabel(), current.subject().routeNodeLabel())
                && row.getItemCode().equals(item.itemCode())
                && row.getItemName().equals(item.itemName())
                && row.getCategory().equals(item.category().name())
                && row.getValueType().equals(item.valueType().name())
                && row.getCriterionKind().equals(current.criterionKind())
                && Objects.equals(decimal(row.getLowerValue()), decimal(item.lowerValue()))
                && Objects.equals(decimal(row.getUpperValue()), decimal(item.upperValue()))
                && Objects.equals(Objects.requireNonNullElse(row.getExpectedText(), ""),
                    item.expectedText())
                && Objects.equals(row.getExpectedBoolean(), item.expectedBoolean())
                && row.getReleaseEffect().equals(current.releaseEffect().name())
                && Objects.equals(row.getUnitCode(), unit == null ? null : unit.code())
                && com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(row.getUnitDimension(), unit == null ? null : unit.dimension().name())
                && com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(row.getUnitBaseCode(), unit == null ? null : unit.baseCode())
                && Objects.equals(decimal(row.getUnitFactorToBase()),
                    decimal(unit == null ? null : unit.factorToBase()))
                && com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(row.getUnitDisplayName(), unit == null ? null : unit.displayName())
                && com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(row.getUnitDisplayScale(), unit == null ? null : unit.displayScale())
                && Objects.equals(row.getUnitConversionFamily(),
                    unit == null ? null : unit.conversionFamily())
                && com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(row.getUnitSnapshotDigest(), unit == null ? null : unitSnapshot(unit))
                && Objects.equals(decimal(row.getTolerance()), decimal(item.tolerance()))
                && row.getStandardReference().equals(item.standardReference())
                && row.getInspectionMethod().equals(item.inspectionMethod())
                && row.getSamplingStrategy().equals(item.samplingStrategy().name())
                && Objects.equals(decimal(row.getSamplingRatio()), decimal(item.samplingRatio()))
                && Objects.equals(row.getMinimumSampleSize(), item.minimumSampleSize())
                && Objects.equals(decimal(row.getAqlLevel()), decimal(item.aqlLevel()))
                && row.getSeverity().equals(item.severity().name())
                && Objects.equals(row.getRequired(), item.required())
                && Objects.equals(row.getPhotoRequiredOnFailure(), item.photoRequiredOnFailure())
                && Objects.equals(row.getNoteRequiredOnFailure(), item.noteRequiredOnFailure())
                && row.getResponsibleRole().equals(item.responsibleRole().name())
                && Objects.equals(row.getRecordRetentionDays(), item.recordRetentionDays())
                && row.getChangeReason().equals(item.changeReason());
    }

    private DraftReceipt receipt(QualityReleaseTemplateDraft row, boolean replayed,
                                 int moduleWrites, int persistedRows) {
        return new DraftReceipt(QualityReleaseTemplateDraftModels.SCHEMA_VERSION,
                row.getId(), row.getFactoryId(), row.getId(), row.getStatus(),
                row.getSourceSnapshotDigest(), row.getCriticalControlSnapshotDigest(),
                row.getBomSnapshotDigest(), row.getMaterialSnapshotDigest(),
                row.getAuthoritySnapshotDigest(), row.getFormalQualitySnapshotDigest(),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                row.getItemCount(), row.getEvidenceCount(),
                Objects.requireNonNullElse(row.getLockVersion(), 0L), replayed,
                moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED",
                "仅可归档该未发布质量放行模板草稿；不改变任何运行态质量或库存数据");
    }

    private boolean sameAuthority(DraftPreview left, DraftPreview right) {
        return "READY".equals(right.status())
                && left.sourceSnapshotDigest().equals(right.sourceSnapshotDigest())
                && left.criticalControlSnapshotDigest().equals(right.criticalControlSnapshotDigest())
                && left.bomSnapshotDigest().equals(right.bomSnapshotDigest())
                && left.materialSnapshotDigest().equals(right.materialSnapshotDigest())
                && left.authoritySnapshotDigest().equals(right.authoritySnapshotDigest())
                && left.formalQualitySnapshotDigest().equals(right.formalQualitySnapshotDigest())
                && left.desiredDigest().equals(right.desiredDigest())
                && left.previewDigest().equals(right.previewDigest());
    }

    private String criterionKind(InspectionSpec item) {
        if (item == null || item.valueType() == null) return "INVALID";
        return switch (item.valueType()) {
            case RANGE -> "RANGE";
            case TEXT -> "EXPECTED_TEXT";
            case BOOLEAN -> "EXPECTED_BOOLEAN";
            case NUMERIC -> item.lowerValue() != null && item.upperValue() == null ? "MINIMUM"
                    : item.lowerValue() == null && item.upperValue() != null ? "MAXIMUM" : "EXACT";
        };
    }

    private String materialRefType(SubjectRefType type) {
        if (type == SubjectRefType.FORMAL_MATERIAL) return "LIVE_MATERIAL";
        if (type == SubjectRefType.MATERIAL_DRAFT) return "MATERIAL_DRAFT";
        return "INVALID";
    }

    private String materialKey(String type, String id) { return type + ":" + id; }
    private String specKey(SubjectType type, String refId, String itemCode) {
        return digest("QUALITY_SPEC", String.valueOf(type), refId, itemCode);
    }

    private String authoritySnapshot(BlueprintResponse response, Set<String> codes) {
        List<String> values = new ArrayList<>();
        values.add(response.getCapabilityCatalogVersion());
        values.add(response.getCapabilityCatalogDigest());
        codes.stream().sorted().forEach(values::add);
        return digest(values.toArray(String[]::new));
    }

    private String authorityEvidenceDigest(String version, String catalogDigest, String code) {
        return digest("CATALOG_AUTHORITY", version, catalogDigest, code);
    }

    private String digestBlueprint(BlueprintResponse response) {
        return digest("BLUEPRINT", response.getFactoryId(), response.getId(),
                String.valueOf(response.getVersion()), String.valueOf(response.getRowVersion()),
                response.getLifecycleState(), response.getSchemaVersion(),
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                String.valueOf(response.getUpdatedAt()));
    }

    private String criticalSnapshot(CriticalControlDraft parent,
                                    List<CriticalControlDraftItem> items,
                                    List<CriticalControlDraftEvidence> evidence) {
        List<String> values = new ArrayList<>();
        values.add(parent.getId()); values.add(parent.getDesiredDigest());
        values.add(parent.getPreviewDigest()); values.add(parent.getSourceSnapshotDigest());
        values.add(parent.getHazardSnapshotDigest()); values.add(parent.getAuthoritySnapshotDigest());
        values.add(parent.getFormalCheckpointSnapshotDigest());
        for (CriticalControlDraftItem item : items) values.add(controlSnapshot(item));
        evidence.stream().sorted(Comparator.comparing(CriticalControlDraftEvidence::getItemId)
                        .thenComparing(CriticalControlDraftEvidence::getAuthorityCode))
                .forEach(row -> { values.add(row.getItemId()); values.add(row.getAuthorityCode());
                    values.add(row.getAuthoritySnapshotDigest()); });
        return digest(values.toArray(String[]::new));
    }

    private String controlSnapshot(CriticalControlDraftItem item) {
        return digest("CONTROL_ITEM", item.getId(), item.getControlKey(), item.getRouteNodeId(),
                item.getRouteNodeLabel(), item.getBomDraftId(), item.getBomItemId(),
                item.getMaterialRefType(), item.getMaterialRefId(), item.getMaterialSnapshotDigest(),
                item.getControlClassification(), item.getCriterionType(), item.getParameterCode(),
                item.getParameterName(), item.getLimitKind(), decimal(item.getLowerLimit()),
                decimal(item.getUpperLimit()), item.getUnitCode(), item.getUnitSnapshotDigest(),
                item.getResponsibleRole(), item.getVerificationMethod(),
                String.valueOf(item.getRecordRetentionDays()));
    }

    private String bomSnapshot(BomRecipeDraft parent, List<BomRecipeDraftItem> items) {
        if (parent == null) return digest("MISSING_BOM");
        List<String> values = new ArrayList<>();
        values.add(parent.getId()); values.add(parent.getDesiredDigest());
        values.add(parent.getPreviewDigest()); values.add(parent.getSourceSnapshotDigest());
        values.add(parent.getTargetProductRefType()); values.add(parent.getTargetProductRefId());
        values.add(parent.getTargetProductSnapshotDigest());
        for (BomRecipeDraftItem item : items) {
            values.add(item.getId()); values.add(String.valueOf(item.getOrdinal()));
            values.add(item.getMaterialRefType()); values.add(item.getMaterialRefId());
            values.add(item.getMaterialSnapshotDigest()); values.add(item.getMaterialCategory());
            values.add(decimal(item.getStandardQuantity())); values.add(item.getUnit());
            values.add(String.valueOf(item.getOptional())); values.add(item.getWorkflowProcessNodeId());
        }
        return digest(values.toArray(String[]::new));
    }

    private String materialSnapshot(List<BomRecipeDraftItem> items) {
        List<String> values = new ArrayList<>();
        items.stream().sorted(Comparator.comparing(BomRecipeDraftItem::getOrdinal)).forEach(item -> {
            values.add(item.getId()); values.add(item.getMaterialRefType());
            values.add(item.getMaterialRefId()); values.add(item.getMaterialSnapshotDigest());
            values.add(item.getMaterialCategory()); values.add(String.valueOf(item.getOptional()));
        });
        return digest(values.toArray(String[]::new));
    }

    private String formalQualitySnapshot(String factoryId) {
        List<QualityCheckItem> system = new ArrayList<>(Objects.requireNonNullElse(
                formalQualityItems.findByFactoryIdIsNullAndEnabledTrueAndDeletedAtIsNull(), List.of()));
        List<QualityCheckItem> factory = new ArrayList<>(Objects.requireNonNullElse(
                formalQualityItems.findByFactoryIdAndEnabledTrueAndDeletedAtIsNull(factoryId), List.of()));
        List<QualityCheckItemBinding> bindings = new ArrayList<>(Objects.requireNonNullElse(
                formalQualityBindings.findByFactoryIdAndDeletedAtIsNull(factoryId), List.of()));
        Comparator<QualityCheckItem> itemOrder = Comparator.comparing(QualityCheckItem::getItemCode)
                .thenComparing(QualityCheckItem::getId);
        system.sort(itemOrder); factory.sort(itemOrder);
        bindings.sort(Comparator.comparing(QualityCheckItemBinding::getProductTypeId)
                .thenComparing(QualityCheckItemBinding::getQualityCheckItemId)
                .thenComparing(QualityCheckItemBinding::getId));
        List<String> values = new ArrayList<>(); values.add(factoryId);
        system.forEach(row -> addFormalItem(values, "PLATFORM_DEFAULT", row));
        factory.forEach(row -> addFormalItem(values, "FACTORY", row));
        for (QualityCheckItemBinding row : bindings) {
            values.add(row.getId()); values.add(row.getFactoryId()); values.add(row.getProductTypeId());
            values.add(row.getQualityCheckItemId()); values.add(row.getOverrideStandardValue());
            values.add(decimal(row.getOverrideMinValue())); values.add(decimal(row.getOverrideMaxValue()));
            values.add(decimal(row.getOverrideSamplingRatio()));
            values.add(String.valueOf(row.getOverrideIsRequired())); values.add(String.valueOf(row.getSortOrder()));
            values.add(String.valueOf(row.getEnabled())); values.add(row.getNotes());
            values.add(String.valueOf(row.getUpdatedAt()));
        }
        return digest(values.toArray(String[]::new));
    }

    private void addFormalItem(List<String> values, String scope, QualityCheckItem row) {
        values.add(scope); values.add(row.getId()); values.add(row.getFactoryId());
        values.add(row.getItemCode()); values.add(row.getItemName());
        values.add(String.valueOf(row.getCategory())); values.add(row.getDescription());
        values.add(row.getCheckMethod()); values.add(row.getStandardReference());
        values.add(row.getValueType()); values.add(row.getStandardValue());
        values.add(decimal(row.getMinValue())); values.add(decimal(row.getMaxValue()));
        values.add(row.getUnit()); values.add(decimal(row.getTolerance()));
        values.add(String.valueOf(row.getSamplingStrategy())); values.add(decimal(row.getSamplingRatio()));
        values.add(String.valueOf(row.getMinSampleSize())); values.add(decimal(row.getAqlLevel()));
        values.add(String.valueOf(row.getSeverity())); values.add(String.valueOf(row.getIsRequired()));
        values.add(String.valueOf(row.getRequirePhotoOnFail()));
        values.add(String.valueOf(row.getRequireNoteOnFail())); values.add(String.valueOf(row.getVersion()));
        values.add(String.valueOf(row.getUpdatedAt()));
    }

    private String unitSnapshot(CanonicalUnit unit) {
        return digest("CANONICAL_UNIT", unit.code(), unit.dimension().name(), unit.baseCode(),
                decimal(unit.factorToBase()), unit.displayName(), String.valueOf(unit.displayScale()),
                unit.conversionFamily());
    }

    private String desiredDigest(DraftRequest request) {
        List<String> values = new ArrayList<>();
        values.add(QualityReleaseTemplateDraftModels.SCHEMA_VERSION);
        values.add(QualityReleaseTemplateDraftModels.DECISION_ALGORITHM_VERSION);
        values.add(request.factoryId()); values.add(request.blueprintId());
        values.add(String.valueOf(request.blueprintVersion())); values.add(request.sourceDigest());
        values.add(request.criticalControl().draftId()); values.add(request.criticalControl().desiredDigest());
        for (InspectionSpec item : request.items()) {
            if (item == null) { values.add("NULL_ITEM"); continue; }
            values.add(String.valueOf(item.subjectType())); values.add(String.valueOf(item.subjectRefType()));
            values.add(item.subjectRefId()); values.add(item.linkedCriticalControlItemId());
            values.add(item.itemCode()); values.add(item.itemName()); values.add(String.valueOf(item.category()));
            values.add(String.valueOf(item.valueType())); values.add(criterionKind(item));
            values.add(decimal(item.lowerValue())); values.add(decimal(item.upperValue()));
            values.add(item.expectedText()); values.add(String.valueOf(item.expectedBoolean()));
            values.add(item.unit()); values.add(decimal(item.tolerance()));
            values.add(item.standardReference()); values.add(item.inspectionMethod());
            values.add(String.valueOf(item.samplingStrategy())); values.add(decimal(item.samplingRatio()));
            values.add(String.valueOf(item.minimumSampleSize())); values.add(decimal(item.aqlLevel()));
            values.add(String.valueOf(item.severity()));
            values.add(item.severity() == null ? "" : QualityReleaseTemplateDraftModels
                    .releaseEffect(item.severity()).name());
            values.add(String.valueOf(item.required()));
            values.add(String.valueOf(item.photoRequiredOnFailure()));
            values.add(String.valueOf(item.noteRequiredOnFailure()));
            values.add(String.valueOf(item.responsibleRole()));
            values.add(String.valueOf(item.recordRetentionDays()));
            sortedDistinct(item.authorityCodes()).forEach(values::add); values.add(item.changeReason());
        }
        ReleasePolicy policy = request.releasePolicy();
        if (policy == null) values.add("NULL_POLICY");
        else {
            values.add(String.valueOf(policy.maximumMajorFailuresForConditionalReview()));
            values.add(String.valueOf(policy.maximumMinorFailuresForConditionalReview()));
            values.add(String.valueOf(policy.approvalRole()));
            values.add(String.valueOf(policy.recordRetentionDays())); values.add(policy.changeReason());
        }
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        return digest(request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), desiredDigest(request));
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream().map(issue -> issue.code() + "|" + issue.target() + "|"
                + issue.message() + "|" + issue.recovery()).toArray(String[]::new));
    }

    private Map<String, List<BomRecipeDraftItem>> immutableLists(
            Map<String, List<BomRecipeDraftItem>> source) {
        Map<String, List<BomRecipeDraftItem>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private boolean validDecimal(BigDecimal value) {
        return value == null || (value.precision() <= 19 && value.scale() <= 6);
    }

    private boolean validSamplingDecimal(BigDecimal value) {
        return value == null || (value.precision() <= 7 && value.scale() <= 4);
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
                    .contains("uk_quality_release_draft_idempotency")) return true;
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
                              UpstreamResolution upstream, List<ItemResolution> items) {}

    private record BlueprintResolution(String catalogVersion, String catalogDigest,
                                       Set<String> authorityCodes, String authoritySnapshotDigest,
                                       String snapshotDigest) {
        static BlueprintResolution missing() {
            return new BlueprintResolution("", "", Set.of(), digestStatic("MISSING_AUTHORITY"),
                    digestStatic("MISSING_BLUEPRINT"));
        }
    }

    private record UpstreamResolution(CriticalControlDraft parent,
                                      Map<String, CriticalControlDraftItem> controls,
                                      BomRecipeDraft bom,
                                      List<BomRecipeDraftItem> materials,
                                      Map<String, List<BomRecipeDraftItem>> materialsByKey,
                                      String criticalSnapshotDigest,
                                      String bomSnapshotDigest,
                                      String materialSnapshotDigest) {
        static UpstreamResolution missing() {
            return new UpstreamResolution(null, Map.of(), null, List.of(), Map.of(),
                    digestStatic("MISSING_CRITICAL_CONTROL"), digestStatic("MISSING_BOM"),
                    digestStatic("MISSING_MATERIALS"));
        }
    }

    private record SubjectResolution(String snapshotDigest, String bomItemId,
                                     String routeNodeId, String routeNodeLabel) {
        static SubjectResolution missing() {
            return new SubjectResolution(digestStatic("MISSING_SUBJECT"), null, null, null);
        }
    }

    private record ItemResolution(InspectionSpec desired, SubjectResolution subject,
                                  CriticalControlDraftItem linkedControl, CanonicalUnit unit,
                                  String criterionKind, ReleaseEffect releaseEffect,
                                  String specKey) {
        static ItemResolution missing() {
            return new ItemResolution(null, SubjectResolution.missing(), null, null,
                    "INVALID", ReleaseEffect.ADVISORY, digestStatic("MISSING_SPEC"));
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



