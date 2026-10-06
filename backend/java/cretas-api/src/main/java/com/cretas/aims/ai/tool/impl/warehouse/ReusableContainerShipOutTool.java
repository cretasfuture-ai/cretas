package com.cretas.aims.ai.tool.impl.warehouse;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.warehouse.ReusableContainerTransaction;
import com.cretas.aims.service.warehouse.ReusableContainerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C4 — 随货发出周转框给客户.
 */
@Slf4j
@Component
public class ReusableContainerShipOutTool extends AbstractBusinessTool {

    @Autowired
    private ReusableContainerService service;

    @Override
    public String getToolName() {
        return "reusable_container_ship_out";
    }

    @Override
    public String getDescription() {
        return "随货发出周转框给客户 — 减在库+加在途, 生成 SHIP_OUT 流水";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "containerId", Map.of("type", "string", "description", "周转耗材 ID"),
                "quantity", Map.of("type", "integer", "description", "发出数量"),
                "customerId", Map.of("type", "string", "description", "客户 ID"),
                "customerName", Map.of("type", "string", "description", "客户名称"),
                "remark", Map.of("type", "string", "description", "备注(可选)")
            ),
            "required", List.of("containerId", "quantity", "customerId", "customerName")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("containerId", "quantity", "customerId", "customerName");
    }

    @Override
    public ActionType getActionType() {
        return ActionType.WRITE;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("warehouse", "reusable-container");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String containerId = getString(params, "containerId");
        Integer quantity = getInteger(params, "quantity");
        String customerId = getString(params, "customerId");
        String customerName = getString(params, "customerName");
        String remark = getString(params, "remark");

        ReusableContainerTransaction tx = service.shipOut(factoryId, containerId, quantity,
                customerId, customerName, null, remark);
        return buildSimpleResult("已发出 " + quantity + " 个周转框给 " + customerName, tx);
    }

    /**
     * 只读预览 —— AI 对话里「预览 → 确认」的预览段。⛔ 只调校验 (assertShipOutAllowed), 不调 {@code shipOut};
     * 规则见 {@link ReusableContainerPreview}。
     */
    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        return ReusableContainerPreview.shipOut(service, factoryId,
                getString(params, "containerId"), getInteger(params, "quantity"),
                getString(params, "customerName"));
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "containerId":
                return "请问是哪一种周转框？说名称就行。";
            case "quantity":
                return "请问发出多少个？";
            case "customerId":
                return "请问是哪个客户？说名称就行。";
            case "customerName":
                return "请问客户的名称是什么？";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "containerId":
                return "周转框";
            case "customerId":
                return "客户";
            case "customerName":
                return "客户名称";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
