package com.cretas.aims.ai.tool.impl.factorycatalog;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.intent.catalog.FactoryInvoiceTaxQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Catalog entry for tax-group invoice intent. The normal operation is a read-only
 * status lookup for an automatically generated invoice record. Manual issuance is
 * still explicitly unsupported.
 */
@Component
public class TaxGroupInvoiceTool extends AbstractBusinessTool {
    /** Field injection preserves the no-arg test subclass and keeps this tool gateway-compatible. */
    @Autowired(required = false)
    private FactoryInvoiceTaxQueryService queryService;

    @Override
    public String getToolName() { return "tax_group_invoice"; }

    @Override
    public String getDescription() {
        return "查询销售订单自动生成发票的状态与真实税率分组明细；人工开票仍不支持";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "salesOrderId", Map.of("type", "string", "description", "销售订单ID（必填），用于定位自动生成记录")
                , "action", Map.of("type", "string", "enum", List.of("STATUS", "ISSUE"),
                        "default", "STATUS", "description", "默认 STATUS；ISSUE 仅返回不支持，不创建发票")
        ), "required", List.of("salesOrderId"));
    }

    @Override
    protected List<String> getRequiredParameters() { return List.of("salesOrderId"); }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) {
        String action = getString(params, "action");
        if ("ISSUE".equalsIgnoreCase(action)) {
            throw new BusinessException(410,
                    "不支持AI手工开票；销售订单全部发货后由系统自动生成含税率明细的待审核记录")
                    .withHint("请使用 STATUS 查询自动生成记录")
                    .withHintTarget("tax_group_invoice");
        }
        if (queryService == null) {
            throw new IllegalStateException("发票状态查询服务暂不可用");
        }
        return queryService.query(factoryId, getString(params, "salesOrderId"));
    }

    @Override
    protected String buildErrorResult(String message) {
        try {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("success", false);
            result.put("status", "FAILED");
            result.put("error", sanitizeErrorMessage(message));
            result.put("route_policy", "UNSUPPORTED_MANUAL_ISSUE");
            result.put("next_tool", "tax_group_invoice");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"手工开票路径不支持\",\"route_policy\":\"UNSUPPORTED_MANUAL_ISSUE\"}";
        }
    }

    @Override
    public AccessMode getAccessMode() { return AccessMode.READ; }

    @Override
    public ActionType getActionType() { return ActionType.READ; }

    @Override
    public boolean requiresPermission() { return true; }

    @Override
    public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("finance:read", "finance:read_write");
    }
}
