package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 流动比率分析 Tool
 *
 * 从财务数据计算流动比率和速动比率。
 * 对应意图: QUERY_LIQUIDITY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FinanceLiquidityTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "finance_liquidity";
    }

    @Override
    public String getDescription() {
        return "计算流动比率和速动比率，评估企业短期偿债能力。" +
                "适用场景：流动性分析、流动比率查询、短期偿债能力评估。";
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

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行流动比率分析 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        Map<String, Object> result = new HashMap<>();
        result.put("ratioType", "LIQUIDITY");
        result.put("period", startDate + " 至 " + endDate);
        // 金额与比率只看价格查看 (与 ROE / ROA / 杜邦 / #1801 利润表同口径)
        if (!replyShowsPriceSpec(context, FinanceFigures.AMOUNT_SPEC)) {
            result.put("amountsHidden", true);
            result.put("message", FinanceFigures.amountsHiddenMessage("流动比率 / 速动比率", "流动资产、流动负债"));
            return result;
        }

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);
        result.put("financeData", finance);
        FinanceFigures.sameVisibilityForAmounts(List.of("currentAssets", "currentLiabilities", "inventory", "currentRatio", "quickRatio"));

        Double currentAssets = FinanceFigures.figure(finance, "currentAssets");
        Double currentLiabilities = FinanceFigures.positive(FinanceFigures.figure(finance, "currentLiabilities"));
        Double inventory = FinanceFigures.figure(finance, "inventory");
        // 缺数 ⇒ 不出比率 (以前缺了按 1 算; 速动比率按流动资产 × 0.7 估, 都是假数)
        Double currentRatio = currentAssets != null && currentLiabilities != null
                ? currentAssets / currentLiabilities : null;
        Double quickRatio = currentAssets != null && currentLiabilities != null && inventory != null
                ? (currentAssets - inventory) / currentLiabilities : null;
        List<String> missing = new ArrayList<>();
        if (currentAssets == null) missing.add("流动资产");
        if (currentLiabilities == null) missing.add("流动负债");
        if (inventory == null) missing.add("存货");

        if (currentRatio != null) result.put("currentRatio", currentRatio);
        if (quickRatio != null) result.put("quickRatio", quickRatio);
        result.put("dataSufficient", missing.isEmpty());
        if (!missing.isEmpty()) result.put("missingFields", missing);

        StringBuilder sb = new StringBuilder();
        sb.append("流动比率分析\n");
        sb.append("分析周期: ").append(startDate).append(" ~ ").append(endDate).append("\n\n");
        sb.append("流动比率: ").append(currentRatio != null ? String.format("%.2f", currentRatio) : FinanceFigures.INSUFFICIENT).append("\n");
        sb.append("速动比率: ").append(quickRatio != null ? String.format("%.2f", quickRatio) : FinanceFigures.INSUFFICIENT).append("\n");
        if (currentAssets != null) sb.append("  流动资产: ").append(String.format("%.0f", currentAssets)).append("元\n");
        if (currentLiabilities != null) sb.append("  流动负债: ").append(String.format("%.0f", currentLiabilities)).append("元\n");
        if (!missing.isEmpty()) sb.append("  缺少: ").append(String.join("、", missing)).append("\n");
        sb.append("  健康参考: 流动比率 > 2.0, 速动比率 > 1.0");

        result.put("message", sb.toString());

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    /**
     * S6 gated-data rule (2026-10-29): the AI / Skill path admits exactly who the direct API admits
     * for the same data; pinned by GatedDataToolAiPathPermissionTest.
     */
    @Override
    public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("finance:read", "finance:read_write", "procurement:price:view");
    }

    /** 读的是 GET /reports/finance 同一份数据: 另需经营分析模块, 与接口 / App 同口径。 */
    @Override
    public java.util.Set<String> getAlsoRequiredPermissions() {
        return com.cretas.aims.ai.tool.impl.report.ReportFinanceTool.FINANCE_REPORT_SURFACE;
    }
}
