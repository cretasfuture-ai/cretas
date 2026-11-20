package com.cretas.aims.ai.tool.impl.factorycatalog;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.SupplierService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Creates a zero-cost, source-line-idempotent FREE_GIFT material batch. */
@Component
public class FreeGiftInboundTool extends AbstractBusinessTool {
    private final MaterialBatchService materialBatchService;
    private final RawMaterialTypeService materialTypeService;
    private final SupplierService supplierService;

    public FreeGiftInboundTool(MaterialBatchService materialBatchService,
                               RawMaterialTypeService materialTypeService,
                               SupplierService supplierService) {
        this.materialBatchService = materialBatchService;
        this.materialTypeService = materialTypeService;
        this.supplierService = supplierService;
    }

    @Override public String getToolName() { return "free_gift_inbound"; }
    @Override public String getDescription() {
        return "登记供应商赠品原料入库，零采购成本，按来源单据行幂等，不产生采购应付";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "materialTypeId", Map.of("type", "string", "description", "本工厂原材料类型ID"),
                "supplierId", Map.of("type", "string", "description", "本工厂供应商ID"),
                "quantity", Map.of("type", "number", "minimum", 0.01),
                "unit", Map.of("type", "string"),
                "totalWeight", Map.of("type", "number", "minimum", 0.001,
                        "description", "实际入库总重量，单位kg；不可由数量或包装数猜测"),
                "sourceDocId", Map.of("type", "string", "description", "来源单据ID（不含行号）"),
                "sourceLineNo", Map.of("type", "string", "description", "来源单据行号"),
                "notes", Map.of("type", "string", "description", "来源供应商/赠品说明"),
                "warehouseId", Map.of("type", "string", "description", "目标仓库ID，可选")
        ), "required", List.of("materialTypeId", "supplierId", "quantity", "unit", "totalWeight",
                "sourceDocId", "sourceLineNo", "notes"));
    }

    @Override protected List<String> getRequiredParameters() {
        return List.of("materialTypeId", "supplierId", "quantity", "unit", "totalWeight",
                "sourceDocId", "sourceLineNo", "notes");
    }

    private void resolveFactoryScopedReferences(String factoryId, Map<String, Object> params) {
        materialTypeService.getMaterialTypeById(factoryId, getString(params, "materialTypeId"));
        supplierService.getSupplierById(factoryId, getString(params, "supplierId"));
        // The receipt service owns UOM conversion and cost validation; names need not match.
    }

    private CreateMaterialBatchRequest request(Map<String, Object> params) {
        BigDecimal quantity = getBigDecimal(params, "quantity");
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("赠品入库数量必须大于0");
        }
        BigDecimal totalWeight = getBigDecimal(params, "totalWeight");
        if (totalWeight == null || totalWeight.signum() <= 0) {
            throw new IllegalArgumentException("赠品入库总重量必须大于0，单位kg");
        }
        String unit = getString(params, "unit");
        CreateMaterialBatchRequest request = new CreateMaterialBatchRequest();
        request.setMaterialTypeId(getString(params, "materialTypeId"));
        request.setSupplierId(getString(params, "supplierId"));
        request.setReceiptDate(LocalDate.now());
        request.setReceiptQuantity(quantity);
        request.setQuantityUnit(unit);
        request.setTotalWeight(totalWeight);
        request.setTotalValue(BigDecimal.ZERO);
        request.setUnitPrice(BigDecimal.ZERO);
        request.setSourceDocType("FREE_GIFT");
        request.setSourceDocId(getString(params, "sourceDocId"));
        request.setSourceLineNo(getString(params, "sourceLineNo"));
        request.setSourceEventKey(getString(params, "sourceDocId") + "#line:" + getString(params, "sourceLineNo"));
        request.setWarehouseId(getString(params, "warehouseId"));
        request.setNotes(getString(params, "notes"));
        return request;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) {
        resolveFactoryScopedReferences(factoryId, params);
        MaterialBatchDTO batch = materialBatchService.createMaterialBatch(
                factoryId, request(params), requireUserId(context, "登记赠品入库"));
        return Map.of("status", "RECEIVED", "batchId", batch.getId(),
                "batchNumber", batch.getBatchNumber(), "quantity", batch.getReceiptQuantity(),
                "unit", batch.getQuantityUnit(), "totalValue", BigDecimal.ZERO,
                "sourceDocId", getString(params, "sourceDocId"),
                "sourceLineNo", getString(params, "sourceLineNo"),
                "message", "赠品已真实入库，采购成本为0且不会产生采购应付");
    }

    @Override public boolean supportsPreview() { return true; }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) {
        resolveFactoryScopedReferences(factoryId, params);
        CreateMaterialBatchRequest request = request(params);
        return Map.of("status", "PREVIEW", "sourceDocType", "FREE_GIFT",
                "sourceDocId", request.getSourceDocId(),
                "sourceLineNo", request.getSourceLineNo(),
                "quantity", request.getReceiptQuantity(), "unit", request.getQuantityUnit(),
                "totalValue", BigDecimal.ZERO, "willWrite", true,
                "message", "确认后写入赠品批次；同一来源行重复确认将返回原批次");
    }

    @Override public AccessMode getAccessMode() { return AccessMode.WRITE; }
    @Override public ActionType getActionType() { return ActionType.WRITE; }

    /**
     * 只新建记录 (确认前状态核对按新建型处理, 见 ToolExecutor#previewCreatesNewRecordOnly)。
     * 新建赠品批次 (按来源行幂等); 物料移动平均价是执行时按当时库存重算的派生值, 不依赖预览时的值。
     */
    @Override
    public boolean previewCreatesNewRecordOnly() {
        return true;
    }
    @Override public boolean requiresPermission() { return true; }
    @Override public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("warehouse:read_write");
    }
}
