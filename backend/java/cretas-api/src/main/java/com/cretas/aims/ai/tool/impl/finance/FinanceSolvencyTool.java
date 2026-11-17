package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 偿债能力分析 Tool
 *
 * 从财务数据计算资产负债率和权益比率。
 * 对应意图: QUERY_SOLVENCY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FinanceSolvencyTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "finance_solvency";
    }

    @Override
    public String getDescription() {
        return "计算资产负债率和权益比率，评估企业长期偿债能力。" +
                "适用场景：偿债能力分析、资产负债率查询、财务稳健性评估。";
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
        log.info("执行偿债能力分析 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        Map<String, Object> result = new HashMap<>();
        result.put("ratioType", "SOLVENCY");
        result.put("period", startDate + " 至 " + endDate);
        // 金额与比率只看价格查看 (与 ROE / ROA / 杜邦 / #1801 利润表同口径)
        if (!replyShowsPriceSpec(context, FinanceFigures.AMOUNT_SPEC)) {
            result.put("amountsHidden", true);
            result.put("message", FinanceFigures.amountsHiddenMessage("资产负债率 / 权益比率", "总负债、总资产、股东权益"));
            return result;
        }

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);
        result.put("financeData", finance);
        FinanceFigures.sameVisibilityForAmounts(finance, List.of("totalLiabilities", "totalAssets", "debtRatio", "equityRatio"));

        Double totalAssets = FinanceFigures.positive(FinanceFigures.figure(finance, "totalAssets"));
        Double totalLiabilities = FinanceFigures.figure(finance, "totalLiabilities");
        Double totalEquity = FinanceFigures.figure(finance, "totalEquity");
        // 缺数 ⇒ 不出比率 (以前缺了按 1 算, 资产负债率 = 1 / 1 = 100% 这类假数)
        Double debtRatio = totalAssets != null && totalLiabilities != null ? (totalLiabilities / totalAssets) * 100 : null;
        Double equityRatio = totalAssets != null && totalEquity != null ? (totalEquity / totalAssets) * 100 : null;
        List<String> missing = new ArrayList<>();
        if (totalAssets == null) missing.add("总资产");
        if (totalLiabilities == null) missing.add("总负债");
        if (totalEquity == null) missing.add("股东权益");

        if (debtRatio != null) result.put("debtRatio", debtRatio);
        if (equityRatio != null) result.put("equityRatio", equityRatio);
        result.put("dataSufficient", missing.isEmpty());
        if (!missing.isEmpty()) result.put("missingFields", missing);

        StringBuilder sb = new StringBuilder();
        sb.append("偿债能力分析\n");
        sb.append("分析周期: ").append(startDate).append(" ~ ").append(endDate).append("\n\n");
        sb.append("资产负债率: ").append(debtRatio != null ? String.format("%.2f%%", debtRatio) : FinanceFigures.INSUFFICIENT).append("\n");
        sb.append("权益比率: ").append(equityRatio != null ? String.format("%.2f%%", equityRatio) : FinanceFigures.INSUFFICIENT).append("\n");
        if (totalLiabilities != null) sb.append("  总负债: ").append(String.format("%.0f", totalLiabilities)).append("元\n");
        if (totalAssets != null) sb.append("  总资产: ").append(String.format("%.0f", totalAssets)).append("元\n");
        if (!missing.isEmpty()) sb.append("  缺少: ").append(String.join("、", missing)).append("\n");
        sb.append("  健康参考: 资产负债率 < 60%");

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
