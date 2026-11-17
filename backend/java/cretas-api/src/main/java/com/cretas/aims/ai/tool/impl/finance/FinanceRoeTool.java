package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 净资产收益率 (ROE) 分析 Tool
 *
 * 从财务数据计算净资产收益率。
 * 对应意图: QUERY_FINANCE_ROE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FinanceRoeTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "finance_roe";
    }

    @Override
    public String getDescription() {
        return "计算净资产收益率(ROE)，分析净利润与股东权益的比率。" +
                "适用场景：ROE分析、净资产回报率查询、股东权益收益评估。";
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
        log.info("执行ROE分析 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        Map<String, Object> result = new HashMap<>();
        result.put("ratioType", "ROE");
        result.put("period", startDate + " 至 " + endDate);
        // 金额与比率只看价格查看 (与 #1801 利润表 / 指标中心同口径, 不另要 finance:read_write)
        if (!replyShowsPriceSpec(context, FinanceFigures.AMOUNT_SPEC)) {
            result.put("amountsHidden", true);
            result.put("message", FinanceFigures.amountsHiddenMessage("净资产收益率(ROE)", "净利润、股东权益"));
            return result;
        }

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);
        result.put("financeData", finance);
        FinanceFigures.sameVisibilityForAmounts(FinanceFigures.keys(FinanceFigures.profitInputKeys(finance),
                List.of("totalEquity", "netProfit", "roe")));

        Double netProfit = FinanceFigures.netProfit(finance);
        Double totalEquity = FinanceFigures.figure(finance, "totalEquity");
        List<String> missing = new ArrayList<>();
        if (netProfit == null) missing.add("净利润");
        if (totalEquity == null || totalEquity <= 0) missing.add("股东权益");

        StringBuilder sb = new StringBuilder();
        sb.append("净资产收益率分析\n");
        sb.append("分析周期: ").append(startDate).append(" ~ ").append(endDate).append("\n\n");
        if (!missing.isEmpty()) {
            result.put("dataSufficient", false);
            result.put("missingFields", missing);
            sb.append("净资产收益率(ROE): ").append(FinanceFigures.INSUFFICIENT).append("（缺少")
                    .append(String.join("、", missing)).append("）");
            FinanceFigures.appendProfitReason(sb, finance);
            result.put("message", sb.toString());
            return result;
        }

        double roe = (netProfit / totalEquity) * 100;
        result.put("dataSufficient", true);
        result.put("roe", roe);
        result.put("netProfit", netProfit);
        result.put("totalEquity", totalEquity);
        sb.append("净资产收益率(ROE): ").append(String.format("%.2f%%", roe)).append("\n");
        sb.append("  净利润: ").append(String.format("%.0f", netProfit)).append("元\n");
        sb.append("  股东权益: ").append(String.format("%.0f", totalEquity)).append("元\n");
        sb.append("  行业参考: 食品加工 8%-15%");

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
