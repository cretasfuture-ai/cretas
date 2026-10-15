package com.cretas.aims.service.bomdraft;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftItem;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftSubstitute;
import com.cretas.aims.entity.material.MaterialTypeDraft;
import com.cretas.aims.entity.packagingdraft.PackagingDraft;
import com.cretas.aims.entity.packagingdraft.PackagingDraftItem;
import com.cretas.aims.entity.product.ProductTypeDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftItemRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftSubstituteRepository;
import com.cretas.aims.repository.material.MaterialTypeDraftRepository;
import com.cretas.aims.repository.packagingdraft.PackagingDraftItemRepository;
import com.cretas.aims.repository.packagingdraft.PackagingDraftRepository;
import com.cretas.aims.repository.product.ProductTypeDraftRepository;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.BomItem;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.DraftIssue;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.DraftPreview;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.DraftReadback;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.DraftReceipt;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.DraftRequest;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.MaterialCategory;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.MaterialRef;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.MaterialRefType;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.PackagingDependencyType;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.PackagingPin;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.PortDirection;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.ProductRef;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.ProductRefType;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.RecoveryPlan;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.SubstituteCandidate;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.SubstituteRatioBasis;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.WorkflowPin;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitUsageScope;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.packagingdraft.PackagingDraftCommand;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.TargetKind;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/**
 * Deterministic module command for a Blueprint-owned BOM draft.
 *
 * <p>It reads live BOM/Workflow/catalog state only as authority. It never invokes a live BOM,
 * Workflow, inventory, costing or production writer.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class BomRecipeDraftCommand {
    public static final String CAPABILITY_ID = "bom";
    public static final String REQUIRED_PERMISSION = "bom:draft:write";
    private static final int MAX_ITEMS = 500;
    private static final int MAX_SUBSTITUTES = 100;
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000000000");

    private final BomRecipeDraftRepository drafts;
    private final BomRecipeDraftItemRepository draftItems;
    private final BomRecipeDraftSubstituteRepository draftSubstitutes;
    private final ProductTypeRepository products;
    private final RawMaterialTypeRepository materials;
    private final ProductTypeDraftRepository productDrafts;
    private final MaterialTypeDraftRepository materialDrafts;
    private final ProductProcessWorkflowRepository workflows;
    private final ProductProcessWorkflowRevisionRepository workflowRevisions;
    private final BomRecipeRepository liveRecipes;
    private final BomRecipeItemRepository liveRecipeItems;
    private final PackagingDraftRepository packagingDrafts;
    private final PackagingDraftItemRepository packagingDraftItems;
    private final UnitContractService units;
    private final UserRepository users;
    private final ProductProcessWorkflowValidator workflowValidator;
    private final ObjectMapper objectMapper;
    private final PackagingDraftCommand packagingAuthority;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomRecipeDraftPromotionAuthority promotionAuthority;

    public BomRecipeDraftCommand(
            BomRecipeDraftRepository drafts,
            BomRecipeDraftItemRepository draftItems,
            BomRecipeDraftSubstituteRepository draftSubstitutes,
            ProductTypeRepository products,
            RawMaterialTypeRepository materials,
            ProductTypeDraftRepository productDrafts,
            MaterialTypeDraftRepository materialDrafts,
            ProductProcessWorkflowRepository workflows,
            ProductProcessWorkflowRevisionRepository workflowRevisions,
            BomRecipeRepository liveRecipes,
            BomRecipeItemRepository liveRecipeItems,
            PackagingDraftRepository packagingDrafts,
            PackagingDraftItemRepository packagingDraftItems,
            UnitContractService units,
            UserRepository users,
            ProductProcessWorkflowValidator workflowValidator,
            ObjectMapper objectMapper) {
        this(drafts, draftItems, draftSubstitutes, products, materials, productDrafts, materialDrafts,
                workflows, workflowRevisions, liveRecipes, liveRecipeItems, packagingDrafts,
                packagingDraftItems, units, users, workflowValidator, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BomRecipeDraftCommand(
            BomRecipeDraftRepository drafts,
            BomRecipeDraftItemRepository draftItems,
            BomRecipeDraftSubstituteRepository draftSubstitutes,
            ProductTypeRepository products,
            RawMaterialTypeRepository materials,
            ProductTypeDraftRepository productDrafts,
            MaterialTypeDraftRepository materialDrafts,
            ProductProcessWorkflowRepository workflows,
            ProductProcessWorkflowRevisionRepository workflowRevisions,
            BomRecipeRepository liveRecipes,
            BomRecipeItemRepository liveRecipeItems,
            PackagingDraftRepository packagingDrafts,
            PackagingDraftItemRepository packagingDraftItems,
            UnitContractService units,
            UserRepository users,
            ProductProcessWorkflowValidator workflowValidator,
            ObjectMapper objectMapper,
            PackagingDraftCommand packagingAuthority) {
        this.drafts = drafts;
        this.draftItems = draftItems;
        this.draftSubstitutes = draftSubstitutes;
        this.products = products;
        this.materials = materials;
        this.productDrafts = productDrafts;
        this.materialDrafts = materialDrafts;
        this.workflows = workflows;
        this.workflowRevisions = workflowRevisions;
        this.liveRecipes = liveRecipes;
        this.liveRecipeItems = liveRecipeItems;
        this.packagingDrafts = packagingDrafts;
        this.packagingDraftItems = packagingDraftItems;
        this.units = units;
        this.users = users;
        this.workflowValidator = workflowValidator;
        this.objectMapper = objectMapper;
        this.packagingAuthority = packagingAuthority;
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        List<DraftIssue> issues = new ArrayList<>();
        StringBuilder source = new StringBuilder();

        ProductResolution product = resolveProduct(request, issues);
        source.append("product:").append(product.sourceDigest()).append('|');

        WorkflowResolution workflow = resolveWorkflow(request, product, issues);
        source.append("workflow:").append(workflow.snapshotDigest()).append('|');

        CanonicalUnit outputUnit = resolveUnit(request.factoryId(), request.outputUnit(),
                "outputUnit", issues);
        CanonicalUnit netUnit = request.netContentUnit().isBlank() ? null
                : resolveUnit(request.factoryId(), request.netContentUnit(), "netContentUnit", issues);
        if (!validDecimal(request.outputQuantity(), 20, 8, MAX_QUANTITY)) {
            issues.add(issue("BOM_OUTPUT_QUANTITY_INVALID", "outputQuantity",
                    "产出数量必须是精确正数且不超过 8 位小数", "修正 BOM 产出口径"));
        }
        boolean hasNetQuantity = request.netContentQuantity() != null;
        boolean hasNetUnit = !request.netContentUnit().isBlank();
        if (hasNetQuantity != hasNetUnit || (hasNetQuantity
                && !validDecimal(request.netContentQuantity(), 20, 8, MAX_QUANTITY))) {
            issues.add(issue("BOM_NET_CONTENT_INVALID", "netContentQuantity",
                    "净含量数量和单位必须成对且数量为精确正数", "补全或清空净含量"));
        }
        if (outputUnit != null && product.canonicalUnit() != null
                && outputUnit.dimension() != product.canonicalUnit().dimension()) {
            issues.add(issue("BOM_PRODUCT_OUTPUT_UNIT_MISMATCH", "outputUnit",
                    "BOM 产出单位与产品主数据量纲不一致", "选择产品可用的产出单位"));
        }
        source.append("output-unit:").append(unitDigest(outputUnit)).append('|');
        source.append("net-unit:").append(unitDigest(netUnit)).append('|');

        PackagingResolution packaging = resolvePackaging(request, issues);
        source.append("packaging:").append(packaging.sourceDigest()).append('|');

        Set<String> itemPorts = new HashSet<>();
        for (int index = 0; index < request.items().size(); index++) {
            BomItem item = request.items().get(index);
            String target = "items[" + index + "]";
            validateItemShape(item, target, issues);
            if (item == null) {
                source.append("missing-item|");
                continue;
            }
            MaterialResolution material = resolveMaterial(request, item.material(), target + ".material", issues);
            CanonicalUnit itemUnit = resolveUnit(request.factoryId(), item.unit(), target + ".unit", issues);
            CanonicalUnit priceUnit = item.priceUnit().isBlank() ? null
                    : resolveUnit(request.factoryId(), item.priceUnit(), target + ".priceUnit", issues);
            source.append("material:").append(material.sourceDigest()).append('|');
            source.append("item-unit:").append(unitDigest(itemUnit)).append('|');
            source.append("price-unit:").append(unitDigest(priceUnit)).append('|');

            if (itemUnit != null && material.canonicalUnit() != null
                    && itemUnit.dimension() != material.canonicalUnit().dimension()) {
                issues.add(issue("BOM_MATERIAL_UNIT_DIMENSION_MISMATCH", target + ".unit",
                        "BOM 数量单位与物料主数据量纲不一致", "选择该物料可用的 BOM 数量单位"));
            }
            if (itemUnit != null && priceUnit != null
                    && itemUnit.dimension() != priceUnit.dimension()) {
                issues.add(issue("BOM_PRICE_UNIT_DIMENSION_MISMATCH", target + ".priceUnit",
                        "价格分母单位与 BOM 数量单位量纲不一致", "选择同量纲价格单位并提供精确因子"));
            }

            String portKey = item.workflowProcessNodeId() + ':' + item.workflowPortId();
            if (!itemPorts.add(portKey)) {
                issues.add(issue("BOM_WORKFLOW_PORT_DUPLICATE", target + ".workflowPortId",
                        "同一 Workflow 端口不能绑定多条 BOM 行", "合并重复行或选择不同端口"));
            }
            validateWorkflowBinding(request.factoryId(), workflow, item, material, itemUnit, target, issues);
            validateSubstitutes(request, item, material, itemUnit, target, source, issues);
        }

        validatePackagingCoverage(request, packaging, issues);
        source.append("live-bom:").append(liveBomDigest(request, product)).append('|');

        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String sourceSnapshotDigest = digest(source.toString());
        String desiredDigest = desiredDigest(request);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest(BomRecipeDraftModels.SCHEMA_VERSION, request.factoryId(),
                request.blueprintId(), String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                sourceSnapshotDigest, workflow.snapshotDigest(), desiredDigest, status);
        return new DraftPreview(BomRecipeDraftModels.SCHEMA_VERSION, status, request.factoryId(),
                List.of(), request, sourceSnapshotDigest, workflow.snapshotDigest(), desiredDigest,
                previewDigest, issues, 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, AuthenticatedOperator operator) {
        validateEnvelope(request, true);
        authorize(request, operator);
        String requestDigest = requestDigest(request, operator);
        Optional<BomRecipeDraft> prior = drafts.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同 BOM 草稿请求", "BOM_DRAFT_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        DraftPreview preview = preview(request);
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "BOM 草稿预览被阻断", "BOM_DRAFT_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "BOM 草稿预览摘要已变化", "BOM_DRAFT_PREVIEW_DIGEST_CONFLICT");
        }

        int substituteCount = request.items().stream().mapToInt(item -> item.substitutes().size()).sum();
        PackagingPin packaging = request.packaging();
        BomRecipeDraft parent = BomRecipeDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest())
                .requestDigest(requestDigest)
                .targetProductRefType(request.product().type().name())
                .targetProductRefId(request.product().refId())
                .targetProductSnapshotDigest(request.product().snapshotDigest())
                .workflowId(request.workflow().workflowId())
                .workflowRevisionId(request.workflow().revisionId())
                .workflowRevisionHash(request.workflow().revisionHash())
                .workflowDefinitionVersion(request.workflow().definitionVersion())
                .workflowSchemaVersion(request.workflow().schemaVersion())
                .workflowSnapshotDigest(preview.workflowSnapshotDigest())
                .packagingDependencyType(packaging.type().name())
                .packagingDraftId(blankToNull(packaging.draftId()))
                .packagingDraftDigest(blankToNull(packaging.desiredDigest()))
                .outputQuantity(request.outputQuantity())
                .outputUnit(request.outputUnit())
                .netContentQuantity(request.netContentQuantity())
                .netContentUnit(blankToNull(request.netContentUnit()))
                .itemCount(request.items().size())
                .substituteCount(substituteCount)
                .dependencyBasis(BomRecipeDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .status("DRAFT")
                .build();

        BomRecipeDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "BOM 草稿幂等键发生并发竞争", "BOM_DRAFT_IDEMPOTENCY_RACE");
        }

        List<BomRecipeDraftItem> itemRows = new ArrayList<>();
        List<BomRecipeDraftSubstitute> substituteRows = new ArrayList<>();
        for (int index = 0; index < request.items().size(); index++) {
            BomRecipeDraftItem row = toRow(saved.getId(), request.factoryId(), index,
                    request.items().get(index));
            itemRows.add(row);
            for (SubstituteCandidate candidate : request.items().get(index).substitutes()) {
                substituteRows.add(toSubstituteRow(saved.getId(), row.getId(), request.factoryId(), candidate));
            }
        }
        draftItems.saveAllAndFlush(itemRows);
        if (!substituteRows.isEmpty()) draftSubstitutes.saveAllAndFlush(substituteRows);

        DraftReadback readback = readBack(saved.getFactoryId(), saved.getId());
        if (!preview.desiredDigest().equals(desiredDigest(readback.desired()))) {
            throw invalid(409, "BOM 草稿逐字段回读不一致", "BOM_DRAFT_READBACK_MISMATCH");
        }
        DraftPreview authorityReadback = preview(request);
        if (!"READY".equals(authorityReadback.status())
                || !preview.sourceSnapshotDigest().equals(authorityReadback.sourceSnapshotDigest())
                || !preview.workflowSnapshotDigest().equals(authorityReadback.workflowSnapshotDigest())
                || !preview.previewDigest().equals(authorityReadback.previewDigest())) {
            throw invalid(409, "BOM 权威在草稿写入期间发生变化",
                    "BOM_DRAFT_AUTHORITY_DRIFT_DURING_WRITE");
        }
        return receipt(saved, false, 1, 1 + itemRows.size() + substituteRows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        BomRecipeDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "BOM 草稿不存在", "BOM_DRAFT_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !BomRecipeDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())) {
            throw invalid(409, "BOM 草稿依赖闭包或状态无效", "BOM_DRAFT_READBACK_INVARIANT_BROKEN");
        }

        List<BomRecipeDraftItem> rows = draftItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<BomRecipeDraftSubstitute> substituteRows = draftSubstitutes
                .findByDraftIdAndFactoryIdOrderByItemIdAscPriorityAsc(draftId, factoryId);
        Map<String, List<SubstituteCandidate>> byItem = new HashMap<>();
        for (BomRecipeDraftSubstitute row : substituteRows) {
            byItem.computeIfAbsent(row.getItemId(), ignored -> new ArrayList<>()).add(fromRow(row));
        }
        List<BomItem> items = rows.stream().map(row -> fromRow(row,
                byItem.getOrDefault(row.getId(), List.of()))).toList();

        Long readbackOperatorId = parent.getOperatorId() == null ? parent.getActorId() : parent.getOperatorId();
        if (readbackOperatorId == null) throw invalid(409, "草稿缺少 typed actor 身份，无法安全回读", "DRAFT_ACTOR_IDENTITY_MISSING");
        DraftRequest desired = new DraftRequest(parent.getFactoryId(), parent.getBlueprintId(),
                parent.getBlueprintVersion(), parent.getSourceDigest(),
                new ProductRef(ProductRefType.valueOf(parent.getTargetProductRefType()),
                        parent.getTargetProductRefId(), parent.getTargetProductSnapshotDigest()),
                new WorkflowPin(parent.getWorkflowId(), parent.getWorkflowRevisionId(),
                        parent.getWorkflowRevisionHash(), parent.getWorkflowDefinitionVersion(),
                        parent.getWorkflowSchemaVersion()),
                new PackagingPin(PackagingDependencyType.valueOf(parent.getPackagingDependencyType()),
                        parent.getPackagingDraftId(), parent.getPackagingDraftDigest()),
                parent.getOutputQuantity(), parent.getOutputUnit(), parent.getNetContentQuantity(),
                parent.getNetContentUnit(), items, parent.getPreviewDigest(),
                parent.getIdempotencyKey(), readbackOperatorId);
        int substituteCount = items.stream().mapToInt(item -> item.substitutes().size()).sum();
        if (rows.size() != parent.getItemCount()
                || substituteCount != parent.getSubstituteCount()
                || !desiredDigest(desired).equals(parent.getDesiredDigest())) {
            throw invalid(409, "BOM 草稿回读不完整", "BOM_DRAFT_READBACK_MISMATCH");
        }
        return new DraftReadback(BomRecipeDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getStatus(), desired, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        BomRecipeDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "BOM 草稿不存在", "BOM_DRAFT_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus())) {
            throw invalid(409, "只有未发布 Blueprint BOM 草稿可恢复", "BOM_DRAFT_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(BomRecipeDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getDesiredDigest(), "ARCHIVE_DRAFT_AFTER_BOM_OWNER_CONFIRMATION",
                "只归档 Blueprint BOM 草稿；不改变正式 BOM、Workflow、库存、成本、计划、领料或生产记录",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || ((operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致", "BOM_DRAFT_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少 BOM 草稿权限", "BOM_DRAFT_PERMISSION_REQUIRED");
        }
        if (operator.actor().type() == com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER
                && users.findByIdAndFactoryId(operator.actor().id(), operator.factoryId())
                .filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员", "BOM_DRAFT_OPERATOR_MEMBERSHIP_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.product() == null || request.workflow() == null
                || request.items().isEmpty() || request.items().size() > MAX_ITEMS) {
            throw invalid(400, "Blueprint envelope 或 BOM 条目范围无效", "BOM_DRAFT_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效", "BOM_DRAFT_OPERATOR_REQUIRED");
        }
    }

    private ProductResolution resolveProduct(DraftRequest request, List<DraftIssue> issues) {
        ProductRef ref = request.product();
        if (ref.type() == null || ref.refId().isBlank() || !isDigest(ref.snapshotDigest())) {
            issues.add(issue("BOM_PRODUCT_REF_INVALID", "product", "目标产品引用无效",
                    "选择当前工厂的正式产品或同 Blueprint 产品草稿"));
            return ProductResolution.missing();
        }
        if (ref.type() == ProductRefType.LIVE_PRODUCT) {
            ProductType row = products.findByIdAndFactoryId(ref.refId(), request.factoryId()).orElse(null);
            if (row == null || !Boolean.TRUE.equals(row.getIsActive())) {
                issues.add(issue("BOM_PRODUCT_NOT_FOUND", "product", "当前工厂有效产品不存在",
                        "刷新产品目录后重新选择"));
                return ProductResolution.missing();
            }
            String current = productDigest(row);
            if (!current.equals(ref.snapshotDigest())) {
                issues.add(issue("BOM_PRODUCT_DRIFT", "product.snapshotDigest", "产品快照已变化",
                        "重新预览并确认当前产品"));
            }
            CanonicalUnit unit = resolveUnit(request.factoryId(), row.getUnit(), "product.unit", issues, false);
            return new ProductResolution(row.getId(), current, current, unit);
        }

        ProductTypeDraft row = productDrafts.findByIdAndFactoryId(ref.refId(), request.factoryId()).orElse(null);
        if (row == null || !"DRAFT".equals(row.getStatus())
                || !request.blueprintId().equals(row.getBlueprintId())
                || request.blueprintVersion() != row.getBlueprintVersion()
                || !request.sourceDigest().equals(row.getSourceDigest())) {
            issues.add(issue("BOM_PRODUCT_DRAFT_LINEAGE_MISMATCH", "product", "产品草稿不属于当前 Blueprint",
                    "选择同工厂、同 Blueprint 版本的 A1 草稿"));
            return ProductResolution.missing();
        }
        String current = productDraftDigest(row);
        if (!current.equals(ref.snapshotDigest())) {
            issues.add(issue("BOM_PRODUCT_DRAFT_DRIFT", "product.snapshotDigest", "产品草稿快照已变化",
                    "重新预览 A1 产品草稿"));
        }
        CanonicalUnit unit = resolveUnit(request.factoryId(), row.getDesiredUnit(), "product.unit", issues, false);
        String effectiveId = blankToNull(row.getTargetProductTypeId()) == null ? row.getId()
                : row.getTargetProductTypeId();
        return new ProductResolution(effectiveId, current, current, unit);
    }

    private WorkflowResolution resolveWorkflow(DraftRequest request, ProductResolution product,
            List<DraftIssue> issues) {
        WorkflowPin pin = request.workflow();
        if (pin.workflowId() <= 0 || pin.revisionId() <= 0 || pin.definitionVersion() <= 0
                || pin.schemaVersion() <= 0 || !isDigest(pin.revisionHash())) {
            issues.add(issue("BOM_WORKFLOW_PIN_INVALID", "workflow", "Workflow revision pin 无效",
                    "选择当前产品的完整 Workflow DRAFT revision"));
            return WorkflowResolution.missing();
        }
        ProductProcessWorkflowRevision revision = workflowRevisions
                .findByIdAndFactoryId(pin.revisionId(), request.factoryId()).orElse(null);
        ProductProcessWorkflow workflow = workflows
                .findByIdAndFactoryId(pin.workflowId(), request.factoryId()).orElse(null);
        if (revision == null || workflow == null) {
            issues.add(issue("BOM_WORKFLOW_NOT_FOUND", "workflow", "同厂 Workflow 或 revision 不存在",
                    "刷新 Workflow 草稿后重新选择"));
            return WorkflowResolution.missing();
        }
        boolean exact = Objects.equals(revision.getWorkflowId(), workflow.getId())
                && Objects.equals(revision.getProductTypeId(), product.effectiveProductId())
                && Objects.equals(workflow.getProductTypeId(), product.effectiveProductId())
                && workflow.getStatus() == ProductProcessWorkflow.Status.DRAFT
                && revision.getStatus() == ProductProcessWorkflowRevision.Status.DRAFT
                && Objects.equals(workflow.getCurrentRevisionId(), revision.getId())
                && Objects.equals(workflow.getCurrentRevisionHash(), revision.getRevisionHash())
                && Objects.equals(pin.revisionHash(), revision.getRevisionHash())
                && pin.definitionVersion() == revision.getDefinitionVersion()
                && pin.schemaVersion() == revision.getSchemaVersion()
                && Boolean.TRUE.equals(revision.getStructurallyComplete());
        if (!exact) {
            issues.add(issue("BOM_WORKFLOW_PIN_DRIFT", "workflow",
                    "Workflow 必须是当前产品、当前 DRAFT 的完整且精确 revision",
                    "重新保存完整 Workflow 并选择其 current revision"));
        }

        String snapshot = workflowDigest(workflow, revision);
        Graph graph = parseAndValidateGraph(revision, issues);
        return new WorkflowResolution(snapshot, graph.nodes(), graph.edges());
    }

    private Graph parseAndValidateGraph(ProductProcessWorkflowRevision revision, List<DraftIssue> issues) {
        try {
            List<ProductProcessWorkflowDTO.Node> nodes = objectMapper.readValue(revision.getNodesJson(),
                    new TypeReference<>() {});
            List<ProductProcessWorkflowDTO.Edge> edges = objectMapper.readValue(revision.getEdgesJson(),
                    new TypeReference<>() {});
            ProductProcessWorkflowDTO.Viewport viewport = objectMapper.readValue(revision.getViewportJson(),
                    ProductProcessWorkflowDTO.Viewport.class);
            ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
            dto.setSchemaVersion(revision.getSchemaVersion());
            dto.setNodes(nodes);
            dto.setEdges(edges);
            dto.setViewport(viewport);
            workflowValidator.validateStructureComplete(dto);

            Map<String, ProductProcessWorkflowDTO.Node> nodesById = new LinkedHashMap<>();
            for (ProductProcessWorkflowDTO.Node node : nodes) {
                if (nodesById.put(node.getId(), node) != null) {
                    throw new IllegalArgumentException("duplicate node");
                }
            }
            Map<String, ProductProcessWorkflowDTO.Edge> edgesById = new LinkedHashMap<>();
            for (ProductProcessWorkflowDTO.Edge edge : edges) {
                if (edgesById.put(edge.getId(), edge) != null) {
                    throw new IllegalArgumentException("duplicate edge");
                }
            }
            return new Graph(nodesById, edgesById);
        } catch (Exception exception) {
            issues.add(issue("BOM_WORKFLOW_GRAPH_INVALID", "workflow",
                    "Workflow revision 图无法通过现有结构闸", "回到 Workflow 画布修复并重新保存"));
            return Graph.missing();
        }
    }

    private PackagingResolution resolvePackaging(DraftRequest request, List<DraftIssue> issues) {
        PackagingPin pin = request.packaging();
        if (pin.type() == PackagingDependencyType.NONE) {
            if (!pin.draftId().isBlank() || !pin.desiredDigest().isBlank()) {
                issues.add(issue("BOM_PACKAGING_PIN_INVALID", "packaging",
                        "NONE 不能携带包装草稿引用", "清空包装引用或选择 PACKAGING_DRAFT"));
            }
            return PackagingResolution.none();
        }
        if (pin.draftId().isBlank() || !isDigest(pin.desiredDigest())) {
            issues.add(issue("BOM_PACKAGING_PIN_INVALID", "packaging", "包装草稿 pin 无效",
                    "选择同 Blueprint 的 packaging DRAFT"));
            return PackagingResolution.none();
        }
        PackagingDraft row = packagingDrafts.findByIdAndFactoryId(pin.draftId(), request.factoryId())
                .orElse(null);
        if (row == null || !"DRAFT".equals(row.getStatus())
                || !request.blueprintId().equals(row.getBlueprintId())
                || request.blueprintVersion() != row.getBlueprintVersion()
                || !request.sourceDigest().equals(row.getSourceDigest())
                || !pin.desiredDigest().equals(row.getDesiredDigest())) {
            issues.add(issue("BOM_PACKAGING_DRAFT_DRIFT", "packaging",
                    "包装草稿不存在、lineage 不同或摘要已变化", "重新预览同 Blueprint 包装草稿"));
            return PackagingResolution.none();
        }
        List<PackagingDraftItem> items = packagingDraftItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(row.getId(), request.factoryId());
        String snapshot = packagingDigest(row, items);
        Map<String, String> targetSnapshots = new LinkedHashMap<>();
        for (PackagingDraftItem item : items) {
            String target = item.getTargetKind() + ':' + item.getTargetId();
            String snapshotDigest = Objects.toString(item.getTargetSnapshotDigest(), "");
            String existing = targetSnapshots.putIfAbsent(target, snapshotDigest);
            if (existing != null && !existing.equals(snapshotDigest)) {
                issues.add(issue("BOM_PACKAGING_TARGET_AMBIGUOUS", "packaging.targets[" + target + "]",
                        "包装草稿对同一目标包含互相冲突的快照", "重新生成只有一个权威目标快照的 packaging DRAFT"));
            }
        }
        return new PackagingResolution(snapshot, Map.copyOf(targetSnapshots));
    }

    private void validatePackagingCoverage(DraftRequest request, PackagingResolution packaging,
            List<DraftIssue> issues) {
        boolean hasPackaging = request.items().stream().filter(Objects::nonNull)
                .anyMatch(item -> item.category() == MaterialCategory.PACKAGING);
        if (request.packaging().type() != PackagingDependencyType.PACKAGING_DRAFT) {
            if (!hasPackaging) return;
            issues.add(issue("BOM_PACKAGING_DEPENDENCY_REQUIRED", "packaging",
                    "包含包材的 BOM 必须 pin 同 Blueprint 包装草稿", "先确认 packaging DRAFT"));
            return;
        }
        ProductRef productRef = request.product();
        String productTarget = (productRef.type() == ProductRefType.LIVE_PRODUCT
                ? "PRODUCT" : "PRODUCT_DRAFT") + ':' + productRef.refId();
        String productSnapshot = packaging.targetSnapshots().get(productTarget);
        if (productSnapshot != null) {
            verifyPackagingTarget(request, productRef.type() == ProductRefType.LIVE_PRODUCT
                    ? TargetKind.PRODUCT : TargetKind.PRODUCT_DRAFT, productRef.refId(), productSnapshot,
                    productRef.snapshotDigest(), "product.snapshotDigest", issues);
        }
        if (!hasPackaging) return;
        for (int index = 0; index < request.items().size(); index++) {
            BomItem item = request.items().get(index);
            if (item == null || item.category() != MaterialCategory.PACKAGING || item.material() == null) continue;
            MaterialRef ref = item.material();
            String target = (ref.type() == MaterialRefType.LIVE_MATERIAL
                    ? "MATERIAL" : "MATERIAL_DRAFT") + ':' + ref.refId();
            String packagingSnapshot = packaging.targetSnapshots().get(target);
            if (packagingSnapshot == null) {
                issues.add(issue("BOM_PACKAGING_TARGET_NOT_COVERED", "items[" + index + "].material",
                        "所选包装草稿未覆盖该 BOM 包材", "选择逐项覆盖全部 BOM 包材的 packaging DRAFT"));
            } else {
                verifyPackagingTarget(request, ref.type() == MaterialRefType.LIVE_MATERIAL
                        ? TargetKind.MATERIAL : TargetKind.MATERIAL_DRAFT, ref.refId(), packagingSnapshot,
                        ref.snapshotDigest(), "items[" + index + "].material.snapshotDigest", issues);
            }
        }
    }

    private void verifyPackagingTarget(DraftRequest request, TargetKind kind, String targetId,
            String packagingSnapshot, String expectedRefSnapshot, String target, List<DraftIssue> issues) {
        if (packagingAuthority == null) {
            // Legacy unit tests construct this command without Spring. Preserve their direct
            // authority check while the application path uses the typed adapter below.
            if (!packagingSnapshot.equals(expectedRefSnapshot)) {
                issues.add(issue("BOM_PACKAGING_TARGET_DRIFT", target,
                        "包装草稿中的目标快照已变化", "重新预览并选择当前 packaging DRAFT"));
            }
            return;
        }
        Optional<PackagingDraftCommand.TargetAuthority> current = packagingAuthority.currentTargetAuthority(
                request.factoryId(), request.blueprintId(), request.blueprintVersion(), request.sourceDigest(),
                kind, targetId);
        if (current.isEmpty() || !packagingSnapshot.equals(current.get().snapshotDigest())) {
            issues.add(issue("BOM_PACKAGING_TARGET_DRIFT", target,
                    "包装草稿中的目标快照已变化或目标无法解析", "重新读取当前 packaging authority 并生成预览"));
        }
    }

    private MaterialResolution resolveMaterial(DraftRequest request, MaterialRef ref, String target,
            List<DraftIssue> issues) {
        if (ref == null || ref.type() == null || ref.refId().isBlank() || !isDigest(ref.snapshotDigest())) {
            issues.add(issue("BOM_MATERIAL_REF_INVALID", target, "物料引用无效",
                    "选择当前工厂物料或同 Blueprint A2 草稿"));
            return MaterialResolution.missing();
        }
        if (ref.type() == MaterialRefType.LIVE_MATERIAL) {
            RawMaterialType row = materials.findByIdAndFactoryId(ref.refId(), request.factoryId()).orElse(null);
            if (row == null || !Boolean.TRUE.equals(row.getIsActive())) {
                issues.add(issue("BOM_MATERIAL_NOT_FOUND", target, "当前工厂有效物料不存在",
                        "刷新物料目录后重新选择"));
                return MaterialResolution.missing();
            }
            String current = materialDigest(row);
            if (!current.equals(ref.snapshotDigest())) {
                issues.add(issue("BOM_MATERIAL_DRIFT", target + ".snapshotDigest", "物料快照已变化",
                        "重新预览并确认当前物料"));
            }
            CanonicalUnit unit = resolveUnit(request.factoryId(), row.getUnit(), target + ".catalogUnit", issues, false);
            return new MaterialResolution(row.getId(), current, current, unit);
        }
        MaterialTypeDraft row = materialDrafts.findByIdAndFactoryId(ref.refId(), request.factoryId()).orElse(null);
        if (row == null || !"DRAFT".equals(row.getStatus())
                || !request.factoryId().equals(row.getFactoryId())
                || !request.blueprintId().equals(row.getBlueprintId())
                || request.blueprintVersion() != row.getBlueprintVersion()
                || !request.sourceDigest().equals(row.getSourceDigest())) {
            issues.add(issue("BOM_MATERIAL_DRAFT_LINEAGE_MISMATCH", target,
                    "物料草稿不属于当前 Blueprint", "选择同工厂、同 Blueprint 版本的 A2 草稿"));
            return MaterialResolution.missing();
        }
        String current = materialDraftDigest(row);
        if (!current.equals(ref.snapshotDigest())) {
            issues.add(issue("BOM_MATERIAL_DRAFT_DRIFT", target + ".snapshotDigest",
                    "物料草稿快照已变化", "重新预览 A2 物料草稿"));
        }
        CanonicalUnit unit = resolveUnit(request.factoryId(), row.getDesiredUnit(), target + ".catalogUnit", issues, false);
        String effectiveId = blankToNull(row.getTargetMaterialTypeId()) == null ? row.getId()
                : row.getTargetMaterialTypeId();
        return new MaterialResolution(effectiveId, current, current, unit);
    }

    /** A unit the BOM draft itself saves (item / price / output / net content): must be the canonical code. */
    private CanonicalUnit resolveUnit(String factoryId, String raw, String target,
            List<DraftIssue> issues) {
        return resolveUnit(factoryId, raw, target, issues, true);
    }

    /**
     * @param requireCanonicalCode true for units the draft saves; false for the unit of a live (or draft) product /
     *        material row it only <em>reads</em>: the platform stores such rows in the catalog's storage unit
     *        (a count unit is stored as 「个」, its canonical code is {@code pcs}), so demanding the code of a row's
     *        own unit would refuse every material that is not counted in kg.
     */
    private CanonicalUnit resolveUnit(String factoryId, String raw, String target,
            List<DraftIssue> issues, boolean requireCanonicalCode) {
        if (raw == null || raw.isBlank() || raw.length() > 20) {
            issues.add(issue("BOM_UNIT_REQUIRED", target, "BOM 单位不能为空", "选择单位目录中的数量单位"));
            return null;
        }
        CanonicalUnit unit = units.describe(factoryId, raw).orElse(null);
        if (unit == null || !unit.active() || !units.supportsUsage(factoryId, raw, UnitUsageScope.BOM_QUANTITY)) {
            issues.add(issue("BOM_UNIT_NOT_SUPPORTED", target, "单位未获准用于 BOM 数量",
                    "选择当前工厂 BOM_QUANTITY 目录中的单位"));
            return null;
        }
        if (requireCanonicalCode && !raw.equals(unit.code())) {
            issues.add(issue("BOM_UNIT_CANONICAL_REQUIRED", target,
                    "BOM 草稿必须保存平台单位目录的 canonical code",
                    "使用平台解析后的单位 " + unit.code() + " 重新预览"));
        }
        return unit;
    }

    private void validateItemShape(BomItem item, String target, List<DraftIssue> issues) {
        if (item == null) {
            issues.add(issue("BOM_ITEM_REQUIRED", target, "BOM 条目不能为空", "补充 typed BOM 条目"));
            return;
        }
        if (item.category() == null || item.portDirection() == null
                || isBlankOrTooLong(item.workflowMaterialNodeId(), 128)
                || isBlankOrTooLong(item.workflowProcessNodeId(), 128)
                || isBlankOrTooLong(item.workflowPortId(), 128)
                || isBlankOrTooLong(item.workflowEdgeId(), 128)
                || isBlankOrTooLong(item.changeReason(), 500)) {
            issues.add(issue("BOM_ITEM_FIELDS_REQUIRED", target,
                    "BOM 类别、Workflow pin 和变更原因均必填", "补全 typed BOM 字段"));
        }
        if (!validDecimal(item.standardQuantity(), 20, 8, MAX_QUANTITY)) {
            issues.add(issue("BOM_QUANTITY_INVALID", target + ".standardQuantity",
                    "标准数量必须是精确正数且不超过 8 位小数", "修正标准数量"));
        }
        if (!validPercent(item.yieldPercent())) {
            issues.add(issue("BOM_YIELD_INVALID", target + ".yieldPercent",
                    "出成率必须大于 0 且不超过 100", "修正出成率"));
        }
        boolean hasPriceUnit = !item.priceUnit().isBlank();
        boolean hasFactor = item.quantityToPriceFactor() != null;
        if (hasPriceUnit != hasFactor || (hasFactor
                && !validDecimal(item.quantityToPriceFactor(), 24, 12, MAX_QUANTITY))) {
            issues.add(issue("BOM_PRICE_FACTOR_INVALID", target,
                    "价格单位与数量换算因子必须成对且为精确正数", "补全或清空价格换算快照"));
        }
        if ((item.category() == MaterialCategory.BYPRODUCT) != (item.portDirection() == PortDirection.OUTPUT)) {
            issues.add(issue("BOM_ITEM_DIRECTION_INVALID", target,
                    "副产物必须绑定 OUTPUT，投入物料必须绑定 INPUT", "修正类别或端口方向"));
        }
        if (item.substitutes().size() > MAX_SUBSTITUTES || (item.category() == MaterialCategory.BYPRODUCT
                && !item.substitutes().isEmpty())) {
            issues.add(issue("BOM_SUBSTITUTE_SCOPE_INVALID", target + ".substitutes",
                    "副产物不能配置替代料，单行替代候选也不能超过上限", "移除无效替代候选"));
        }
    }

    private void validateSubstitutes(DraftRequest request, BomItem item, MaterialResolution primary,
            CanonicalUnit itemUnit, String target, StringBuilder source, List<DraftIssue> issues) {
        Set<Integer> priorities = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < item.substitutes().size(); index++) {
            SubstituteCandidate candidate = item.substitutes().get(index);
            String candidateTarget = target + ".substitutes[" + index + "]";
            if (candidate == null || candidate.material() == null) {
                issues.add(issue("BOM_SUBSTITUTE_REQUIRED", candidateTarget,
                        "替代候选不能为空", "补充 typed 替代候选"));
                source.append("missing-substitute|");
                continue;
            }
            if (candidate.priority() <= 0 || candidate.priority() > 100
                    || !priorities.add(candidate.priority())) {
                issues.add(issue("BOM_SUBSTITUTE_PRIORITY_INVALID", candidateTarget + ".priority",
                        "替代优先级必须在 1 到 100 且不能重复", "修正优先级"));
            }
            String identity = candidate.material().type() + ":" + candidate.material().refId();
            if (!identities.add(identity) || (item.material() != null
                    && item.material().type() == candidate.material().type()
                    && item.material().refId().equals(candidate.material().refId()))) {
                issues.add(issue("BOM_SUBSTITUTE_DUPLICATE", candidateTarget + ".material",
                        "替代候选不能重复或等于主物料", "选择不同候选"));
            }
            if (candidate.conversionBasis() != SubstituteRatioBasis.PRIMARY_UNIT_TO_CANDIDATE_UNIT
                    || !validDecimal(candidate.conversionRatio(), 20, 8, MAX_QUANTITY)
                    || !validPercent(candidate.maxSharePercent())
                    || isBlankOrTooLong(candidate.reason(), 500)) {
                issues.add(issue("BOM_SUBSTITUTE_FIELDS_INVALID", candidateTarget,
                        "替代换算方向、比例、最大占比和原因必须完整有效", "修正 typed 替代字段"));
            }
            MaterialResolution resolved = resolveMaterial(request, candidate.material(),
                    candidateTarget + ".material", issues);
            source.append("substitute:").append(resolved.sourceDigest()).append('|');
            if (primary.canonicalUnit() != null && resolved.canonicalUnit() != null
                    && primary.canonicalUnit().dimension() != resolved.canonicalUnit().dimension()) {
                issues.add(issue("BOM_SUBSTITUTE_DIMENSION_MISMATCH", candidateTarget + ".material",
                        "替代候选与主物料量纲不一致", "选择同量纲替代物料"));
            }
            if (itemUnit != null && resolved.canonicalUnit() != null
                    && itemUnit.dimension() != resolved.canonicalUnit().dimension()) {
                issues.add(issue("BOM_SUBSTITUTE_UNIT_MISMATCH", candidateTarget + ".material",
                        "替代候选不能按当前 BOM 单位计量", "选择兼容单位的候选"));
            } else if (itemUnit != null && resolved.canonicalUnit() != null
                    && validDecimal(candidate.conversionRatio(), 20, 8, MAX_QUANTITY)) {
                BigDecimal primaryFactor = itemUnit.factorToBase();
                BigDecimal candidateFactor = resolved.canonicalUnit().factorToBase();
                if (primaryFactor == null || candidateFactor == null
                        || primaryFactor.signum() <= 0 || candidateFactor.signum() <= 0) {
                    issues.add(issue("BOM_SUBSTITUTE_CONVERSION_UNRESOLVED", candidateTarget + ".conversionRatio",
                            "单位目录无法确定替代换算", "修复单位目录后重新预览"));
                } else {
                    BigDecimal expected = primaryFactor.divide(candidateFactor, 8, RoundingMode.HALF_UP)
                            .stripTrailingZeros();
                    if (candidate.conversionRatio().compareTo(expected) != 0) {
                        issues.add(issue("BOM_SUBSTITUTE_CONVERSION_NOT_CONSERVATIVE",
                                candidateTarget + ".conversionRatio",
                                "替代比例不满足 1 个主料 BOM 单位等于 N 个候选物料目录单位",
                                "使用平台单位目录计算的精确比例 " + expected.toPlainString()));
                    }
                }
            }
        }
    }

    private void validateWorkflowBinding(String factoryId, WorkflowResolution workflow, BomItem item,
            MaterialResolution material, CanonicalUnit itemUnit, String target, List<DraftIssue> issues) {
        ProductProcessWorkflowDTO.Node materialNode = workflow.nodes().get(item.workflowMaterialNodeId());
        ProductProcessWorkflowDTO.Node processNode = workflow.nodes().get(item.workflowProcessNodeId());
        ProductProcessWorkflowDTO.Edge edge = workflow.edges().get(item.workflowEdgeId());
        if (materialNode == null || processNode == null || edge == null
                || !"PROCESS".equals(processNode.getKind())) {
            issues.add(issue("BOM_WORKFLOW_BINDING_NOT_FOUND", target,
                    "BOM 行引用的 Workflow 节点、工序或连线不存在", "从 current revision 重新选择完整 pin"));
            return;
        }
        if (item.category() == MaterialCategory.BYPRODUCT
                ? "RAW_MATERIAL".equals(materialNode.getKind())
                : "PROCESS".equals(materialNode.getKind())) {
            issues.add(issue("BOM_WORKFLOW_MATERIAL_KIND_INVALID", target + ".workflowMaterialNodeId",
                    "Workflow 物料节点种类与 BOM 投入/产出语义不一致", "选择正确的物料节点"));
        }
        String skuId = text(materialNode.getData(), "skuId");
        if (!material.effectiveMaterialId().equals(skuId)) {
            issues.add(issue("BOM_WORKFLOW_MATERIAL_MISMATCH", target + ".workflowMaterialNodeId",
                    "Workflow 物料节点与 BOM 物料引用不一致", "选择绑定同一 SKU 的节点"));
        }

        Map<String, Object> port = null;
        int matches = 0;
        Object rawPorts = processNode.getData() == null ? null : processNode.getData().get("ports");
        if (rawPorts instanceof List<?> ports) {
            for (Object raw : ports) {
                if (raw instanceof Map<?, ?> map && item.workflowPortId().equals(text(map, "id"))) {
                    matches++;
                    port = stringMap(map);
                }
            }
        }
        if (matches != 1 || port == null
                || !item.portDirection().name().equals(text(port, "direction"))
                || !item.workflowMaterialNodeId().equals(text(port, "materialNodeId"))) {
            issues.add(issue("BOM_WORKFLOW_PORT_MISMATCH", target + ".workflowPortId",
                    "Workflow 端口不存在、重复、方向错误或未绑定该物料节点", "选择唯一且方向正确的端口"));
            return;
        }
        CanonicalUnit portUnit = units.describe(factoryId, text(port, "unit")).orElse(null);
        if (itemUnit != null && (portUnit == null || !sameCanonical(itemUnit, portUnit))) {
            issues.add(issue("BOM_WORKFLOW_PORT_UNIT_MISMATCH", target + ".unit",
                    "BOM 单位与 Workflow 端口单位不一致", "使用端口精确单位或修正 Workflow"));
        }

        boolean edgeExact;
        if (item.portDirection() == PortDirection.INPUT) {
            edgeExact = item.workflowMaterialNodeId().equals(edge.getSource())
                    && item.workflowProcessNodeId().equals(edge.getTarget())
                    && "output".equals(edge.getSourceHandle())
                    && item.workflowPortId().equals(edge.getTargetHandle());
        } else {
            edgeExact = item.workflowProcessNodeId().equals(edge.getSource())
                    && item.workflowMaterialNodeId().equals(edge.getTarget())
                    && item.workflowPortId().equals(edge.getSourceHandle())
                    && "input".equals(edge.getTargetHandle());
        }
        if (!edgeExact) {
            issues.add(issue("BOM_WORKFLOW_EDGE_MISMATCH", target + ".workflowEdgeId",
                    "Workflow 连线与物料、工序、端口方向不一致", "选择 current revision 中精确连线"));
        }
    }

    private String liveBomDigest(DraftRequest request, ProductResolution product) {
        if (product.effectiveProductId().isBlank()) return digest("MISSING");
        java.util.Map<String, BomRecipeDraftPromotionAuthority.VerifiedPromotionTarget> verifiedOwnPromotionTargets
                = verifiedOwnPromotionTargets(request);
        List<String> values = new ArrayList<>();
        for (BomRecipe row : liveRecipes.findByFactoryIdAndProductTypeIdOrderByVersionDesc(
                request.factoryId(), product.effectiveProductId())) {
            BomRecipeDraftPromotionAuthority.VerifiedPromotionTarget proof = verifiedOwnPromotionTargets.get(row.getId());
            if (proof != null && promotionAuthority.matchesCurrent(proof, row)) continue;
            values.add(row.getId()); values.add(row.getFactoryId()); values.add(row.getProductTypeId());
            values.add(String.valueOf(row.getVersion())); values.add(String.valueOf(row.getStatus()));
            values.add(String.valueOf(row.getIsCurrent())); values.add(decimal(row.getOutputQuantityPerUnit()));
            values.add(row.getOutputUnit()); values.add(decimal(row.getNetContentQuantity()));
            values.add(row.getNetContentUnit()); values.add(String.valueOf(row.getWorkflowRevisionId()));
            values.add(row.getWorkflowRevisionHash()); values.add(String.valueOf(row.getUpdatedAt()));
        }
        for (BomRecipeItem row : liveRecipeItems.findCurrentByProduct(
                request.factoryId(), product.effectiveProductId())) {
            values.add(String.valueOf(row.getId())); values.add(row.getRecipeId());
            values.add(row.getMaterialTypeId()); values.add(decimal(row.getStandardQuantity()));
            values.add(decimal(row.getYieldRate())); values.add(row.getUnit());
            values.add(row.getMaterialCategory()); values.add(row.getWorkflowMaterialNodeId());
            values.add(row.getWorkflowInputPortId()); values.add(row.getWorkflowEdgeId());
            values.add(String.valueOf(row.getUpdatedAt()));
        }
        return digest(values.toArray(String[]::new));
    }

    private java.util.Map<String, BomRecipeDraftPromotionAuthority.VerifiedPromotionTarget> verifiedOwnPromotionTargets(DraftRequest request) {
        if (promotionAuthority == null || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()) return java.util.Map.of();
        BomRecipeDraft persisted = drafts.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey()).orElse(null);
        if (persisted == null || !"DRAFT".equals(persisted.getStatus())
                || !Objects.equals(persisted.getBlueprintId(), request.blueprintId())
                || !Objects.equals(persisted.getBlueprintVersion(), request.blueprintVersion())
                || !Objects.equals(persisted.getSourceDigest(), request.sourceDigest())
                || !Objects.equals(persisted.getDesiredDigest(), desiredDigest(request))) return java.util.Map.of();
        return promotionAuthority.verifiedFormalRecipeId(persisted)
                .map(proof -> java.util.Map.of(proof.id(), proof)).orElseGet(java.util.Map::of);
    }

    private BomRecipeDraftItem toRow(String draftId, String factoryId, int ordinal, BomItem item) {
        return BomRecipeDraftItem.builder().id(UUID.randomUUID().toString()).draftId(draftId)
                .factoryId(factoryId).ordinal(ordinal).materialRefType(item.material().type().name())
                .materialRefId(item.material().refId()).materialSnapshotDigest(item.material().snapshotDigest())
                .materialCategory(item.category().name()).standardQuantity(item.standardQuantity())
                .unit(item.unit()).yieldPercent(item.yieldPercent())
                .priceUnit(blankToNull(item.priceUnit())).quantityToPriceFactor(item.quantityToPriceFactor())
                .optional(item.optional()).workflowMaterialNodeId(item.workflowMaterialNodeId())
                .workflowProcessNodeId(item.workflowProcessNodeId()).workflowPortId(item.workflowPortId())
                .workflowEdgeId(item.workflowEdgeId()).workflowPortDirection(item.portDirection().name())
                .changeReason(item.changeReason()).build();
    }

    private BomRecipeDraftSubstitute toSubstituteRow(String draftId, String itemId, String factoryId,
            SubstituteCandidate candidate) {
        return BomRecipeDraftSubstitute.builder().id(UUID.randomUUID().toString()).itemId(itemId)
                .draftId(draftId).factoryId(factoryId).priority(candidate.priority())
                .candidateRefType(candidate.material().type().name())
                .candidateRefId(candidate.material().refId())
                .candidateSnapshotDigest(candidate.material().snapshotDigest())
                .conversionBasis(candidate.conversionBasis().name())
                .conversionRatio(candidate.conversionRatio()).maxSharePercent(candidate.maxSharePercent())
                .reason(candidate.reason()).build();
    }

    private BomItem fromRow(BomRecipeDraftItem row, List<SubstituteCandidate> substitutes) {
        return new BomItem(new MaterialRef(MaterialRefType.valueOf(row.getMaterialRefType()),
                row.getMaterialRefId(), row.getMaterialSnapshotDigest()),
                MaterialCategory.valueOf(row.getMaterialCategory()), row.getStandardQuantity(), row.getUnit(),
                row.getYieldPercent(), row.getPriceUnit(), row.getQuantityToPriceFactor(),
                Boolean.TRUE.equals(row.getOptional()), row.getWorkflowMaterialNodeId(),
                row.getWorkflowProcessNodeId(), row.getWorkflowPortId(), row.getWorkflowEdgeId(),
                PortDirection.valueOf(row.getWorkflowPortDirection()), substitutes, row.getChangeReason());
    }

    private SubstituteCandidate fromRow(BomRecipeDraftSubstitute row) {
        return new SubstituteCandidate(new MaterialRef(MaterialRefType.valueOf(row.getCandidateRefType()),
                row.getCandidateRefId(), row.getCandidateSnapshotDigest()), row.getPriority(),
                SubstituteRatioBasis.valueOf(row.getConversionBasis()), row.getConversionRatio(),
                row.getMaxSharePercent(), row.getReason());
    }

    private DraftReceipt receipt(BomRecipeDraft parent, boolean replayed, int writes, int rows) {
        return new DraftReceipt(BomRecipeDraftModels.SCHEMA_VERSION, parent.getId(), parent.getFactoryId(),
                parent.getId(), parent.getStatus(), parent.getSourceSnapshotDigest(),
                parent.getWorkflowSnapshotDigest(), parent.getDesiredDigest(), parent.getPreviewDigest(),
                parent.getRequestDigest(), parent.getItemCount(), parent.getSubstituteCount(),
                Objects.requireNonNullElse(parent.getLockVersion(), 0L), replayed, writes,
                replayed ? 0 : 1, rows, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED",
                "ARCHIVE_DRAFT_AFTER_BOM_OWNER_CONFIRMATION");
    }

    /** The snapshot a {@code LIVE_PRODUCT} ref must carry; public so an assembler pins the same value the preview recomputes. */
    public String productDigest(ProductType row) {
        return digest(row.getId(), row.getFactoryId(), row.getCode(), row.getName(), row.getUnit(),
                row.getProductCategory(), String.valueOf(row.getIsActive()), String.valueOf(row.getUpdatedAt()));
    }

    private String productDraftDigest(ProductTypeDraft row) {
        return digest(row.getId(), row.getFactoryId(), row.getBlueprintId(),
                String.valueOf(row.getBlueprintVersion()), row.getSourceDigest(), row.getTargetProductTypeId(),
                row.getNaturalKey(), row.getDesiredName(), row.getDesiredUnit(), row.getDesiredProductCategory(),
                row.getSourceSnapshotDigest(), row.getDesiredDigest(), row.getPreviewDigest(), row.getStatus());
    }

    /** The snapshot a {@code LIVE_MATERIAL} ref must carry; see {@link #productDigest}. */
    public String materialDigest(RawMaterialType row) {
        return digest(row.getId(), row.getFactoryId(), row.getCode(), row.getName(), row.getUnit(),
                row.getCategory(), String.valueOf(row.getIsByproduct()), String.valueOf(row.getIsActive()),
                String.valueOf(row.getUpdatedAt()));
    }

    private String materialDraftDigest(MaterialTypeDraft row) {
        return digest(row.getId(), row.getFactoryId(), row.getBlueprintId(),
                String.valueOf(row.getBlueprintVersion()), row.getSourceDigest(), row.getTargetMaterialTypeId(),
                row.getNaturalKey(), row.getDesiredName(), row.getDesiredCategory(), row.getDesiredUnit(),
                String.valueOf(row.getDesiredIsByproduct()), String.valueOf(row.getDesiredPackagingCategory()),
                row.getSourceSnapshotDigest(), row.getDesiredDigest(), row.getPreviewDigest(), row.getStatus());
    }

    private String workflowDigest(ProductProcessWorkflow workflow, ProductProcessWorkflowRevision revision) {
        return digest(String.valueOf(workflow.getId()), workflow.getFactoryId(), workflow.getProductTypeId(),
                String.valueOf(workflow.getStatus()), String.valueOf(workflow.getDefinitionVersion()),
                String.valueOf(workflow.getCurrentRevisionId()), workflow.getCurrentRevisionHash(),
                String.valueOf(workflow.getLockVersion()), String.valueOf(workflow.getUpdatedAt()),
                String.valueOf(revision.getId()), revision.getFactoryId(), revision.getProductTypeId(),
                String.valueOf(revision.getWorkflowId()), String.valueOf(revision.getDefinitionVersion()),
                String.valueOf(revision.getRevisionNumber()), revision.getRevisionHash(),
                String.valueOf(revision.getStatus()), String.valueOf(revision.getSchemaVersion()),
                revision.getNodesJson(), revision.getEdgesJson(), revision.getViewportJson(),
                String.valueOf(revision.getProcessCount()), String.valueOf(revision.getStructurallyComplete()),
                revision.getValidationMessage(), String.valueOf(revision.getUpdatedAt()));
    }

    private String packagingDigest(PackagingDraft row, List<PackagingDraftItem> items) {
        List<String> values = new ArrayList<>();
        values.add(row.getId()); values.add(row.getFactoryId()); values.add(row.getBlueprintId());
        values.add(String.valueOf(row.getBlueprintVersion())); values.add(row.getSourceSnapshotDigest());
        values.add(row.getDesiredDigest()); values.add(row.getPreviewDigest()); values.add(row.getStatus());
        for (PackagingDraftItem item : items) {
            values.add(item.getId()); values.add(item.getTargetKind()); values.add(item.getTargetId());
            values.add(item.getTargetSnapshotDigest()); values.add(item.getPackagingRole());
            values.add(item.getPackageUnit()); values.add(item.getContainedUnit());
            values.add(decimal(item.getUnitsPerPackage())); values.add(String.valueOf(item.getUpdatedAt()));
        }
        return digest(values.toArray(String[]::new));
    }

    private String unitDigest(CanonicalUnit unit) {
        if (unit == null) return digest("MISSING");
        return digest(unit.code(), String.valueOf(unit.dimension()), unit.baseCode(),
                decimal(unit.factorToBase()), unit.displayName(), String.valueOf(unit.displayScale()),
                String.valueOf(unit.active()), unit.conversionFamily(),
                unit.usageScopes().stream().map(Enum::name).sorted().toList().toString());
    }

    private String desiredDigest(DraftRequest request) {
        List<String> values = new ArrayList<>();
        values.add(BomRecipeDraftModels.SCHEMA_VERSION);
        values.add(String.valueOf(request.product().type())); values.add(request.product().refId());
        values.add(request.product().snapshotDigest()); values.add(String.valueOf(request.workflow().workflowId()));
        values.add(String.valueOf(request.workflow().revisionId())); values.add(request.workflow().revisionHash());
        values.add(String.valueOf(request.workflow().definitionVersion()));
        values.add(String.valueOf(request.workflow().schemaVersion()));
        values.add(String.valueOf(request.packaging().type())); values.add(request.packaging().draftId());
        values.add(request.packaging().desiredDigest()); values.add(decimal(request.outputQuantity()));
        values.add(request.outputUnit()); values.add(decimal(request.netContentQuantity()));
        values.add(request.netContentUnit());
        for (BomItem item : request.items()) {
            if (item == null) { values.add("NULL_ITEM"); continue; }
            values.add(item.material() == null ? "" : String.valueOf(item.material().type()));
            values.add(item.material() == null ? "" : item.material().refId());
            values.add(item.material() == null ? "" : item.material().snapshotDigest());
            values.add(String.valueOf(item.category())); values.add(decimal(item.standardQuantity()));
            values.add(item.unit()); values.add(decimal(item.yieldPercent())); values.add(item.priceUnit());
            values.add(decimal(item.quantityToPriceFactor())); values.add(String.valueOf(item.optional()));
            values.add(item.workflowMaterialNodeId()); values.add(item.workflowProcessNodeId());
            values.add(item.workflowPortId()); values.add(item.workflowEdgeId());
            values.add(String.valueOf(item.portDirection())); values.add(item.changeReason());
            for (SubstituteCandidate candidate : item.substitutes()) {
                if (candidate == null) { values.add("NULL_SUBSTITUTE"); continue; }
                values.add(candidate.material() == null ? "" : String.valueOf(candidate.material().type()));
                values.add(candidate.material() == null ? "" : candidate.material().refId());
                values.add(candidate.material() == null ? "" : candidate.material().snapshotDigest());
                values.add(String.valueOf(candidate.priority())); values.add(String.valueOf(candidate.conversionBasis()));
                values.add(decimal(candidate.conversionRatio()));
                values.add(decimal(candidate.maxSharePercent())); values.add(candidate.reason());
            }
        }
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        return digest(request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), desiredDigest(request));
    }

    private boolean sameCanonical(CanonicalUnit left, CanonicalUnit right) {
        return left.dimension() == right.dimension() && Objects.equals(left.code(), right.code())
                && Objects.equals(left.baseCode(), right.baseCode())
                && Objects.equals(decimal(left.factorToBase()), decimal(right.factorToBase()));
    }

    private boolean validDecimal(BigDecimal value, int precision, int scale, BigDecimal max) {
        return value != null && value.signum() > 0 && value.compareTo(max) <= 0
                && value.precision() <= precision && value.scale() <= scale;
    }

    private boolean validPercent(BigDecimal value) {
        return value != null && value.signum() > 0 && value.compareTo(new BigDecimal("100")) <= 0
                && value.precision() <= 7 && value.scale() <= 4;
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String decimal(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }

    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_bom_recipe_draft_idempotency")) return true;
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

    private String text(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String string ? string : "";
    }

    private Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private record ProductResolution(String effectiveProductId, String currentDigest,
                                     String sourceDigest, CanonicalUnit canonicalUnit) {
        static ProductResolution missing() { return new ProductResolution("", "", digestStatic("MISSING"), null); }
    }

    private record MaterialResolution(String effectiveMaterialId, String currentDigest,
                                      String sourceDigest, CanonicalUnit canonicalUnit) {
        static MaterialResolution missing() { return new MaterialResolution("", "", digestStatic("MISSING"), null); }
    }

    private record WorkflowResolution(String snapshotDigest,
                                      Map<String, ProductProcessWorkflowDTO.Node> nodes,
                                      Map<String, ProductProcessWorkflowDTO.Edge> edges) {
        static WorkflowResolution missing() { return new WorkflowResolution(digestStatic("MISSING"), Map.of(), Map.of()); }
    }

    private record PackagingResolution(String sourceDigest, Map<String, String> targetSnapshots) {
        static PackagingResolution none() { return new PackagingResolution(digestStatic("NONE"), Map.of()); }
    }

    private record Graph(Map<String, ProductProcessWorkflowDTO.Node> nodes,
                         Map<String, ProductProcessWorkflowDTO.Edge> edges) {
        static Graph missing() { return new Graph(Map.of(), Map.of()); }
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


