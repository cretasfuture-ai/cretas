package com.cretas.aims.ai.tool.impl.factorycatalog;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.factory.StocktakeDiffPreviewDTO;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Applies an already approved, positive-only stocktake. */
@Component
public class InventoryGainInboundTool extends AbstractBusinessTool {
    private final FactoryStocktakeService stocktakeService;

    public InventoryGainInboundTool(FactoryStocktakeService stocktakeService) {
        this.stocktakeService = stocktakeService;
    }

    @Override public String getToolName() { return "inventory_gain_inbound"; }

    @Override
    public String getDescription() {
        return "将已审批且仅含盘盈的盘点单按既有事务流程调账入库，不代填盘点、不代办审批";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "stocktakeId", Map.of("type", "string", "description", "已审批盘点单ID")
        ), "required", List.of("stocktakeId"));
    }

    @Override protected List<String> getRequiredParameters() { return List.of("stocktakeId"); }

    private Evidence verifyApprovedPositive(String factoryId, String stocktakeId) {
        StocktakeDTO detail = stocktakeService.getDetail(stocktakeId, factoryId);
        if (!FactoryStocktake.Status.APPROVED.name().equals(detail.getStatus())) {
            throw new BusinessException(409, "盘盈入库只接受已审批(APPROVED)盘点单，不能代办审批")
                    .withHint("请先在盘点流程完成审批，再重试盘盈入库");
        }
        StocktakeDiffPreviewDTO diff = stocktakeService.previewDiff(stocktakeId, factoryId);
        if (diff.getDiffLines() == null || diff.getDiffLines().isEmpty()
                || diff.getSurplusCount() <= 0 || diff.getShortageCount() > 0
                || diff.getDiffLines().stream().anyMatch(line ->
                        line.getActualQty() == null || line.getSystemQty() == null
                                || !("SURPLUS".equals(line.getDifferenceType())
                                || "MATCH".equals(line.getDifferenceType())))) {
            throw new BusinessException(409, "盘点单必须全部已清点且只能包含盘盈/零差，负差或混合差异不得作为盘盈入库")
                    .withHint("请完成盘点并仅保留正差或零差；负差或混合差异请走完整盘点调整");
        }
        return new Evidence(detail, diff);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) {
        String stocktakeId = getString(params, "stocktakeId");
        Evidence evidence = verifyApprovedPositive(factoryId, stocktakeId);
        stocktakeService.apply(stocktakeId, factoryId, requireUserId(context, "盘盈调账入库"));
        return Map.of("status", "APPLIED", "stocktakeId", stocktakeId,
                "surplusCount", evidence.diff().getSurplusCount(),
                "message", "已按已审批盘点正差完成调账入库");
    }

    @Override public boolean supportsPreview() { return true; }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) {
        Evidence evidence = verifyApprovedPositive(factoryId, getString(params, "stocktakeId"));
        // 改前值 (确认前状态核对): 盘点单在预览之后被应用 / 改动就不按旧预览入库
        Map<String, Object> currentValues = new java.util.LinkedHashMap<>();
        currentValues.put("盘点单状态", String.valueOf(evidence.detail().getStatus()));
        currentValues.put("盘盈行数", evidence.diff().getSurplusCount());
        return Map.of("status", "PREVIEW", "stocktakeId", evidence.detail().getId(),
                "diffLines", evidence.diff().getDiffLines(),
                "surplusCount", evidence.diff().getSurplusCount(),
                "willWrite", true, "requiresApproved", true,
                "currentValues", currentValues);
    }

    @Override public AccessMode getAccessMode() { return AccessMode.WRITE; }
    @Override public ActionType getActionType() { return ActionType.WRITE; }
    @Override public boolean requiresPermission() { return true; }
    @Override public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("warehouse:read_write");
    }

    private record Evidence(StocktakeDTO detail, StocktakeDiffPreviewDTO diff) { }
}
