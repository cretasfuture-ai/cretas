package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.inventory.PurchaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class PurchaseStatsTool extends AbstractBusinessTool {

    @Autowired
    private PurchaseService purchaseService;

    @Override
    public String getToolName() {
        return "purchase_statistics";
    }

    @Override
    public String getDescription() {
        return "查询采购统计数据。返回采购订单总数、各状态数量、采购金额汇总等统计信息。" +
                "适用场景：采购概况、采购报表、采购分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    /** S5-12: 采购统计含采购金额, 至少要采购读; 原来 requiredPermissions 为空, 任何能聊天的人都能问到。 */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("procurement:read");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        Map<String, Object> stats = purchaseService.getPurchaseStatistics(factoryId);
        Map<String, Object> result = new HashMap<>(stats);
        // 金额按网关算好的采购价口径 (配置的采购读, 未配置只认采购岗) 判; 没有这个判定就不给 (fail closed)
        if (context == null || !Boolean.TRUE.equals(context.get("canViewPurchasePrices"))) {
            result.put("monthlyPurchaseAmount", null);
        }
        result.put("message", "采购统计查询成功");
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
