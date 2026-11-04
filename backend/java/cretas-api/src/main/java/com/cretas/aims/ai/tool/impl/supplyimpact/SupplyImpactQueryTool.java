package com.cretas.aims.ai.tool.impl.supplyimpact;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.supplyimpact.SupplyImpactService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 供应商 / 原料影响分析 (只读): 断供 / 质量 / 涨价三种事件, 与 web 的「影响分析」同一个服务。
 */
@Component
public class SupplyImpactQueryTool extends AbstractBusinessTool {

    @Autowired
    private SupplyImpactService supplyImpactService;

    @Override
    public String getToolName() {
        return "supply_impact_query";
    }

    @Override
    public String getDescription() {
        return "供应商/原料影响分析(只读)。event=disruption 断供: 依赖该供应商或原料的配方、未来计划、库存能撑几天、受影响订单; "
                + "event=quality 质量问题: 某时间段入库批次的全部下游在制品/成品/发货/客户; "
                + "event=price 涨价: 原料涨 percent% 对各产品单位成本和未完成订单的影响。缺数据会如实列出。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("event", Map.of("type", "string", "enum", List.of("disruption", "quality", "price"),
                "description", "事件类型"));
        props.put("supplierId", Map.of("type", "string", "description", "供应商ID (与 materialTypeId 二选一; price 只能用原料)"));
        props.put("materialTypeId", Map.of("type", "string", "description", "原料品种ID"));
        props.put("horizonDays", Map.of("type", "integer", "description", "断供: 向后看几天, 默认14"));
        props.put("from", Map.of("type", "string", "description", "质量: 入库起始日期 yyyy-MM-dd"));
        props.put("to", Map.of("type", "string", "description", "质量: 入库结束日期 yyyy-MM-dd"));
        props.put("percent", Map.of("type", "number", "description", "涨价: 涨幅百分比, 如 10 表示涨10%"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", Collections.singletonList("event"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("event");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) {
        String event = getString(params, "event");
        String supplierId = getString(params, "supplierId");
        String materialTypeId = getString(params, "materialTypeId");
        Map<String, Object> result = new HashMap<>();
        switch (event == null ? "" : event) {
            case "disruption" -> result.put("analysis", supplyImpactService.disruption(
                    factoryId, supplierId, materialTypeId, getInteger(params, "horizonDays")));
            case "quality" -> result.put("analysis", supplyImpactService.quality(factoryId, supplierId, materialTypeId,
                    parseDate(getString(params, "from")), parseDate(getString(params, "to"))));
            case "price" -> result.put("analysis", supplyImpactService.price(
                    factoryId, materialTypeId, getBigDecimal(params, "percent")));
            default -> throw new IllegalArgumentException("event 必须是 disruption / quality / price");
        }
        result.put("event", event);
        result.put("message", "影响分析完成 (只读); 请如实转述 gaps 里列出的缺数据项");
        return result;
    }

    private static LocalDate parseDate(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
    }

    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    /** 与 SupplyImpactController 同一道闸 (S6: AI 能做的 = App 里本来就能做的)。 */
    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("finance:read", "finance:read_write", "procurement:price:view");
    }
}
