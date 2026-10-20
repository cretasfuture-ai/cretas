package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Canonical AI purchase preview. Only the persisted HTTP confirmation adapter may write. */
@Component
@RequiredArgsConstructor
public class PurchaseOrderCreateTool extends AbstractBusinessTool {
    private final ControlledPurchaseTaskExecutionService controlledPurchase;
    @Override public String getToolName() { return "purchase_order_create"; }
    @Override public String getVersion() { return "2.0.0"; }
    @Override public String getDescription() {
        return "预览受控采购草稿：明确供应商、物料、数量、kg未税单价、CNY、税率0或13及含税预算。"
                + "必须由真实用户在确认卡确认；创建后仅为草稿，采购与财务OA仍独立审批。";
    }
    @Override public Map<String,Object> getParametersSchema() {
        Map<String,Object> properties = new LinkedHashMap<>();
        for (String name : List.of("supplierId","materialTypeId","unit","currency","expectedDeliveryDate","remark"))
            properties.put(name, Map.of("type","string"));
        for (String name : List.of("quantity","unitPrice","taxRate","amountCap"))
            properties.put(name, Map.of("type","number"));
        properties.put("taxRate", Map.of("type","number","enum",List.of(0,13),"description","百分比点，13代表13%；必须明确填写"));
        properties.put("amountCap",Map.of("type","number","description","用户确认的含税预算上限"));
        return Map.of("type","object","properties",properties,"required",getRequiredParameters());
    }
    @Override protected List<String> getRequiredParameters() {
        return List.of("supplierId","materialTypeId","quantity","unit","unitPrice","currency","taxRate","amountCap");
    }
    @Override protected List<String> previewMissingParameters(Map<String,Object> parameters) {
        return parameters.containsKey("purchaseOrderRequest") ? List.of() : super.previewMissingParameters(parameters);
    }
    @Override public boolean supportsPreview() { return true; }
    @Override public boolean requiresPermission() { return true; }
    @Override public Set<String> getRequiredPermissions() { return Set.of("procurement:read_write"); }
    @Override protected Map<String,Object> doPreview(String factoryId, Map<String,Object> params, Map<String,Object> context) {
        Map<String,Object> approved = params.containsKey("purchaseOrderRequest")
                ? params : controlledPurchase.previewParameters(factoryId, params);
        return controlledPurchase.preview(factoryId, approved);
    }
    @Override protected Map<String,Object> doExecute(String factoryId, Map<String,Object> params, Map<String,Object> context) {
        throw new com.cretas.aims.exception.BusinessException(403,
                "采购草稿仅能通过服务端预览确认入口创建；普通Tool、Skill或confirmed参数不能授权写入");
    }
    @Override public AccessMode getAccessMode() { return AccessMode.WRITE; }
}
