package com.cretas.aims.ai.tool.impl.factorycatalog;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Writes real delivery-line batch allocations through the sales service. */
@Component
public class SalesDeliveryBatchAllocateTool extends AbstractBusinessTool {
    private final SalesDeliveryBatchAllocationService allocationService;
    private final SalesDeliveryItemRepository deliveryItemRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UnitContractService unitContractService;

    public SalesDeliveryBatchAllocateTool(SalesDeliveryBatchAllocationService allocationService,
                                          SalesDeliveryItemRepository deliveryItemRepository) {
        this.allocationService = allocationService;
        this.deliveryItemRepository = deliveryItemRepository;
    }

    @Override
    public String getToolName() { return "sales_delivery_batch_allocate"; }

    @Override
    public String getDescription() {
        return "为销售发货明细真实分配或自动FIFO预留成品批次；推荐结果不会冒充已分配";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "deliveryItemId", Map.of("type", "string", "description", "销售发货明细ID"),
                "allocationMode", Map.of("type", "string", "enum", List.of("AUTO_FIFO", "EXPLICIT")),
                "productTypeId", Map.of("type", "string", "description", "产品ID（AUTO_FIFO必填）"),
                "requiredQty", Map.of("type", "number", "minimum", 0, "description", "需分配数量（AUTO_FIFO必填）"),
                "unit", Map.of("type", "string"),
                "sourceWarehouseCode", Map.of("type", "string"),
                "allocations", Map.of("type", "array", "description", "[{finishedGoodsBatchId, allocatedQty}]（EXPLICIT必填）")
        ), "required", List.of("deliveryItemId", "allocationMode"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("deliveryItemId", "allocationMode");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) {
        String deliveryItemId = getString(params, "deliveryItemId");
        SalesDeliveryItem deliveryItem = resolveDeliveryItem(factoryId, deliveryItemId);
        requireDeliveryLineUnits(factoryId, deliveryItem, params);
        String mode = getString(params, "allocationMode");
        if ("AUTO_FIFO".equalsIgnoreCase(mode)) {
            BigDecimal requiredQty = getBigDecimal(params, "requiredQty");
            String productTypeId = getString(params, "productTypeId");
            if (requiredQty == null || productTypeId == null || productTypeId.isBlank()) {
                throw new IllegalArgumentException("AUTO_FIFO需要productTypeId和requiredQty");
            }
            if (!productTypeId.equals(deliveryItem.getProductTypeId())
                    || deliveryItem.getDeliveredQuantity() == null
                    || requiredQty.compareTo(deliveryItem.getDeliveredQuantity()) != 0) {
                throw new BusinessException(409, "productTypeId或requiredQty与该发货明细不一致，已拒绝跨行分配")
                        .withHint("请使用该发货明细真实的产品ID和待发货数量后重试");
            }
            boolean allocated = allocationService.autoAllocateFifo(factoryId, deliveryItemId,
                    productTypeId, requiredQty, deliveryItem.getUnit(), deliveryItem.getSourceWarehouseCode());
            if (!allocated) {
                throw new com.cretas.aims.exception.BusinessException(409,
                        "可用成品库存不足，未写入部分批次分配")
                        .withHint("请补足来源仓可用成品库存，或拆分发货明细后再分配");
            }
        } else if ("EXPLICIT".equalsIgnoreCase(mode)) {
            List<?> raw = getList(params, "allocations");
            if (raw == null || raw.isEmpty()) {
                throw new IllegalArgumentException("EXPLICIT需要allocations");
            }
            List<BatchAllocationDTO> allocations = new ArrayList<>();
            for (Object row : raw) {
                BatchAllocationDTO allocation = objectMapper.convertValue(row, BatchAllocationDTO.class);
                if (allocation.getFinishedGoodsBatchId() == null || allocation.getAllocatedQty() == null
                        || allocation.getAllocatedQty().signum() <= 0) {
                    throw new IllegalArgumentException("批次ID和正数分配量不能为空");
                }
                allocations.add(allocation);
            }
            // #1548 review finding 4: 换算确认只能由人在 web「分配批次」界面做; 模型给出的确认标记一律拒绝,
            // 否则留痕会以登录用户的名义记下一次其实是 AI 做的确认。
            if (allocations.stream().anyMatch(a -> Boolean.TRUE.equals(a.getPackagingConversionConfirmed()))) {
                throw new com.cretas.aims.exception.BusinessException(409,
                        "包装规格换算须由操作人在分配批次界面确认，AI 不能代为确认")
                        .withCode("PACKAGING_CONFIRMATION_HUMAN_ONLY")
                        .withHint("请到发货单的「分配批次」里核对规格并勾选确认换算");
            }
            // Q10: 有登录身份就带上 (换算确认要记「谁」); 没有身份走不带身份的入口, 它拒绝任何换算确认
            Long actorUserId = getUserId(context);
            if (actorUserId != null) {
                allocationService.allocateBatchesAsUser(factoryId, deliveryItemId, allocations, actorUserId);
            } else {
                allocationService.allocateBatches(factoryId, deliveryItemId, allocations);
            }
        } else {
            throw new IllegalArgumentException("allocationMode只能是AUTO_FIFO或EXPLICIT");
        }
        return Map.of("status", "ALLOCATED", "deliveryItemId", deliveryItemId,
                "fullyAllocated", allocationService.isFullyAllocated(factoryId, deliveryItemId),
                "message", "发货批次已按真实库存完成分配");
    }

    @Override
    public boolean supportsPreview() { return true; }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) {
        String deliveryItemId = getString(params, "deliveryItemId");
        SalesDeliveryItem deliveryItem = resolveDeliveryItem(factoryId, deliveryItemId);
        requireDeliveryLineUnits(factoryId, deliveryItem, params);
        if (!"AUTO_FIFO".equalsIgnoreCase(getString(params, "allocationMode"))) {
            return Map.of("status", "PREVIEW", "canDo", false, "deliveryItemId", deliveryItemId,
                    "message", "AI当前只支持AUTO_FIFO分配预览；指定批次请在发货明细页面核对后操作，本次未产生确认令牌。");
        }
        String productTypeId = getString(params, "productTypeId");
        BigDecimal requiredQty = getBigDecimal(params, "requiredQty");
        if (productTypeId == null || requiredQty == null) {
            return Map.of("status", "PREVIEW", "deliveryItemId", deliveryItemId,
                    "canDo", false, "willWrite", false, "message", "预览FIFO需要productTypeId和requiredQty");
        }
        if (!productTypeId.equals(deliveryItem.getProductTypeId())
                || deliveryItem.getDeliveredQuantity() == null
                || requiredQty.compareTo(deliveryItem.getDeliveredQuantity()) != 0) {
            throw new BusinessException(409, "productTypeId或requiredQty与该发货明细不一致，已拒绝跨行推荐")
                    .withHint("请使用该发货明细真实的产品ID和待发货数量后重试预览");
        }
        Object recommendations = allocationService.recommendFifo(factoryId, deliveryItemId,
                productTypeId, requiredQty, deliveryItem.getUnit(), deliveryItem.getSourceWarehouseCode());
        // 改前值 (确认前状态核对): 发货数量或 FIFO 可分配批次在预览之后变了, 用户看到的分配就不是会执行的分配
        Map<String, Object> currentValues = new java.util.LinkedHashMap<>();
        currentValues.put("发货数量", deliveryItem.getDeliveredQuantity());
        currentValues.put("可分配批次", recommendations);
        return Map.of("status", "PREVIEW", "deliveryItemId", deliveryItemId,
                "recommendations", recommendations,
                "willWrite", false,
                "message", "这是库存推荐，确认执行后才会真实分配",
                "currentValues", currentValues);
    }

    private void requireDeliveryLineUnits(String factoryId, SalesDeliveryItem item, Map<String,Object> params) {
        String unit = getString(params, "unit");
        String warehouse = getString(params, "sourceWarehouseCode");
        if (unit != null && !unitsEquivalent(factoryId, unit, item.getUnit())
                || warehouse != null && !java.util.Objects.equals(warehouse, item.getSourceWarehouseCode())) {
            throw new BusinessException(409, "单位或来源仓库与发货明细不一致，请先在发货单核对，不能由模型改写")
                    .withHint("请按发货明细记录的单位和来源仓库提交，模型不能修改发货行属性");
        }
    }

    private boolean unitsEquivalent(String factoryId, String left, String right) {
        if (left == null || right == null) return left == right;
        if (unitContractService != null) {
            String leftStorage = unitContractService.storageUnit(factoryId, left);
            String rightStorage = unitContractService.storageUnit(factoryId, right);
            return leftStorage != null && !leftStorage.isBlank() && leftStorage.equals(rightStorage);
        }
        return UnitContractServiceImpl.crossLanguageCode(left)
                .equals(UnitContractServiceImpl.crossLanguageCode(right));
    }

    private SalesDeliveryItem resolveDeliveryItem(String factoryId, String deliveryItemId) {
        final Long id;
        try {
            id = Long.valueOf(deliveryItemId);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("deliveryItemId必须是有效的发货明细ID");
        }
        SalesDeliveryItem item = deliveryItemRepository.findByIdAndDeliveryRecordFactoryId(id, factoryId).orElseThrow(
                () -> new BusinessException(404, "发货明细不存在或已删除"));
        return item;
    }

    @Override
    public AccessMode getAccessMode() { return AccessMode.WRITE; }

    @Override
    public ActionType getActionType() { return ActionType.WRITE; }

    @Override
    public boolean requiresPermission() { return true; }

    @Override
    public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("sales:read_write", "warehouse:read_write");
    }

    @Override
    public java.util.Set<String> getAlsoRequiredPermissions() {
        return java.util.Set.of("sales_order");
    }
}
