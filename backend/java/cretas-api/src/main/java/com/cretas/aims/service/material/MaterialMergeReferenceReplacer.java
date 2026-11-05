package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.MaterialMergeDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.SupplierMaterialPurchaseSpec;
import com.cretas.aims.entity.material.MaterialPackagingSpec;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.event.PurchaseOrderSupplyChangedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.unit.UnitContractService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.math.BigDecimal;

/**
 * 合并重复物料时, 把还没生效的引用自动换到主物料。
 *
 * <p>Owner 2026-11-04 裁定的范围: 草稿工艺图、供应商供货关系、一点没收货的采购单行。
 * 已发布的工艺图和当前配方仍由 {@link MaterialAliasMergeService} 拦下让人发新版本, 历史数据不动。</p>
 *
 * <p>⛔ 只在两个物料单位等价时替换: 供货价和采购单换算都按单位记, 单位不同硬换会算错钱。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialMergeReferenceReplacer {

    private static final Set<String> NAME_FIELDS = Set.of("name", "materialName");

    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final UnitContractService unitContractService;

    @PersistenceContext
    private EntityManager entityManager;

    /** 一次合并要动的引用。stuckPurchaseOrders 是已开始收货、不能替换的采购单。 */
    public record Plan(Map<Long, String> draftWorkflows,
                       Map<String, String> suppliers,
                       Map<Long, String> purchaseOrderItems,
                       List<String> stuckPurchaseOrders,
                       Map<String, String> purchaseOrders,
                       List<String> supplierTombstones,
                       List<String> openStandaloneReceipts) {

        public List<String> purchaseOrderNumbers() {
            return List.copyOf(new LinkedHashSet<>(purchaseOrderItems.values()));
        }

        public List<String> purchaseOrderIds() {
            return List.copyOf(purchaseOrders.keySet());
        }
    }

    public Plan plan(String factoryId, String materialTypeId) {
        return plan(factoryId, materialTypeId, null);
    }

    public Plan plan(String factoryId, String materialTypeId, String masterMaterialTypeId) {
        Map<Long, String> workflows = new java.util.LinkedHashMap<>();
        for (Object[] row : rows(entityManager.createNativeQuery(
                        "SELECT w.id, COALESCE(p.name, w.product_type_id) FROM product_process_workflows w "
                                + "LEFT JOIN product_types p ON p.id = w.product_type_id "
                                + "AND p.factory_id = w.factory_id AND p.deleted_at IS NULL "
                                + "WHERE w.factory_id = ?1 AND w.deleted_at IS NULL AND w.status = 'DRAFT' "
                                + "AND CAST(w.nodes_json AS VARCHAR) LIKE ?2 ORDER BY 2, 1")
                .setParameter(1, factoryId)
                .setParameter(2, "%\"" + materialTypeId + "\"%")
                .getResultList())) {
            workflows.put(((Number) row[0]).longValue(), (String) row[1]);
        }

        Map<String, String> suppliers = new java.util.LinkedHashMap<>();
        for (Object[] row : rows(entityManager.createNativeQuery(
                        "SELECT sm.id, COALESCE(s.name, sm.supplier_id) FROM supplier_materials sm "
                                + "LEFT JOIN suppliers s ON s.id = sm.supplier_id "
                                + "AND s.factory_id = sm.factory_id AND s.deleted_at IS NULL "
                                + "WHERE sm.factory_id = ?1 AND sm.material_type_id = ?2 "
                                + "AND sm.deleted_at IS NULL AND sm.active = TRUE ORDER BY 2, 1")
                .setParameter(1, factoryId)
                .setParameter(2, materialTypeId)
                .getResultList())) {
            suppliers.put((String) row[0], (String) row[1]);
        }

        List<String> supplierTombstones = masterMaterialTypeId == null ? List.of() : strings(entityManager.createNativeQuery(
                        "SELECT DISTINCT COALESCE(s.name, sm.supplier_id) FROM supplier_materials sm "
                                + "LEFT JOIN suppliers s ON s.id = sm.supplier_id "
                                + "AND s.factory_id = sm.factory_id AND s.deleted_at IS NULL "
                                + "WHERE sm.factory_id = ?1 AND sm.material_type_id = ?2 "
                                + "AND sm.deleted_at IS NULL AND sm.active = TRUE "
                                + "AND EXISTS (SELECT 1 FROM supplier_materials tombstone "
                                + "WHERE tombstone.factory_id = sm.factory_id "
                                + "AND tombstone.supplier_id = sm.supplier_id "
                                + "AND tombstone.material_type_id = ?3 "
                                + "AND tombstone.deleted_at IS NOT NULL) ORDER BY 1")
                .setParameter(1, factoryId)
                .setParameter(2, materialTypeId)
                .setParameter(3, masterMaterialTypeId)
                .getResultList());

        Map<Long, String> items = new java.util.LinkedHashMap<>();
        Set<String> stuck = new LinkedHashSet<>();
        Map<String, String> purchaseOrders = new java.util.LinkedHashMap<>();
        for (Object[] row : rows(entityManager.createNativeQuery(
                        "SELECT i.id, po.id, po.order_number, CASE WHEN i.received_quantity > 0 OR EXISTS ("
                                + "  SELECT 1 FROM purchase_receive_items r WHERE r.purchase_order_item_id = i.id "
                                + "  AND r.deleted_at IS NULL) THEN 1 ELSE 0 END "
                                + "FROM purchase_orders po "
                                + "JOIN purchase_order_items i ON i.purchase_order_id = po.id "
                                + "WHERE po.factory_id = ?1 AND i.material_type_id = ?2 "
                                + "AND po.deleted_at IS NULL AND i.deleted_at IS NULL "
                                + "AND po.status NOT IN ('COMPLETED', 'CANCELLED', 'CLOSED') "
                                + "ORDER BY po.order_number, i.id")
                .setParameter(1, factoryId)
                .setParameter(2, materialTypeId)
                .getResultList())) {
            if (((Number) row[3]).intValue() == 1) {
                stuck.add((String) row[2]);
            } else {
                items.put(((Number) row[0]).longValue(), (String) row[2]);
            }
            purchaseOrders.put((String) row[1], (String) row[2]);
        }
        List<String> openStandaloneReceipts = strings(entityManager.createNativeQuery(
                        "SELECT DISTINCT r.receive_number FROM purchase_receive_records r "
                                + "JOIN purchase_receive_items i ON i.receive_record_id = r.id "
                                + "WHERE r.factory_id = ?1 AND r.purchase_order_id IS NULL "
                                + "AND r.status IN ('DRAFT', 'PENDING_QC') "
                                + "AND r.deleted_at IS NULL AND i.material_type_id = ?2 "
                                + "AND i.deleted_at IS NULL ORDER BY 1")
                .setParameter(1, factoryId)
                .setParameter(2, materialTypeId)
                .getResultList());
        return new Plan(workflows, suppliers, items, new ArrayList<>(stuck), purchaseOrders,
                supplierTombstones, openStandaloneReceipts);
    }

    /** 在合并事务里执行; 调用方已锁住两个物料, 并确认过 plan 里没有挡合并的项。 */
    public MaterialMergeDTO.ReplacementResult apply(String factoryId, RawMaterialType duplicate,
                                                    RawMaterialType master, Plan plan) {
        assertPlanStillSafe(factoryId, duplicate, master, plan);
        for (Long id : plan.draftWorkflows().keySet()) {
            ProductProcessWorkflow workflow = entityManager.find(ProductProcessWorkflow.class, id);
            if (workflow == null) {
                throw mergeConflict("草稿工艺图已不存在，不能自动替换");
            }
            workflow.setNodesJson(replaceInNodes(workflow.getNodesJson(), duplicate, master));
        }

        List<String> notes = new ArrayList<>();
        Map<Long, SupplierReferenceMapping> supplierMappings = resolveSupplierReferenceMappings(
                factoryId, duplicate, master, plan.purchaseOrderItems().keySet());
        Map<Long, String> materialPackagingMappings = resolveMaterialPackagingMappings(
                factoryId, duplicate, master, plan.purchaseOrderItems().keySet());
        boolean masterHasPreferred = hasPreferred(factoryId, master.getId());
        for (Map.Entry<String, String> entry : plan.suppliers().entrySet()) {
            SupplierMaterial row = entityManager.find(SupplierMaterial.class, entry.getKey());
            masterHasPreferred = moveSupplier(factoryId, row, master, entry.getValue(), masterHasPreferred, notes);
        }

        Set<String> touchedOrders = new LinkedHashSet<>();
        for (Long id : plan.purchaseOrderItems().keySet()) {
            PurchaseOrderItem item = entityManager.find(PurchaseOrderItem.class, id);
            if (item == null) {
                throw mergeConflict("采购单行已不存在，不能自动替换");
            }
            item.setMaterialTypeId(master.getId());
            item.setMaterialName(master.getName());
            SupplierReferenceMapping mapping = supplierMappings.get(id);
            if (mapping != null) {
                item.setSupplierMaterialId(mapping.supplierMaterialId());
                item.setPurchasePackagingSpecId(mapping.purchasePackagingSpecId());
            }
            if (materialPackagingMappings.containsKey(id)) {
                item.setMaterialPackagingSpecId(materialPackagingMappings.get(id));
            }
            touchedOrders.add(item.getPurchaseOrderId());
        }
        // 主物料的在途量变了, 缺料实例要重算 (监听器在提交后才跑)
        for (String orderId : touchedOrders) {
            eventPublisher.publishEvent(new PurchaseOrderSupplyChangedEvent(
                    this, factoryId, orderId, Set.of(duplicate.getId(), master.getId())));
        }
        log.info("合并物料已替换引用: factoryId={}, duplicate={}, master={}, 草稿工艺图={}, 供货关系={}, 采购单行={}",
                factoryId, duplicate.getId(), master.getId(), plan.draftWorkflows().size(),
                plan.suppliers().size(), plan.purchaseOrderItems().size());
        return MaterialMergeDTO.ReplacementResult.builder()
                .draftWorkflows(plan.draftWorkflows().size())
                .suppliers(plan.suppliers().size())
                .purchaseOrderItems(plan.purchaseOrderItems().size())
                .notes(notes)
                .build();
    }

    private void assertPlanStillSafe(String factoryId, RawMaterialType duplicate, RawMaterialType master,
                                     Plan plan) {
        if (!plan.stuckPurchaseOrders().isEmpty() || !plan.supplierTombstones().isEmpty()
                || !plan.openStandaloneReceipts().isEmpty()) {
            throw mergeConflict("合并引用在执行前已变为不可安全替换，请刷新后重试");
        }
        for (Long id : plan.draftWorkflows().keySet()) {
            ProductProcessWorkflow workflow = entityManager.find(ProductProcessWorkflow.class, id);
            if (workflow == null || !factoryId.equals(workflow.getFactoryId())
                    || workflow.getStatus() != ProductProcessWorkflow.Status.DRAFT
                    || workflow.getNodesJson() == null
                    || !workflow.getNodesJson().contains("\"" + duplicate.getId() + "\"")) {
                throw mergeConflict("草稿工艺图状态已变化，不能自动替换");
            }
        }
        for (String id : plan.suppliers().keySet()) {
            SupplierMaterial row = entityManager.find(SupplierMaterial.class, id);
            if (row == null || !factoryId.equals(row.getFactoryId())
                    || !duplicate.getId().equals(row.getMaterialTypeId())
                    || !Boolean.TRUE.equals(row.getActive())) {
                throw mergeConflict("供应商供货关系状态已变化，不能自动替换");
            }
            if (hasSupplierTombstone(factoryId, row.getSupplierId(), master.getId())) {
                throw mergeConflict("主物料已有已删除的同供应商供货记录，不能自动恢复");
            }
        }
        for (Long id : plan.purchaseOrderItems().keySet()) {
            List<Object[]> current = rows(entityManager.createNativeQuery(
                            "SELECT po.factory_id, po.status, i.material_type_id, i.received_quantity, "
                                    + "CASE WHEN EXISTS (SELECT 1 FROM purchase_receive_items r "
                                    + "WHERE r.purchase_order_item_id = i.id AND r.deleted_at IS NULL) "
                                    + "THEN 1 ELSE 0 END "
                                    + "FROM purchase_order_items i JOIN purchase_orders po ON po.id = i.purchase_order_id "
                                    + "WHERE i.id = ?1 AND i.deleted_at IS NULL AND po.deleted_at IS NULL")
                    .setParameter(1, id).getResultList());
            if (current.size() != 1) {
                throw mergeConflict("采购单行已不存在，不能自动替换");
            }
            Object[] row = current.get(0);
            if (!factoryId.equals(row[0]) || !duplicate.getId().equals(row[2])
                    || Set.of("COMPLETED", "CANCELLED", "CLOSED").contains(String.valueOf(row[1]))
                    || ((Number) row[4]).intValue() == 1
                    || ((java.math.BigDecimal) row[3]).signum() > 0) {
                throw mergeConflict("采购单状态或收货量已变化，不能自动替换");
            }
        }
    }

    private boolean hasSupplierTombstone(String factoryId, String supplierId, String masterId) {
        Number count = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM supplier_materials WHERE factory_id = ?1 AND supplier_id = ?2 "
                                + "AND material_type_id = ?3 AND deleted_at IS NOT NULL")
                .setParameter(1, factoryId)
                .setParameter(2, supplierId)
                .setParameter(3, masterId)
                .getSingleResult();
        return count.longValue() > 0;
    }

    private static BusinessException mergeConflict(String message) {
        return new BusinessException(409, message).withCode("MATERIAL_MERGE_CONFLICT")
                .withHint("请刷新合并预检；先处理冲突单据或选择唯一等价的采购规格，再重新合并");
    }

    /**
     * 同一家供应商不能对同一物料有两条记录 (uk_supplier_material_identity, 含已删除行),
     * 一个物料也只能有一条首选 (uk_supplier_material_one_preferred)。
     *
     * @return 处理完这条之后主物料是否已有首选
     */
    private boolean moveSupplier(String factoryId, SupplierMaterial row, RawMaterialType master, String supplierName,
                                 boolean masterHasPreferred, List<String> notes) {
        List<Object[]> existing = rows(entityManager.createNativeQuery(
                        "SELECT id, active, deleted_at FROM supplier_materials "
                                + "WHERE factory_id = ?1 AND supplier_id = ?2 AND material_type_id = ?3")
                .setParameter(1, factoryId)
                .setParameter(2, row.getSupplierId())
                .setParameter(3, master.getId())
                .getResultList());
        if (existing.isEmpty()) {
            boolean preferred = Boolean.TRUE.equals(row.getPreferred());
            if (preferred && masterHasPreferred) {
                row.setPreferred(false);
                notes.add("「" + supplierName + "」原来是首选供应商，主物料已有首选，这里改为非首选");
            }
            row.setMaterialTypeId(master.getId());
            syncMovedSupplierSpecs(factoryId, row.getId(), master.getId());
            return masterHasPreferred || preferred;
        }
        row.setActive(false);
        row.setPreferred(false);
        Object[] kept = existing.get(0);
        if (kept[2] != null) {
            throw mergeConflict("「" + supplierName + "」对主物料已有已删除的供货记录，不能自动恢复价格或版本");
        } else if (Boolean.TRUE.equals(kept[1])) {
            notes.add("「" + supplierName + "」已在供主物料，沿用主物料的价格，旧的供货关系已停用");
        } else {
            entityManager.find(SupplierMaterial.class, kept[0]).setActive(true);
            notes.add("「" + supplierName + "」对主物料的供货记录已重新启用，请核对价格");
        }
        return masterHasPreferred;
    }

    private record SupplierReferenceMapping(String supplierMaterialId, String purchasePackagingSpecId) {}

    private Map<Long, String> resolveMaterialPackagingMappings(
            String factoryId, RawMaterialType duplicate, RawMaterialType master, Set<Long> itemIds) {
        Map<Long, String> mappings = new HashMap<>();
        for (Long itemId : itemIds) {
            PurchaseOrderItem item = entityManager.find(PurchaseOrderItem.class, itemId);
            if (item == null) {
                throw mergeConflict("采购单行已不存在，不能自动替换");
            }
            String sourceId = item.getMaterialPackagingSpecId();
            if (sourceId == null || sourceId.isBlank()) {
                continue;
            }
            MaterialPackagingSpec source = entityManager.find(MaterialPackagingSpec.class, sourceId);
            if (source == null || !factoryId.equals(source.getFactoryId())
                    || !duplicate.getId().equals(source.getMaterialTypeId())
                    || !Boolean.TRUE.equals(source.getActive())) {
                throw mergeConflict("采购单行的原料包装规格已停用、跨工厂或不匹配旧物料，不能自动替换");
            }
            List<String> targetIds = strings(entityManager.createNativeQuery(
                            "SELECT id FROM material_packaging_specs WHERE factory_id = ?1 "
                                    + "AND material_type_id = ?2 AND conversion_factor = ?3 "
                                    + "AND deleted_at IS NULL AND is_active = TRUE ORDER BY id")
                    .setParameter(1, factoryId)
                    .setParameter(2, master.getId())
                    .setParameter(3, source.getConversionFactor())
                    .getResultList());
            List<MaterialPackagingSpec> equivalents = targetIds.stream()
                    .map(id -> entityManager.find(MaterialPackagingSpec.class, id))
                    .filter(Objects::nonNull)
                    .filter(candidate -> factoryId.equals(candidate.getFactoryId())
                            && master.getId().equals(candidate.getMaterialTypeId())
                            && Boolean.TRUE.equals(candidate.getActive()))
                    .filter(candidate -> sameStorageUnit(factoryId, source.getPackageUnit(), candidate.getPackageUnit())
                            && sameStorageUnit(factoryId, source.getBaseUnit(), candidate.getBaseUnit()))
                    .toList();
            if (equivalents.size() != 1) {
                throw mergeConflict(equivalents.isEmpty()
                        ? "主物料没有等价的原料包装规格，不能自动替换"
                        : "主物料有多个等价的原料包装规格，不能猜采购依据");
            }
            MaterialPackagingSpec target = equivalents.get(0);
            if (target == null || !factoryId.equals(target.getFactoryId())
                    || !master.getId().equals(target.getMaterialTypeId())
                    || !Boolean.TRUE.equals(target.getActive())) {
                throw mergeConflict("主物料的原料包装规格状态已变化，不能自动替换");
            }
            mappings.put(itemId, target.getId());
        }
        return mappings;
    }

    /**
     * Resolve PO supplier identities before moving/deactivating duplicate relations.
     * A collision may only switch to a master relation when the selected purchase
     * specification is an exact commercial equivalent; guessing a price/spec would
     * silently change an approval-ready order.
     */
    private Map<Long, SupplierReferenceMapping> resolveSupplierReferenceMappings(
            String factoryId, RawMaterialType duplicate, RawMaterialType master, Set<Long> itemIds) {
        Map<Long, SupplierReferenceMapping> mappings = new HashMap<>();
        for (Long itemId : itemIds) {
            PurchaseOrderItem item = entityManager.find(PurchaseOrderItem.class, itemId);
            if (item == null) {
                throw mergeConflict("采购单行已不存在，不能自动替换");
            }
            if (item.getSupplierMaterialId() == null || item.getSupplierMaterialId().isBlank()) {
                // Legacy rows without a supplier identity remain valid legacy rows.
                if (item.getPurchasePackagingSpecId() != null
                        && !item.getPurchasePackagingSpecId().isBlank()) {
                    throw mergeConflict("采购单行缺少供应关系但仍引用采购包装规格，不能猜采购依据");
                }
                continue;
            }
            SupplierMaterial source = entityManager.find(SupplierMaterial.class, item.getSupplierMaterialId());
            if (source == null || !factoryId.equals(source.getFactoryId())
                    || !Boolean.TRUE.equals(source.getActive())
                    || (!duplicate.getId().equals(source.getMaterialTypeId())
                        && !master.getId().equals(source.getMaterialTypeId()))) {
                throw mergeConflict("采购单行的供应关系已停用、跨工厂或不再匹配物料，不能自动替换");
            }
            Number activeSupplierCount = (Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM suppliers WHERE id = ?1 AND factory_id = ?2 "
                                    + "AND deleted_at IS NULL AND is_active = TRUE")
                    .setParameter(1, source.getSupplierId())
                    .setParameter(2, factoryId)
                    .getSingleResult();
            if (activeSupplierCount.longValue() != 1L) {
                throw mergeConflict("采购单行的供应商已停用、跨工厂或不存在，不能自动替换");
            }
            String mappedRelationId = source.getId();
            String mappedSpecId = item.getPurchasePackagingSpecId();
            if (mappedSpecId != null && !mappedSpecId.isBlank()) {
                SupplierMaterialPurchaseSpec sourceSpec = entityManager.find(
                        SupplierMaterialPurchaseSpec.class, mappedSpecId);
                if (sourceSpec == null || !factoryId.equals(sourceSpec.getFactoryId())
                        || !source.getId().equals(sourceSpec.getSupplierMaterialId())
                        || !Objects.equals(source.getMaterialTypeId(), sourceSpec.getMaterialTypeId())
                        || !Boolean.TRUE.equals(sourceSpec.getActive())) {
                    throw mergeConflict("采购单行的采购包装规格已失效或不属于供应关系，不能自动替换");
                }
            }
            if (duplicate.getId().equals(source.getMaterialTypeId())) {
                List<String> activeMasterIds = strings(entityManager.createNativeQuery(
                                "SELECT id FROM supplier_materials WHERE factory_id = ?1 "
                                        + "AND supplier_id = ?2 AND material_type_id = ?3 "
                                        + "AND deleted_at IS NULL ORDER BY id")
                        .setParameter(1, factoryId)
                        .setParameter(2, source.getSupplierId())
                        .setParameter(3, master.getId())
                        .getResultList());
                if (activeMasterIds.size() > 1) {
                    throw mergeConflict("同一供应商的主物料供货关系不唯一，不能猜采购依据");
                }
                if (activeMasterIds.size() == 1) {
                    mappedRelationId = activeMasterIds.get(0);
                    if (mappedSpecId != null && !mappedSpecId.isBlank()) {
                        mappedSpecId = resolveEquivalentSpec(factoryId, source.getId(), mappedRelationId,
                                master.getId(), mappedSpecId);
                    }
                }
            }
            if (mappedSpecId != null && !mappedSpecId.isBlank()) {
                SupplierMaterialPurchaseSpec spec = entityManager.find(
                        SupplierMaterialPurchaseSpec.class, mappedSpecId);
                if (spec == null || !factoryId.equals(spec.getFactoryId())
                        || !mappedRelationId.equals(spec.getSupplierMaterialId())
                        || !Boolean.TRUE.equals(spec.getActive())) {
                    throw mergeConflict("采购单行的采购包装规格已失效或不属于供应关系，不能自动替换");
                }
            }
            mappings.put(itemId, new SupplierReferenceMapping(mappedRelationId, mappedSpecId));
        }
        return mappings;
    }

    private String resolveEquivalentSpec(String factoryId, String sourceRelationId,
                                         String targetRelationId, String targetMaterialTypeId,
                                         String sourceSpecId) {
        SupplierMaterial sourceRelation = entityManager.find(SupplierMaterial.class, sourceRelationId);
        SupplierMaterialPurchaseSpec source = entityManager.find(
                SupplierMaterialPurchaseSpec.class, sourceSpecId);
        if (source == null || !factoryId.equals(source.getFactoryId())
                || sourceRelation == null
                || !sourceRelationId.equals(source.getSupplierMaterialId())
                || !Objects.equals(sourceRelation.getMaterialTypeId(), source.getMaterialTypeId())
                || !Boolean.TRUE.equals(source.getActive())) {
            throw mergeConflict("采购单行的采购包装规格已失效或不属于供应关系，不能自动替换");
        }
        List<String> targetIds = strings(entityManager.createNativeQuery(
                        "SELECT id FROM supplier_material_purchase_specs WHERE factory_id = ?1 "
                                + "AND supplier_material_id = ?2 AND deleted_at IS NULL AND active = TRUE ORDER BY id")
                .setParameter(1, factoryId)
                .setParameter(2, targetRelationId)
                .getResultList());
        List<String> equivalents = targetIds.stream()
                .map(id -> entityManager.find(SupplierMaterialPurchaseSpec.class, id))
                .filter(Objects::nonNull)
                .filter(candidate -> Objects.equals(targetMaterialTypeId, candidate.getMaterialTypeId()))
                .filter(candidate -> equivalentSpec(factoryId, source, candidate))
                .map(SupplierMaterialPurchaseSpec::getId)
                .toList();
        if (equivalents.size() != 1) {
            throw mergeConflict(equivalents.isEmpty()
                    ? "主物料没有与采购单行等价的采购包装规格，不能自动替换"
                    : "主物料有多个与采购单行等价的采购包装规格，不能猜采购依据");
        }
        return equivalents.get(0);
    }

    private boolean equivalentSpec(String factoryId, SupplierMaterialPurchaseSpec left, SupplierMaterialPurchaseSpec right) {
        return sameStorageUnit(factoryId, left.getPurchasePackageUnit(), right.getPurchasePackageUnit())
                && sameStorageUnit(factoryId, left.getInventoryBaseUnit(), right.getInventoryBaseUnit())
                && sameDecimal(left.getConversionFactor(), right.getConversionFactor())
                && sameDecimal(left.getQuotedPrice(), right.getQuotedPrice())
                && sameDecimal(left.getTaxIncludedQuotedPrice(), right.getTaxIncludedQuotedPrice())
                && Objects.equals(left.getCurrency(), right.getCurrency());
    }

    /** Alias identity only; quantities, prices and conversion factors are never implicitly converted. */
    private boolean sameStorageUnit(String factoryId, String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return false;
        String l = unitContractService.storageUnit(factoryId, left);
        String r = unitContractService.storageUnit(factoryId, right);
        return l != null && !l.isBlank() && l.equals(r);
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private void syncMovedSupplierSpecs(String factoryId, String supplierMaterialId, String masterMaterialId) {
        for (String id : strings(entityManager.createNativeQuery(
                        "SELECT id FROM supplier_material_purchase_specs WHERE factory_id = ?1 "
                                + "AND supplier_material_id = ?2 AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .setParameter(2, supplierMaterialId)
                .getResultList())) {
            SupplierMaterialPurchaseSpec spec = entityManager.find(SupplierMaterialPurchaseSpec.class, id);
            if (spec != null) {
                spec.setMaterialTypeId(masterMaterialId);
            }
        }
    }

    private boolean hasPreferred(String factoryId, String materialTypeId) {
        return !entityManager.createNativeQuery(
                        "SELECT id FROM supplier_materials WHERE factory_id = ?1 AND material_type_id = ?2 "
                                + "AND deleted_at IS NULL AND active = TRUE AND preferred = TRUE")
                .setParameter(1, factoryId)
                .setParameter(2, materialTypeId)
                .getResultList().isEmpty();
    }

    /** 节点里存 skuId / skuCode / 名称三样, 只换 id 的话工艺图上还显示旧物料名。 */
    private String replaceInNodes(String nodesJson, RawMaterialType duplicate, RawMaterialType master) {
        try {
            JsonNode root = objectMapper.readTree(nodesJson);
            replace(root, duplicate, master);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new BusinessException(409, "草稿工艺图内容无法解析，不能自动替换物料")
                    .withHint("请先打开这张工艺图确认能正常显示，再重新合并");
        }
    }

    private void replace(JsonNode node, RawMaterialType duplicate, RawMaterialType master) {
        if (node instanceof ArrayNode array) {
            array.forEach(child -> replace(child, duplicate, master));
            return;
        }
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        boolean refersToDuplicate = false;
        for (var field : (Iterable<Map.Entry<String, JsonNode>>) object::fields) {
            if (Set.of("skuId", "materialTypeId").contains(field.getKey())
                    && duplicate.getId().equals(field.getValue().asText(null)) && field.getValue().isTextual()) {
                field.setValue(TextNode.valueOf(master.getId()));
                refersToDuplicate = true;
            }
        }
        if (refersToDuplicate) {
            for (var field : (Iterable<Map.Entry<String, JsonNode>>) object::fields) {
                if (NAME_FIELDS.contains(field.getKey())) {
                    field.setValue(TextNode.valueOf(master.getName()));
                } else if ("skuCode".equals(field.getKey()) && field.getValue().isTextual()) {
                    field.setValue(TextNode.valueOf(master.getCode()));
                }
            }
        }
        object.forEach(child -> replace(child, duplicate, master));
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(List<?> rows) {
        return (List<Object[]>) rows;
    }

    @SuppressWarnings("rawtypes")
    private static List<String> strings(List rows) {
        List<String> out = new ArrayList<>();
        for (Object row : rows) {
            if (row != null) {
                out.add(row.toString());
            }
        }
        return out;
    }
}
