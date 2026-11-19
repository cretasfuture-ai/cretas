package com.cretas.aims.ai.tool.impl.sales;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Read-only entry: even injected salesOrderId/items cannot reach shipment confirmation. */
@Component
public class ShipmentPendingTodayTool extends AbstractBusinessTool {
    @Autowired private ShipmentConfirmCreateTool shipments;
    @Override public String getToolName() { return "shipment_pending_today"; }
    @Override public String getDescription() { return "查询当前工厂今日及逾期未发完的销售订单和待发数量，不创建发货单。"; }
    @Override public ActionType getActionType() { return ActionType.READ; }
    @Override public AccessMode getAccessMode() { return AccessMode.READ; }
    /**
     * Same gate as the App's sales order list ({@code SalesController#listOrders}): this tool lists sales orders and
     * their open quantities (2026-11-19). The former extra {@code "sales_order"} was a module code passed as a
     * permission code; {@code PermissionServiceImpl} answers false for any code without {@code module:action}, so it
     * admitted only factory_super_admin / platform_admin. Warehouse roles see deliveries in the App, not order lists.
     */
    @Override public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("sales:read", "sales:read_write", "finance:read", "procurement:read");
    }
    @Override public Map<String,Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of(), "additionalProperties", false);
    }
    @Override protected List<String> getRequiredParameters() { return List.of(); }
    @Override protected Map<String,Object> doExecute(String factoryId, Map<String,Object> params, Map<String,Object> context) {
        return shipments.queryTodayPendingShipments(factoryId);
    }
}
