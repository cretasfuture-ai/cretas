package com.cretas.aims.service.deviationcapadraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraft;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraftEvidence;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraftItem;
import com.cretas.aims.entity.deviationcapadraft.DeviationCapaTemplateDraft;
import com.cretas.aims.entity.deviationcapadraft.DeviationCapaTemplateDraftEvidence;
import com.cretas.aims.entity.deviationcapadraft.DeviationCapaTemplateDraftRule;
import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraft;
import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraftEvidence;
import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraftItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.ApprovalChainConfigRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftEvidenceRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftItemRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftRepository;
import com.cretas.aims.repository.deviationcapadraft.DeviationCapaTemplateDraftEvidenceRepository;
import com.cretas.aims.repository.deviationcapadraft.DeviationCapaTemplateDraftRepository;
import com.cretas.aims.repository.deviationcapadraft.DeviationCapaTemplateDraftRuleRepository;
import com.cretas.aims.repository.qualityreleasedraft.QualityReleaseTemplateDraftEvidenceRepository;
import com.cretas.aims.repository.qualityreleasedraft.QualityReleaseTemplateDraftItemRepository;
import com.cretas.aims.repository.qualityreleasedraft.QualityReleaseTemplateDraftRepository;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftCommand;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.ApprovalRoute;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.Classification;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.CorrectionTemplateCode;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.CorrectiveActionCode;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.DraftIssue;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.DraftPreview;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.DraftReadback;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.DraftReceipt;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.DraftRequest;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.DerivedRule;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.EffectivenessMetricCode;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.ImmediateControlAction;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.PreventiveActionCode;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.RecoveryPlan;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.RuleSpec;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.Severity;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.SourceType;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.TemplatePolicy;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.TriggerMode;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftCommand;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Deterministic module-owned deviation/CAPA TEMPLATE/DRAFT command.
 *
 * <p>The command reads current B6/B7 drafts and formal approval configuration, then writes
 * only its three typed Blueprint draft tables. It never opens a deviation/CAPA case, changes
 * a batch or inventory state, executes disposition, starts approval, or emits alert/notice/
 * recall output.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class DeviationCapaTemplateDraftCommand {
    public static final String CAPABILITY_ID = "deviation-capa";
    public static final String REQUIRED_PERMISSION = "quality:capa:template:draft";
    private static final int MAX_RULES = 2000;
    private static final int MAX_EVIDENCE_PER_RULE = 20;

    private final DeviationCapaTemplateDraftRepository drafts;
    private final DeviationCapaTemplateDraftRuleRepository draftRules;
    private final DeviationCapaTemplateDraftEvidenceRepository draftEvidence;
    private final CriticalControlDraftRepository criticalDrafts;
    private final CriticalControlDraftItemRepository criticalItems;
    private final CriticalControlDraftEvidenceRepository criticalEvidence;
    private final CriticalControlDraftCommand criticalCommand;
    private final QualityReleaseTemplateDraftRepository qualityDrafts;
    private final QualityReleaseTemplateDraftItemRepository qualityItems;
    private final QualityReleaseTemplateDraftEvidenceRepository qualityEvidence;
    private final QualityReleaseTemplateDraftCommand qualityCommand;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final ApprovalChainConfigRepository approvalConfigs;
    private final UserRepository users;

    public DeviationCapaTemplateDraftCommand(
            DeviationCapaTemplateDraftRepository drafts,
            DeviationCapaTemplateDraftRuleRepository draftRules,
            DeviationCapaTemplateDraftEvidenceRepository draftEvidence,
            CriticalControlDraftRepository criticalDrafts,
            CriticalControlDraftItemRepository criticalItems,
            CriticalControlDraftEvidenceRepository criticalEvidence,
            CriticalControlDraftCommand criticalCommand,
            QualityReleaseTemplateDraftRepository qualityDrafts,
            QualityReleaseTemplateDraftItemRepository qualityItems,
            QualityReleaseTemplateDraftEvidenceRepository qualityEvidence,
            QualityReleaseTemplateDraftCommand qualityCommand,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            ApprovalChainConfigRepository approvalConfigs,
            UserRepository users) {
        this.drafts = drafts;
        this.draftRules = draftRules;
        this.draftEvidence = draftEvidence;
        this.criticalDrafts = criticalDrafts;
        this.criticalItems = criticalItems;
        this.criticalEvidence = criticalEvidence;
        this.criticalCommand = criticalCommand;
        this.qualityDrafts = qualityDrafts;
        this.qualityItems = qualityItems;
        this.qualityEvidence = qualityEvidence;
        this.qualityCommand = qualityCommand;
        this.canonicalBlueprints = canonicalBlueprints;
        this.approvalConfigs = approvalConfigs;
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
        Optional<DeviationCapaTemplateDraft> prior = drafts.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同偏差/CAPA 模板请求",
                        "DEVIATION_CAPA_DRAFT_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview preview = evaluation.preview();
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "偏差/CAPA 模板预览被阻断",
                    "DEVIATION_CAPA_DRAFT_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "偏差/CAPA 模板确认摘要已漂移",
                    "DEVIATION_CAPA_DRAFT_PREVIEW_DRIFT");
        }

        int evidenceCount = request.rules().stream()
                .mapToInt(rule -> sortedDistinct(rule.authorityCodes()).size()).sum();
        UpstreamResolution upstream = evaluation.upstream();
        TemplatePolicy policy = request.policy();
        DeviationCapaTemplateDraft parent = DeviationCapaTemplateDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .criticalControlDraftId(upstream.critical().getId())
                .criticalControlDesiredDigest(upstream.critical().getDesiredDigest())
                .criticalControlSnapshotDigest(preview.criticalControlSnapshotDigest())
                .qualityReleaseTemplateDraftId(upstream.quality().getId())
                .qualityReleaseDesiredDigest(upstream.quality().getDesiredDigest())
                .qualityReleaseSnapshotDigest(preview.qualityReleaseSnapshotDigest())
                .capabilityCatalogVersion(evaluation.blueprint().catalogVersion())
                .capabilityCatalogDigest(evaluation.blueprint().catalogDigest())
                .authoritySnapshotDigest(preview.authoritySnapshotDigest())
                .formalDispositionSnapshotDigest(preview.formalDispositionSnapshotDigest())
                .classificationAlgorithmVersion(
                        DeviationCapaTemplateDraftModels.DECISION_ALGORITHM_VERSION)
                .deviationSlaHours(policy.deviationSlaHours())
                .nonConformanceSlaHours(policy.nonConformanceSlaHours())
                .capaSlaHours(policy.capaSlaHours())
                .rootCauseMethod(policy.rootCauseMethod().name())
                .capaEffectivenessReviewDays(policy.capaEffectivenessReviewDays())
                .recordRetentionDays(policy.recordRetentionDays())
                .policyChangeReason(policy.changeReason())
                .desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest())
                .requestDigest(requestDigest)
                .ruleCount(request.rules().size())
                .evidenceCount(evidenceCount)
                .dependencyBasis(DeviationCapaTemplateDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .status("DRAFT")
                .build();

        DeviationCapaTemplateDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "偏差/CAPA 模板幂等键发生并发竞争",
                    "DEVIATION_CAPA_DRAFT_IDEMPOTENCY_RACE");
        }

        List<DeviationCapaTemplateDraftRule> ruleRows = new ArrayList<>();
        List<DeviationCapaTemplateDraftEvidence> evidenceRows = new ArrayList<>();
        for (int index = 0; index < evaluation.rules().size(); index++) {
            RuleResolution resolved = evaluation.rules().get(index);
            DeviationCapaTemplateDraftRule ruleRow = toRow(saved.getId(), request.factoryId(),
                    upstream.critical().getId(), upstream.quality().getId(), index, resolved);
            ruleRows.add(ruleRow);
            for (String authorityCode : sortedDistinct(resolved.desired().authorityCodes())) {
                evidenceRows.add(DeviationCapaTemplateDraftEvidence.builder()
                        .id(UUID.randomUUID().toString())
                        .ruleId(ruleRow.getId())
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
        draftRules.saveAllAndFlush(ruleRows);
        draftEvidence.saveAllAndFlush(evidenceRows);

        DraftReadback readback = readBack(saved.getFactoryId(), saved.getId());
        if (!preview.desiredDigest().equals(desiredDigest(readback.desired()))) {
            throw invalid(409, "偏差/CAPA 模板逐字段回读不一致",
                    "DEVIATION_CAPA_DRAFT_READBACK_MISMATCH");
        }
        DraftPreview current = evaluate(request).preview();
        if (!sameAuthority(preview, current)) {
            throw invalid(409, "偏差/CAPA 模板写入期间依赖发生漂移",
                    "DEVIATION_CAPA_DRAFT_AUTHORITY_DRIFT");
        }
        return receipt(saved, false, 1, 1 + ruleRows.size() + evidenceRows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        DeviationCapaTemplateDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该偏差/CAPA 模板草稿",
                        "DEVIATION_CAPA_DRAFT_NOT_FOUND"));
        List<DeviationCapaTemplateDraftRule> rows = draftRules
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<DeviationCapaTemplateDraftEvidence> evidence = draftEvidence
                .findByDraftIdAndFactoryIdOrderByRuleIdAscAuthorityCodeAsc(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !DeviationCapaTemplateDraftModels.DEPENDENCY_BASIS.equals(
                        parent.getDependencyBasis())
                || !DeviationCapaTemplateDraftModels.DECISION_ALGORITHM_VERSION.equals(
                        parent.getClassificationAlgorithmVersion())
                || parent.getRuleCount() != rows.size() || parent.getEvidenceCount() != evidence.size()) {
            throw invalid(409, "偏差/CAPA 模板父子行、状态或算法不一致",
                    "DEVIATION_CAPA_DRAFT_READBACK_MISMATCH");
        }
        Map<String, List<String>> authorityByRule = new HashMap<>();
        for (DeviationCapaTemplateDraftEvidence row : evidence) {
            if (!"CATALOG_AUTHORITY".equals(row.getEvidenceType())
                    || !row.getAuthoritySnapshotDigest().equals(authorityEvidenceDigest(
                            parent.getCapabilityCatalogVersion(), parent.getCapabilityCatalogDigest(),
                            row.getAuthorityCode()))) {
                throw invalid(409, "偏差/CAPA 模板依据摘要不一致",
                        "DEVIATION_CAPA_DRAFT_EVIDENCE_MISMATCH");
            }
            authorityByRule.computeIfAbsent(row.getRuleId(), ignored -> new ArrayList<>())
                    .add(row.getAuthorityCode());
        }
        List<RuleSpec> desiredRules = rows.stream()
                .map(row -> fromRow(row, authorityByRule.getOrDefault(row.getId(), List.of())))
                .toList();
        TemplatePolicy policy = new TemplatePolicy(parent.getDeviationSlaHours(),
                parent.getNonConformanceSlaHours(), parent.getCapaSlaHours(),
                DeviationCapaTemplateDraftModels.RootCauseMethod.valueOf(
                        parent.getRootCauseMethod()),
                parent.getCapaEffectivenessReviewDays(), parent.getRecordRetentionDays(),
                parent.getPolicyChangeReason());
        Long readbackOperatorId = parent.getOperatorId() == null ? parent.getActorId() : parent.getOperatorId();
        if (readbackOperatorId == null) throw invalid(409, "草稿缺少 typed actor 身份，无法安全回读", "DRAFT_ACTOR_IDENTITY_MISSING");
        DraftRequest desired = new DraftRequest(parent.getFactoryId(), parent.getBlueprintId(),
                parent.getBlueprintVersion(), parent.getSourceDigest(),
                new DeviationCapaTemplateDraftModels.CriticalControlPin(
                        parent.getCriticalControlDraftId(), parent.getCriticalControlDesiredDigest()),
                new DeviationCapaTemplateDraftModels.QualityReleasePin(
                        parent.getQualityReleaseTemplateDraftId(),
                        parent.getQualityReleaseDesiredDigest()),
                desiredRules, policy, parent.getPreviewDigest(), parent.getIdempotencyKey(),
                readbackOperatorId);
        Evaluation current = evaluate(desired);
        if (!"READY".equals(current.preview().status())
                || !parent.getDesiredDigest().equals(desiredDigest(desired))
                || !parent.getSourceSnapshotDigest().equals(current.preview().sourceSnapshotDigest())
                || !parent.getCriticalControlSnapshotDigest().equals(
                        current.preview().criticalControlSnapshotDigest())
                || !parent.getQualityReleaseSnapshotDigest().equals(
                        current.preview().qualityReleaseSnapshotDigest())
                || !parent.getAuthoritySnapshotDigest().equals(
                        current.preview().authoritySnapshotDigest())
                || !parent.getFormalDispositionSnapshotDigest().equals(
                        current.preview().formalDispositionSnapshotDigest())
                || !parent.getPreviewDigest().equals(current.preview().previewDigest())
                || current.rules().size() != rows.size()) {
            throw invalid(409, "偏差/CAPA 模板当前依赖或逐字段回读已漂移",
                    "DEVIATION_CAPA_DRAFT_AUTHORITY_DRIFT");
        }
        for (int index = 0; index < rows.size(); index++) {
            if (!matches(rows.get(index), current.rules().get(index), parent, index)) {
                throw invalid(409, "偏差/CAPA 模板派生字段被篡改",
                        "DEVIATION_CAPA_DRAFT_READBACK_MISMATCH");
            }
        }
        return new DraftReadback(DeviationCapaTemplateDraftModels.SCHEMA_VERSION,
                factoryId, draftId, parent.getStatus(), desired,
                current.rules().stream().map(RuleResolution::derived).toList(),
                0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recovery(String factoryId, String draftId, String expectedDesiredDigest) {
        DraftReadback readback = readBack(factoryId, draftId);
        String actual = desiredDigest(readback.desired());
        if (!actual.equals(expectedDesiredDigest)) {
            throw invalid(409, "恢复前偏差/CAPA 模板摘要不一致",
                    "DEVIATION_CAPA_DRAFT_RECOVERY_DIGEST_CONFLICT");
        }
        return new RecoveryPlan(DeviationCapaTemplateDraftModels.SCHEMA_VERSION,
                factoryId, draftId, expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "仅归档未发布模板草稿；不创建偏差/CAPA 案件，不改变质检、批次、库存、审批、告警或召回",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintResolution blueprint = resolveBlueprint(request, issues);
        UpstreamResolution upstream = resolveUpstream(request, issues);
        List<ApprovalChainConfig> approvalRows = approvalConfigs
                .findByFactoryIdOrderByDecisionTypeAscPriorityDesc(request.factoryId());
        if (approvalRows == null) approvalRows = List.of();
        String formalSnapshot = formalDispositionSnapshot(approvalRows);
        List<RuleResolution> resolved = new ArrayList<>();
        Set<String> ruleKeys = new HashSet<>();
        for (int index = 0; index < request.rules().size(); index++) {
            RuleSpec rule = request.rules().get(index);
            String target = "rules[" + index + "]";
            validateRuleShape(rule, target, blueprint, issues);
            RuleResolution resolution = resolveRule(rule, request.policy(), target, upstream, issues);
            if (!ruleKeys.add(resolution.ruleKey())) {
                issues.add(issue("DEVIATION_CAPA_RULE_DUPLICATE", target,
                        "同一来源和触发模式不能重复", "合并重复模板规则"));
            }
            resolved.add(resolution);
        }
        validatePolicy(request.policy(), issues);
        validateCoverage(upstream, resolved, issues);
        validateApprovalRoutes(resolved, approvalRows, issues);

        String desiredDigest = desiredDigest(request);
        String sourceSnapshot = digest("DEVIATION_CAPA_SOURCE", blueprint.snapshotDigest(),
                upstream.criticalSnapshotDigest(), upstream.qualitySnapshotDigest(), formalSnapshot);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("DEVIATION_CAPA_PREVIEW",
                DeviationCapaTemplateDraftModels.SCHEMA_VERSION,
                DeviationCapaTemplateDraftModels.DECISION_ALGORITHM_VERSION,
                status, sourceSnapshot, upstream.criticalSnapshotDigest(),
                upstream.qualitySnapshotDigest(), blueprint.authoritySnapshotDigest(),
                formalSnapshot, desiredDigest, issueDigest(issues));
        DraftPreview preview = new DraftPreview(
                DeviationCapaTemplateDraftModels.SCHEMA_VERSION, status, request.factoryId(),
                List.of("critical-controls", "quality-release"), request,
                resolved.stream().map(RuleResolution::derived).toList(), sourceSnapshot,
                upstream.criticalSnapshotDigest(), upstream.qualitySnapshotDigest(),
                blueprint.authoritySnapshotDigest(), formalSnapshot, desiredDigest,
                previewDigest, issues, 0, 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, blueprint, upstream, List.copyOf(resolved));
    }

    private BlueprintResolution resolveBlueprint(DraftRequest request, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(request.factoryId());
        if (response == null || !request.blueprintId().equals(response.getId())
                || !Objects.equals(Math.toIntExact(request.blueprintVersion()), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                    || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("DEVIATION_CAPA_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
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
            issues.add(issue("DEVIATION_CAPA_CAPABILITY_NOT_READY", "capability",
                    "deviation-capa 能力或 critical-controls/quality-release 依赖尚未就绪",
                    "补齐当前模块草稿后重新编译 Blueprint"));
        }
        Set<String> authorityCodes = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || authorityCodes.isEmpty()) {
            issues.add(issue("DEVIATION_CAPA_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本、摘要或偏差/CAPA 依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        return new BlueprintResolution(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), authorityCodes,
                authoritySnapshot(response, authorityCodes), digestBlueprint(response));
    }

    private UpstreamResolution resolveUpstream(DraftRequest request, List<DraftIssue> issues) {
        CriticalControlDraft critical = request.criticalControl() == null ? null
                : criticalDrafts.findByIdAndFactoryId(request.criticalControl().draftId(),
                        request.factoryId()).orElse(null);
        QualityReleaseTemplateDraft quality = request.qualityRelease() == null ? null
                : qualityDrafts.findByIdAndFactoryId(request.qualityRelease().draftId(),
                        request.factoryId()).orElse(null);
        if (!validCritical(request, critical)) {
            issues.add(issue("DEVIATION_CAPA_CRITICAL_CONTROL_DRIFT", "criticalControl",
                    "critical-controls DRAFT 不存在、lineage 不一致或摘要已变化",
                    "选择同 Blueprint 的当前 critical-controls DRAFT"));
            return UpstreamResolution.missing();
        }
        if (!validQuality(request, quality, critical)) {
            issues.add(issue("DEVIATION_CAPA_QUALITY_RELEASE_DRIFT", "qualityRelease",
                    "quality-release DRAFT 不存在、上游 B6 不一致或摘要已变化",
                    "选择同 Blueprint 且绑定当前 B6 的 quality-release DRAFT"));
            return UpstreamResolution.missing();
        }
        try {
            var readback = criticalCommand.readBack(request.factoryId(), critical.getId());
            var current = criticalCommand.preview(readback.desired());
            if (!"READY".equals(current.status())
                    || !critical.getDesiredDigest().equals(current.desiredDigest())
                    || !critical.getSourceSnapshotDigest().equals(current.sourceSnapshotDigest())
                    || !critical.getPreviewDigest().equals(current.previewDigest())) {
                issues.add(issue("DEVIATION_CAPA_CRITICAL_CONTROL_AUTHORITY_BLOCKED",
                        "criticalControl", "critical-controls DRAFT 当前依赖已漂移或不能回读",
                        "重新生成 readback-ready critical-controls DRAFT"));
            }
        } catch (BusinessException exception) {
            issues.add(issue("DEVIATION_CAPA_CRITICAL_CONTROL_AUTHORITY_BLOCKED",
                    "criticalControl", "critical-controls DRAFT 当前不能逐字段回读",
                    "修复或重新生成 critical-controls DRAFT"));
        }
        try {
            var readback = qualityCommand.readBack(request.factoryId(), quality.getId());
            var current = qualityCommand.preview(readback.desired());
            if (!"READY".equals(current.status())
                    || !quality.getDesiredDigest().equals(current.desiredDigest())
                    || !quality.getSourceSnapshotDigest().equals(current.sourceSnapshotDigest())
                    || !quality.getCriticalControlSnapshotDigest().equals(
                            current.criticalControlSnapshotDigest())
                    || !quality.getAuthoritySnapshotDigest().equals(current.authoritySnapshotDigest())
                    || !quality.getFormalQualitySnapshotDigest().equals(
                            current.formalQualitySnapshotDigest())
                    || !quality.getPreviewDigest().equals(current.previewDigest())) {
                issues.add(issue("DEVIATION_CAPA_QUALITY_RELEASE_AUTHORITY_BLOCKED",
                        "qualityRelease", "quality-release DRAFT 当前依赖已漂移或不能回读",
                        "重新生成 readback-ready quality-release DRAFT"));
            }
        } catch (BusinessException exception) {
            issues.add(issue("DEVIATION_CAPA_QUALITY_RELEASE_AUTHORITY_BLOCKED",
                    "qualityRelease", "quality-release DRAFT 当前不能逐字段回读",
                    "修复或重新生成 quality-release DRAFT"));
        }

        List<CriticalControlDraftItem> controls = criticalItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(critical.getId(), request.factoryId());
        List<CriticalControlDraftEvidence> controlEvidence = criticalEvidence
                .findByDraftIdAndFactoryIdOrderByItemIdAscAuthorityCodeAsc(
                        critical.getId(), request.factoryId());
        List<QualityReleaseTemplateDraftItem> specs = qualityItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(quality.getId(), request.factoryId());
        List<QualityReleaseTemplateDraftEvidence> specEvidence = qualityEvidence
                .findByDraftIdAndFactoryIdOrderByItemIdAscAuthorityCodeAsc(
                        quality.getId(), request.factoryId());
        if (critical.getItemCount() != controls.size()
                || critical.getEvidenceCount() != controlEvidence.size()
                || quality.getItemCount() != specs.size()
                || quality.getEvidenceCount() != specEvidence.size()) {
            issues.add(issue("DEVIATION_CAPA_UPSTREAM_ROW_COUNT_MISMATCH", "dependencies",
                    "B6/B7 父子行数量不一致", "修复上游草稿后重新预览"));
        }
        Map<String, CriticalControlDraftItem> controlsById = new LinkedHashMap<>();
        controls.forEach(row -> controlsById.putIfAbsent(row.getId(), row));
        Map<String, QualityReleaseTemplateDraftItem> specsById = new LinkedHashMap<>();
        specs.forEach(row -> specsById.putIfAbsent(row.getId(), row));
        return new UpstreamResolution(critical, quality, Map.copyOf(controlsById),
                Map.copyOf(specsById), criticalSnapshot(critical, controls, controlEvidence),
                qualitySnapshot(quality, specs, specEvidence));
    }

    private boolean validCritical(DraftRequest request, CriticalControlDraft parent) {
        return parent != null && "DRAFT".equals(parent.getStatus())
                && Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                && CriticalControlDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                && CriticalControlDraftModels.DECISION_ALGORITHM_VERSION.equals(
                        parent.getDecisionAlgorithmVersion())
                && request.blueprintId().equals(parent.getBlueprintId())
                && request.blueprintVersion() == parent.getBlueprintVersion()
                && request.sourceDigest().equals(parent.getSourceDigest())
                && request.criticalControl().desiredDigest().equals(parent.getDesiredDigest());
    }

    private boolean validQuality(DraftRequest request, QualityReleaseTemplateDraft parent,
                                 CriticalControlDraft critical) {
        return parent != null && "DRAFT".equals(parent.getStatus())
                && Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                && QualityReleaseTemplateDraftModels.DEPENDENCY_BASIS.equals(
                        parent.getDependencyBasis())
                && QualityReleaseTemplateDraftModels.DECISION_ALGORITHM_VERSION.equals(
                        parent.getDecisionAlgorithmVersion())
                && request.blueprintId().equals(parent.getBlueprintId())
                && request.blueprintVersion() == parent.getBlueprintVersion()
                && request.sourceDigest().equals(parent.getSourceDigest())
                && request.qualityRelease().desiredDigest().equals(parent.getDesiredDigest())
                && critical.getId().equals(parent.getCriticalControlDraftId())
                && critical.getDesiredDigest().equals(parent.getCriticalControlDesiredDigest());
    }

    private RuleResolution resolveRule(RuleSpec rule, TemplatePolicy policy, String target,
                                       UpstreamResolution upstream, List<DraftIssue> issues) {
        if (rule == null || rule.sourceType() == null) return RuleResolution.missing();
        CriticalControlDraftItem control = null;
        QualityReleaseTemplateDraftItem spec = null;
        Severity severity = Severity.MINOR;
        String sourceSnapshot = digest("MISSING_SOURCE");
        if (rule.sourceType() == SourceType.CRITICAL_CONTROL) {
            control = upstream.controls().get(rule.sourceItemId());
            if (control == null) {
                issues.add(issue("DEVIATION_CAPA_CONTROL_SOURCE_NOT_FOUND", target,
                        "规则引用的 B6 control item 不存在", "选择当前 B6 的 control item"));
            } else {
                severity = controlSeverity(control);
                sourceSnapshot = controlSnapshot(control);
            }
        } else {
            spec = upstream.qualitySpecs().get(rule.sourceItemId());
            if (spec == null) {
                issues.add(issue("DEVIATION_CAPA_QUALITY_SOURCE_NOT_FOUND", target,
                        "规则引用的 B7 quality spec 不存在", "选择当前 B7 的 quality spec"));
            } else {
                try {
                    severity = Severity.valueOf(spec.getSeverity());
                } catch (RuntimeException exception) {
                    issues.add(issue("DEVIATION_CAPA_QUALITY_SEVERITY_INVALID", target,
                            "B7 quality spec severity 无效", "重新生成 B7 草稿"));
                }
                sourceSnapshot = qualitySpecSnapshot(spec);
            }
        }
        DerivedRule derived = derive(rule, policy, severity);
        return new RuleResolution(rule, control, spec, sourceSnapshot, derived,
                ruleKey(rule.sourceType(), rule.sourceItemId(), rule.triggerMode()));
    }

    private static DerivedRule derive(RuleSpec rule, TemplatePolicy policy, Severity severity) {
        TriggerMode mode = rule == null || rule.triggerMode() == null
                ? TriggerMode.SINGLE_EVENT : rule.triggerMode();
        Classification classification = mode == TriggerMode.REPEAT_EVENT ? Classification.CAPA
                : severity == Severity.CRITICAL ? Classification.DEVIATION
                : Classification.NON_CONFORMANCE;
        ImmediateControlAction immediate = severity == Severity.CRITICAL
                ? ImmediateControlAction.HOLD_AFFECTED_PRODUCT
                : ImmediateControlAction.ESCALATE_AND_ASSESS;
        ApprovalRoute approval = classification == Classification.CAPA
                ? ApprovalRoute.QUALITY_THEN_FACTORY_OWNER
                : classification == Classification.DEVIATION
                ? ApprovalRoute.QUALITY_THEN_PRODUCTION_OWNER : ApprovalRoute.QUALITY_OWNER;
        int sla = policy == null ? 0 : classification == Classification.CAPA
                ? policy.capaSlaHours() : classification == Classification.DEVIATION
                ? policy.deviationSlaHours() : policy.nonConformanceSlaHours();
        boolean capa = classification == Classification.CAPA;
        boolean deviation = classification == Classification.DEVIATION;
        SourceType source = rule == null || rule.sourceType() == null
                ? SourceType.CRITICAL_CONTROL : rule.sourceType();
        boolean control = source == SourceType.CRITICAL_CONTROL;
        return new DerivedRule(rule, severity, classification, immediate, approval, sla,
                capa || deviation, true, capa, capa,
                capa || deviation ? policy.rootCauseMethod() : null,
                capa ? policy.capaEffectivenessReviewDays() : null,
                control ? CorrectionTemplateCode.STOP_AND_CONTAIN
                        : CorrectionTemplateCode.SEGREGATE_AND_ASSESS,
                control ? CorrectiveActionCode.RESTORE_CONTROL
                        : CorrectiveActionCode.REVISE_PROCESS_CONTROL,
                capa ? control ? PreventiveActionCode.SYSTEMIC_CONTROL_REDESIGN
                        : PreventiveActionCode.AUDIT_EXPANSION : null,
                capa ? control ? EffectivenessMetricCode.CONTROL_COMPLIANCE
                        : EffectivenessMetricCode.DEFECT_RATE_REDUCTION : null);
    }

    private void validateRuleShape(RuleSpec rule, String target, BlueprintResolution blueprint,
                                   List<DraftIssue> issues) {
        if (rule == null || rule.sourceType() == null || rule.triggerMode() == null
                || isBlankOrTooLong(rule.sourceItemId(), 36)) {
            issues.add(issue("DEVIATION_CAPA_RULE_REQUIRED_FIELDS", target,
                    "sourceType、sourceItemId 和 triggerMode 必填", "补齐 typed 规则来源"));
            return;
        }
        if (rule.triggerMode() == TriggerMode.SINGLE_EVENT
                && (rule.repeatThreshold() != null || rule.recurrenceWindowDays() != null)) {
            issues.add(issue("DEVIATION_CAPA_SINGLE_EVENT_SHAPE", target,
                    "SINGLE_EVENT 不能携带重复阈值", "清空 repeatThreshold/window"));
        }
        if (rule.triggerMode() == TriggerMode.REPEAT_EVENT
                && (rule.repeatThreshold() == null || rule.repeatThreshold() < 2
                    || rule.repeatThreshold() > 100 || rule.recurrenceWindowDays() == null
                    || rule.recurrenceWindowDays() < 1 || rule.recurrenceWindowDays() > 3650)) {
            issues.add(issue("DEVIATION_CAPA_REPEAT_EVENT_SHAPE", target,
                    "REPEAT_EVENT 需要 2..100 次和 1..3650 天窗口",
                    "填写封闭范围内的重复阈值"));
        }
        List<String> authority = sortedDistinct(rule.authorityCodes());
        if (authority.isEmpty() || authority.size() > MAX_EVIDENCE_PER_RULE
                || authority.stream().anyMatch(code -> isBlankOrTooLong(code, 100)
                        || !blueprint.authorityCodes().contains(code))) {
            issues.add(issue("DEVIATION_CAPA_AUTHORITY_INVALID", target,
                    "每条规则必须引用当前 capability catalog 中 1..20 个依据",
                    "选择当前 deviation-capa authority refs"));
        }
        if (isBlankOrTooLong(rule.changeReason(), 500)) {
            issues.add(issue("DEVIATION_CAPA_CHANGE_REASON_REQUIRED", target,
                    "规则 changeReason 必填且不超过 500 字", "说明为什么需要该规则"));
        }
    }

    private void validatePolicy(TemplatePolicy policy, List<DraftIssue> issues) {
        if (policy == null || policy.rootCauseMethod() == null) {
            issues.add(issue("DEVIATION_CAPA_POLICY_REQUIRED", "policy",
                    "偏差/CAPA 策略和根因方法必填", "选择 typed 根因方法并填写策略"));
            return;
        }
        if (policy.deviationSlaHours() < 1 || policy.deviationSlaHours() > 8760
                || policy.nonConformanceSlaHours() < policy.deviationSlaHours()
                || policy.nonConformanceSlaHours() > 8760
                || policy.capaSlaHours() < policy.nonConformanceSlaHours()
                || policy.capaSlaHours() > 8760
                || policy.capaEffectivenessReviewDays() < 1
                || policy.capaEffectivenessReviewDays() > 3650
                || policy.recordRetentionDays() < 1 || policy.recordRetentionDays() > 36500
                || isBlankOrTooLong(policy.changeReason(), 500)) {
            issues.add(issue("DEVIATION_CAPA_POLICY_INVALID", "policy",
                    "SLA 必须逐级不减且在 1..8760 小时，复核/保留期与原因必须有效",
                    "修正 typed SLA、复核天数、保留期和原因"));
        }
    }

    private void validateCoverage(UpstreamResolution upstream, List<RuleResolution> rules,
                                  List<DraftIssue> issues) {
        Set<String> covered = new HashSet<>();
        for (RuleResolution rule : rules) {
            if (rule.desired() != null && rule.desired().sourceType() != null
                    && rule.desired().triggerMode() != null) {
                covered.add(coverageKey(rule.desired().sourceType(),
                        rule.desired().sourceItemId(), rule.desired().triggerMode()));
            }
        }
        upstream.controls().values().stream()
                .filter(row -> "CCP".equals(row.getControlClassification())
                        || "OPRP".equals(row.getControlClassification()))
                .forEach(row -> requireCoverage(SourceType.CRITICAL_CONTROL, row.getId(), covered,
                        "B6 CCP/OPRP", issues));
        upstream.qualitySpecs().values().stream()
                .filter(row -> "CRITICAL".equals(row.getSeverity()) || "MAJOR".equals(row.getSeverity()))
                .forEach(row -> requireCoverage(SourceType.QUALITY_RELEASE_SPEC, row.getId(), covered,
                        "B7 CRITICAL/MAJOR spec", issues));
    }

    private void requireCoverage(SourceType type, String id, Set<String> covered, String label,
                                 List<DraftIssue> issues) {
        for (TriggerMode mode : TriggerMode.values()) {
            if (!covered.contains(coverageKey(type, id, mode))) {
                issues.add(issue("DEVIATION_CAPA_COVERAGE_INCOMPLETE", "rules",
                        label + " 缺少 " + mode + " 模板规则",
                        "为每个关键来源补齐单次与重复事件规则"));
            }
        }
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人、请求和工厂范围不一致",
                    "DEVIATION_CAPA_DRAFT_TENANT_FORBIDDEN");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少偏差/CAPA 模板草稿权限",
                    "DEVIATION_CAPA_DRAFT_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(), operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员",
                    "DEVIATION_CAPA_DRAFT_MEMBER_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || isBlankOrTooLong(request.factoryId(), 50)
                || isBlankOrTooLong(request.blueprintId(), 100)
                || request.blueprintVersion() < 1 || request.blueprintVersion() > Integer.MAX_VALUE
                || !isDigest(request.sourceDigest()) || request.criticalControl() == null
                || isBlankOrTooLong(request.criticalControl().draftId(), 36)
                || !isDigest(request.criticalControl().desiredDigest())
                || request.qualityRelease() == null
                || isBlankOrTooLong(request.qualityRelease().draftId(), 36)
                || !isDigest(request.qualityRelease().desiredDigest())
                || request.rules().isEmpty() || request.rules().size() > MAX_RULES
                || request.policy() == null || request.operatorId() < 1
                || isBlankOrTooLong(request.idempotencyKey(), 128)
                || (materialize && !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "偏差/CAPA 模板请求 envelope 无效",
                    "DEVIATION_CAPA_DRAFT_REQUEST_INVALID");
        }
    }

    private DeviationCapaTemplateDraftRule toRow(
            String draftId, String factoryId, String criticalDraftId, String qualityDraftId,
            int ordinal, RuleResolution resolved) {
        RuleSpec rule = resolved.desired();
        DerivedRule derived = resolved.derived();
        return DeviationCapaTemplateDraftRule.builder()
                .id(UUID.randomUUID().toString()).draftId(draftId).factoryId(factoryId)
                .ordinal(ordinal).ruleKey(resolved.ruleKey())
                .criticalControlDraftId(criticalDraftId)
                .criticalControlItemId(resolved.control() == null ? null : resolved.control().getId())
                .qualityReleaseTemplateDraftId(qualityDraftId)
                .qualityReleaseTemplateItemId(resolved.qualitySpec() == null
                        ? null : resolved.qualitySpec().getId())
                .sourceType(rule.sourceType().name()).sourceItemId(rule.sourceItemId())
                .sourceSnapshotDigest(resolved.sourceSnapshotDigest())
                .triggerMode(rule.triggerMode().name()).repeatThreshold(rule.repeatThreshold())
                .recurrenceWindowDays(rule.recurrenceWindowDays())
                .sourceSeverity(derived.sourceSeverity().name())
                .derivedClassification(derived.classification().name())
                .immediateControlAction(derived.immediateControlAction().name())
                .approvalRoute(derived.approvalRoute().name())
                .escalationSlaHours(derived.escalationSlaHours())
                .rootCauseRequired(derived.rootCauseRequired())
                .correctiveActionRequired(derived.correctiveActionRequired())
                .preventiveActionRequired(derived.preventiveActionRequired())
                .effectivenessReviewRequired(derived.effectivenessReviewRequired())
                .rootCauseMethod(derived.rootCauseMethod() == null
                        ? null : derived.rootCauseMethod().name())
                .effectivenessReviewDays(derived.effectivenessReviewDays())
                .correctionTemplateCode(derived.correctionTemplateCode().name())
                .correctiveActionCode(derived.correctiveActionCode().name())
                .preventiveActionCode(derived.preventiveActionCode() == null
                        ? null : derived.preventiveActionCode().name())
                .effectivenessMetricCode(derived.effectivenessMetricCode() == null
                        ? null : derived.effectivenessMetricCode().name())
                .changeReason(rule.changeReason()).build();
    }

    private RuleSpec fromRow(DeviationCapaTemplateDraftRule row, List<String> authorityCodes) {
        return new RuleSpec(SourceType.valueOf(row.getSourceType()), row.getSourceItemId(),
                TriggerMode.valueOf(row.getTriggerMode()), row.getRepeatThreshold(),
                row.getRecurrenceWindowDays(), sortedDistinct(authorityCodes), row.getChangeReason());
    }

    private boolean matches(DeviationCapaTemplateDraftRule row, RuleResolution current,
                            DeviationCapaTemplateDraft parent, int ordinal) {
        DerivedRule derived = current.derived();
        return ordinal == row.getOrdinal()
                && current.ruleKey().equals(row.getRuleKey())
                && current.sourceSnapshotDigest().equals(row.getSourceSnapshotDigest())
                && parent.getCriticalControlDraftId().equals(row.getCriticalControlDraftId())
                && parent.getQualityReleaseTemplateDraftId().equals(
                        row.getQualityReleaseTemplateDraftId())
                && Objects.equals(current.control() == null ? null : current.control().getId(),
                        row.getCriticalControlItemId())
                && Objects.equals(current.qualitySpec() == null ? null : current.qualitySpec().getId(),
                        row.getQualityReleaseTemplateItemId())
                && derived.sourceSeverity().name().equals(row.getSourceSeverity())
                && derived.classification().name().equals(row.getDerivedClassification())
                && derived.immediateControlAction().name().equals(row.getImmediateControlAction())
                && derived.approvalRoute().name().equals(row.getApprovalRoute())
                && derived.escalationSlaHours() == row.getEscalationSlaHours()
                && derived.rootCauseRequired() == row.getRootCauseRequired()
                && derived.correctiveActionRequired() == row.getCorrectiveActionRequired()
                && derived.preventiveActionRequired() == row.getPreventiveActionRequired()
                && derived.effectivenessReviewRequired() == row.getEffectivenessReviewRequired()
                && Objects.equals(derived.rootCauseMethod() == null
                        ? null : derived.rootCauseMethod().name(), row.getRootCauseMethod())
                && Objects.equals(derived.effectivenessReviewDays(), row.getEffectivenessReviewDays())
                && derived.correctionTemplateCode().name().equals(row.getCorrectionTemplateCode())
                && derived.correctiveActionCode().name().equals(row.getCorrectiveActionCode())
                && Objects.equals(derived.preventiveActionCode() == null
                        ? null : derived.preventiveActionCode().name(), row.getPreventiveActionCode())
                && Objects.equals(derived.effectivenessMetricCode() == null
                        ? null : derived.effectivenessMetricCode().name(), row.getEffectivenessMetricCode());
    }

    private DraftReceipt receipt(DeviationCapaTemplateDraft row, boolean replayed,
                                 int moduleWrites, int persistedRows) {
        return new DraftReceipt(DeviationCapaTemplateDraftModels.SCHEMA_VERSION,
                row.getId(), row.getFactoryId(), row.getId(), row.getStatus(),
                row.getSourceSnapshotDigest(), row.getCriticalControlSnapshotDigest(),
                row.getQualityReleaseSnapshotDigest(), row.getAuthoritySnapshotDigest(),
                row.getFormalDispositionSnapshotDigest(), row.getDesiredDigest(),
                row.getPreviewDigest(), row.getRequestDigest(), row.getRuleCount(),
                row.getEvidenceCount(), row.getLockVersion() == null ? 0L : row.getLockVersion(),
                replayed, moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED",
                "ARCHIVE_DRAFT_ONLY");
    }

    private boolean sameAuthority(DraftPreview left, DraftPreview right) {
        return Objects.equals(left.status(), right.status())
                && Objects.equals(left.sourceSnapshotDigest(), right.sourceSnapshotDigest())
                && Objects.equals(left.criticalControlSnapshotDigest(),
                        right.criticalControlSnapshotDigest())
                && Objects.equals(left.qualityReleaseSnapshotDigest(),
                        right.qualityReleaseSnapshotDigest())
                && Objects.equals(left.authoritySnapshotDigest(), right.authoritySnapshotDigest())
                && Objects.equals(left.formalDispositionSnapshotDigest(),
                        right.formalDispositionSnapshotDigest())
                && Objects.equals(left.desiredDigest(), right.desiredDigest())
                && Objects.equals(left.previewDigest(), right.previewDigest());
    }

    private Severity controlSeverity(CriticalControlDraftItem item) {
        if ("CCP".equals(item.getControlClassification())) return Severity.CRITICAL;
        if ("OPRP".equals(item.getControlClassification())) return Severity.MAJOR;
        return Severity.MINOR;
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
        items.stream().sorted(Comparator.comparing(CriticalControlDraftItem::getOrdinal))
                .forEach(item -> values.add(controlSnapshot(item)));
        evidence.stream().sorted(Comparator.comparing(CriticalControlDraftEvidence::getItemId)
                        .thenComparing(CriticalControlDraftEvidence::getAuthorityCode))
                .forEach(row -> { values.add(row.getItemId()); values.add(row.getAuthorityCode());
                    values.add(row.getAuthoritySnapshotDigest()); });
        return digest(values.toArray(String[]::new));
    }

    private String controlSnapshot(CriticalControlDraftItem item) {
        return digest("CONTROL_ITEM", item.getId(), item.getControlKey(), item.getRouteNodeId(),
                item.getHazardItemId(), item.getControlClassification(), item.getCriterionType(),
                item.getParameterCode(), item.getLimitKind(), String.valueOf(item.getLowerLimit()),
                String.valueOf(item.getUpperLimit()), item.getUnitCode(),
                item.getDeviationDisposition(), item.getCorrectiveAction(),
                item.getResponsibleRole(), String.valueOf(item.getRecordRetentionDays()));
    }

    private String qualitySnapshot(QualityReleaseTemplateDraft parent,
                                   List<QualityReleaseTemplateDraftItem> items,
                                   List<QualityReleaseTemplateDraftEvidence> evidence) {
        List<String> values = new ArrayList<>();
        values.add(parent.getId()); values.add(parent.getDesiredDigest());
        values.add(parent.getPreviewDigest()); values.add(parent.getSourceSnapshotDigest());
        values.add(parent.getCriticalControlSnapshotDigest()); values.add(parent.getBomSnapshotDigest());
        values.add(parent.getMaterialSnapshotDigest()); values.add(parent.getAuthoritySnapshotDigest());
        values.add(parent.getFormalQualitySnapshotDigest());
        items.stream().sorted(Comparator.comparing(QualityReleaseTemplateDraftItem::getOrdinal))
                .forEach(item -> values.add(qualitySpecSnapshot(item)));
        evidence.stream().sorted(Comparator.comparing(QualityReleaseTemplateDraftEvidence::getItemId)
                        .thenComparing(QualityReleaseTemplateDraftEvidence::getAuthorityCode))
                .forEach(row -> { values.add(row.getItemId()); values.add(row.getAuthorityCode());
                    values.add(row.getAuthoritySnapshotDigest()); });
        return digest(values.toArray(String[]::new));
    }

    private String qualitySpecSnapshot(QualityReleaseTemplateDraftItem item) {
        return digest("QUALITY_SPEC", item.getId(), item.getSpecKey(), item.getSubjectType(),
                item.getSubjectRefType(), item.getSubjectRefId(), item.getSubjectSnapshotDigest(),
                item.getCriticalControlItemId(), item.getItemCode(), item.getCategory(),
                item.getValueType(), item.getCriterionKind(), item.getSeverity(),
                item.getReleaseEffect(), item.getSamplingStrategy(), item.getResponsibleRole(),
                String.valueOf(item.getRecordRetentionDays()));
    }

    private void validateApprovalRoutes(List<RuleResolution> rules,
                                        List<ApprovalChainConfig> approvalRows,
                                        List<DraftIssue> issues) {
        Set<ApprovalRoute> required = new HashSet<>();
        rules.stream().map(RuleResolution::derived).filter(Objects::nonNull)
                .map(DerivedRule::approvalRoute).filter(Objects::nonNull)
                .forEach(required::add);
        for (ApprovalRoute route : required) {
            List<Set<String>> steps = switch (route) {
                case QUALITY_OWNER -> List.of(Set.of("quality_manager", "quality_supervisor"));
                case QUALITY_THEN_PRODUCTION_OWNER -> List.of(
                        Set.of("quality_manager", "quality_supervisor"),
                        Set.of("production_manager"));
                case QUALITY_THEN_FACTORY_OWNER -> List.of(
                        Set.of("quality_manager", "quality_supervisor"),
                        Set.of("factory_super_admin"));
            };
            if (!hasOrderedApprovalRoute(approvalRows, steps)) {
                issues.add(issue("DEVIATION_CAPA_APPROVAL_ROUTE_MISSING", "approvalRoute",
                        "当前工厂没有启用且职责顺序匹配的 " + route + " 审批链",
                        "在 QUALITY_EXCEPTION 审批配置中按级别配置所需负责人角色"));
            }
        }
    }

    private boolean hasOrderedApprovalRoute(List<ApprovalChainConfig> rows,
                                            List<Set<String>> requiredSteps) {
        int priorLevel = 0;
        for (Set<String> roles : requiredSteps) {
            int matchedLevel = Integer.MAX_VALUE;
            for (ApprovalChainConfig row : rows) {
                if (row == null || !Boolean.TRUE.equals(row.getEnabled())
                        || row.getDecisionType() != ApprovalChainConfig.DecisionType.QUALITY_EXCEPTION
                        || row.getApprovalLevel() == null || row.getApprovalLevel() <= priorLevel
                        || row.getRequiredApprovers() == null || row.getRequiredApprovers() < 1) {
                    continue;
                }
                Set<String> configured = approvalRoleTokens(row.getApproverRoles());
                if (roles.stream().anyMatch(configured::contains)) {
                    matchedLevel = Math.min(matchedLevel, row.getApprovalLevel());
                }
            }
            if (matchedLevel == Integer.MAX_VALUE) return false;
            priorLevel = matchedLevel;
        }
        return true;
    }

    private Set<String> approvalRoleTokens(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (String token : raw.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    private String formalDispositionSnapshot(List<ApprovalChainConfig> rows) {
        List<String> values = new ArrayList<>();
        rows.stream().sorted(Comparator.comparing(ApprovalChainConfig::getDecisionType)
                        .thenComparing(ApprovalChainConfig::getPriority,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ApprovalChainConfig::getId))
                .forEach(row -> {
                    values.add(row.getId()); values.add(row.getFactoryId());
                    values.add(String.valueOf(row.getDecisionType())); values.add(row.getName());
                    values.add(row.getTriggerCondition()); values.add(String.valueOf(row.getApprovalLevel()));
                    values.add(String.valueOf(row.getRequiredApprovers())); values.add(row.getApproverRoles());
                    values.add(String.valueOf(row.getTimeoutMinutes())); values.add(row.getEscalationConfigId());
                    values.add(String.valueOf(row.getEnabled())); values.add(String.valueOf(row.getVersion()));
                });
        return digest(values.toArray(String[]::new));
    }

    private String desiredDigest(DraftRequest request) {
        List<String> values = new ArrayList<>();
        values.add(DeviationCapaTemplateDraftModels.SCHEMA_VERSION);
        values.add(DeviationCapaTemplateDraftModels.DECISION_ALGORITHM_VERSION);
        values.add(request.factoryId()); values.add(request.blueprintId());
        values.add(String.valueOf(request.blueprintVersion())); values.add(request.sourceDigest());
        values.add(request.criticalControl().draftId());
        values.add(request.criticalControl().desiredDigest());
        values.add(request.qualityRelease().draftId());
        values.add(request.qualityRelease().desiredDigest());
        for (RuleSpec rule : request.rules()) {
            if (rule == null) { values.add("NULL_RULE"); continue; }
            values.add(String.valueOf(rule.sourceType())); values.add(rule.sourceItemId());
            values.add(String.valueOf(rule.triggerMode()));
            values.add(String.valueOf(rule.repeatThreshold()));
            values.add(String.valueOf(rule.recurrenceWindowDays()));
            sortedDistinct(rule.authorityCodes()).forEach(values::add);
            values.add(rule.changeReason());
        }
        TemplatePolicy policy = request.policy();
        if (policy == null) values.add("NULL_POLICY");
        else {
            values.add(String.valueOf(policy.deviationSlaHours()));
            values.add(String.valueOf(policy.nonConformanceSlaHours()));
            values.add(String.valueOf(policy.capaSlaHours()));
            values.add(String.valueOf(policy.rootCauseMethod()));
            values.add(String.valueOf(policy.capaEffectivenessReviewDays()));
            values.add(String.valueOf(policy.recordRetentionDays()));
            values.add(policy.changeReason());
        }
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        return digest(request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), desiredDigest(request));
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream().map(issue -> issue.code() + "|" + issue.target() + "|"
                + issue.message() + "|" + issue.recovery()).toArray(String[]::new));
    }

    private String ruleKey(SourceType sourceType, String sourceItemId, TriggerMode triggerMode) {
        return digest("RULE", String.valueOf(sourceType), sourceItemId, String.valueOf(triggerMode));
    }

    private String coverageKey(SourceType sourceType, String sourceItemId, TriggerMode triggerMode) {
        return sourceType + ":" + sourceItemId + ":" + triggerMode;
    }

    private List<String> sortedDistinct(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(Objects::nonNull).map(String::trim).distinct().sorted().toList();
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_deviation_capa_draft_idempotency")) return true;
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
                              UpstreamResolution upstream, List<RuleResolution> rules) {}

    private record BlueprintResolution(String catalogVersion, String catalogDigest,
                                       Set<String> authorityCodes, String authoritySnapshotDigest,
                                       String snapshotDigest) {
        static BlueprintResolution missing() {
            return new BlueprintResolution("", "", Set.of(), digestStatic("MISSING_AUTHORITY"),
                    digestStatic("MISSING_BLUEPRINT"));
        }
    }

    private record UpstreamResolution(CriticalControlDraft critical,
                                      QualityReleaseTemplateDraft quality,
                                      Map<String, CriticalControlDraftItem> controls,
                                      Map<String, QualityReleaseTemplateDraftItem> qualitySpecs,
                                      String criticalSnapshotDigest,
                                      String qualitySnapshotDigest) {
        static UpstreamResolution missing() {
            return new UpstreamResolution(null, null, Map.of(), Map.of(),
                    digestStatic("MISSING_CRITICAL_CONTROL"),
                    digestStatic("MISSING_QUALITY_RELEASE"));
        }
    }

    private record RuleResolution(RuleSpec desired, CriticalControlDraftItem control,
                                  QualityReleaseTemplateDraftItem qualitySpec,
                                  String sourceSnapshotDigest, DerivedRule derived,
                                  String ruleKey) {
        static RuleResolution missing() {
            RuleSpec rule = new RuleSpec(SourceType.CRITICAL_CONTROL, "MISSING",
                    TriggerMode.SINGLE_EVENT, null, null, List.of(), "MISSING");
            TemplatePolicy policy = new TemplatePolicy(1, 1, 1,
                    DeviationCapaTemplateDraftModels.RootCauseMethod.FIVE_WHYS,
                    1, 1, "MISSING");
            return new RuleResolution(rule, null, null, digestStatic("MISSING_SOURCE"),
                    derive(rule, policy, Severity.MINOR),
                    digestStatic("MISSING_RULE"));
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



