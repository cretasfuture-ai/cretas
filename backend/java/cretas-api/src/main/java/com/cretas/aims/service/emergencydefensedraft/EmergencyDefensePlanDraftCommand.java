package com.cretas.aims.service.emergencydefensedraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.alertpolicydraft.AlertPolicyDraft;
import com.cretas.aims.entity.emergencydefensedraft.EmergencyDefensePlanDraft;
import com.cretas.aims.entity.emergencydefensedraft.EmergencyDefensePlanDraftEvidence;
import com.cretas.aims.entity.emergencydefensedraft.EmergencyDefensePlanDraftItem;
import com.cretas.aims.entity.tracerecalldraft.TraceRecallPlanDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.alertpolicydraft.AlertPolicyDraftRepository;
import com.cretas.aims.repository.emergencydefensedraft.EmergencyDefensePlanDraftEvidenceRepository;
import com.cretas.aims.repository.emergencydefensedraft.EmergencyDefensePlanDraftItemRepository;
import com.cretas.aims.repository.emergencydefensedraft.EmergencyDefensePlanDraftRepository;
import com.cretas.aims.repository.tracerecalldraft.TraceRecallPlanDraftRepository;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftCommand;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.AlertPolicyPin;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.ContainmentAction;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.DerivedPlan;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.DraftIssue;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.DraftPreview;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.DraftReadback;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.DraftReceipt;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.DraftRequest;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.EvidenceType;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.ImpactTier;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.NotificationTier;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.PlanInput;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.RecoveryPlan;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.ResponsibleRole;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.ScenarioType;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.TraceRecallPin;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftCommand;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module-owned emergency and food-defense PLAN/DRAFT command.
 *
 * <p>The immutable capability catalog v2 declares
 * {@code emergency-defense -> trace-recall + alerts}. The adapter contract previously declared
 * {@code factory + alerts}, omitting {@code trace-recall} entirely; B17 converges it and pins both
 * real dependencies: the draft locks a same-factory, same-lineage, readback-ready B9 trace/recall
 * PLAN draft and B15 alert-policy draft, every plan's detection trigger must name a policy that
 * the pinned alert draft actually owns, and every scenario that can put product in the market must
 * name a segment the pinned trace/recall draft actually owns.</p>
 *
 * <p>These are plans and drill templates. Nothing here takes an emergency action: recall event,
 * recall action, inventory or batch freeze, alert event, notification, production stop and
 * equipment command writer counts are fixed at zero. It exposes no generic JSON/JSONB writer, no
 * arbitrary SQL and no expression evaluation.</p>
 *
 * <p>{@code emergency-defense-policy/v1} derives food-safety impact, whether a trace link is
 * required, whether a periodic drill is required, the notification tier and the containment SLA
 * from the scenario and impact tier alone. PostgreSQL CHECK, this preview and the field-by-field
 * readback each recompute them independently.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class EmergencyDefensePlanDraftCommand {
    public static final String CAPABILITY_ID = "emergency-defense";
    public static final String REQUIRED_PERMISSION = "emergency:plan:draft";
    private static final String AUTHORITY_CODE = "EMERGENCY_DEFENSE_CATALOG_AUTHORITY";
    private static final List<String> DEPENDENCIES =
            FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID);
    private static final int MAX_PLANS = 200;

    private final EmergencyDefensePlanDraftRepository drafts;
    private final EmergencyDefensePlanDraftItemRepository items;
    private final EmergencyDefensePlanDraftEvidenceRepository evidence;
    private final TraceRecallPlanDraftRepository traceDrafts;
    private final TraceRecallPlanDraftCommand traceCommand;
    private final AlertPolicyDraftRepository alertDrafts;
    private final AlertPolicyDraftCommand alertCommand;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UserRepository users;

    public EmergencyDefensePlanDraftCommand(
            EmergencyDefensePlanDraftRepository drafts,
            EmergencyDefensePlanDraftItemRepository items,
            EmergencyDefensePlanDraftEvidenceRepository evidence,
            TraceRecallPlanDraftRepository traceDrafts,
            TraceRecallPlanDraftCommand traceCommand,
            AlertPolicyDraftRepository alertDrafts,
            AlertPolicyDraftCommand alertCommand,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UserRepository users) {
        this.drafts = drafts;
        this.items = items;
        this.evidence = evidence;
        this.traceDrafts = traceDrafts;
        this.traceCommand = traceCommand;
        this.alertDrafts = alertDrafts;
        this.alertCommand = alertCommand;
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
        Optional<EmergencyDefensePlanDraft> prior = drafts
                .findByFactoryIdAndIdempotencyKey(request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同应急与食品防护计划请求",
                        "EMERGENCY_DEFENSE_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview plan = evaluation.preview();
        if (!"READY".equals(plan.status())) {
            throw invalid(409, "应急与食品防护计划预览被阻断",
                    "EMERGENCY_DEFENSE_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(plan.previewDigest())) {
            throw invalid(409, "应急与食品防护计划确认摘要已漂移",
                    "EMERGENCY_DEFENSE_PREVIEW_DRIFT");
        }

        BlueprintAuthority authority = evaluation.authority();
        TraceAuthority trace = evaluation.trace();
        AlertAuthority alerts = evaluation.alerts();
        EmergencyDefensePlanDraft parent = EmergencyDefensePlanDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(plan.sourceSnapshotDigest())
                .traceRecallDraftId(trace.draftId())
                .traceRecallDesiredDigest(trace.desiredDigest())
                .traceRecallSnapshotDigest(trace.snapshotDigest())
                .alertPolicyDraftId(alerts.draftId())
                .alertPolicyDesiredDigest(alerts.desiredDigest())
                .alertPolicySnapshotDigest(alerts.snapshotDigest())
                .capabilityCatalogVersion(authority.catalogVersion())
                .capabilityCatalogDigest(authority.catalogDigest())
                .authoritySnapshotDigest(authority.authoritySnapshotDigest())
                .desiredDigest(plan.desiredDigest())
                .previewDigest(plan.previewDigest())
                .requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .planCount(plan.plans().size())
                .evidenceCount(1)
                .dependencyBasis(EmergencyDefensePlanDraftModels.DEPENDENCY_BASIS)
                .algorithmVersion(EmergencyDefensePlanDraftModels.ALGORITHM_VERSION)
                .dependencyClosureComplete(true)
                .status("DRAFT")
                .build();
        EmergencyDefensePlanDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) {
                throw exception;
            }
            throw invalid(409, "应急与食品防护计划幂等键发生并发竞争",
                    "EMERGENCY_DEFENSE_IDEMPOTENCY_RACE");
        }

        List<EmergencyDefensePlanDraftItem> rows = plan.plans().stream()
                .map(value -> toRow(saved, value)).toList();
        items.saveAllAndFlush(rows);
        evidence.saveAndFlush(EmergencyDefensePlanDraftEvidence.builder()
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
            throw invalid(409, "应急与食品防护计划回读摘要不一致",
                    "EMERGENCY_DEFENSE_READBACK_MISMATCH");
        }
        return receipt(saved, false, 1, 2 + rows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        EmergencyDefensePlanDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该应急与食品防护计划草稿",
                        "EMERGENCY_DEFENSE_NOT_FOUND"));
        List<EmergencyDefensePlanDraftItem> rows = items
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<EmergencyDefensePlanDraftEvidence> evidenceRows = evidence
                .findByDraftIdAndFactoryIdOrderByAuthorityCode(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !EmergencyDefensePlanDraftModels.DEPENDENCY_BASIS
                        .equals(parent.getDependencyBasis())
                || !EmergencyDefensePlanDraftModels.ALGORITHM_VERSION
                        .equals(parent.getAlgorithmVersion())
                || parent.getPlanCount() == null || parent.getPlanCount() != rows.size()
                || parent.getEvidenceCount() == null || parent.getEvidenceCount() != 1
                || evidenceRows.size() != 1) {
            throw invalid(409, "应急与食品防护计划父子行或依赖闭包不完整",
                    "EMERGENCY_DEFENSE_READBACK_INVARIANT_BROKEN");
        }

        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(factoryId, parent.getBlueprintId(),
                parent.getBlueprintVersion(), issues);
        if (!issues.isEmpty()
                || !parent.getAuthoritySnapshotDigest().equals(authority.authoritySnapshotDigest())
                || !parent.getCapabilityCatalogVersion().equals(authority.catalogVersion())
                || !parent.getCapabilityCatalogDigest().equals(authority.catalogDigest())) {
            throw invalid(409, "应急与食品防护计划上游 Blueprint 已漂移",
                    "EMERGENCY_DEFENSE_UPSTREAM_DRIFT");
        }

        TraceAuthority trace = resolveStoredTrace(parent, issues);
        AlertAuthority alerts = resolveStoredAlerts(parent, issues);
        if (!issues.isEmpty()
                || !parent.getTraceRecallSnapshotDigest().equals(trace.snapshotDigest())
                || !parent.getAlertPolicySnapshotDigest().equals(alerts.snapshotDigest())) {
            throw invalid(409, "应急与食品防护计划锁定的召回或告警上游已漂移",
                    "EMERGENCY_DEFENSE_DEPENDENCY_DRIFT");
        }
        String currentSourceSnapshot = sourceSnapshot(authority, trace, alerts);
        if (!currentSourceSnapshot.equals(parent.getSourceSnapshotDigest())) {
            throw invalid(409, "应急与食品防护计划源快照摘要已漂移",
                    "EMERGENCY_DEFENSE_SOURCE_SNAPSHOT_DRIFT");
        }
        if (!sameEvidence(evidenceRows.get(0), authority)) {
            throw invalid(409, "应急与食品防护计划目录依据摘要不一致",
                    "EMERGENCY_DEFENSE_EVIDENCE_MISMATCH");
        }

        List<DerivedPlan> derived = new ArrayList<>();
        for (EmergencyDefensePlanDraftItem row : rows) {
            DerivedPlan value = fromRow(row);
            if (value.foodSafetyImpacting() != EmergencyDefensePlanDraftModels
                        .foodSafetyImpacting(value.scenarioType())
                    || value.traceLinkRequired() != EmergencyDefensePlanDraftModels
                        .traceLinkRequired(value.scenarioType())
                    || value.drillRequired() != EmergencyDefensePlanDraftModels
                        .drillRequired(value.scenarioType())
                    || value.notificationTier() != EmergencyDefensePlanDraftModels
                        .notificationTier(value.scenarioType(), value.impactTier())
                    || value.containmentSlaMinutes() != EmergencyDefensePlanDraftModels
                        .containmentSlaMinutes(value.impactTier())) {
                throw invalid(409, "应急响应派生结论与服务端算法不一致",
                        "EMERGENCY_DEFENSE_POLICY_TAMPERED");
            }
            AlertTrigger trigger = alerts.policies().get(value.detectionAlertPolicyCode());
            if (trigger == null || !trigger.severity().equals(value.detectionAlertSeverity())
                    || !trigger.snapshotDigest().equals(value.detectionAlertSnapshotDigest())) {
                throw invalid(409, "应急计划锁定的告警策略不再存在或已变化",
                        "EMERGENCY_DEFENSE_ALERT_TRIGGER_DRIFT");
            }
            if (value.traceLinkRequired()
                    && !trace.segmentKeys().contains(value.traceRecallSegmentKey())) {
                throw invalid(409, "应急计划锁定的追溯召回段不再存在",
                        "EMERGENCY_DEFENSE_TRACE_SEGMENT_DRIFT");
            }
            derived.add(value);
        }
        if (!desiredDigest(derived).equals(parent.getDesiredDigest())) {
            throw invalid(409, "应急与食品防护计划逐字段回读摘要不一致",
                    "EMERGENCY_DEFENSE_READBACK_MISMATCH");
        }
        return new DraftReadback(EmergencyDefensePlanDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getStatus(), derived, parent.getSourceSnapshotDigest(),
                parent.getDesiredDigest(), 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId,
                                     String expectedDesiredDigest) {
        EmergencyDefensePlanDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该应急与食品防护计划草稿",
                        "EMERGENCY_DEFENSE_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus()) || !isDigest(expectedDesiredDigest)
                || !expectedDesiredDigest.equals(parent.getDesiredDigest())) {
            throw invalid(409, "只能恢复摘要精确匹配的未发布应急与食品防护计划草稿",
                    "EMERGENCY_DEFENSE_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(EmergencyDefensePlanDraftModels.SCHEMA_VERSION, factoryId, draftId,
                expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "只归档未发布应急计划草稿；不触发任何应急处置、不创建召回事件或召回动作、"
                        + "不冻结库存或批次、不发出告警或通知、不停线、不下发设备指令",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(request.factoryId(), request.blueprintId(),
                request.blueprintVersion(), issues);
        TraceAuthority trace = resolveTrace(request, issues);
        AlertAuthority alerts = resolveAlerts(request, issues);

        List<DerivedPlan> derived = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int ordinal = 0; ordinal < request.plans().size(); ordinal++) {
            PlanInput input = request.plans().get(ordinal);
            String target = "plans[" + ordinal + "]";
            if (!validatePlan(input, target, issues)) {
                continue;
            }
            if (!codes.add(input.planCode())) {
                issues.add(issue("EMERGENCY_DEFENSE_DUPLICATE_PLAN_CODE", target,
                        "同一草稿内应急计划编码不能重复", "合并重复应急计划"));
                continue;
            }
            AlertTrigger trigger = alerts.policies().get(input.detectionAlertPolicyCode());
            if (trigger == null) {
                issues.add(issue("EMERGENCY_DEFENSE_ALERT_POLICY_MISSING", target,
                        "应急计划的发现触发必须引用同一 readback-ready 告警策略草稿中的策略",
                        "重新选择当前告警策略草稿中的策略编码"));
                continue;
            }
            if (!validateAgainstDependencies(input, target, trigger, trace, issues)) {
                continue;
            }
            derived.add(derive(ordinal, input, trigger));
        }
        for (String gap : uncoveredTraceScenarios(derived)) {
            issues.add(issue("EMERGENCY_DEFENSE_RECALL_DRILL_REQUIRED", "plans",
                    "锁定召回计划后必须至少有一条召回演练计划：" + gap,
                    "补一条 RECALL_DRILL 场景的应急计划"));
        }
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));

        String desired = desiredDigest(derived);
        String sourceSnapshot = sourceSnapshot(authority, trace, alerts);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("EMERGENCY_DEFENSE_PREVIEW",
                EmergencyDefensePlanDraftModels.SCHEMA_VERSION,
                EmergencyDefensePlanDraftModels.ALGORITHM_VERSION, status, sourceSnapshot,
                trace.snapshotDigest(), alerts.snapshotDigest(),
                authority.authoritySnapshotDigest(), desired, issueDigest(issues));
        DraftPreview preview = new DraftPreview(EmergencyDefensePlanDraftModels.SCHEMA_VERSION,
                EmergencyDefensePlanDraftModels.ALGORITHM_VERSION, status, request.factoryId(),
                DEPENDENCIES, request, derived, sourceSnapshot, trace.snapshotDigest(),
                alerts.snapshotDigest(), authority.authoritySnapshotDigest(), desired,
                previewDigest, issues, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, authority, trace, alerts);
    }

    private BlueprintAuthority resolveBlueprint(String factoryId, String blueprintId,
                                                long blueprintVersion, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(factoryId);
        if (response == null || !blueprintId.equals(response.getId())
                || !Objects.equals(Math.toIntExact(blueprintVersion), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                     || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("EMERGENCY_DEFENSE_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
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
            issues.add(issue("EMERGENCY_DEFENSE_CAPABILITY_NOT_READY", "capability",
                    "emergency-defense 或 trace-recall / alerts 目录依赖尚未就绪",
                    "补齐追溯召回与告警依据后重新编译 Blueprint"));
        }
        Set<String> refs = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || refs.isEmpty()) {
            issues.add(issue("EMERGENCY_DEFENSE_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本、摘要或应急依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        String authoritySnapshot = digest("EMERGENCY_DEFENSE_AUTHORITY",
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                refs.stream().sorted().reduce("", (left, right) -> left + "|" + right));
        return new BlueprintAuthority(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), refs, authoritySnapshot,
                digestBlueprint(response));
    }

    private TraceAuthority resolveTrace(DraftRequest request, List<DraftIssue> issues) {
        TraceRecallPin pin = request.traceRecall();
        if (pin == null || pin.draftId().isBlank() || !isDigest(pin.desiredDigest())) {
            issues.add(issue("EMERGENCY_DEFENSE_TRACE_PIN_INVALID", "traceRecall",
                    "应急与食品防护计划必须锁定同一 Blueprint 的追溯召回计划草稿",
                    "选择当前 Blueprint 的追溯召回计划草稿并刷新摘要"));
            return TraceAuthority.none();
        }
        TraceRecallPlanDraft parent = traceDrafts
                .findByIdAndFactoryId(pin.draftId(), request.factoryId()).orElse(null);
        if (!sameTraceLineage(parent, request, pin.desiredDigest())) {
            issues.add(issue("EMERGENCY_DEFENSE_TRACE_DRIFT", "traceRecall",
                    "B9 追溯召回计划草稿不属于当前 Blueprint 或摘要已变化",
                    "重新读取并选择当前追溯召回计划草稿"));
            return TraceAuthority.none();
        }
        try {
            return traceAuthority(parent,
                    traceCommand.readBack(request.factoryId(), pin.draftId()));
        } catch (RuntimeException exception) {
            issues.add(issue("EMERGENCY_DEFENSE_TRACE_READBACK_FAILED", "traceRecall",
                    "B9 追溯召回计划草稿逐字段回读失败",
                    "修复追溯召回计划草稿后重新预览"));
            return TraceAuthority.none();
        }
    }

    private TraceAuthority resolveStoredTrace(EmergencyDefensePlanDraft parent,
                                              List<DraftIssue> issues) {
        TraceRecallPlanDraft stored = traceDrafts.findByIdAndFactoryId(
                parent.getTraceRecallDraftId(), parent.getFactoryId()).orElse(null);
        if (stored == null
                || !Objects.equals(parent.getTraceRecallDesiredDigest(), stored.getDesiredDigest())
                || !parent.getBlueprintId().equals(stored.getBlueprintId())
                || !Objects.equals(parent.getBlueprintVersion(), stored.getBlueprintVersion())
                || !parent.getSourceDigest().equals(stored.getSourceDigest())) {
            issues.add(issue("EMERGENCY_DEFENSE_TRACE_DRIFT", "traceRecall",
                    "已保存的 B9 追溯召回 pin 已漂移", "重新生成应急与食品防护计划草稿"));
            return TraceAuthority.none();
        }
        try {
            return traceAuthority(stored,
                    traceCommand.readBack(parent.getFactoryId(), stored.getId()));
        } catch (RuntimeException exception) {
            issues.add(issue("EMERGENCY_DEFENSE_TRACE_READBACK_FAILED", "traceRecall",
                    "已保存的 B9 追溯召回计划草稿逐字段回读失败",
                    "修复追溯召回计划草稿后重新生成草稿"));
            return TraceAuthority.none();
        }
    }

    private TraceAuthority traceAuthority(TraceRecallPlanDraft parent,
                                          TraceRecallPlanDraftModels.DraftReadback readback) {
        Set<String> keys = new TreeSet<>();
        for (TraceRecallPlanDraftModels.DerivedSegment segment : readback.segments()) {
            keys.add(segment.segmentKey());
        }
        return new TraceAuthority(parent.getId(), parent.getDesiredDigest(),
                digestTrace(readback), Set.copyOf(keys));
    }

    private AlertAuthority resolveAlerts(DraftRequest request, List<DraftIssue> issues) {
        AlertPolicyPin pin = request.alertPolicy();
        if (pin == null || pin.draftId().isBlank() || !isDigest(pin.desiredDigest())) {
            issues.add(issue("EMERGENCY_DEFENSE_ALERT_PIN_INVALID", "alertPolicy",
                    "应急与食品防护计划必须锁定同一 Blueprint 的告警策略草稿",
                    "选择当前 Blueprint 的告警策略草稿并刷新摘要"));
            return AlertAuthority.none();
        }
        AlertPolicyDraft parent = alertDrafts
                .findByIdAndFactoryId(pin.draftId(), request.factoryId()).orElse(null);
        if (!sameAlertLineage(parent, request, pin.desiredDigest())) {
            issues.add(issue("EMERGENCY_DEFENSE_ALERT_DRIFT", "alertPolicy",
                    "B15 告警策略草稿不属于当前 Blueprint 或摘要已变化",
                    "重新读取并选择当前告警策略草稿"));
            return AlertAuthority.none();
        }
        try {
            return alertAuthority(parent,
                    alertCommand.readBack(request.factoryId(), pin.draftId()));
        } catch (RuntimeException exception) {
            issues.add(issue("EMERGENCY_DEFENSE_ALERT_READBACK_FAILED", "alertPolicy",
                    "B15 告警策略草稿逐字段回读失败", "修复告警策略草稿后重新预览"));
            return AlertAuthority.none();
        }
    }

    private AlertAuthority resolveStoredAlerts(EmergencyDefensePlanDraft parent,
                                               List<DraftIssue> issues) {
        AlertPolicyDraft stored = alertDrafts.findByIdAndFactoryId(
                parent.getAlertPolicyDraftId(), parent.getFactoryId()).orElse(null);
        if (stored == null
                || !Objects.equals(parent.getAlertPolicyDesiredDigest(), stored.getDesiredDigest())
                || !parent.getBlueprintId().equals(stored.getBlueprintId())
                || !Objects.equals(parent.getBlueprintVersion(), stored.getBlueprintVersion())
                || !parent.getSourceDigest().equals(stored.getSourceDigest())) {
            issues.add(issue("EMERGENCY_DEFENSE_ALERT_DRIFT", "alertPolicy",
                    "已保存的 B15 告警策略 pin 已漂移", "重新生成应急与食品防护计划草稿"));
            return AlertAuthority.none();
        }
        try {
            return alertAuthority(stored,
                    alertCommand.readBack(parent.getFactoryId(), stored.getId()));
        } catch (RuntimeException exception) {
            issues.add(issue("EMERGENCY_DEFENSE_ALERT_READBACK_FAILED", "alertPolicy",
                    "已保存的 B15 告警策略草稿逐字段回读失败",
                    "修复告警策略草稿后重新生成草稿"));
            return AlertAuthority.none();
        }
    }

    private AlertAuthority alertAuthority(AlertPolicyDraft parent,
                                          AlertPolicyDraftModels.DraftReadback readback) {
        Map<String, AlertTrigger> policies = new LinkedHashMap<>();
        for (AlertPolicyDraftModels.DerivedPolicy policy : readback.policies()) {
            policies.putIfAbsent(policy.policyCode(), new AlertTrigger(
                    policy.policyCode(), policy.severity().name(),
                    digest("EMERGENCY_DEFENSE_ALERT_TRIGGER", policy.policyCode(),
                            policy.severity().name(), policy.escalationRole().name(),
                            String.valueOf(policy.acknowledgeSlaMinutes()),
                            String.valueOf(policy.twoPersonAcknowledgeRequired()),
                            policy.moduleSnapshotDigest())));
        }
        return new AlertAuthority(parent.getId(), parent.getDesiredDigest(),
                digestAlerts(readback), Map.copyOf(policies));
    }

    private boolean validatePlan(PlanInput input, String target, List<DraftIssue> issues) {
        if (input == null || isBlankOrTooLong(input.planCode(), 60)
                || !input.planCode().matches("[A-Z0-9][A-Z0-9_.-]{0,59}")
                || isBlankOrTooLong(input.title(), 200)
                || input.scenarioType() == null || input.impactTier() == null
                || input.responsibleRole() == null
                || isBlankOrTooLong(input.detectionAlertPolicyCode(), 60)
                || isBlankOrTooLong(input.changeReason(), 500)) {
            issues.add(issue("EMERGENCY_DEFENSE_PLAN_FIELDS_REQUIRED", target,
                    "计划编码、名称、场景、影响面、发现触发、责任角色和变更原因均必填",
                    "补齐 typed 应急计划字段"));
            return false;
        }
        boolean valid = true;
        if (input.containmentActions().isEmpty() || input.containmentActions().size() > 6
                || new HashSet<>(input.containmentActions()).size()
                   != input.containmentActions().size()) {
            issues.add(issue("EMERGENCY_DEFENSE_CONTAINMENT_INVALID", target,
                    "处置动作必须为非空、不重复的封闭枚举", "选择有效处置动作"));
            valid = false;
        }
        if (input.requiredEvidence().isEmpty() || input.requiredEvidence().size() > 5
                || new HashSet<>(input.requiredEvidence()).size()
                   != input.requiredEvidence().size()) {
            issues.add(issue("EMERGENCY_DEFENSE_EVIDENCE_INVALID", target,
                    "留痕要求必须为非空、不重复的封闭枚举", "选择有效留痕要求"));
            valid = false;
        }
        boolean drill = EmergencyDefensePlanDraftModels.drillRequired(input.scenarioType());
        if (drill && (input.drillFrequencyDays() == null || input.drillFrequencyDays() < 1
                || input.drillFrequencyDays() > 365)) {
            issues.add(issue("EMERGENCY_DEFENSE_DRILL_FREQUENCY_REQUIRED", target,
                    "食品安全相关场景必须给出 1–365 天的演练周期", "补齐演练周期"));
            valid = false;
        }
        if (!drill && input.drillFrequencyDays() != null) {
            issues.add(issue("EMERGENCY_DEFENSE_DRILL_FREQUENCY_NOT_ALLOWED", target,
                    "非食品安全相关场景不接受演练周期", "移除演练周期"));
            valid = false;
        }
        if (drill && !input.requiredEvidence().contains(EvidenceType.DRILL_REPORT)) {
            issues.add(issue("EMERGENCY_DEFENSE_DRILL_REPORT_REQUIRED", target,
                    "需要演练的场景必须留存演练报告", "增加演练报告留痕要求"));
            valid = false;
        }
        if (EmergencyDefensePlanDraftModels.foodSafetyImpacting(input.scenarioType())
                && !input.requiredEvidence().contains(EvidenceType.QUALITY_SIGNOFF)) {
            issues.add(issue("EMERGENCY_DEFENSE_QUALITY_SIGNOFF_REQUIRED", target,
                    "影响食品安全的场景必须留存质量签署", "增加质量签署留痕要求"));
            valid = false;
        }
        if (input.scenarioType() == ScenarioType.INTENTIONAL_ADULTERATION
                && !input.containmentActions().contains(ContainmentAction.SECURE_ACCESS_POINTS)) {
            issues.add(issue("EMERGENCY_DEFENSE_ACCESS_CONTROL_REQUIRED", target,
                    "蓄意投毒场景必须包含控制出入口的处置动作", "增加控制出入口处置动作"));
            valid = false;
        }
        if (input.impactTier() == ImpactTier.SITE_WIDE) {
            if (!input.containmentActions().contains(ContainmentAction.STOP_SITE)) {
                issues.add(issue("EMERGENCY_DEFENSE_SITE_STOP_REQUIRED", target,
                        "全厂级影响必须包含全厂停产的处置动作", "增加全厂停产处置动作"));
                valid = false;
            }
            if (input.responsibleRole() != ResponsibleRole.FACTORY_DIRECTOR) {
                issues.add(issue("EMERGENCY_DEFENSE_SITE_OWNER_REQUIRED", target,
                        "全厂级影响的责任角色必须是厂长", "把责任角色改为厂长"));
                valid = false;
            }
        }
        if (input.impactTier() == ImpactTier.LINE_WIDE
                && !input.containmentActions().contains(ContainmentAction.STOP_AFFECTED_LINE)
                && !input.containmentActions().contains(ContainmentAction.STOP_SITE)) {
            issues.add(issue("EMERGENCY_DEFENSE_LINE_STOP_REQUIRED", target,
                    "产线级影响必须包含停产线或全厂停产的处置动作", "增加停产线处置动作"));
            valid = false;
        }
        return valid;
    }

    /**
     * Rules that can only be checked against the two pinned upstream drafts.
     *
     * <p>Kept separate from {@link #validatePlan} so a dependency that failed to resolve reports
     * a pin issue once, instead of re-reporting itself on every plan.</p>
     */
    private boolean validateAgainstDependencies(PlanInput input, String target,
                                                AlertTrigger trigger, TraceAuthority trace,
                                                List<DraftIssue> issues) {
        boolean valid = true;
        if (EmergencyDefensePlanDraftModels.foodSafetyImpacting(input.scenarioType())
                && !"CRITICAL".equals(trigger.severity())) {
            issues.add(issue("EMERGENCY_DEFENSE_DETECTION_SEVERITY_TOO_LOW", target,
                    "影响食品安全的应急场景必须由 CRITICAL 告警策略发现",
                    "改用 CRITICAL 级告警策略作为发现触发"));
            valid = false;
        }
        boolean traceRequired = EmergencyDefensePlanDraftModels
                .traceLinkRequired(input.scenarioType());
        String segmentKey = EmergencyDefensePlanDraftModels.clean(input.traceRecallSegmentKey());
        if (traceRequired) {
            if (segmentKey.isEmpty() || !trace.segmentKeys().contains(segmentKey)) {
                issues.add(issue("EMERGENCY_DEFENSE_TRACE_SEGMENT_MISSING", target,
                        "可能已流入市场的场景必须引用同一 readback-ready 召回计划草稿中的追溯段",
                        "重新选择当前召回计划草稿中的追溯段"));
                valid = false;
            }
        } else if (!segmentKey.isEmpty()) {
            issues.add(issue("EMERGENCY_DEFENSE_TRACE_SEGMENT_NOT_ALLOWED", target,
                    "不涉及市场流通的场景不接受追溯段引用", "移除追溯段引用"));
            valid = false;
        }
        return valid;
    }

    /**
     * A plan set that pins a recall plan but never rehearses it is a plan on paper only.
     * Reported once for the whole draft, not per plan.
     */
    private List<String> uncoveredTraceScenarios(List<DerivedPlan> derived) {
        if (derived.isEmpty() || derived.stream()
                .anyMatch(plan -> plan.scenarioType() == ScenarioType.RECALL_DRILL)) {
            return List.of();
        }
        return List.of("当前草稿没有召回演练");
    }

    private DerivedPlan derive(int ordinal, PlanInput input, AlertTrigger trigger) {
        List<ContainmentAction> actions = input.containmentActions().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        List<EvidenceType> evidenceTypes = input.requiredEvidence().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        ScenarioType scenario = input.scenarioType();
        ImpactTier impact = input.impactTier();
        boolean traceRequired = EmergencyDefensePlanDraftModels.traceLinkRequired(scenario);
        return new DerivedPlan(ordinal, input.planCode(), input.title(), scenario, impact,
                trigger.policyCode(), trigger.severity(), trigger.snapshotDigest(),
                traceRequired
                        ? EmergencyDefensePlanDraftModels.clean(input.traceRecallSegmentKey())
                        : null,
                actions, input.responsibleRole(), evidenceTypes, input.drillFrequencyDays(),
                EmergencyDefensePlanDraftModels.foodSafetyImpacting(scenario), traceRequired,
                EmergencyDefensePlanDraftModels.drillRequired(scenario),
                EmergencyDefensePlanDraftModels.notificationTier(scenario, impact),
                EmergencyDefensePlanDraftModels.containmentSlaMinutes(impact),
                input.changeReason());
    }

    private EmergencyDefensePlanDraftItem toRow(EmergencyDefensePlanDraft parent, DerivedPlan v) {
        return EmergencyDefensePlanDraftItem.builder()
                .id(UUID.randomUUID().toString()).draftId(parent.getId())
                .factoryId(parent.getFactoryId()).ordinal(v.ordinal())
                .planCode(v.planCode()).title(v.title())
                .scenarioType(v.scenarioType().name()).impactTier(v.impactTier().name())
                .detectionAlertPolicyCode(v.detectionAlertPolicyCode())
                .detectionAlertSeverity(v.detectionAlertSeverity())
                .detectionAlertSnapshotDigest(v.detectionAlertSnapshotDigest())
                .traceRecallSegmentKey(v.traceRecallSegmentKey())
                .containmentActions(joinEnums(v.containmentActions()))
                .responsibleRole(v.responsibleRole().name())
                .requiredEvidence(joinEnums(v.requiredEvidence()))
                .drillFrequencyDays(v.drillFrequencyDays())
                .foodSafetyImpacting(v.foodSafetyImpacting())
                .traceLinkRequired(v.traceLinkRequired())
                .drillRequired(v.drillRequired())
                .notificationTier(v.notificationTier().name())
                .containmentSlaMinutes(v.containmentSlaMinutes())
                .changeReason(v.changeReason()).build();
    }

    private DerivedPlan fromRow(EmergencyDefensePlanDraftItem row) {
        return new DerivedPlan(row.getOrdinal(), row.getPlanCode(), row.getTitle(),
                ScenarioType.valueOf(row.getScenarioType()),
                ImpactTier.valueOf(row.getImpactTier()),
                row.getDetectionAlertPolicyCode(), row.getDetectionAlertSeverity(),
                row.getDetectionAlertSnapshotDigest(), row.getTraceRecallSegmentKey(),
                parseEnums(row.getContainmentActions(), ContainmentAction::valueOf),
                ResponsibleRole.valueOf(row.getResponsibleRole()),
                parseEnums(row.getRequiredEvidence(), EvidenceType::valueOf),
                row.getDrillFrequencyDays(),
                Boolean.TRUE.equals(row.getFoodSafetyImpacting()),
                Boolean.TRUE.equals(row.getTraceLinkRequired()),
                Boolean.TRUE.equals(row.getDrillRequired()),
                NotificationTier.valueOf(row.getNotificationTier()),
                row.getContainmentSlaMinutes(), row.getChangeReason());
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "EMERGENCY_DEFENSE_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少应急与食品防护计划草稿权限",
                    "EMERGENCY_DEFENSE_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(),
                operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员",
                    "EMERGENCY_DEFENSE_MEMBER_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.plans().isEmpty() || request.plans().size() > MAX_PLANS) {
            throw invalid(400, "Blueprint envelope 或应急计划数量无效",
                    "EMERGENCY_DEFENSE_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "EMERGENCY_DEFENSE_MATERIALIZE_ENVELOPE_INVALID");
        }
    }

    private DraftReceipt receipt(EmergencyDefensePlanDraft row, boolean replayed, int moduleWrites,
                                 int persistedRows) {
        return new DraftReceipt(EmergencyDefensePlanDraftModels.SCHEMA_VERSION, row.getId(),
                row.getFactoryId(), row.getId(), row.getStatus(), row.getSourceSnapshotDigest(),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                row.getPlanCount(), row.getEvidenceCount(),
                row.getLockVersion() == null ? 0L : row.getLockVersion(), replayed,
                moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED", "ARCHIVE_DRAFT_ONLY");
    }

    private boolean sameTraceLineage(TraceRecallPlanDraft parent, DraftRequest request,
                                     String desiredDigest) {
        return parent != null && "DRAFT".equals(parent.getStatus())
                && Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                && request.blueprintId().equals(parent.getBlueprintId())
                && Objects.equals(request.blueprintVersion(), parent.getBlueprintVersion())
                && request.sourceDigest().equals(parent.getSourceDigest())
                && desiredDigest.equals(parent.getDesiredDigest());
    }

    private boolean sameAlertLineage(AlertPolicyDraft parent, DraftRequest request,
                                     String desiredDigest) {
        return parent != null && "DRAFT".equals(parent.getStatus())
                && Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                && request.blueprintId().equals(parent.getBlueprintId())
                && Objects.equals(request.blueprintVersion(), parent.getBlueprintVersion())
                && request.sourceDigest().equals(parent.getSourceDigest())
                && desiredDigest.equals(parent.getDesiredDigest());
    }

    private boolean sameEvidence(EmergencyDefensePlanDraftEvidence row,
                                 BlueprintAuthority authority) {
        return AUTHORITY_CODE.equals(row.getAuthorityCode())
                && authority.catalogVersion().equals(row.getCatalogVersion())
                && authority.catalogDigest().equals(row.getCatalogDigest())
                && authorityEvidenceDigest(authority).equals(row.getAuthoritySnapshotDigest());
    }

    private String sourceSnapshot(BlueprintAuthority authority, TraceAuthority trace,
                                  AlertAuthority alerts) {
        return digest("EMERGENCY_DEFENSE_SOURCE", authority.blueprintSnapshotDigest(),
                authority.authoritySnapshotDigest(), trace.snapshotDigest(),
                alerts.snapshotDigest());
    }

    private String digestTrace(TraceRecallPlanDraftModels.DraftReadback readback) {
        List<String> values = new ArrayList<>(List.of("TRACE_RECALL_PIN",
                readback.schemaVersion(), readback.status(), readback.factoryId(),
                readback.draftId(), readback.publishState(), readback.activationState()));
        readback.segments().stream()
                .sorted(Comparator.comparingInt(TraceRecallPlanDraftModels.DerivedSegment::ordinal))
                .forEach(segment -> values.add(segment.ordinal() + "|" + segment.segmentKey() + "|"
                        + segment.stage() + "|" + segment.direction() + "|"
                        + segment.identifierType() + "|" + segment.requiredLink() + "|"
                        + segment.sourceSnapshotDigest()));
        return digest(values.toArray(String[]::new));
    }

    private String digestAlerts(AlertPolicyDraftModels.DraftReadback readback) {
        List<String> values = new ArrayList<>(List.of("ALERT_POLICY_PIN",
                readback.schemaVersion(), readback.status(), readback.desiredDigest(),
                readback.sourceSnapshotDigest()));
        readback.policies().stream()
                .sorted(Comparator.comparingInt(AlertPolicyDraftModels.DerivedPolicy::ordinal))
                .forEach(policy -> values.add(policy.ordinal() + "|" + policy.policyCode() + "|"
                        + policy.severity() + "|" + policy.escalationRole() + "|"
                        + policy.acknowledgeSlaMinutes() + "|"
                        + policy.twoPersonAcknowledgeRequired() + "|"
                        + policy.moduleSnapshotDigest()));
        return digest(values.toArray(String[]::new));
    }

    private String desiredDigest(List<DerivedPlan> derived) {
        List<String> values = new ArrayList<>(List.of(
                EmergencyDefensePlanDraftModels.SCHEMA_VERSION,
                EmergencyDefensePlanDraftModels.ALGORITHM_VERSION));
        derived.stream().sorted(Comparator.comparingInt(DerivedPlan::ordinal))
                .forEach(value -> values.add(value.ordinal() + "|" + value.planCode() + "|"
                        + value.title() + "|" + value.scenarioType() + "|" + value.impactTier()
                        + "|" + value.detectionAlertPolicyCode() + "|"
                        + value.detectionAlertSeverity() + "|"
                        + value.detectionAlertSnapshotDigest() + "|"
                        + String.valueOf(value.traceRecallSegmentKey()) + "|"
                        + joinEnums(value.containmentActions()) + "|" + value.responsibleRole()
                        + "|" + joinEnums(value.requiredEvidence()) + "|"
                        + String.valueOf(value.drillFrequencyDays()) + "|"
                        + value.foodSafetyImpacting() + "|" + value.traceLinkRequired() + "|"
                        + value.drillRequired() + "|" + value.notificationTier() + "|"
                        + value.containmentSlaMinutes() + "|" + value.changeReason()));
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
                    .contains("uk_emergency_defense_draft_idempotency")) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private String joinEnums(List<? extends Enum<?>> values) {
        return String.join("|", values.stream().map(Enum::name).toList());
    }

    private <E> List<E> parseEnums(String value, java.util.function.Function<String, E> parser) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
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
                              TraceAuthority trace, AlertAuthority alerts) {
    }

    private record BlueprintAuthority(String catalogVersion, String catalogDigest,
                                      Set<String> authorityRefs, String authoritySnapshotDigest,
                                      String blueprintSnapshotDigest) {
        static BlueprintAuthority missing() {
            return new BlueprintAuthority("", digestStatic("MISSING_CATALOG"), Set.of(),
                    digestStatic("MISSING_AUTHORITY"), digestStatic("MISSING_BLUEPRINT"));
        }
    }

    private record TraceAuthority(String draftId, String desiredDigest, String snapshotDigest,
                                  Set<String> segmentKeys) {
        static TraceAuthority none() {
            return new TraceAuthority(null, null, digestStatic("NO_TRACE_RECALL_DRAFT"), Set.of());
        }
    }

    private record AlertAuthority(String draftId, String desiredDigest, String snapshotDigest,
                                  Map<String, AlertTrigger> policies) {
        static AlertAuthority none() {
            return new AlertAuthority(null, null, digestStatic("NO_ALERT_POLICY_DRAFT"), Map.of());
        }
    }

    private record AlertTrigger(String policyCode, String severity, String snapshotDigest) {
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
