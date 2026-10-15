package com.cretas.aims.service.tracerecalldraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteEdge;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteNode;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftItem;
import com.cretas.aims.entity.deviationcapadraft.DeviationCapaTemplateDraft;
import com.cretas.aims.entity.deviationcapadraft.DeviationCapaTemplateDraftEvidence;
import com.cretas.aims.entity.deviationcapadraft.DeviationCapaTemplateDraftRule;
import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraft;
import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraftItem;
import com.cretas.aims.entity.tracerecalldraft.TraceRecallPlanDraft;
import com.cretas.aims.entity.tracerecalldraft.TraceRecallPlanDraftEvidence;
import com.cretas.aims.entity.tracerecalldraft.TraceRecallPlanDraftSegment;
import com.cretas.aims.entity.warehousecontrol.WarehouseControlDraft;
import com.cretas.aims.entity.warehousecontrol.WarehouseControlDraftColdControl;
import com.cretas.aims.entity.warehousecontrol.WarehouseControlDraftItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftItemRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.repository.deviationcapadraft.DeviationCapaTemplateDraftEvidenceRepository;
import com.cretas.aims.repository.deviationcapadraft.DeviationCapaTemplateDraftRepository;
import com.cretas.aims.repository.deviationcapadraft.DeviationCapaTemplateDraftRuleRepository;
import com.cretas.aims.repository.qualityreleasedraft.QualityReleaseTemplateDraftItemRepository;
import com.cretas.aims.repository.qualityreleasedraft.QualityReleaseTemplateDraftRepository;
import com.cretas.aims.repository.tracerecalldraft.TraceRecallPlanDraftEvidenceRepository;
import com.cretas.aims.repository.tracerecalldraft.TraceRecallPlanDraftRepository;
import com.cretas.aims.repository.tracerecalldraft.TraceRecallPlanDraftSegmentRepository;
import com.cretas.aims.repository.warehousecontrol.WarehouseControlDraftColdControlRepository;
import com.cretas.aims.repository.warehousecontrol.WarehouseControlDraftItemRepository;
import com.cretas.aims.repository.warehousecontrol.WarehouseControlDraftRepository;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels;
import com.cretas.aims.service.bomdraft.BomRecipeDraftCommand;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftCommand;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftCommand;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.DerivedSegment;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.DraftIssue;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.DraftPreview;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.DraftReadback;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.DraftReceipt;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.DraftRequest;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.EscalationRole;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.IdentifierType;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.PlanPolicy;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.RecoveryPlan;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.SourceType;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.Stage;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftCommand;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic module-owned trace/recall preparedness PLAN/DRAFT command.
 *
 * <p>Only current-factory Blueprint and typed draft authority is read. This command never calls
 * lineage query tools, never creates a recall, and never freezes inventory, sends a notification,
 * emits an alert, or writes production, batch, shipment, supplier or customer data.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class TraceRecallPlanDraftCommand {
    public static final String CAPABILITY_ID = "trace-recall";
    public static final String REQUIRED_PERMISSION = "trace:plan:draft";
    private static final String AUTHORITY_CODE = "TRACE_RECALL_CATALOG_AUTHORITY";
    private static final List<String> DEPENDENCIES =
            FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID);
    private static final List<EscalationRole> REQUIRED_ESCALATION_ROUTE = List.of(
            EscalationRole.QUALITY_OWNER, EscalationRole.PRODUCTION_OWNER,
            EscalationRole.WAREHOUSE_OWNER, EscalationRole.FACTORY_OWNER);

    private final TraceRecallPlanDraftRepository drafts;
    private final TraceRecallPlanDraftSegmentRepository segments;
    private final TraceRecallPlanDraftEvidenceRepository evidence;
    private final DeviationCapaTemplateDraftRepository deviationDrafts;
    private final DeviationCapaTemplateDraftRuleRepository deviationRules;
    private final DeviationCapaTemplateDraftEvidenceRepository deviationEvidence;
    private final DeviationCapaTemplateDraftCommand deviationCommand;
    private final QualityReleaseTemplateDraftRepository qualityDrafts;
    private final QualityReleaseTemplateDraftItemRepository qualityItems;
    private final QualityReleaseTemplateDraftCommand qualityCommand;
    private final BomRecipeDraftRepository bomDrafts;
    private final BomRecipeDraftItemRepository bomItems;
    private final BomRecipeDraftCommand bomCommand;
    private final WarehouseControlDraftRepository warehouseDrafts;
    private final WarehouseControlDraftItemRepository warehouseItems;
    private final WarehouseControlDraftColdControlRepository coldControls;
    private final WarehouseControlDraftCommand warehouseCommand;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UserRepository users;

    public TraceRecallPlanDraftCommand(
            TraceRecallPlanDraftRepository drafts,
            TraceRecallPlanDraftSegmentRepository segments,
            TraceRecallPlanDraftEvidenceRepository evidence,
            DeviationCapaTemplateDraftRepository deviationDrafts,
            DeviationCapaTemplateDraftRuleRepository deviationRules,
            DeviationCapaTemplateDraftEvidenceRepository deviationEvidence,
            DeviationCapaTemplateDraftCommand deviationCommand,
            QualityReleaseTemplateDraftRepository qualityDrafts,
            QualityReleaseTemplateDraftItemRepository qualityItems,
            QualityReleaseTemplateDraftCommand qualityCommand,
            BomRecipeDraftRepository bomDrafts,
            BomRecipeDraftItemRepository bomItems,
            BomRecipeDraftCommand bomCommand,
            WarehouseControlDraftRepository warehouseDrafts,
            WarehouseControlDraftItemRepository warehouseItems,
            WarehouseControlDraftColdControlRepository coldControls,
            WarehouseControlDraftCommand warehouseCommand,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UserRepository users) {
        this.drafts = drafts;
        this.segments = segments;
        this.evidence = evidence;
        this.deviationDrafts = deviationDrafts;
        this.deviationRules = deviationRules;
        this.deviationEvidence = deviationEvidence;
        this.deviationCommand = deviationCommand;
        this.qualityDrafts = qualityDrafts;
        this.qualityItems = qualityItems;
        this.qualityCommand = qualityCommand;
        this.bomDrafts = bomDrafts;
        this.bomItems = bomItems;
        this.bomCommand = bomCommand;
        this.warehouseDrafts = warehouseDrafts;
        this.warehouseItems = warehouseItems;
        this.coldControls = coldControls;
        this.warehouseCommand = warehouseCommand;
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
        Optional<TraceRecallPlanDraft> prior = drafts.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同追溯/召回准备计划请求",
                        "TRACE_RECALL_PLAN_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview plan = evaluation.preview();
        if (!"READY".equals(plan.status())) {
            throw invalid(409, "追溯/召回准备计划预览被阻断",
                    "TRACE_RECALL_PLAN_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(plan.previewDigest())) {
            throw invalid(409, "追溯/召回准备计划确认摘要已漂移",
                    "TRACE_RECALL_PLAN_PREVIEW_DRIFT");
        }

        Upstream upstream = evaluation.upstream();
        BlueprintAuthority authority = evaluation.authority();
        PlanPolicy policy = request.policy();
        TraceRecallPlanDraft parent = TraceRecallPlanDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId()).blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion()).sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(plan.sourceSnapshotDigest())
                .deviationCapaTemplateDraftId(upstream.deviation().getId())
                .deviationCapaDesiredDigest(upstream.deviation().getDesiredDigest())
                .deviationCapaSnapshotDigest(plan.deviationCapaSnapshotDigest())
                .qualityReleaseTemplateDraftId(upstream.quality().getId())
                .qualityReleaseDesiredDigest(upstream.quality().getDesiredDigest())
                .qualityReleaseSnapshotDigest(plan.qualityReleaseSnapshotDigest())
                .bomDraftId(upstream.bom().getId()).bomDesiredDigest(upstream.bom().getDesiredDigest())
                .bomSnapshotDigest(plan.bomSnapshotDigest())
                .warehouseControlDraftId(upstream.warehouse().getId())
                .warehouseDesiredDigest(upstream.warehouse().getDesiredDigest())
                .warehouseSnapshotDigest(plan.warehouseSnapshotDigest())
                .routeSnapshotDigest(plan.routeSnapshotDigest())
                .capabilityCatalogVersion(authority.catalogVersion())
                .capabilityCatalogDigest(authority.catalogDigest())
                .authoritySnapshotDigest(plan.authoritySnapshotDigest())
                .planAlgorithmVersion(TraceRecallPlanDraftModels.PLAN_ALGORITHM_VERSION)
                .traceDirection(policy.direction().name())
                .maxTraversalDepth(policy.maxTraversalDepth())
                .maxTraversalNodes(policy.maxTraversalNodes())
                .traceCompletionMinutes(policy.traceCompletionMinutes())
                .massBalanceToleranceBasisPoints(policy.massBalanceToleranceBasisPoints())
                .mockRecallFrequencyDays(policy.mockRecallFrequencyDays())
                .evidenceRetentionDays(policy.evidenceRetentionDays())
                .escalationRoute(escalationRoute(policy.escalationRoles()))
                .policyChangeReason(policy.changeReason())
                .desiredDigest(plan.desiredDigest()).previewDigest(plan.previewDigest())
                .requestDigest(requestDigest).segmentCount(plan.segments().size()).evidenceCount(1)
                .dependencyBasis(TraceRecallPlanDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true).idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id()).status("DRAFT").build();
        TraceRecallPlanDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "追溯/召回准备计划幂等键发生并发竞争",
                    "TRACE_RECALL_PLAN_IDEMPOTENCY_RACE");
        }

        List<TraceRecallPlanDraftSegment> rows = plan.segments().stream()
                .map(item -> toRow(saved.getId(), request.factoryId(), item)).toList();
        segments.saveAllAndFlush(rows);
        evidence.saveAndFlush(TraceRecallPlanDraftEvidence.builder()
                .id(UUID.randomUUID().toString()).draftId(saved.getId())
                .factoryId(request.factoryId()).evidenceType("CATALOG_AUTHORITY")
                .authorityCode(AUTHORITY_CODE)
                .authoritySnapshotDigest(authorityEvidenceDigest(authority)).build());

        DraftReadback readback = readBack(saved.getFactoryId(), saved.getId());
        if (!plan.desiredDigest().equals(desiredDigest(readback.desired()))) {
            throw invalid(409, "追溯/召回准备计划逐字段回读不一致",
                    "TRACE_RECALL_PLAN_READBACK_MISMATCH");
        }
        DraftPreview current = evaluate(request).preview();
        if (!sameAuthority(plan, current)) {
            throw invalid(409, "追溯/召回准备计划写入期间依赖发生漂移",
                    "TRACE_RECALL_PLAN_AUTHORITY_DRIFT");
        }
        return receipt(saved, false, 1, 1 + rows.size() + 1);
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        TraceRecallPlanDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该追溯/召回准备计划草稿",
                        "TRACE_RECALL_PLAN_NOT_FOUND"));
        List<TraceRecallPlanDraftSegment> rows = segments
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<TraceRecallPlanDraftEvidence> evidenceRows = evidence
                .findByDraftIdAndFactoryIdOrderByAuthorityCode(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !TraceRecallPlanDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !TraceRecallPlanDraftModels.PLAN_ALGORITHM_VERSION.equals(
                        parent.getPlanAlgorithmVersion())
                || parent.getSegmentCount() != rows.size()
                || parent.getEvidenceCount() != evidenceRows.size()
                || evidenceRows.size() != 1
                || !"CATALOG_AUTHORITY".equals(evidenceRows.get(0).getEvidenceType())
                || !AUTHORITY_CODE.equals(evidenceRows.get(0).getAuthorityCode())) {
            throw invalid(409, "追溯/召回准备计划父子行、状态或算法不一致",
                    "TRACE_RECALL_PLAN_READBACK_MISMATCH");
        }
        BlueprintAuthority storedAuthority = new BlueprintAuthority(parent.getCapabilityCatalogVersion(),
                parent.getCapabilityCatalogDigest(), Set.of(), parent.getAuthoritySnapshotDigest(),
                parent.getRouteSnapshotDigest(), "", List.of());
        if (!evidenceRows.get(0).getAuthoritySnapshotDigest().equals(
                authorityEvidenceDigest(storedAuthority))) {
            throw invalid(409, "追溯/召回准备计划目录依据摘要不一致",
                    "TRACE_RECALL_PLAN_EVIDENCE_MISMATCH");
        }

        PlanPolicy policy = new PlanPolicy(
                TraceRecallPlanDraftModels.TraceDirection.valueOf(parent.getTraceDirection()),
                parent.getMaxTraversalDepth(), parent.getMaxTraversalNodes(),
                parent.getTraceCompletionMinutes(), parent.getMassBalanceToleranceBasisPoints(),
                parent.getMockRecallFrequencyDays(), parent.getEvidenceRetentionDays(),
                parseEscalationRoute(parent.getEscalationRoute()), parent.getPolicyChangeReason());
        Long readbackOperatorId = parent.getOperatorId() == null ? parent.getActorId() : parent.getOperatorId();
        if (readbackOperatorId == null) throw invalid(409, "草稿缺少 typed actor 身份，无法安全回读", "DRAFT_ACTOR_IDENTITY_MISSING");
        DraftRequest desired = new DraftRequest(parent.getFactoryId(), parent.getBlueprintId(),
                parent.getBlueprintVersion(), parent.getSourceDigest(),
                new TraceRecallPlanDraftModels.DraftPin(parent.getDeviationCapaTemplateDraftId(),
                        parent.getDeviationCapaDesiredDigest()),
                new TraceRecallPlanDraftModels.DraftPin(parent.getQualityReleaseTemplateDraftId(),
                        parent.getQualityReleaseDesiredDigest()),
                new TraceRecallPlanDraftModels.DraftPin(parent.getBomDraftId(),
                        parent.getBomDesiredDigest()),
                new TraceRecallPlanDraftModels.DraftPin(parent.getWarehouseControlDraftId(),
                        parent.getWarehouseDesiredDigest()),
                policy, parent.getPreviewDigest(), parent.getIdempotencyKey(), readbackOperatorId);
        List<DerivedSegment> storedSegments = rows.stream().map(this::fromRow).toList();
        Evaluation current = evaluate(desired);
        if (!"READY".equals(current.preview().status())
                || !parent.getDesiredDigest().equals(desiredDigest(desired))
                || !sameStoredAuthority(parent, current.preview())
                || !storedSegments.equals(current.preview().segments())) {
            throw invalid(409, "追溯/召回准备计划当前依赖或逐字段回读已漂移",
                    "TRACE_RECALL_PLAN_AUTHORITY_DRIFT");
        }
        return new DraftReadback(TraceRecallPlanDraftModels.SCHEMA_VERSION, factoryId,
                draftId, parent.getStatus(), desired, storedSegments,
                0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recovery(String factoryId, String draftId, String expectedDesiredDigest) {
        TraceRecallPlanDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该追溯/召回准备计划草稿",
                        "TRACE_RECALL_PLAN_NOT_FOUND"));
        if (!isDigest(expectedDesiredDigest)
                || !expectedDesiredDigest.equals(parent.getDesiredDigest())) {
            throw invalid(409, "恢复目标摘要与追溯/召回准备计划不一致",
                    "TRACE_RECALL_PLAN_RECOVERY_DIGEST_MISMATCH");
        }
        return new RecoveryPlan(TraceRecallPlanDraftModels.SCHEMA_VERSION, factoryId, draftId,
                expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "仅归档未发布准备计划；不查询真实谱系，不创建召回，不冻结库存，不发送告警或通知",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(request, issues);
        Upstream upstream = resolveUpstream(request, issues);
        validatePolicy(request.policy(), issues);
        List<DerivedSegment> derived = deriveSegments(request.policy(), authority, upstream);
        if (derived.isEmpty()) {
            issues.add(issue("TRACE_RECALL_PLAN_COVERAGE_EMPTY", "dependencies",
                    "路线、BOM、仓库、质量与偏差草稿没有可编译的追溯字段",
                    "补齐本厂当前 Blueprint 依赖草稿后重新预览"));
        }
        if (derived.size() > request.policy().maxTraversalNodes()) {
            issues.add(issue("TRACE_RECALL_PLAN_NODE_LIMIT_TOO_SMALL", "policy.maxTraversalNodes",
                    "遍历节点上限小于确定性计划字段数量",
                    "提高 typed 节点上限或精简本厂 Blueprint 范围"));
        }
        if (derived.size() > 10000) {
            issues.add(issue("TRACE_RECALL_PLAN_SEGMENT_LIMIT_EXCEEDED", "dependencies",
                    "追溯字段计划超过 10000 项安全上限", "拆分 Blueprint 产品范围"));
        }
        String desired = desiredDigest(request);
        String sourceSnapshot = digest("TRACE_RECALL_SOURCE", authority.blueprintSnapshotDigest(),
                authority.routeSnapshotDigest(), upstream.bomSnapshotDigest(),
                upstream.warehouseSnapshotDigest(), upstream.qualitySnapshotDigest(),
                upstream.deviationSnapshotDigest());
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("TRACE_RECALL_PREVIEW",
                TraceRecallPlanDraftModels.SCHEMA_VERSION,
                TraceRecallPlanDraftModels.PLAN_ALGORITHM_VERSION, status, sourceSnapshot,
                authority.routeSnapshotDigest(), upstream.bomSnapshotDigest(),
                upstream.warehouseSnapshotDigest(), upstream.qualitySnapshotDigest(),
                upstream.deviationSnapshotDigest(), authority.authoritySnapshotDigest(), desired,
                digestSegments(derived), issueDigest(issues));
        DraftPreview preview = new DraftPreview(TraceRecallPlanDraftModels.SCHEMA_VERSION,
                status, request.factoryId(), DEPENDENCIES, request, derived, sourceSnapshot,
                authority.routeSnapshotDigest(), upstream.bomSnapshotDigest(),
                upstream.warehouseSnapshotDigest(), upstream.qualitySnapshotDigest(),
                upstream.deviationSnapshotDigest(), authority.authoritySnapshotDigest(), desired,
                previewDigest, issues, 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, authority, upstream);
    }

    private BlueprintAuthority resolveBlueprint(DraftRequest request, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(request.factoryId());
        if (response == null || !request.blueprintId().equals(response.getId())
                || !Objects.equals(Math.toIntExact(request.blueprintVersion()), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                    || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("TRACE_RECALL_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
                    "当前工厂 Blueprint DRAFT/VALIDATED 版本与请求不一致",
                    "刷新当前 Blueprint 后重新预览"));
            return BlueprintAuthority.missing();
        }
        CapabilityView capability = response.getCapabilities() == null ? null
                : response.getCapabilities().stream()
                .filter(item -> CAPABILITY_ID.equals(item.getId())).findFirst().orElse(null);
        Set<String> dependencies = capability == null || capability.getDependsOn() == null
                ? Set.of() : new HashSet<>(capability.getDependsOn());
        if (capability == null || !capability.isIncluded() || !"READY".equals(capability.getStatus())
                || !dependencies.equals(Set.copyOf(DEPENDENCIES))) {
            issues.add(issue("TRACE_RECALL_CAPABILITY_NOT_READY", "capability",
                    "trace-recall 或 routes/bom/warehouses/quality-release 依赖尚未就绪",
                    "补齐当前模块草稿后重新编译 Blueprint"));
        }
        Set<String> refs = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || refs.isEmpty()) {
            issues.add(issue("TRACE_RECALL_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本、摘要或追溯召回依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        List<RouteGraph> graphs = response.getRouteGraphs() == null
                ? List.of() : response.getRouteGraphs();
        if (graphs.isEmpty() || graphs.stream().anyMatch(graph -> graph == null
                || graph.validation() == null || !graph.validation().valid()
                || graph.nodes().isEmpty())) {
            issues.add(issue("TRACE_RECALL_ROUTE_GRAPH_INVALID", "routes",
                    "当前 Blueprint 没有完整可验证的路线图", "修复路线节点、连线和起止点"));
        }
        String routeSnapshot = routeSnapshot(graphs);
        String authoritySnapshot = digest("TRACE_RECALL_AUTHORITY",
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                refs.stream().sorted().reduce("", (left, right) -> left + "|" + right));
        return new BlueprintAuthority(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), refs, authoritySnapshot, routeSnapshot,
                digestBlueprint(response), List.copyOf(graphs));
    }

    private Upstream resolveUpstream(DraftRequest request, List<DraftIssue> issues) {
        DeviationCapaTemplateDraft deviation = request.deviationCapa() == null ? null
                : deviationDrafts.findByIdAndFactoryId(request.deviationCapa().draftId(),
                        request.factoryId()).orElse(null);
        QualityReleaseTemplateDraft quality = request.qualityRelease() == null ? null
                : qualityDrafts.findByIdAndFactoryId(request.qualityRelease().draftId(),
                        request.factoryId()).orElse(null);
        BomRecipeDraft bom = request.bom() == null ? null
                : bomDrafts.findByIdAndFactoryId(request.bom().draftId(), request.factoryId())
                        .orElse(null);
        WarehouseControlDraft warehouse = request.warehouse() == null ? null
                : warehouseDrafts.findByIdAndFactoryId(request.warehouse().draftId(),
                        request.factoryId()).orElse(null);
        boolean validDeviation = validDeviation(request, deviation);
        boolean validQuality = validDeviation && validQuality(request, quality, deviation);
        boolean validBom = validQuality && validBom(request, bom, quality);
        boolean validWarehouse = validWarehouse(request, warehouse);
        if (!validDeviation) issues.add(issue("TRACE_RECALL_DEVIATION_CAPA_DRIFT", "deviationCapa",
                "deviation-capa DRAFT 不存在、lineage 不一致或摘要已变化",
                "选择同 Blueprint 的当前 deviation-capa DRAFT"));
        if (!validQuality) issues.add(issue("TRACE_RECALL_QUALITY_RELEASE_DRIFT", "qualityRelease",
                "quality-release DRAFT 不存在、B8 链路不一致或摘要已变化",
                "选择 B8 锁定的当前 quality-release DRAFT"));
        if (!validBom) issues.add(issue("TRACE_RECALL_BOM_DRIFT", "bom",
                "BOM DRAFT 不存在、B7 链路不一致或摘要已变化",
                "选择 B7 锁定的当前 BOM DRAFT"));
        if (!validWarehouse) issues.add(issue("TRACE_RECALL_WAREHOUSE_DRIFT", "warehouse",
                "warehouse DRAFT 不存在、lineage 不一致或摘要已变化",
                "选择同 Blueprint 的当前 warehouse DRAFT"));
        if (!(validDeviation && validQuality && validBom && validWarehouse)) return Upstream.missing();

        try {
            var readback = deviationCommand.readBack(request.factoryId(), deviation.getId());
            if (readback == null || !deviation.getId().equals(readback.draftId())
                    || !"DRAFT".equals(readback.status())) {
                issues.add(issue("TRACE_RECALL_DEVIATION_CAPA_AUTHORITY_BLOCKED", "deviationCapa",
                        "deviation-capa DRAFT 当前不能逐字段回读", "重新生成 readback-ready B8 DRAFT"));
            }
        } catch (BusinessException exception) {
            issues.add(issue("TRACE_RECALL_DEVIATION_CAPA_AUTHORITY_BLOCKED", "deviationCapa",
                    "deviation-capa DRAFT 当前依赖已漂移或不能回读", "修复或重新生成 B8 DRAFT"));
        }
        try {
            var readback = qualityCommand.readBack(request.factoryId(), quality.getId());
            if (readback == null || !quality.getId().equals(readback.draftId())
                    || !"DRAFT".equals(readback.status())) {
                issues.add(issue("TRACE_RECALL_QUALITY_RELEASE_AUTHORITY_BLOCKED", "qualityRelease",
                        "quality-release DRAFT 当前不能逐字段回读", "重新生成 readback-ready B7 DRAFT"));
            }
        } catch (BusinessException exception) {
            issues.add(issue("TRACE_RECALL_QUALITY_RELEASE_AUTHORITY_BLOCKED", "qualityRelease",
                    "quality-release DRAFT 当前依赖已漂移或不能回读", "修复或重新生成 B7 DRAFT"));
        }
        try {
            var readback = bomCommand.readBack(request.factoryId(), bom.getId());
            if (readback == null || !bom.getId().equals(readback.draftId())
                    || !"DRAFT".equals(readback.status())) {
                issues.add(issue("TRACE_RECALL_BOM_AUTHORITY_BLOCKED", "bom",
                        "BOM DRAFT 当前不能逐字段回读", "重新生成 readback-ready B4 DRAFT"));
            }
        } catch (BusinessException exception) {
            issues.add(issue("TRACE_RECALL_BOM_AUTHORITY_BLOCKED", "bom",
                    "BOM DRAFT 当前依赖已漂移或不能回读", "修复或重新生成 B4 DRAFT"));
        }
        try {
            var readback = warehouseCommand.readBack(request.factoryId(), warehouse.getId());
            if (readback == null || !warehouse.getId().equals(readback.draftId())
                    || !"DRAFT".equals(readback.status())) {
                issues.add(issue("TRACE_RECALL_WAREHOUSE_AUTHORITY_BLOCKED", "warehouse",
                        "warehouse DRAFT 当前不能逐字段回读", "重新生成 readback-ready warehouse DRAFT"));
            }
        } catch (BusinessException exception) {
            issues.add(issue("TRACE_RECALL_WAREHOUSE_AUTHORITY_BLOCKED", "warehouse",
                    "warehouse DRAFT 当前依赖已漂移或不能回读", "修复或重新生成 warehouse DRAFT"));
        }

        List<DeviationCapaTemplateDraftRule> deviationRuleRows = deviationRules
                .findByDraftIdAndFactoryIdOrderByOrdinal(deviation.getId(), request.factoryId());
        List<DeviationCapaTemplateDraftEvidence> deviationEvidenceRows = deviationEvidence
                .findByDraftIdAndFactoryIdOrderByRuleIdAscAuthorityCodeAsc(
                        deviation.getId(), request.factoryId());
        List<QualityReleaseTemplateDraftItem> qualityItemRows = qualityItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(quality.getId(), request.factoryId());
        List<BomRecipeDraftItem> bomItemRows = bomItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(bom.getId(), request.factoryId());
        List<WarehouseControlDraftItem> warehouseItemRows = warehouseItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(warehouse.getId(), request.factoryId());
        List<WarehouseControlDraftColdControl> coldRows = coldControls
                .findByDraftIdAndFactoryIdOrderByOrdinal(warehouse.getId(), request.factoryId());
        if (deviation.getRuleCount() != deviationRuleRows.size()
                || deviation.getEvidenceCount() != deviationEvidenceRows.size()
                || quality.getItemCount() != qualityItemRows.size()
                || bom.getItemCount() != bomItemRows.size()
                || warehouse.getWarehouseCount() != warehouseItemRows.size()
                || warehouse.getColdControlCount() != coldRows.size()) {
            issues.add(issue("TRACE_RECALL_UPSTREAM_ROW_COUNT_MISMATCH", "dependencies",
                    "B8/B7/B4/warehouse 父子行数量不一致", "修复上游草稿后重新预览"));
        }
        return new Upstream(deviation, quality, bom, warehouse, deviationRuleRows,
                qualityItemRows, bomItemRows, warehouseItemRows, coldRows,
                deviationSnapshot(deviation, deviationRuleRows, deviationEvidenceRows),
                qualitySnapshot(quality, qualityItemRows), bomSnapshot(bom, bomItemRows),
                warehouseSnapshot(warehouse, warehouseItemRows, coldRows));
    }

    private boolean validDeviation(DraftRequest request, DeviationCapaTemplateDraft row) {
        return row != null && "DRAFT".equals(row.getStatus())
                && Boolean.TRUE.equals(row.getDependencyClosureComplete())
                && DeviationCapaTemplateDraftModels.DEPENDENCY_BASIS.equals(row.getDependencyBasis())
                && DeviationCapaTemplateDraftModels.DECISION_ALGORITHM_VERSION.equals(
                        row.getClassificationAlgorithmVersion())
                && sameLineage(request, row.getBlueprintId(), row.getBlueprintVersion(),
                        row.getSourceDigest())
                && request.deviationCapa().desiredDigest().equals(row.getDesiredDigest());
    }

    private boolean validQuality(DraftRequest request, QualityReleaseTemplateDraft row,
                                 DeviationCapaTemplateDraft deviation) {
        return row != null && "DRAFT".equals(row.getStatus())
                && Boolean.TRUE.equals(row.getDependencyClosureComplete())
                && QualityReleaseTemplateDraftModels.DEPENDENCY_BASIS.equals(row.getDependencyBasis())
                && QualityReleaseTemplateDraftModels.DECISION_ALGORITHM_VERSION.equals(
                        row.getDecisionAlgorithmVersion())
                && sameLineage(request, row.getBlueprintId(), row.getBlueprintVersion(),
                        row.getSourceDigest())
                && request.qualityRelease().desiredDigest().equals(row.getDesiredDigest())
                && row.getId().equals(deviation.getQualityReleaseTemplateDraftId())
                && row.getDesiredDigest().equals(deviation.getQualityReleaseDesiredDigest());
    }

    private boolean validBom(DraftRequest request, BomRecipeDraft row,
                             QualityReleaseTemplateDraft quality) {
        return row != null && "DRAFT".equals(row.getStatus())
                && Boolean.TRUE.equals(row.getDependencyClosureComplete())
                && BomRecipeDraftModels.DEPENDENCY_BASIS.equals(row.getDependencyBasis())
                && sameLineage(request, row.getBlueprintId(), row.getBlueprintVersion(),
                        row.getSourceDigest())
                && request.bom().desiredDigest().equals(row.getDesiredDigest())
                && row.getId().equals(quality.getBomDraftId())
                && row.getDesiredDigest().equals(quality.getBomDesiredDigest());
    }

    private boolean validWarehouse(DraftRequest request, WarehouseControlDraft row) {
        return row != null && "DRAFT".equals(row.getStatus())
                && Boolean.TRUE.equals(row.getDependencyClosureComplete())
                && WarehouseControlDraftModels.DEPENDENCY_BASIS.equals(row.getDependencyBasis())
                && sameLineage(request, row.getBlueprintId(), row.getBlueprintVersion(),
                        row.getSourceDigest())
                && request.warehouse().desiredDigest().equals(row.getDesiredDigest());
    }

    private boolean sameLineage(DraftRequest request, String blueprintId,
                                Long blueprintVersion, String sourceDigest) {
        return request.blueprintId().equals(blueprintId)
                && request.blueprintVersion() == blueprintVersion
                && request.sourceDigest().equals(sourceDigest);
    }

    private List<DerivedSegment> deriveSegments(PlanPolicy policy, BlueprintAuthority authority,
                                                Upstream upstream) {
        if (upstream.deviation() == null) return List.of();
        List<SegmentSeed> seeds = new ArrayList<>();
        List<RouteGraph> graphs = authority.routeGraphs();
        graphs.stream().sorted(Comparator.comparing(graph -> clean(graph.productCode())))
                .forEach(graph -> {
                    graph.nodes().stream().sorted(Comparator.comparing(RouteNode::id)).forEach(node ->
                            seeds.add(new SegmentSeed(SourceType.ROUTE_NODE,
                                    clean(graph.productCode()) + ":" + node.id(),
                                    digest("ROUTE_NODE", clean(graph.productCode()), node.id(), node.label()),
                                    Stage.PRODUCTION_TRANSFORMATION, IdentifierType.PRODUCTION_BATCH, true)));
                    graph.terminalNodeIds().stream().sorted().forEach(nodeId ->
                            seeds.add(new SegmentSeed(SourceType.ROUTE_NODE,
                                    clean(graph.productCode()) + ":" + nodeId + ":DISPATCH",
                                    digest("ROUTE_TERMINAL", clean(graph.productCode()), nodeId),
                                    Stage.CUSTOMER_DISPATCH, IdentifierType.SHIPMENT_REFERENCE, true)));
                });
        upstream.bomItems().stream().filter(item -> !Boolean.TRUE.equals(item.getOptional()))
                .sorted(Comparator.comparing(BomRecipeDraftItem::getOrdinal)).forEach(item ->
                        seeds.add(new SegmentSeed(SourceType.BOM_ITEM, item.getId(),
                                bomItemSnapshot(item), Stage.SUPPLIER_RECEIPT,
                                IdentifierType.SUPPLIER_LOT, true)));
        upstream.warehouseItems().stream().filter(item -> Boolean.TRUE.equals(item.getDesiredActive()))
                .sorted(Comparator.comparing(WarehouseControlDraftItem::getOrdinal)).forEach(item -> {
                    boolean finished = String.valueOf(item.getWarehouseType()).contains("FINISHED");
                    seeds.add(new SegmentSeed(SourceType.WAREHOUSE, item.getId(),
                            warehouseItemSnapshot(item), finished ? Stage.FINISHED_GOODS_STORAGE
                                    : Stage.MATERIAL_STORAGE,
                            finished ? IdentifierType.FINISHED_LOT : IdentifierType.MATERIAL_BATCH,
                            true));
                });
        upstream.qualityItems().stream().filter(item -> Boolean.TRUE.equals(item.getRequired()))
                .sorted(Comparator.comparing(QualityReleaseTemplateDraftItem::getOrdinal))
                .forEach(item -> seeds.add(new SegmentSeed(SourceType.QUALITY_RELEASE_SPEC,
                        item.getId(), qualityItemSnapshot(item), Stage.QUALITY_DISPOSITION,
                        IdentifierType.QUALITY_DECISION_REFERENCE, false)));
        upstream.deviationRules().stream()
                .sorted(Comparator.comparing(DeviationCapaTemplateDraftRule::getOrdinal))
                .forEach(item -> seeds.add(new SegmentSeed(SourceType.DEVIATION_RULE,
                        item.getId(), deviationRuleSnapshot(item), Stage.QUALITY_DISPOSITION,
                        IdentifierType.QUALITY_DECISION_REFERENCE, false)));
        List<DerivedSegment> result = new ArrayList<>();
        for (int index = 0; index < seeds.size(); index++) {
            SegmentSeed seed = seeds.get(index);
            result.add(new DerivedSegment(index, seed.sourceType(), seed.sourceRefId(),
                    seed.sourceSnapshotDigest(), seed.stage(), policy.direction(),
                    seed.identifierType(), true, seed.quantityCapture(), true,
                    policy.traceCompletionMinutes(), policy.evidenceRetentionDays(),
                    digest("TRACE_SEGMENT", seed.sourceType().name(), seed.sourceRefId(),
                            seed.stage().name(), policy.direction().name())));
        }
        return List.copyOf(result);
    }

    private void validatePolicy(PlanPolicy policy, List<DraftIssue> issues) {
        if (policy == null) return;
        if (policy.direction() == null) issues.add(issue("TRACE_RECALL_DIRECTION_REQUIRED",
                "policy.direction", "追溯方向必须明确", "选择上游、下游或双向"));
        if (policy.maxTraversalDepth() < 1 || policy.maxTraversalDepth() > 10)
            issues.add(issue("TRACE_RECALL_DEPTH_INVALID", "policy.maxTraversalDepth",
                    "最大深度必须为 1–10，不能静默修正", "填写合法的 typed 深度"));
        if (policy.maxTraversalNodes() < 10 || policy.maxTraversalNodes() > 10000)
            issues.add(issue("TRACE_RECALL_NODE_LIMIT_INVALID", "policy.maxTraversalNodes",
                    "最大节点数必须为 10–10000", "填写合法的 typed 节点上限"));
        if (policy.traceCompletionMinutes() < 1 || policy.traceCompletionMinutes() > 1440)
            issues.add(issue("TRACE_RECALL_SLA_INVALID", "policy.traceCompletionMinutes",
                    "追溯完成时限必须为 1–1440 分钟", "填写合法完成时限"));
        if (policy.massBalanceToleranceBasisPoints() < 0
                || policy.massBalanceToleranceBasisPoints() > 1000)
            issues.add(issue("TRACE_RECALL_MASS_BALANCE_INVALID",
                    "policy.massBalanceToleranceBasisPoints", "质量平衡容差必须为 0–1000 基点",
                    "填写合法 typed 容差"));
        if (policy.mockRecallFrequencyDays() < 1 || policy.mockRecallFrequencyDays() > 365)
            issues.add(issue("TRACE_RECALL_MOCK_FREQUENCY_INVALID", "policy.mockRecallFrequencyDays",
                    "模拟召回周期必须为 1–365 天", "填写合法演练周期"));
        if (policy.evidenceRetentionDays() < 1 || policy.evidenceRetentionDays() > 36500)
            issues.add(issue("TRACE_RECALL_RETENTION_INVALID", "policy.evidenceRetentionDays",
                    "证据留存必须为 1–36500 天", "填写合法留存期"));
        if (!REQUIRED_ESCALATION_ROUTE.equals(policy.escalationRoles()))
            issues.add(issue("TRACE_RECALL_ESCALATION_ROUTE_INVALID", "policy.escalationRoles",
                    "升级职责必须依次覆盖质量、生产、仓库和工厂负责人",
                    "使用平台固定职责分离路线"));
        if (isBlankOrTooLong(policy.changeReason(), 500))
            issues.add(issue("TRACE_RECALL_CHANGE_REASON_REQUIRED", "policy.changeReason",
                    "计划变更原因不能为空且不得超过 500 字符", "补充本厂当前回答对应的原因"));
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId()))
            throw invalid(403, "操作人、请求和工厂范围不一致",
                    "TRACE_RECALL_PLAN_TENANT_FORBIDDEN");
        if (!operator.permissions().contains(REQUIRED_PERMISSION))
            throw invalid(403, "缺少追溯/召回准备计划草稿权限",
                    "TRACE_RECALL_PLAN_PERMISSION_REQUIRED");
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(), operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty())
            throw invalid(403, "操作人不是当前工厂有效成员",
                    "TRACE_RECALL_PLAN_MEMBER_REQUIRED");
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || isBlankOrTooLong(request.factoryId(), 50)
                || isBlankOrTooLong(request.blueprintId(), 100)
                || request.blueprintVersion() < 1 || request.blueprintVersion() > Integer.MAX_VALUE
                || !isDigest(request.sourceDigest()) || !validPin(request.deviationCapa())
                || !validPin(request.qualityRelease()) || !validPin(request.bom())
                || !validPin(request.warehouse()) || request.policy() == null
                || request.operatorId() < 1 || isBlankOrTooLong(request.idempotencyKey(), 128)
                || (materialize && !isDigest(request.confirmedPreviewDigest())))
            throw invalid(400, "追溯/召回准备计划请求 envelope 无效",
                    "TRACE_RECALL_PLAN_REQUEST_INVALID");
    }

    private boolean validPin(TraceRecallPlanDraftModels.DraftPin pin) {
        return pin != null && !isBlankOrTooLong(pin.draftId(), 36) && isDigest(pin.desiredDigest());
    }

    private TraceRecallPlanDraftSegment toRow(String draftId, String factoryId, DerivedSegment item) {
        return TraceRecallPlanDraftSegment.builder().id(UUID.randomUUID().toString())
                .draftId(draftId).factoryId(factoryId).ordinal(item.ordinal())
                .segmentKey(item.segmentKey()).sourceType(item.sourceType().name())
                .sourceRefId(item.sourceRefId()).sourceSnapshotDigest(item.sourceSnapshotDigest())
                .stage(item.stage().name()).traceDirection(item.direction().name())
                .identifierType(item.identifierType().name()).requiredLink(item.requiredLink())
                .quantityCaptureRequired(item.quantityCaptureRequired())
                .evidenceRequired(item.evidenceRequired())
                .completionSlaMinutes(item.completionSlaMinutes())
                .evidenceRetentionDays(item.evidenceRetentionDays()).build();
    }

    private DerivedSegment fromRow(TraceRecallPlanDraftSegment row) {
        return new DerivedSegment(row.getOrdinal(), SourceType.valueOf(row.getSourceType()),
                row.getSourceRefId(), row.getSourceSnapshotDigest(), Stage.valueOf(row.getStage()),
                TraceRecallPlanDraftModels.TraceDirection.valueOf(row.getTraceDirection()),
                IdentifierType.valueOf(row.getIdentifierType()), row.getRequiredLink(),
                row.getQuantityCaptureRequired(), row.getEvidenceRequired(),
                row.getCompletionSlaMinutes(), row.getEvidenceRetentionDays(), row.getSegmentKey());
    }

    private DraftReceipt receipt(TraceRecallPlanDraft row, boolean replayed,
                                 int moduleWrites, int persistedRows) {
        return new DraftReceipt(TraceRecallPlanDraftModels.SCHEMA_VERSION, row.getId(),
                row.getFactoryId(), row.getId(), row.getStatus(), row.getSourceSnapshotDigest(),
                row.getRouteSnapshotDigest(), row.getBomSnapshotDigest(),
                row.getWarehouseSnapshotDigest(), row.getQualityReleaseSnapshotDigest(),
                row.getDeviationCapaSnapshotDigest(), row.getAuthoritySnapshotDigest(),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                row.getSegmentCount(), row.getEvidenceCount(),
                row.getLockVersion() == null ? 0L : row.getLockVersion(), replayed,
                moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED", "ARCHIVE_DRAFT_ONLY");
    }

    private boolean sameAuthority(DraftPreview left, DraftPreview right) {
        return Objects.equals(left.status(), right.status())
                && Objects.equals(left.sourceSnapshotDigest(), right.sourceSnapshotDigest())
                && Objects.equals(left.routeSnapshotDigest(), right.routeSnapshotDigest())
                && Objects.equals(left.bomSnapshotDigest(), right.bomSnapshotDigest())
                && Objects.equals(left.warehouseSnapshotDigest(), right.warehouseSnapshotDigest())
                && Objects.equals(left.qualityReleaseSnapshotDigest(), right.qualityReleaseSnapshotDigest())
                && Objects.equals(left.deviationCapaSnapshotDigest(), right.deviationCapaSnapshotDigest())
                && Objects.equals(left.authoritySnapshotDigest(), right.authoritySnapshotDigest())
                && Objects.equals(left.desiredDigest(), right.desiredDigest())
                && Objects.equals(left.previewDigest(), right.previewDigest())
                && Objects.equals(left.segments(), right.segments());
    }

    private boolean sameStoredAuthority(TraceRecallPlanDraft row, DraftPreview current) {
        return row.getSourceSnapshotDigest().equals(current.sourceSnapshotDigest())
                && row.getRouteSnapshotDigest().equals(current.routeSnapshotDigest())
                && row.getBomSnapshotDigest().equals(current.bomSnapshotDigest())
                && row.getWarehouseSnapshotDigest().equals(current.warehouseSnapshotDigest())
                && row.getQualityReleaseSnapshotDigest().equals(current.qualityReleaseSnapshotDigest())
                && row.getDeviationCapaSnapshotDigest().equals(current.deviationCapaSnapshotDigest())
                && row.getAuthoritySnapshotDigest().equals(current.authoritySnapshotDigest())
                && row.getPreviewDigest().equals(current.previewDigest());
    }

    private String routeSnapshot(List<RouteGraph> graphs) {
        List<String> values = new ArrayList<>();
        graphs.stream().sorted(Comparator.comparing(graph -> clean(graph.productCode())))
                .forEach(graph -> {
                    values.add(clean(graph.productCode())); values.add(clean(graph.productName()));
                    graph.nodes().stream().sorted(Comparator.comparing(RouteNode::id))
                            .forEach(node -> { values.add(node.id()); values.add(node.label()); });
                    graph.edges().stream().sorted(Comparator.comparing(RouteEdge::fromNodeId)
                                    .thenComparing(RouteEdge::toNodeId)
                                    .thenComparing(edge -> edge.type().name()))
                            .forEach(edge -> { values.add(edge.fromNodeId()); values.add(edge.toNodeId());
                                values.add(edge.type().name()); });
                    graph.entryNodeIds().stream().sorted().forEach(values::add);
                    graph.terminalNodeIds().stream().sorted().forEach(values::add);
                });
        return digest(values.toArray(String[]::new));
    }

    private String deviationSnapshot(DeviationCapaTemplateDraft parent,
                                     List<DeviationCapaTemplateDraftRule> rows,
                                     List<DeviationCapaTemplateDraftEvidence> evidenceRows) {
        List<String> values = new ArrayList<>(List.of(parent.getId(), parent.getDesiredDigest(),
                parent.getPreviewDigest(), parent.getSourceSnapshotDigest(),
                parent.getQualityReleaseSnapshotDigest(), parent.getAuthoritySnapshotDigest()));
        rows.stream().sorted(Comparator.comparing(DeviationCapaTemplateDraftRule::getOrdinal))
                .forEach(row -> values.add(deviationRuleSnapshot(row)));
        evidenceRows.stream().sorted(Comparator.comparing(DeviationCapaTemplateDraftEvidence::getRuleId)
                        .thenComparing(DeviationCapaTemplateDraftEvidence::getAuthorityCode))
                .forEach(row -> { values.add(row.getRuleId()); values.add(row.getAuthorityCode());
                    values.add(row.getAuthoritySnapshotDigest()); });
        return digest(values.toArray(String[]::new));
    }

    private String deviationRuleSnapshot(DeviationCapaTemplateDraftRule row) {
        return digest("DEVIATION_RULE", row.getId(), row.getRuleKey(), row.getSourceType(),
                row.getSourceItemId(), row.getSourceSnapshotDigest(), row.getTriggerMode(),
                row.getDerivedClassification(), row.getImmediateControlAction(),
                row.getApprovalRoute(), String.valueOf(row.getEscalationSlaHours()));
    }

    private String qualitySnapshot(QualityReleaseTemplateDraft parent,
                                   List<QualityReleaseTemplateDraftItem> rows) {
        List<String> values = new ArrayList<>(List.of(parent.getId(), parent.getDesiredDigest(),
                parent.getPreviewDigest(), parent.getSourceSnapshotDigest(),
                parent.getBomSnapshotDigest(), parent.getAuthoritySnapshotDigest()));
        rows.stream().sorted(Comparator.comparing(QualityReleaseTemplateDraftItem::getOrdinal))
                .forEach(row -> values.add(qualityItemSnapshot(row)));
        return digest(values.toArray(String[]::new));
    }

    private String qualityItemSnapshot(QualityReleaseTemplateDraftItem row) {
        return digest("QUALITY_SPEC", row.getId(), row.getSpecKey(), row.getSubjectType(),
                row.getSubjectRefType(), row.getSubjectRefId(), row.getSubjectSnapshotDigest(),
                row.getRouteNodeId(), row.getItemCode(), row.getCategory(), row.getValueType(),
                row.getCriterionKind(), row.getSeverity(), row.getReleaseEffect(),
                String.valueOf(row.getRequired()), row.getResponsibleRole(),
                String.valueOf(row.getRecordRetentionDays()));
    }

    private String bomSnapshot(BomRecipeDraft parent, List<BomRecipeDraftItem> rows) {
        List<String> values = new ArrayList<>(List.of(parent.getId(), parent.getDesiredDigest(),
                parent.getPreviewDigest(), parent.getSourceSnapshotDigest(),
                parent.getWorkflowSnapshotDigest(), parent.getTargetProductSnapshotDigest()));
        rows.stream().sorted(Comparator.comparing(BomRecipeDraftItem::getOrdinal))
                .forEach(row -> values.add(bomItemSnapshot(row)));
        return digest(values.toArray(String[]::new));
    }

    private String bomItemSnapshot(BomRecipeDraftItem row) {
        return digest("BOM_ITEM", row.getId(), row.getMaterialRefType(), row.getMaterialRefId(),
                row.getMaterialSnapshotDigest(), row.getMaterialCategory(),
                String.valueOf(row.getStandardQuantity()), row.getUnit(),
                String.valueOf(row.getOptional()), row.getWorkflowProcessNodeId(),
                row.getWorkflowPortId(), row.getWorkflowPortDirection());
    }

    private String warehouseSnapshot(WarehouseControlDraft parent,
                                     List<WarehouseControlDraftItem> rows,
                                     List<WarehouseControlDraftColdControl> coldRows) {
        List<String> values = new ArrayList<>(List.of(parent.getId(), parent.getDesiredDigest(),
                parent.getPreviewDigest(), parent.getSourceSnapshotDigest()));
        rows.stream().sorted(Comparator.comparing(WarehouseControlDraftItem::getOrdinal))
                .forEach(row -> values.add(warehouseItemSnapshot(row)));
        coldRows.stream().sorted(Comparator.comparing(WarehouseControlDraftColdControl::getOrdinal))
                .forEach(row -> values.add(digest("COLD_CONTROL", row.getId(), row.getEquipmentCode(),
                        row.getWarehouseCode(), String.valueOf(row.getTargetTempMinCelsius()),
                        String.valueOf(row.getTargetTempMaxCelsius()),
                        String.valueOf(row.getToleranceMinutes()), row.getDeviationAlgorithm())));
        return digest(values.toArray(String[]::new));
    }

    private String warehouseItemSnapshot(WarehouseControlDraftItem row) {
        return digest("WAREHOUSE", row.getId(), row.getOperation(), row.getWarehouseCode(),
                row.getWarehouseName(), row.getWarehouseType(), String.valueOf(row.getDesiredActive()),
                row.getCurrentSnapshotDigest());
    }

    private String desiredDigest(DraftRequest request) {
        PlanPolicy policy = request.policy();
        List<String> values = new ArrayList<>(List.of(TraceRecallPlanDraftModels.SCHEMA_VERSION,
                TraceRecallPlanDraftModels.PLAN_ALGORITHM_VERSION, request.factoryId(),
                request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), request.deviationCapa().draftId(),
                request.deviationCapa().desiredDigest(), request.qualityRelease().draftId(),
                request.qualityRelease().desiredDigest(), request.bom().draftId(),
                request.bom().desiredDigest(), request.warehouse().draftId(),
                request.warehouse().desiredDigest(), String.valueOf(policy.direction()),
                String.valueOf(policy.maxTraversalDepth()), String.valueOf(policy.maxTraversalNodes()),
                String.valueOf(policy.traceCompletionMinutes()),
                String.valueOf(policy.massBalanceToleranceBasisPoints()),
                String.valueOf(policy.mockRecallFrequencyDays()),
                String.valueOf(policy.evidenceRetentionDays()), policy.changeReason()));
        policy.escalationRoles().forEach(role -> values.add(role.name()));
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        return digest(request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), desiredDigest(request));
    }

    private String digestBlueprint(BlueprintResponse response) {
        return digest("BLUEPRINT", response.getFactoryId(), response.getId(),
                String.valueOf(response.getVersion()), String.valueOf(response.getRowVersion()),
                response.getLifecycleState(), response.getSchemaVersion(),
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                String.valueOf(response.getUpdatedAt()));
    }

    private String digestSegments(List<DerivedSegment> values) {
        return digest(values.stream().map(item -> item.ordinal() + "|" + item.segmentKey() + "|"
                + item.sourceSnapshotDigest() + "|" + item.completionSlaMinutes() + "|"
                + item.evidenceRetentionDays()).toArray(String[]::new));
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream().map(item -> item.code() + "|" + item.target() + "|"
                + item.message() + "|" + item.recovery()).toArray(String[]::new));
    }

    private String authorityEvidenceDigest(BlueprintAuthority authority) {
        return digest("CATALOG_AUTHORITY", authority.catalogVersion(), authority.catalogDigest(),
                AUTHORITY_CODE, authority.authoritySnapshotDigest());
    }

    private String escalationRoute(List<EscalationRole> values) {
        return String.join(">", values.stream().map(Enum::name).toList());
    }

    private List<EscalationRole> parseEscalationRoute(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split(">")).stream().map(EscalationRole::valueOf).toList();
    }

    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause())
            if (String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_trace_recall_plan_idempotency")) return true;
        return false;
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }
    private boolean isDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private String clean(String value) { return value == null ? "" : value.trim(); }

    private String digest(String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                md.update((byte) (bytes.length >>> 24)); md.update((byte) (bytes.length >>> 16));
                md.update((byte) (bytes.length >>> 8)); md.update((byte) bytes.length); md.update(bytes);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }
    private BusinessException invalid(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code);
    }

    private record Evaluation(DraftPreview preview, BlueprintAuthority authority, Upstream upstream) {}
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
    private record Upstream(DeviationCapaTemplateDraft deviation,
                            QualityReleaseTemplateDraft quality, BomRecipeDraft bom,
                            WarehouseControlDraft warehouse,
                            List<DeviationCapaTemplateDraftRule> deviationRules,
                            List<QualityReleaseTemplateDraftItem> qualityItems,
                            List<BomRecipeDraftItem> bomItems,
                            List<WarehouseControlDraftItem> warehouseItems,
                            List<WarehouseControlDraftColdControl> coldControls,
                            String deviationSnapshotDigest, String qualitySnapshotDigest,
                            String bomSnapshotDigest, String warehouseSnapshotDigest) {
        static Upstream missing() {
            return new Upstream(null, null, null, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), digestStatic("MISSING_DEVIATION"),
                    digestStatic("MISSING_QUALITY"), digestStatic("MISSING_BOM"),
                    digestStatic("MISSING_WAREHOUSE"));
        }
    }
    private record SegmentSeed(SourceType sourceType, String sourceRefId,
                               String sourceSnapshotDigest, Stage stage,
                               IdentifierType identifierType, boolean quantityCapture) {}

    private static String digestStatic(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}



