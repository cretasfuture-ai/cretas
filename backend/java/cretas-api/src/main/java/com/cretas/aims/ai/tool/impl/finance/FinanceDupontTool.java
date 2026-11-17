package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 杜邦分析 Tool
 *
 * 杜邦三因素分解：销售净利率 x 资产周转率 x 权益乘数。
 * 对应意图: QUERY_DUPONT_ANALYSIS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FinanceDupontTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "finance_dupont";
    }

    @Override
    public String getDescription() {
        return "杜邦分析三因素分解，计算销售净利率、资产周转率、权益乘数及综合ROE。" +
                "适用场景：杜邦分析、综合财务分析、ROE分解。";
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
        log.info("执行杜邦分析 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        Map<String, Object> result = new HashMap<>();
        result.put("ratioType", "DUPONT");
        result.put("period", startDate + " 至 " + endDate);
        // 与 ROE / ROA / web GET /reports/finance 同一组金额键、同一个判定
        if (!replyShowsAmounts(context, FinanceRoeTool.FINANCE_AMOUNT_KEYS)) {
            result.put("amountsHidden", true);
            result.put("message", FinanceRoeTool.amountsHiddenMessage("杜邦分析", "净利润、营业收入、总资产、股东权益"));
            return result;
        }

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);
        result.put("financeData", finance);

        Double totalRevenue = FinanceRoeTool.figure(finance, "totalRevenue");
        Double netProfit = FinanceRoeTool.figure(finance, "totalProfit");
        Double totalAssets = FinanceRoeTool.figure(finance, "totalAssets");
        Double totalEquity = FinanceRoeTool.figure(finance, "totalEquity");

        // 每个因素只在它自己的输入都在时才算; 缺一个 ⇒ 该因素与 ROE 都说「数据不足」, ⛔ 不拿 0 / 1 顶
        Double profitMargin = netProfit != null && totalRevenue != null && totalRevenue > 0
                ? (netProfit / totalRevenue) * 100 : null;
        Double assetTurnover = totalRevenue != null && totalAssets != null && totalAssets > 0
                ? totalRevenue / totalAssets : null;
        Double equityMultiplier = totalAssets != null && totalEquity != null && totalEquity > 0
                ? totalAssets / totalEquity : null;

        List<String> missing = new ArrayList<>();
        if (netProfit == null) missing.add("净利润");
        if (totalRevenue == null || totalRevenue <= 0) missing.add("营业收入");
        if (totalAssets == null || totalAssets <= 0) missing.add("总资产");
        if (totalEquity == null || totalEquity <= 0) missing.add("股东权益");

        if (profitMargin != null) result.put("profitMargin", profitMargin);
        if (assetTurnover != null) result.put("assetTurnover", assetTurnover);
        if (equityMultiplier != null) result.put("equityMultiplier", equityMultiplier);

        StringBuilder sb = new StringBuilder();
        sb.append("杜邦分析\n");
        sb.append("分析周期: ").append(startDate).append(" ~ ").append(endDate).append("\n\n");
        sb.append("杜邦分析三因素分解:\n");
        sb.append("  销售净利率: ").append(profitMargin != null ? String.format("%.2f%%", profitMargin) : FinanceRoeTool.INSUFFICIENT).append("\n");
        sb.append("  资产周转率: ").append(assetTurnover != null ? String.format("%.2f次", assetTurnover) : FinanceRoeTool.INSUFFICIENT).append("\n");
        sb.append("  权益乘数: ").append(equityMultiplier != null ? String.format("%.2f", equityMultiplier) : FinanceRoeTool.INSUFFICIENT).append("\n");
        if (missing.isEmpty()) {
            double dupontRoe = (profitMargin / 100) * assetTurnover * equityMultiplier * 100;
            result.put("dataSufficient", true);
            result.put("dupontROE", dupontRoe);
            sb.append("  ROE = ").append(String.format("%.2f%%", dupontRoe)).append("\n");
            sb.append("  (净利率 × 周转率 × 权益乘数)");
        } else {
            result.put("dataSufficient", false);
            result.put("missingFields", missing);
            sb.append("  ROE: ").append(FinanceRoeTool.INSUFFICIENT).append("（缺少")
                    .append(String.join("、", missing)).append("）");
            FinanceRoeTool.appendProfitReason(sb, finance);
        }

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
