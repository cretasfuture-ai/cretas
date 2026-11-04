package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.MaterialMergeDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.event.PurchaseOrderSupplyChangedEvent;
import com.cretas.aims.exception.BusinessException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @PersistenceContext
    private EntityManager entityManager;

    /** 一次合并要动的引用。stuckPurchaseOrders 是已开始收货、不能替换的采购单。 */
    public record Plan(Map<Long, String> draftWorkflows,
                       Map<String, String> suppliers,
                       Map<Long, String> purchaseOrderItems,
                       List<String> stuckPurchaseOrders) {

        public List<String> purchaseOrderNumbers() {
            return List.copyOf(new LinkedHashSet<>(purchaseOrderItems.values()));
        }
    }

    public Plan plan(String factoryId, String materialTypeId) {
        Map<Long, String> workflows = new java.util.LinkedHashMap<>();
        for (Object[] row : rows(entityManager.createNativeQuery(
                        "SELECT w.id, COALESCE(p.name, w.product_type_id) FROM product_process_workflows w "
                                + "LEFT JOIN product_types p ON p.id = w.product_type_id "
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
                                + "WHERE sm.factory_id = ?1 AND sm.material_type_id = ?2 "
                                + "AND sm.deleted_at IS NULL AND sm.active = TRUE ORDER BY 2, 1")
                .setParameter(1, factoryId)
                .setParameter(2, materialTypeId)
                .getResultList())) {
            suppliers.put((String) row[0], (String) row[1]);
        }

        Map<Long, String> items = new java.util.LinkedHashMap<>();
        Set<String> stuck = new LinkedHashSet<>();
        for (Object[] row : rows(entityManager.createNativeQuery(
                        "SELECT i.id, po.order_number, CASE WHEN i.received_quantity > 0 OR EXISTS ("
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
            if (((Number) row[2]).intValue() == 1) {
                stuck.add((String) row[1]);
            } else {
                items.put(((Number) row[0]).longValue(), (String) row[1]);
            }
        }
        return new Plan(workflows, suppliers, items, new ArrayList<>(stuck));
    }

    /** 在合并事务里执行; 调用方已锁住两个物料, 并确认过 plan 里没有挡合并的项。 */
    public MaterialMergeDTO.ReplacementResult apply(String factoryId, RawMaterialType duplicate,
                                                    RawMaterialType master, Plan plan) {
        for (Long id : plan.draftWorkflows().keySet()) {
            ProductProcessWorkflow workflow = entityManager.find(ProductProcessWorkflow.class, id);
            workflow.setNodesJson(replaceInNodes(workflow.getNodesJson(), duplicate, master));
        }

        List<String> notes = new ArrayList<>();
        boolean masterHasPreferred = hasPreferred(factoryId, master.getId());
        for (Map.Entry<String, String> entry : plan.suppliers().entrySet()) {
            SupplierMaterial row = entityManager.find(SupplierMaterial.class, entry.getKey());
            masterHasPreferred = moveSupplier(factoryId, row, master, entry.getValue(), masterHasPreferred, notes);
        }

        Set<String> touchedOrders = new LinkedHashSet<>();
        for (Long id : plan.purchaseOrderItems().keySet()) {
            PurchaseOrderItem item = entityManager.find(PurchaseOrderItem.class, id);
            item.setMaterialTypeId(master.getId());
            item.setMaterialName(master.getName());
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
            return masterHasPreferred || preferred;
        }
        row.setActive(false);
        row.setPreferred(false);
        Object[] kept = existing.get(0);
        if (kept[2] != null) {
            notes.add("「" + supplierName + "」对主物料的供货记录已被删除，这条供货关系已停用，需要时请重新添加");
        } else if (Boolean.TRUE.equals(kept[1])) {
            notes.add("「" + supplierName + "」已在供主物料，沿用主物料的价格，旧的供货关系已停用");
        } else {
            entityManager.find(SupplierMaterial.class, kept[0]).setActive(true);
            notes.add("「" + supplierName + "」对主物料的供货记录已重新启用，请核对价格");
        }
        return masterHasPreferred;
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
            if (duplicate.getId().equals(field.getValue().asText(null)) && field.getValue().isTextual()) {
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
}
