package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 资产收益率 (ROA) 分析 Tool
 *
 * 从财务数据计算资产收益率。
 * 对应意图: QUERY_FINANCE_ROA
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class FinanceRoaTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "finance_roa";
    }

    @Override
    public String getDescription() {
        return "计算资产收益率(ROA)，分析净利润与总资产的比率。" +
                "适用场景：ROA分析、资产回报率查询、资产收益评估。";
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
        log.info("执行ROA分析 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        Map<String, Object> result = new HashMap<>();
        result.put("ratioType", "ROA");
        result.put("period", startDate + " 至 " + endDate);
        // 与 ROE / 杜邦 / web GET /reports/finance 同一组金额键、同一个判定
        if (!replyShowsAmounts(context, FinanceRoeTool.FINANCE_AMOUNT_KEYS)) {
            result.put("amountsHidden", true);
            result.put("message", FinanceRoeTool.amountsHiddenMessage("资产收益率(ROA)", "净利润、总资产"));
            return result;
        }

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);
        result.put("financeData", finance);

        Double netProfit = FinanceRoeTool.figure(finance, "totalProfit");
        Double totalAssets = FinanceRoeTool.figure(finance, "totalAssets");
        List<String> missing = new ArrayList<>();
        if (netProfit == null) missing.add("净利润");
        if (totalAssets == null || totalAssets <= 0) missing.add("总资产");

        StringBuilder sb = new StringBuilder();
        sb.append("资产收益率分析\n");
        sb.append("分析周期: ").append(startDate).append(" ~ ").append(endDate).append("\n\n");
        if (!missing.isEmpty()) {
            result.put("dataSufficient", false);
            result.put("missingFields", missing);
            sb.append("资产收益率(ROA): ").append(FinanceRoeTool.INSUFFICIENT).append("（缺少")
                    .append(String.join("、", missing)).append("）");
            FinanceRoeTool.appendProfitReason(sb, finance);
            result.put("message", sb.toString());
            return result;
        }

        double roa = (netProfit / totalAssets) * 100;
        result.put("dataSufficient", true);
        result.put("roa", roa);
        sb.append("资产收益率(ROA): ").append(String.format("%.2f%%", roa)).append("\n");
        sb.append("  净利润: ").append(String.format("%.0f", netProfit)).append("元\n");
        sb.append("  总资产: ").append(String.format("%.0f", totalAssets)).append("元\n");
        sb.append("  行业参考: 食品加工 3%-8%");

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
