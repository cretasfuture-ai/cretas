package com.cretas.aims.service.hazardanalysisdraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteEdge;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteNode;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftItem;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftSubstitute;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraft;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraftEvidence;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraftItem;
import com.cretas.aims.entity.product.ProductTypeDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftItemRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftSubstituteRepository;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftEvidenceRepository;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftItemRepository;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftRepository;
import com.cretas.aims.repository.product.ProductTypeDraftRepository;
import com.cretas.aims.service.bomdraft.BomRecipeDraftCommand;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.BomPin;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.ControlMeasureType;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.DraftIssue;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.DraftPreview;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.DraftReadback;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.DraftReceipt;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.DraftRequest;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.HazardItem;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.HazardType;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.Likelihood;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.RecoveryPlan;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.RiskBand;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.Severity;
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
 * Deterministic, module-owned hazard-analysis DRAFT command.
 *
 * <p>It consumes the current read-only Blueprint route graph, a readback-ready BOM DRAFT and
 * server-versioned authority codes. It never creates a live HACCP checkpoint, CCP, monitoring
 * record, inventory movement, production record or quality result.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class HazardAnalysisDraftCommand {
    public static final String CAPABILITY_ID = "hazard-analysis";
    public static final String REQUIRED_PERMISSION = "haccp:draft:write";
    private static final int MAX_ITEMS = 500;
    private static final int MAX_EVIDENCE_PER_ITEM = 20;

    private final HazardAnalysisDraftRepository drafts;
    private final HazardAnalysisDraftItemRepository draftItems;
    private final HazardAnalysisDraftEvidenceRepository draftEvidence;
    private final BomRecipeDraftRepository bomDrafts;
    private final BomRecipeDraftItemRepository bomItems;
    private final BomRecipeDraftSubstituteRepository bomSubstitutes;
    private final BomRecipeDraftCommand bomCommand;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final ProductTypeRepository products;
    private final ProductTypeDraftRepository productDrafts;
    private final UserRepository users;

    public HazardAnalysisDraftCommand(
            HazardAnalysisDraftRepository drafts,
            HazardAnalysisDraftItemRepository draftItems,
            HazardAnalysisDraftEvidenceRepository draftEvidence,
            BomRecipeDraftRepository bomDrafts,
            BomRecipeDraftItemRepository bomItems,
            BomRecipeDraftSubstituteRepository bomSubstitutes,
            BomRecipeDraftCommand bomCommand,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            ProductTypeRepository products,
            ProductTypeDraftRepository productDrafts,
            UserRepository users) {
        this.drafts = drafts;
        this.draftItems = draftItems;
        this.draftEvidence = draftEvidence;
        this.bomDrafts = bomDrafts;
        this.bomItems = bomItems;
        this.bomSubstitutes = bomSubstitutes;
        this.bomCommand = bomCommand;
        this.canonicalBlueprints = canonicalBlueprints;
        this.products = products;
        this.productDrafts = productDrafts;
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
        Optional<HazardAnalysisDraft> prior = drafts.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同危害分析草稿请求", "HAZARD_DRAFT_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview preview = evaluation.preview();
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "危害分析草稿预览被阻断", "HAZARD_DRAFT_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "危害分析草稿预览摘要已变化", "HAZARD_DRAFT_PREVIEW_DIGEST_CONFLICT");
        }

        int evidenceCount = request.items().stream()
                .mapToInt(item -> item.evidenceAuthorityCodes().size()).sum();
        HazardAnalysisDraft parent = HazardAnalysisDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .routeProductCode(request.routeProductCode())
                .routeSnapshotDigest(preview.routeSnapshotDigest())
                .bomDraftId(request.bom().draftId())
                .bomDesiredDigest(request.bom().desiredDigest())
                .bomSnapshotDigest(preview.bomSnapshotDigest())
                .capabilityCatalogVersion(evaluation.blueprint().catalogVersion())
                .capabilityCatalogDigest(evaluation.blueprint().catalogDigest())
                .authoritySnapshotDigest(preview.authoritySnapshotDigest())
                .riskAlgorithmVersion(HazardAnalysisDraftModels.RISK_ALGORITHM_VERSION)
                .desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest())
                .requestDigest(requestDigest)
                .itemCount(request.items().size())
                .evidenceCount(evidenceCount)
                .dependencyBasis(HazardAnalysisDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .status("DRAFT")
                .build();

        HazardAnalysisDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "危害分析草稿幂等键发生并发竞争", "HAZARD_DRAFT_IDEMPOTENCY_RACE");
        }

        List<HazardAnalysisDraftItem> itemRows = new ArrayList<>();
        List<HazardAnalysisDraftEvidence> evidenceRows = new ArrayList<>();
        for (int index = 0; index < evaluation.items().size(); index++) {
            ItemResolution resolved = evaluation.items().get(index);
            HazardAnalysisDraftItem itemRow = toRow(saved.getId(), request.factoryId(),
                    request.bom().draftId(), request.routeProductCode(), index, resolved);
            itemRows.add(itemRow);
            for (String authorityCode : sortedDistinct(resolved.desired().evidenceAuthorityCodes())) {
                evidenceRows.add(HazardAnalysisDraftEvidence.builder()
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
            throw invalid(409, "危害分析草稿逐字段回读不一致", "HAZARD_DRAFT_READBACK_MISMATCH");
        }
        Evaluation authorityReadback = evaluate(request);
        DraftPreview current = authorityReadback.preview();
        if (!"READY".equals(current.status())
                || !preview.sourceSnapshotDigest().equals(current.sourceSnapshotDigest())
                || !preview.routeSnapshotDigest().equals(current.routeSnapshotDigest())
                || !preview.bomSnapshotDigest().equals(current.bomSnapshotDigest())
                || !preview.authoritySnapshotDigest().equals(current.authoritySnapshotDigest())
                || !preview.previewDigest().equals(current.previewDigest())) {
            throw invalid(409, "危害分析权威在草稿写入期间发生变化",
                    "HAZARD_DRAFT_AUTHORITY_DRIFT_DURING_WRITE");
        }
        return receipt(saved, false, 1, 1 + itemRows.size() + evidenceRows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        HazardAnalysisDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "危害分析草稿不存在", "HAZARD_DRAFT_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !HazardAnalysisDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !HazardAnalysisDraftModels.RISK_ALGORITHM_VERSION.equals(
                        parent.getRiskAlgorithmVersion())) {
            throw invalid(409, "危害分析草稿依赖闭包或状态无效", "HAZARD_DRAFT_READBACK_INVARIANT_BROKEN");
        }
        List<HazardAnalysisDraftItem> rows = draftItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<HazardAnalysisDraftEvidence> evidenceRows = draftEvidence
                .findByDraftIdAndFactoryIdOrderByItemIdAscAuthorityCodeAsc(draftId, factoryId);
        Map<String, List<String>> evidenceByItem = new HashMap<>();
        for (HazardAnalysisDraftEvidence row : evidenceRows) {
            if (!"CATALOG_AUTHORITY".equals(row.getEvidenceType())
                    || !authorityEvidenceDigest(parent.getCapabilityCatalogVersion(),
                            parent.getCapabilityCatalogDigest(), row.getAuthorityCode())
                            .equals(row.getAuthoritySnapshotDigest())) {
                throw invalid(409, "危害分析证据回读不一致", "HAZARD_DRAFT_EVIDENCE_READBACK_MISMATCH");
            }
            evidenceByItem.computeIfAbsent(row.getItemId(), ignored -> new ArrayList<>())
                    .add(row.getAuthorityCode());
        }

        List<HazardItem> items = new ArrayList<>();
        for (HazardAnalysisDraftItem row : rows) {
            int expectedScore = HazardAnalysisDraftModels.riskScore(
                    Severity.valueOf(row.getSeverity()), Likelihood.valueOf(row.getLikelihood()));
            RiskBand expectedBand = HazardAnalysisDraftModels.riskBand(expectedScore);
            if (row.getRiskScore() != expectedScore || !expectedBand.name().equals(row.getRiskBand())
                    || Boolean.TRUE.equals(row.getSignificant()) != (expectedScore >= 10)) {
                throw invalid(409, "危害分析风险算法回读不一致", "HAZARD_DRAFT_RISK_READBACK_MISMATCH");
            }
            HazardItem item = new HazardItem(row.getRouteNodeId(), row.getBomItemId(),
                    HazardType.valueOf(row.getHazardType()), row.getHazardDescription(),
                    row.getCauseSummary(), Severity.valueOf(row.getSeverity()),
                    Likelihood.valueOf(row.getLikelihood()),
                    ControlMeasureType.valueOf(row.getControlMeasureType()),
                    row.getPreventiveMeasure(), evidenceByItem.getOrDefault(row.getId(), List.of()),
                    row.getChangeReason());
            if (!hazardKey(parent.getRouteProductCode(), item, row.getMaterialRefType(),
                    row.getMaterialRefId()).equals(row.getHazardKey())) {
                throw invalid(409, "危害分析条目标识回读不一致", "HAZARD_DRAFT_ITEM_READBACK_MISMATCH");
            }
            items.add(item);
        }
        Long readbackOperatorId = parent.getOperatorId() == null ? parent.getActorId() : parent.getOperatorId();
        if (readbackOperatorId == null) throw invalid(409, "草稿缺少 typed actor 身份，无法安全回读", "DRAFT_ACTOR_IDENTITY_MISSING");
        DraftRequest desired = new DraftRequest(parent.getFactoryId(), parent.getBlueprintId(),
                parent.getBlueprintVersion(), parent.getSourceDigest(), parent.getRouteProductCode(),
                parent.getRouteSnapshotDigest(), new BomPin(parent.getBomDraftId(), parent.getBomDesiredDigest()),
                items, parent.getPreviewDigest(), parent.getIdempotencyKey(), readbackOperatorId);
        if (rows.size() != parent.getItemCount() || evidenceRows.size() != parent.getEvidenceCount()
                || !desiredDigest(desired).equals(parent.getDesiredDigest())) {
            throw invalid(409, "危害分析草稿回读不完整", "HAZARD_DRAFT_READBACK_MISMATCH");
        }
        return new DraftReadback(HazardAnalysisDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getStatus(), desired, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        HazardAnalysisDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "危害分析草稿不存在", "HAZARD_DRAFT_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus())) {
            throw invalid(409, "只有未发布危害分析草稿可恢复", "HAZARD_DRAFT_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(HazardAnalysisDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getDesiredDigest(), "ARCHIVE_DRAFT_AFTER_FOOD_SAFETY_OWNER_CONFIRMATION",
                "只归档危害分析草稿；不改变正式 HACCP/CCP、监控、批次、库存、生产或质量结果",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintResolution blueprint = resolveBlueprint(request, issues);
        BomResolution bom = resolveBom(request, issues);
        if (!blueprint.productCode().isBlank() && !bom.productCode().isBlank()
                && !blueprint.productCode().equals(bom.productCode())) {
            issues.add(issue("HAZARD_ROUTE_BOM_PRODUCT_MISMATCH", "routeProductCode",
                    "路线产品与 BOM 草稿产品不一致", "选择同一产品的 route graph 与 BOM DRAFT"));
        }

        List<ItemResolution> itemResolutions = new ArrayList<>();
        Set<String> hazardKeys = new HashSet<>();
        for (int index = 0; index < request.items().size(); index++) {
            HazardItem item = request.items().get(index);
            String target = "items[" + index + "]";
            validateItemShape(item, target, issues);
            if (item == null) {
                itemResolutions.add(ItemResolution.missing());
                continue;
            }
            RouteNode routeNode = blueprint.nodes().get(item.routeNodeId());
            if (routeNode == null) {
                issues.add(issue("HAZARD_ROUTE_NODE_NOT_FOUND", target + ".routeNodeId",
                        "危害引用的路线节点不存在", "从当前 Blueprint route graph 重新选择节点"));
            }
            BomRecipeDraftItem bomItem = item.bomItemId().isBlank()
                    ? null : bom.items().get(item.bomItemId());
            if (!item.bomItemId().isBlank() && bomItem == null) {
                issues.add(issue("HAZARD_BOM_ITEM_NOT_FOUND", target + ".bomItemId",
                        "危害引用的 BOM 行不存在", "从当前 BOM DRAFT 重新选择物料行"));
            }
            String materialType = bomItem == null ? "NONE" : bomItem.getMaterialRefType();
            String materialId = bomItem == null ? null : bomItem.getMaterialRefId();
            String materialSnapshot = bomItem == null ? null : bomItem.getMaterialSnapshotDigest();
            String key = hazardKey(request.routeProductCode(), item, materialType, materialId);
            if (!hazardKeys.add(key)) {
                issues.add(issue("HAZARD_ITEM_DUPLICATE", target,
                        "同一路线节点、物料、危害类型和描述不能重复", "合并重复危害条目"));
            }
            for (String code : sortedDistinct(item.evidenceAuthorityCodes())) {
                if (!blueprint.authorityCodes().contains(code)) {
                    issues.add(issue("HAZARD_EVIDENCE_NOT_RESOLVED", target + ".evidenceAuthorityCodes",
                            "证据不属于当前服务端版本化能力目录", "只选择平台提供的 hazard-analysis 法规/标准依据"));
                }
            }
            itemResolutions.add(new ItemResolution(item, routeNode, bomItem, materialType,
                    materialId, materialSnapshot, key));
        }

        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String sourceSnapshotDigest = digest(request.sourceDigest(), blueprint.snapshotDigest(),
                blueprint.routeSnapshotDigest(), bom.snapshotDigest(), blueprint.authoritySnapshotDigest(),
                HazardAnalysisDraftModels.RISK_ALGORITHM_VERSION);
        String desiredDigest = desiredDigest(request);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest(HazardAnalysisDraftModels.SCHEMA_VERSION, request.factoryId(),
                 request.blueprintId(), String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                 sourceSnapshotDigest, blueprint.routeSnapshotDigest(), bom.snapshotDigest(),
                 blueprint.authoritySnapshotDigest(), HazardAnalysisDraftModels.RISK_ALGORITHM_VERSION,
                 desiredDigest, status);
        DraftPreview preview = new DraftPreview(HazardAnalysisDraftModels.SCHEMA_VERSION, status,
                request.factoryId(), List.of(), request, sourceSnapshotDigest,
                blueprint.routeSnapshotDigest(), bom.snapshotDigest(),
                blueprint.authoritySnapshotDigest(), desiredDigest, previewDigest, issues,
                0, 0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, blueprint, bom, List.copyOf(itemResolutions));
    }

    private BlueprintResolution resolveBlueprint(DraftRequest request, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(request.factoryId());
        if (response == null || !request.blueprintId().equals(response.getId())
                || !Objects.equals(Math.toIntExact(request.blueprintVersion()), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                    || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("HAZARD_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
                    "当前工厂 Blueprint DRAFT/VALIDATED 版本与请求不一致", "刷新当前 Blueprint 后重新预览"));
            return BlueprintResolution.missing();
        }
        CapabilityView capability = response.getCapabilities() == null ? null
                : response.getCapabilities().stream().filter(item -> CAPABILITY_ID.equals(item.getId()))
                .findFirst().orElse(null);
        Set<String> capabilityDependencies = capability == null || capability.getDependsOn() == null
                ? Set.of() : new HashSet<>(capability.getDependsOn());
        if (capability == null || !capability.isIncluded() || !"READY".equals(capability.getStatus())
                || !capabilityDependencies.equals(Set.copyOf(FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID)))) {
            issues.add(issue("HAZARD_CAPABILITY_NOT_READY", "capability",
                    "hazard-analysis 能力或 routes+bom 依赖尚未就绪", "先补齐路线和 BOM 草稿后重新预览"));
        }
        Set<String> authorityCodes = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest())) {
            issues.add(issue("HAZARD_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本或摘要无效", "部署有效的版本化能力目录后重新预览"));
        }
        if (authorityCodes.isEmpty()) {
            issues.add(issue("HAZARD_AUTHORITY_CATALOG_EMPTY", "evidenceAuthorityCodes",
                    "服务端能力目录没有可验证的危害分析依据", "部署带 authority refs 的版本化能力目录"));
        }
        RouteGraph graph = response.getRouteGraphs() == null ? null : response.getRouteGraphs().stream()
                .filter(item -> request.routeProductCode().equals(item.productCode()))
                .findFirst().orElse(null);
        if (graph == null || graph.validation() == null || !graph.validation().valid()
                || graph.nodes().isEmpty()) {
            issues.add(issue("HAZARD_ROUTE_GRAPH_NOT_READY", "routeProductCode",
                    "当前产品路线图不存在或拓扑不完整", "修复 Blueprint route graph 后重新预览"));
            return new BlueprintResolution(response.getCapabilityCatalogVersion(),
                    response.getCapabilityCatalogDigest(), authorityCodes,
                    authoritySnapshot(response, authorityCodes), digestBlueprint(response),
                    digest("MISSING_ROUTE"), request.routeProductCode(), Map.of());
        }
        String currentRouteDigest = routeDigest(graph);
        if (!currentRouteDigest.equals(request.routeSnapshotDigest())) {
            issues.add(issue("HAZARD_ROUTE_DRIFT", "routeSnapshotDigest",
                    "Blueprint route graph 快照已变化", "重新预览并确认当前路线节点"));
        }
        Map<String, RouteNode> nodes = new LinkedHashMap<>();
        for (RouteNode node : graph.nodes()) nodes.putIfAbsent(node.id(), node);
        return new BlueprintResolution(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), authorityCodes,
                authoritySnapshot(response, authorityCodes), digestBlueprint(response),
                currentRouteDigest, graph.productCode(), Map.copyOf(nodes));
    }

    private BomResolution resolveBom(DraftRequest request, List<DraftIssue> issues) {
        BomPin pin = request.bom();
        BomRecipeDraft parent = bomDrafts.findByIdAndFactoryId(pin.draftId(), request.factoryId())
                .orElse(null);
        if (parent == null || !"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !BomRecipeDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !request.blueprintId().equals(parent.getBlueprintId())
                || request.blueprintVersion() != parent.getBlueprintVersion()
                || !request.sourceDigest().equals(parent.getSourceDigest())
                || !pin.desiredDigest().equals(parent.getDesiredDigest())) {
            issues.add(issue("HAZARD_BOM_DRAFT_DRIFT", "bom",
                    "BOM DRAFT 不存在、lineage 不一致或摘要已变化", "选择同 Blueprint 的当前 BOM DRAFT"));
            return BomResolution.missing();
        }
        try {
            var readback = bomCommand.readBack(request.factoryId(), parent.getId());
            var current = bomCommand.preview(readback.desired());
            if (!"READY".equals(current.status())
                    || !parent.getDesiredDigest().equals(current.desiredDigest())
                    || !parent.getSourceSnapshotDigest().equals(current.sourceSnapshotDigest())
                    || !parent.getWorkflowSnapshotDigest().equals(current.workflowSnapshotDigest())
                    || !parent.getPreviewDigest().equals(current.previewDigest())) {
                issues.add(issue("HAZARD_BOM_AUTHORITY_BLOCKED", "bom",
                        "BOM DRAFT 当前依赖已漂移或不能逐字段回读", "先重新生成 readback-ready BOM DRAFT"));
            }
        } catch (BusinessException exception) {
            issues.add(issue("HAZARD_BOM_AUTHORITY_BLOCKED", "bom",
                    "BOM DRAFT 当前不能逐字段回读", "先修复或重新生成 BOM DRAFT"));
        }
        List<BomRecipeDraftItem> rows = bomItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(parent.getId(), request.factoryId());
        List<BomRecipeDraftSubstitute> substitutes = bomSubstitutes
                .findByDraftIdAndFactoryIdOrderByItemIdAscPriorityAsc(parent.getId(), request.factoryId());
        Map<String, BomRecipeDraftItem> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.putIfAbsent(row.getId(), row));
        String productCode = resolveBomProductCode(request, parent, issues);
        return new BomResolution(bomDigest(parent, rows, substitutes), productCode, Map.copyOf(byId));
    }

    private String resolveBomProductCode(DraftRequest request, BomRecipeDraft parent,
                                         List<DraftIssue> issues) {
        if ("LIVE_PRODUCT".equals(parent.getTargetProductRefType())) {
            ProductType product = products.findByIdAndFactoryId(parent.getTargetProductRefId(),
                    request.factoryId()).filter(row -> Boolean.TRUE.equals(row.getIsActive())).orElse(null);
            if (product != null) return Objects.toString(product.getCode(), "").trim();
        } else if ("PRODUCT_DRAFT".equals(parent.getTargetProductRefType())) {
            ProductTypeDraft product = productDrafts.findByIdAndFactoryId(parent.getTargetProductRefId(),
                    request.factoryId()).filter(row -> "DRAFT".equals(row.getStatus())
                            && request.blueprintId().equals(row.getBlueprintId())
                            && request.blueprintVersion() == row.getBlueprintVersion()).orElse(null);
            if (product != null) return Objects.toString(product.getNaturalKey(), "").trim();
        }
        issues.add(issue("HAZARD_BOM_PRODUCT_NOT_RESOLVED", "bom",
                "BOM 产品无法解析为当前路线产品", "修复 BOM 产品引用后重新预览"));
        return "";
    }

    private void validateItemShape(HazardItem item, String target, List<DraftIssue> issues) {
        if (item == null) {
            issues.add(issue("HAZARD_ITEM_REQUIRED", target, "危害条目不能为空", "补充 typed 危害条目"));
            return;
        }
        if (isBlankOrTooLong(item.routeNodeId(), 128)
                || item.hazardType() == null || !com.cretas.aims.blueprint.interview.AssemblyHaccpContract.HAZARD_TYPES.contains(item.hazardType().name())
                || item.severity() == null || item.likelihood() == null || item.controlMeasureType() == null
                || isBlankOrTooLong(item.hazardDescription(), 500)
                || isBlankOrTooLong(item.causeSummary(), 500)
                || isBlankOrTooLong(item.preventiveMeasure(), 1000)
                || isBlankOrTooLong(item.changeReason(), 500)) {
            issues.add(issue("HAZARD_ITEM_FIELDS_REQUIRED", target,
                    "路线节点、危害类型/描述/原因、风险等级、预防措施和变更原因均必填",
                    "补全 typed 危害分析字段"));
        }
        if (item.bomItemId().length() > 36) {
            issues.add(issue("HAZARD_BOM_ITEM_REF_INVALID", target + ".bomItemId",
                    "BOM 行引用长度无效", "从当前 BOM DRAFT 重新选择物料行"));
        }
        if (item.evidenceAuthorityCodes().isEmpty()
                || item.evidenceAuthorityCodes().size() > MAX_EVIDENCE_PER_ITEM
                || item.evidenceAuthorityCodes().stream().anyMatch(code -> isBlankOrTooLong(code, 100))) {
            issues.add(issue("HAZARD_EVIDENCE_REQUIRED", target + ".evidenceAuthorityCodes",
                    "每条危害必须引用 1 至 20 个平台法规/标准依据", "选择平台提供的 authority code"));
        }
        if (new HashSet<>(item.evidenceAuthorityCodes()).size() != item.evidenceAuthorityCodes().size()) {
            issues.add(issue("HAZARD_EVIDENCE_DUPLICATE", target + ".evidenceAuthorityCodes",
                    "同一危害不能重复引用同一依据", "移除重复 authority code"));
        }
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致", "HAZARD_DRAFT_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少危害分析草稿权限", "HAZARD_DRAFT_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(), operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员", "HAZARD_DRAFT_OPERATOR_MEMBERSHIP_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || request.blueprintVersion() > Integer.MAX_VALUE
                || !isDigest(request.sourceDigest())
                || request.routeProductCode().isBlank() || request.routeProductCode().length() > 100
                || !isDigest(request.routeSnapshotDigest()) || request.bom() == null
                || request.bom().draftId().isBlank() || request.bom().draftId().length() > 36
                || !isDigest(request.bom().desiredDigest())
                || request.items().isEmpty() || request.items().size() > MAX_ITEMS) {
            throw invalid(400, "Blueprint envelope 或危害条目范围无效", "HAZARD_DRAFT_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128 || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效", "HAZARD_DRAFT_OPERATOR_REQUIRED");
        }
    }

    private HazardAnalysisDraftItem toRow(String draftId, String factoryId, String bomDraftId,
                                          String routeProductCode, int ordinal,
                                          ItemResolution resolved) {
        HazardItem item = resolved.desired();
        int score = HazardAnalysisDraftModels.riskScore(item.severity(), item.likelihood());
        return HazardAnalysisDraftItem.builder()
                .id(UUID.randomUUID().toString())
                .draftId(draftId)
                .factoryId(factoryId)
                .ordinal(ordinal)
                .hazardKey(resolved.hazardKey())
                .routeProductCode(routeProductCode)
                .routeNodeId(item.routeNodeId())
                .routeNodeLabel(resolved.routeNode().label())
                .bomDraftId(bomDraftId)
                .bomItemId(blankToNull(item.bomItemId()))
                .materialRefType(resolved.materialRefType())
                .materialRefId(resolved.materialRefId())
                .materialSnapshotDigest(resolved.materialSnapshotDigest())
                .hazardType(item.hazardType().name())
                .hazardDescription(item.hazardDescription())
                .causeSummary(item.causeSummary())
                .severity(item.severity().name())
                .likelihood(item.likelihood().name())
                .riskScore(score)
                .riskBand(HazardAnalysisDraftModels.riskBand(score).name())
                .significant(score >= 10)
                .controlMeasureType(item.controlMeasureType().name())
                .preventiveMeasure(item.preventiveMeasure())
                .changeReason(item.changeReason())
                .build();
    }

    private DraftReceipt receipt(HazardAnalysisDraft parent, boolean replayed, int writes, int rows) {
        return new DraftReceipt(HazardAnalysisDraftModels.SCHEMA_VERSION, parent.getId(),
                parent.getFactoryId(), parent.getId(), parent.getStatus(), parent.getSourceSnapshotDigest(),
                parent.getRouteSnapshotDigest(), parent.getBomSnapshotDigest(),
                parent.getAuthoritySnapshotDigest(), parent.getDesiredDigest(), parent.getPreviewDigest(),
                parent.getRequestDigest(), parent.getItemCount(), parent.getEvidenceCount(),
                Objects.requireNonNullElse(parent.getLockVersion(), 0L), replayed, writes,
                replayed ? 0 : 1, rows, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED",
                "ARCHIVE_DRAFT_AFTER_FOOD_SAFETY_OWNER_CONFIRMATION");
    }

    private String routeDigest(RouteGraph graph) {
        List<String> values = new ArrayList<>();
        values.add(graph.productCode());
        values.add(graph.productName());
        graph.nodes().stream().sorted(Comparator.comparing(RouteNode::id))
                .forEach(node -> { values.add(node.id()); values.add(node.label()); });
        graph.edges().stream().sorted(Comparator.comparing(RouteEdge::fromNodeId)
                        .thenComparing(RouteEdge::toNodeId).thenComparing(edge -> edge.type().name()))
                .forEach(edge -> { values.add(edge.fromNodeId()); values.add(edge.toNodeId());
                    values.add(edge.type().name()); });
        graph.entryNodeIds().stream().sorted().forEach(values::add);
        graph.terminalNodeIds().stream().sorted().forEach(values::add);
        values.add(String.valueOf(graph.validation().valid()));
        graph.validation().issues().stream().sorted(Comparator.comparing(issue -> issue.code()))
                .forEach(issue -> { values.add(issue.code()); values.add(issue.message());
                    issue.nodeIds().stream().sorted().forEach(values::add); });
        return digest(values.toArray(String[]::new));
    }

    private String digestBlueprint(BlueprintResponse response) {
        return digest(response.getId(), response.getFactoryId(), String.valueOf(response.getVersion()),
                String.valueOf(response.getRowVersion()), response.getLifecycleState(), response.getSchemaVersion(),
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                String.valueOf(response.getUpdatedAt()));
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

    private String bomDigest(BomRecipeDraft parent, List<BomRecipeDraftItem> items,
                             List<BomRecipeDraftSubstitute> substitutes) {
        List<String> values = new ArrayList<>();
        values.add(parent.getId()); values.add(parent.getFactoryId()); values.add(parent.getBlueprintId());
        values.add(String.valueOf(parent.getBlueprintVersion())); values.add(parent.getSourceDigest());
        values.add(parent.getSourceSnapshotDigest()); values.add(parent.getDesiredDigest());
        values.add(parent.getPreviewDigest()); values.add(parent.getTargetProductRefType());
        values.add(parent.getTargetProductRefId()); values.add(parent.getTargetProductSnapshotDigest());
        values.add(parent.getWorkflowRevisionHash()); values.add(parent.getWorkflowSnapshotDigest());
        values.add(parent.getPackagingDependencyType()); values.add(parent.getPackagingDraftId());
        values.add(parent.getPackagingDraftDigest()); values.add(parent.getStatus());
        values.add(String.valueOf(parent.getLockVersion())); values.add(String.valueOf(parent.getUpdatedAt()));
        for (BomRecipeDraftItem item : items) {
            values.add(item.getId()); values.add(String.valueOf(item.getOrdinal()));
            values.add(item.getMaterialRefType()); values.add(item.getMaterialRefId());
            values.add(item.getMaterialSnapshotDigest()); values.add(item.getMaterialCategory());
            values.add(String.valueOf(item.getStandardQuantity())); values.add(item.getUnit());
            values.add(item.getWorkflowProcessNodeId()); values.add(item.getWorkflowPortId());
            values.add(String.valueOf(item.getUpdatedAt()));
        }
        for (BomRecipeDraftSubstitute substitute : substitutes) {
            values.add(substitute.getId()); values.add(substitute.getItemId());
            values.add(String.valueOf(substitute.getPriority())); values.add(substitute.getCandidateRefType());
            values.add(substitute.getCandidateRefId()); values.add(substitute.getCandidateSnapshotDigest());
            values.add(substitute.getConversionBasis()); values.add(String.valueOf(substitute.getConversionRatio()));
            values.add(String.valueOf(substitute.getMaxSharePercent()));
            values.add(String.valueOf(substitute.getUpdatedAt()));
        }
        return digest(values.toArray(String[]::new));
    }

    private String hazardKey(String routeProductCode, HazardItem item,
                             String materialRefType, String materialRefId) {
        return digest(routeProductCode, item.routeNodeId(), Objects.toString(materialRefType, "NONE"),
                Objects.toString(materialRefId, ""), String.valueOf(item.hazardType()),
                item.hazardDescription().toLowerCase(Locale.ROOT));
    }

    private String desiredDigest(DraftRequest request) {
        List<String> values = new ArrayList<>();
        values.add(HazardAnalysisDraftModels.SCHEMA_VERSION);
        values.add(HazardAnalysisDraftModels.RISK_ALGORITHM_VERSION);
        values.add(request.routeProductCode()); values.add(request.routeSnapshotDigest());
        values.add(request.bom().draftId()); values.add(request.bom().desiredDigest());
        for (HazardItem item : request.items()) {
            if (item == null) { values.add("NULL_ITEM"); continue; }
            values.add(item.routeNodeId()); values.add(item.bomItemId());
            values.add(String.valueOf(item.hazardType())); values.add(item.hazardDescription());
            values.add(item.causeSummary()); values.add(String.valueOf(item.severity()));
            values.add(String.valueOf(item.likelihood())); values.add(String.valueOf(item.controlMeasureType()));
            values.add(item.preventiveMeasure()); sortedDistinct(item.evidenceAuthorityCodes()).forEach(values::add);
            values.add(item.changeReason());
        }
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        return digest(request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), desiredDigest(request));
    }

    private List<String> sortedDistinct(List<String> values) {
        return values == null ? List.of() : values.stream().distinct().sorted().toList();
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_hazard_analysis_draft_idempotency")) return true;
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
                              BomResolution bom, List<ItemResolution> items) {}

    private record BlueprintResolution(String catalogVersion, String catalogDigest,
                                       Set<String> authorityCodes, String authoritySnapshotDigest,
                                       String snapshotDigest, String routeSnapshotDigest,
                                       String productCode, Map<String, RouteNode> nodes) {
        static BlueprintResolution missing() {
            return new BlueprintResolution("", "", Set.of(), digestStatic("MISSING_AUTHORITY"),
                    digestStatic("MISSING_BLUEPRINT"), digestStatic("MISSING_ROUTE"), "", Map.of());
        }
    }

    private record BomResolution(String snapshotDigest, String productCode,
                                 Map<String, BomRecipeDraftItem> items) {
        static BomResolution missing() {
            return new BomResolution(digestStatic("MISSING_BOM"), "", Map.of());
        }
    }

    private record ItemResolution(HazardItem desired, RouteNode routeNode,
                                  BomRecipeDraftItem bomItem, String materialRefType,
                                  String materialRefId, String materialSnapshotDigest,
                                  String hazardKey) {
        static ItemResolution missing() {
            return new ItemResolution(null, new RouteNode("", ""), null,
                    "NONE", null, null, digestStatic("MISSING_ITEM"));
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



