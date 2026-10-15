package com.cretas.aims.blueprint.promotion;

import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.Blocker;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftItem;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftItemRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftSubstituteRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.packagingdraft.PackagingDraftRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.bomdraft.BomRecipeDraftCommand;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.math.RoundingMode;
import com.cretas.aims.service.bom.BomProductOutputMetadata;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.exception.BusinessException;

/** bom: Blueprint BOM DRAFT -> the existing BomRecipeService's formal DRAFT writer. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
class BomDraftPromoter implements DraftPromoter {
    static final String CAPABILITY_ID = "bom";
    static final String PERMISSION = "bom:draft:promote";

    private final BomRecipeService recipeService;
    private final BomRecipeRepository recipes;
    private final BomRecipeItemRepository recipeItems;
    private final BomRecipeDraftItemRepository draftItems;
    private final BomRecipeDraftSubstituteRepository draftSubstitutes;
    private final ProductTypeRepository products;
    private final RawMaterialTypeRepository materials;
    private final ProductProcessWorkflowRepository workflows;
    private final ProductProcessWorkflowRevisionRepository workflowRevisions;
    private final PackagingDraftRepository packagingDrafts;
    private final BomRecipeDraftCommand draftCommand;
    private final UnitContractService units;

    @PersistenceContext
    private EntityManager entityManager;

    BomDraftPromoter(BomRecipeService recipeService, BomRecipeRepository recipes,
                     BomRecipeItemRepository recipeItems, BomRecipeDraftRepository drafts,
                     BomRecipeDraftItemRepository draftItems,
                     BomRecipeDraftSubstituteRepository draftSubstitutes,
                     ProductTypeRepository products,
                     RawMaterialTypeRepository materials, ProductProcessWorkflowRepository workflows,
                     ProductProcessWorkflowRevisionRepository workflowRevisions,
                     PackagingDraftRepository packagingDrafts, BomRecipeDraftCommand draftCommand,
                     UnitContractService units) {
        this.recipeService = recipeService;
        this.recipes = recipes;
        this.recipeItems = recipeItems;
        this.draftItems = draftItems;
        this.draftSubstitutes = draftSubstitutes;
        this.products = products;
        this.materials = materials;
        this.workflows = workflows;
        this.workflowRevisions = workflowRevisions;
        this.packagingDrafts = packagingDrafts;
        this.draftCommand = draftCommand;
        this.units = units;
    }

    @Override public String capabilityId() { return CAPABILITY_ID; }
    @Override public String permission() { return PERMISSION; }

    @Override
    public Optional<DraftSnapshot> read(String factoryId, String draftId, boolean forUpdate) {
        TypedQuery<BomRecipeDraft> query = entityManager.createQuery(
                "select d from BomRecipeDraft d where d.id = :id and d.factoryId = :factoryId and d.deletedAt is null",
                BomRecipeDraft.class).setParameter("id", draftId).setParameter("factoryId", factoryId);
        if (forUpdate) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        List<BomRecipeDraft> rows = query.getResultList();
        if (rows.isEmpty()) return Optional.empty();
        BomRecipeDraft draft = rows.get(0);
        List<BomRecipeDraftItem> items = draftItems.findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        entityManager.detach(draft);
        Projection projection = project(draft, items, null);
        return Optional.of(new DraftSnapshot(draft.getId(), draft.getFactoryId(), draft.getBlueprintId(),
                draft.getBlueprintVersion(), draft.getSourceDigest(), draft.getDesiredDigest(),
                draft.getLockVersion(), draft.getStatus(), draft.getTargetProductRefId(),
                null, projection.after(), new Source(draft, items, projection)));
    }

    @Override
    public DraftSnapshot withPromotionInput(DraftSnapshot draft, Object input) {
        Source source = (Source) draft.source();
        Projection projection = project(source.draft(), source.items(), input);
        return new DraftSnapshot(draft.id(), draft.factoryId(), draft.blueprintId(), draft.blueprintVersion(),
                draft.sourceDigest(), draft.desiredDigest(), draft.lockVersion(), draft.status(), draft.naturalKey(),
                draft.targetLiveId(), projection.after(), new Source(source.draft(), source.items(), projection));
    }

    @Override
    public Set<String> previewOnlyFields() {
        return Set.of("batchOutputQuantity", "batchOutputUnit", "packagingRoles");
    }

    private Projection project(BomRecipeDraft draft, List<BomRecipeDraftItem> items, Object input) {
        List<Blocker> blockers = new ArrayList<>();
        Map<String, String> roles = roles(input, blockers);
        var product = products.findByIdAndFactoryId(draft.getTargetProductRefId(), draft.getFactoryId()).orElse(null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("productTypeId", draft.getTargetProductRefId()); after.put("outputQuantity", BigDecimal.ONE);
        after.put("outputUnit", draft.getOutputUnit());
        var net = product == null ? null : BomProductOutputMetadata.snapshot(product);
        after.put("netContentQuantity", net == null ? null : net.quantity()); after.put("netContentUnit", net == null ? null : net.unit());
        after.put("workflowId", draft.getWorkflowId()); after.put("workflowRevisionId", draft.getWorkflowRevisionId());
        after.put("workflowRevisionHash", draft.getWorkflowRevisionHash()); after.put("batchOutputQuantity", draft.getOutputQuantity());
        after.put("batchOutputUnit", draft.getOutputUnit());
        if (product != null && product.getUnit() != null) {
            var normalized = units.normalize(draft.getFactoryId(), product.getUnit());
            if (normalized != null && normalized.recognized()) after.put("outputUnit", normalized.code());
            else blockers.add(Blocker.of("BOM_SKU_UNIT_UNKNOWN", "SKU 基本单位无法识别，不能创建或激活 BOM", "请修正 SKU 基本单位"));
        }
        List<PlannedItem> planned = new ArrayList<>(); List<Map<String, Object>> choices = new ArrayList<>();
        Set<String> packagingPorts = new java.util.HashSet<>();
        for (BomRecipeDraftItem item : items) {
            BigDecimal quantity = null;
            try { quantity = perSkuQuantity(item.getStandardQuantity(), draft.getOutputQuantity()); }
            catch (BusinessException e) { blockers.add(Blocker.of(e.getErrorCode(), e.getMessage(), e.getActionHint())); }
            String role = null;
            if ("PACKAGING".equals(item.getMaterialCategory())) {
                packagingPorts.add(item.getWorkflowPortId()); role = roles.get(item.getWorkflowPortId());
                var material = materials.findByIdAndFactoryId(item.getMaterialRefId(), draft.getFactoryId()).orElse(null);
                Map<String, Object> choice = new LinkedHashMap<>(); choice.put("materialTypeId", item.getMaterialRefId()); choice.put("workflowPortId", item.getWorkflowPortId());
                choice.put("label", material == null ? item.getMaterialRefId() : material.getName()); choice.put("unit", item.getUnit()); choice.put("role", role); choices.add(java.util.Collections.unmodifiableMap(choice));
                if (role == null) blockers.add(Blocker.of("BOM_PACKAGING_ROLE_REQUIRED", "请明确选择每个包材投入端口的正式包装角色", "填写成品容器、封装或外包装角色后重新预览"));
            }
            planned.add(new PlannedItem(item.getMaterialRefId(), item.getMaterialCategory(), quantity, item.getUnit(), item.getOptional(), item.getOrdinal(), item.getWorkflowMaterialNodeId(), item.getWorkflowPortId(), item.getWorkflowEdgeId(), item.getChangeReason(), role));
        }
        if (!packagingPorts.containsAll(roles.keySet())) blockers.add(Blocker.of("BOM_PACKAGING_ROLE_PORT_INVALID", "包装角色包含当前草稿以外的投入端口", "只为当前草稿列出的包材端口填写角色"));
        after.put("items", planned.stream().map(PlannedItem::fields).toList()); after.put("packagingRoles", List.copyOf(choices));
        return new Projection(java.util.Collections.unmodifiableMap(after), List.copyOf(planned), Map.copyOf(roles), List.copyOf(blockers));
    }

    private static Map<String, String> roles(Object input, List<Blocker> blockers) {
        Map<String, String> roles = new TreeMap<>(); if (input == null) return roles;
        if (input instanceof Map<?, ?> supplied && supplied.size() <= 100) {
            for (var entry : supplied.entrySet()) {
                if (entry.getKey() instanceof String port && entry.getValue() instanceof String role && Set.of("PRIMARY_CONTAINER", "SEAL", "OUTER_CASE").contains(role)) roles.put(port, role);
                else blockers.add(Blocker.of("BOM_PACKAGING_ROLE_INVALID", "包装角色或端口标识无效", "使用预览列出的端口与允许的正式角色"));
            }
        } else blockers.add(Blocker.of("BOM_PACKAGING_ROLE_INVALID", "包装角色请求格式无效", "使用当前预览列出的端口填写角色"));
        return roles;
    }

    private static BigDecimal perSkuQuantity(BigDecimal quantity, BigDecimal output) {
        try {
            if (quantity == null || output == null || quantity.signum() <= 0 || output.signum() <= 0) throw new ArithmeticException();
            BigDecimal normalized = quantity.divide(output, 4, RoundingMode.UNNECESSARY);
            if (normalized.signum() <= 0 || normalized.precision() > 15) throw new ArithmeticException();
            return normalized.stripTrailingZeros();
        } catch (ArithmeticException e) {
            throw new BusinessException(409, "批次用量不能精确表示为正式 BOM 的每单位用量（最多4位小数）").withCode("BOM_PROMOTION_QUANTITY_NOT_REPRESENTABLE").withHint("调整配方批次或用量后重新生成草稿；系统不会自动舍入用量");
        }
    }

    @Override public Optional<LiveRow> findLiveConflict(String factoryId, DraftSnapshot draft) {
        Source source = (Source) draft.source(); int next = nextVersion(factoryId, source.draft().getTargetProductRefId());
        return recipes.findByFactoryIdAndProductTypeIdOrderByVersionDesc(factoryId, source.draft().getTargetProductRefId()).stream().filter(row -> row.getVersion() != null && row.getVersion() == next).findFirst().map(row -> new LiveRow(row.getId(), row.getRecipeCode()));
    }

    @Override
    public List<Blocker> capabilityBlockers(DraftSnapshot snapshot) {
        Source source = (Source) snapshot.source(); BomRecipeDraft draft = source.draft(); List<Blocker> blockers = new ArrayList<>(source.projection().blockers());
        if (!"DRAFT".equals(draft.getStatus()) || !Boolean.TRUE.equals(draft.getDependencyClosureComplete())) { blockers.add(Blocker.of("BOM_DRAFT_NOT_READY", "BOM 草稿状态或依赖闭包无效", "重新生成 BOM 草稿")); return blockers; }
        var product = products.findByIdAndFactoryId(draft.getTargetProductRefId(), draft.getFactoryId()).orElse(null);
        if (product == null || !Boolean.TRUE.equals(product.getIsActive())) blockers.add(Blocker.of("BOM_PRODUCT_NOT_LIVE", "产品主数据不存在或已停用", "先转正并启用产品主数据"));
        else if (!Objects.equals(draft.getTargetProductSnapshotDigest(), draftCommand.productDigest(product))) blockers.add(Blocker.of("BOM_PRODUCT_PIN_DRIFT", "产品快照已变化", "重新生成 BOM 草稿"));
        else { try { BomProductOutputMetadata.validate(product); } catch (BusinessException e) { blockers.add(Blocker.of(e.getErrorCode(), e.getMessage(), e.getActionHint())); } }
        var workflow = workflows.findByIdAndFactoryId(draft.getWorkflowId(), draft.getFactoryId()).orElse(null); var revision = workflowRevisions.findByIdAndFactoryId(draft.getWorkflowRevisionId(), draft.getFactoryId()).orElse(null);
        boolean workflowExact = workflow != null && revision != null && Objects.equals(workflow.getId(), revision.getWorkflowId()) && Objects.equals(workflow.getProductTypeId(), draft.getTargetProductRefId()) && Objects.equals(revision.getProductTypeId(), draft.getTargetProductRefId()) && Objects.equals(workflow.getCurrentRevisionId(), revision.getId()) && Objects.equals(workflow.getCurrentRevisionHash(), revision.getRevisionHash()) && Objects.equals(draft.getWorkflowRevisionHash(), revision.getRevisionHash()) && Objects.equals(draft.getWorkflowDefinitionVersion(), revision.getDefinitionVersion()) && Objects.equals(draft.getWorkflowSchemaVersion(), revision.getSchemaVersion()) && "DRAFT".equals(String.valueOf(workflow.getStatus())) && "DRAFT".equals(String.valueOf(revision.getStatus())) && Boolean.TRUE.equals(revision.getStructurallyComplete());
        if (!workflowExact) blockers.add(Blocker.of("BOM_WORKFLOW_PIN_DRIFT", "Workflow revision pin 已变化或不完整", "重新保存并选择当前 Workflow DRAFT revision"));
        if (draft.getPackagingDraftId() != null) { var packaging = packagingDrafts.findByIdAndFactoryId(draft.getPackagingDraftId(), draft.getFactoryId()).orElse(null); if (packaging == null || !"DRAFT".equals(packaging.getStatus()) || !Objects.equals(draft.getPackagingDraftDigest(), packaging.getDesiredDigest()) || !Objects.equals(draft.getBlueprintId(), packaging.getBlueprintId()) || !Objects.equals(draft.getBlueprintVersion(), packaging.getBlueprintVersion())) blockers.add(Blocker.of("BOM_PACKAGING_PIN_DRIFT", "包装草稿 pin 已变化", "重新生成并确认包装草稿")); }
        else if (!"NONE".equals(draft.getPackagingDependencyType())) blockers.add(Blocker.of("BOM_PACKAGING_PIN_MISSING", "包装依赖声明与引用不一致", "重新生成 BOM 草稿"));
        if (draft.getOutputQuantity() == null || draft.getOutputQuantity().compareTo(BigDecimal.ONE) != 0) {
            blockers.add(Blocker.of("BOM_OUTPUT_QUANTITY_NOT_SUPPORTED",
                    "正式配方接口不支持自定义产出数量（固定为 1），所以不能转正", "将草稿产出数量改为 1 后重新预览"));
        }
        if (product != null && !Objects.equals(draft.getOutputUnit(),
                units.normalize(draft.getFactoryId(), product.getUnit()).code())) {
            blockers.add(Blocker.of("BOM_OUTPUT_UNIT_NOT_SUPPORTED",
                    "正式配方接口不支持自定义产出单位（必须为成品基本单位），所以不能转正", "将草稿产出单位改为成品基本单位后重新预览"));
        }
        if ((draft.getSubstituteCount() != null && draft.getSubstituteCount() > 0)
                || (draftSubstitutes != null && !draftSubstitutes.findByDraftIdAndFactoryIdOrderByItemIdAscPriorityAsc(
                draft.getId(), draft.getFactoryId()).isEmpty())) {
            blockers.add(Blocker.of("BOM_SUBSTITUTES_NOT_SUPPORTED",
                    "正式配方接口不支持草稿替代料的完整转换，所以不能转正", "移除草稿替代料后重新预览"));
        }
        for (BomRecipeDraftItem item : source.items()) {
            if (item.getYieldPercent() == null || item.getYieldPercent().compareTo(new BigDecimal("100")) != 0) {
                blockers.add(Blocker.of("BOM_YIELD_NOT_SUPPORTED",
                        "正式配方接口不支持手填行出成率（由批次历史计算），所以不能转正：" + item.getMaterialRefId(), "将草稿行出成率改为 100 后重新预览"));
            }
            if (item.getPriceUnit() != null) {
                blockers.add(Blocker.of("BOM_PRICE_UNIT_NOT_SUPPORTED",
                        "正式配方接口不支持草稿行计价单位，所以不能转正：" + item.getMaterialRefId(), "移除草稿行计价单位后重新预览"));
            }
            if (item.getQuantityToPriceFactor() != null) {
                blockers.add(Blocker.of("BOM_PRICE_FACTOR_NOT_SUPPORTED",
                        "正式配方接口不支持草稿行计价换算系数，所以不能转正：" + item.getMaterialRefId(), "移除草稿行计价换算系数后重新预览"));
            }
            var material = materials.findByIdAndFactoryId(item.getMaterialRefId(), draft.getFactoryId()).orElse(null);
            if (material == null || !Boolean.TRUE.equals(material.getIsActive())) blockers.add(Blocker.of("BOM_MATERIAL_NOT_LIVE", "物料主数据不存在或已停用：" + item.getMaterialRefId(), "先转正并启用物料主数据"));
            else if (!Objects.equals(item.getMaterialSnapshotDigest(), draftCommand.materialDigest(material))) blockers.add(Blocker.of("BOM_MATERIAL_PIN_DRIFT", "物料快照已变化：" + item.getMaterialRefId(), "重新生成 BOM 草稿"));
            if (item.getUnit() == null || item.getUnit().isBlank()) blockers.add(Blocker.of("BOM_UNIT_NOT_CANONICAL", "BOM 行单位为空：" + item.getMaterialRefId(), "使用标准单位重新生成草稿"));
        }
        return blockers;
    }

    @Override
    public String create(String factoryId, DraftSnapshot snapshot, ActorRef actor) {
        Source source = (Source) snapshot.source(); BomRecipeDraft draft = source.draft(); CreateBomRecipeRequest request = new CreateBomRecipeRequest(); request.setProductTypeId(draft.getTargetProductRefId());
        request.setItems(source.projection().items().stream().map(item -> { BomRecipeItemDTO dto = new BomRecipeItemDTO(); dto.setMaterialTypeId(item.materialId()); dto.setStandardQuantity(item.quantity()); dto.setUnit(item.unit()); dto.setMaterialCategory(item.category()); dto.setIsOptional(item.optional()); dto.setWorkflowMaterialNodeId(item.nodeId()); dto.setWorkflowInputPortId(item.portId()); dto.setWorkflowEdgeId(item.edgeId()); dto.setSortOrder(item.ordinal()); dto.setRemark(item.reason()); if (item.packagingRole() != null) { dto.setPackagingRole(item.packagingRole()); dto.setNaturalQuantity(item.quantity()); } return dto; }).toList());
        request.setNotes("Blueprint BOM draft " + draft.getId() + "; original batch=" + draft.getOutputQuantity().toPlainString() + " " + draft.getOutputUnit() + "; formal basis=1 SKU unit");
        String id = recipeService.createRecipe(factoryId, request).getId(); validateReplayInput(factoryId, id, source.projection().roles()); return id;
    }

    @Override
    public void validateReplayInput(String factoryId, String liveTargetId, Object input) {
        recipes.findById(liveTargetId).filter(row -> factoryId.equals(row.getFactoryId())).orElseThrow(() -> new BusinessException(409, "正式配方不属于当前工厂").withCode("BOM_PRODUCT_NOT_LIVE"));
        List<Blocker> invalid = new ArrayList<>(); Map<String, String> requested = roles(input, invalid); Map<String, String> actual = new TreeMap<>();
        for (BomRecipeItem item : recipeItems.findByRecipeIdOrderBySortOrderAsc(liveTargetId)) { if (!factoryId.equals(item.getFactoryId())) throw new BusinessException(409, "正式配方明细工厂不一致").withCode("BOM_PRODUCT_NOT_LIVE"); if ("PACKAGING".equals(item.getMaterialCategory())) { if (item.getWorkflowInputPortId() == null || item.getPackagingRole() == null || actual.containsKey(item.getWorkflowInputPortId())) invalid.add(Blocker.of("BOM_PACKAGING_ROLE_INVALID", "正式包材端口或角色不完整", "核对原正式配方")); else actual.put(item.getWorkflowInputPortId(), item.getPackagingRole()); } }
        if (!invalid.isEmpty() || !actual.equals(requested)) throw new BusinessException(409, "包材角色与原正式配方不一致，本次没有新增写入").withCode("PROMOTION_PREVIEW_STALE").withHint("核对原预览及正式配方的包材角色后再确认");
    }

    @Override public LiveReadback readBack(String factoryId, String liveTargetId) { entityManager.flush(); return readBackSnapshot(factoryId, liveTargetId); }

    /** Read-only proof checks must never flush unrelated managed state. */
    LiveReadback readBackSnapshot(String factoryId, String liveTargetId) {
        Optional<BomRecipe> found = recipes.findById(liveTargetId).filter(row -> factoryId.equals(row.getFactoryId())); if (found.isEmpty()) return null; BomRecipe recipe = found.get(); if (entityManager == null || !entityManager.contains(recipe)) return null;
        try { entityManager.refresh(recipe); } catch (jakarta.persistence.EntityNotFoundException | IllegalArgumentException removed) { return null; }
        if (!factoryId.equals(recipe.getFactoryId())) return null; List<BomRecipeItem> items = freshOwnedItems(factoryId, recipe.getId()); if (items == null) return null;
        Map<String, Object> fields = new LinkedHashMap<>(); fields.put("productTypeId", recipe.getProductTypeId()); fields.put("outputQuantity", recipe.getOutputQuantityPerUnit()); fields.put("outputUnit", recipe.getOutputUnit()); fields.put("netContentQuantity", recipe.getNetContentQuantity()); fields.put("netContentUnit", recipe.getNetContentUnit()); fields.put("workflowId", recipe.getWorkflowId()); fields.put("workflowRevisionId", recipe.getWorkflowRevisionId()); fields.put("workflowRevisionHash", recipe.getWorkflowRevisionHash());
        fields.put("items", items.stream().map(item -> { Map<String, Object> row = new LinkedHashMap<>(); row.put("materialTypeId", item.getMaterialTypeId()); row.put("materialCategory", item.getMaterialCategory()); row.put("standardQuantity", normalizedQuantity(item.getStandardQuantity())); row.put("unit", item.getUnit()); row.put("optional", item.getIsOptional()); row.put("workflowMaterialNodeId", item.getWorkflowMaterialNodeId()); row.put("workflowInputPortId", item.getWorkflowInputPortId()); row.put("workflowEdgeId", item.getWorkflowEdgeId()); return row; }).toList());
        return new LiveReadback(recipe.getId(), fields, null, com.cretas.aims.service.bomdraft.BomRecipeDraftPromotionAuthority.headerSnapshot(recipe));
    }

    /** Read-only, tenant-owned item snapshot shared by durable proof checks. */
    List<BomRecipeItem> freshOwnedItems(String factoryId, String recipeId) {
        if (entityManager == null) return null; List<BomRecipeItem> items = recipeItems.findByRecipeIdOrderBySortOrderAsc(recipeId); if (items == null) return null;
        try { for (BomRecipeItem item : items) { if (item == null || !entityManager.contains(item)) return null; entityManager.refresh(item); if (!factoryId.equals(item.getFactoryId())) return null; } return List.copyOf(items); }
        catch (jakarta.persistence.EntityNotFoundException | IllegalArgumentException removed) { return null; }
    }

    @Override public boolean recordsCreatedBy() { return false; }
    private int nextVersion(String factoryId, String productTypeId) { return recipes.findMaxVersion(factoryId, productTypeId) + 1; }
    private static BigDecimal normalizedQuantity(BigDecimal quantity) { return quantity == null ? null : quantity.stripTrailingZeros(); }
    private record PlannedItem(String materialId, String category, BigDecimal quantity, String unit, Boolean optional, Integer ordinal, String nodeId, String portId, String edgeId, String reason, String packagingRole) { Map<String, Object> fields() { Map<String, Object> row = new LinkedHashMap<>(); row.put("materialTypeId", materialId); row.put("materialCategory", category); row.put("standardQuantity", normalizedQuantity(quantity)); row.put("unit", unit); row.put("optional", optional); row.put("workflowMaterialNodeId", nodeId); row.put("workflowInputPortId", portId); row.put("workflowEdgeId", edgeId); return java.util.Collections.unmodifiableMap(row); } }
    private record Projection(Map<String, Object> after, List<PlannedItem> items, Map<String, String> roles, List<Blocker> blockers) { }
    private record Source(BomRecipeDraft draft, List<BomRecipeDraftItem> items, Projection projection) { }
}
