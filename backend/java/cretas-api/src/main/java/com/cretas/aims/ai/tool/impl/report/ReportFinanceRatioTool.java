package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.impl.finance.FinanceFigures;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 财务分析指标 Tool
 *
 * 计算财务比率指标：ROA、ROE、流动比率、偿债能力、杜邦分析。
 * 对应意图: QUERY_FINANCE_ROA, QUERY_FINANCE_ROE, QUERY_LIQUIDITY,
 *          QUERY_SOLVENCY, QUERY_DUPONT_ANALYSIS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class ReportFinanceRatioTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_finance_ratio";
    }

    @Override
    public String getDescription() {
        return "计算财务分析指标，支持资产收益率(ROA)、净资产收益率(ROE)、流动比率、偿债能力分析、杜邦分析。" +
                "适用场景：财务指标查询、盈利能力分析、偿债能力评估、杜邦三因素分解。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> ratioType = new HashMap<>();
        ratioType.put("type", "string");
        ratioType.put("description", "财务指标类型: ROA(资产收益率), ROE(净资产收益率), LIQUIDITY(流动比率), SOLVENCY(偿债能力), DUPONT(杜邦分析)");
        ratioType.put("enum", Arrays.asList("ROA", "ROE", "LIQUIDITY", "SOLVENCY", "DUPONT"));
        properties.put("ratioType", ratioType);

        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式: yyyy-MM-dd，默认30天前");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式: yyyy-MM-dd，默认今天");
        endDate.put("format", "date");
        properties.put("endDate", endDate);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("ratioType"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("ratioType");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行财务指标分析 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 大小写不敏感 (模型常给 "roa"): 统一大写后再过利润闸和下面的分支
        String ratioType = getString(params, "ratioType", "ROA").trim().toUpperCase(Locale.ROOT);

        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        if (startDateStr != null) {
            startDate = LocalDate.parse(startDateStr);
        }
        if (endDateStr != null) {
            endDate = LocalDate.parse(endDateStr);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("ratioType", ratioType);
        result.put("period", startDate + " 至 " + endDate);
        // 金额与比率只看价格查看 (与 finance_roe / finance_roa / finance_dupont / #1801 利润表同一判定)。
        // 看不到 ⇒ 不查库, 比率与金额键都不出现 (不是 0)。
        if (!replyShowsPriceSpec(context, FinanceFigures.AMOUNT_SPEC)) {
            result.put("amountsHidden", true);
            result.put("message", FinanceFigures.amountsHiddenMessage(ratioType + " 财务比率", "利润、资产、负债、权益"));
            return result;
        }

        Map<String, Object> finance = reportService.getFinanceReport(factoryId, startDate, endDate);
        result.put("financeData", finance);

        Double totalRevenue = FinanceFigures.figure(finance, "totalRevenue");
        Double totalAssets = FinanceFigures.positive(FinanceFigures.figure(finance, "totalAssets"));
        Double totalEquity = FinanceFigures.figure(finance, "totalEquity");
        Double currentAssets = FinanceFigures.figure(finance, "currentAssets");
        Double currentLiabilities = FinanceFigures.positive(FinanceFigures.figure(finance, "currentLiabilities"));
        Double totalLiabilities = FinanceFigures.figure(finance, "totalLiabilities");
        Double inventory = FinanceFigures.figure(finance, "inventory");
        // 工艺保密: 财务报表的成本只算了调用者能看的计划而营收是全厂的, 报表已把利润置空 ——
        // 这里不能再拿两者相减造一个利润出来, 依赖利润的比率一律不给并说明原因
        Object profitUnavailable = FinanceFigures.profitUnavailableReason(finance);
        if (profitUnavailable != null && java.util.Set.of("ROA", "ROE", "DUPONT").contains(ratioType)) {
            result.put("netProfit", null);
            result.put("unavailableReason", profitUnavailable.toString());
            result.put("ratioName", ratioType);
            result.put("message", "利润不可计算（" + profitUnavailable + "），" + ratioType + " 不提供");
            return result;
        }
        Double netProfit = FinanceFigures.netProfit(finance);

        // 每个比率只在它自己的输入都在时才算; 缺一个 ⇒ 比率键不出现, 如实说「数据不足」(以前缺了按 0 / 1 顶, 出假值)
        String ratioName;
        List<String> missing = new ArrayList<>();
        List<String> visibleKeys = new ArrayList<>();
        switch (ratioType) {
            case "ROA":
                ratioName = "资产收益率";
                if (netProfit == null) missing.add("净利润");
                if (totalAssets == null) missing.add("总资产");
                if (missing.isEmpty()) result.put("roa", (netProfit / totalAssets) * 100);
                visibleKeys.addAll(FinanceFigures.keys(FinanceFigures.profitInputKeys(finance), List.of("totalAssets", "roa")));
                putIfKnown(result, visibleKeys, "netProfit", netProfit);
                putIfKnown(result, visibleKeys, "totalAssets", totalAssets);
                result.put("industryReference", "食品加工 3%-8%");
                break;
            case "ROE":
                ratioName = "净资产收益率";
                Double equity = FinanceFigures.positive(totalEquity);
                if (netProfit == null) missing.add("净利润");
                if (equity == null) missing.add("股东权益");
                if (missing.isEmpty()) result.put("roe", (netProfit / equity) * 100);
                visibleKeys.addAll(FinanceFigures.keys(FinanceFigures.profitInputKeys(finance), List.of("totalEquity", "roe")));
                putIfKnown(result, visibleKeys, "netProfit", netProfit);
                putIfKnown(result, visibleKeys, "totalEquity", totalEquity);
                result.put("industryReference", "食品加工 8%-15%");
                break;
            case "LIQUIDITY":
                ratioName = "流动比率";
                if (currentAssets == null) missing.add("流动资产");
                if (currentLiabilities == null) missing.add("流动负债");
                if (currentAssets != null && currentLiabilities != null) {
                    result.put("currentRatio", currentAssets / currentLiabilities);
                    if (inventory != null) {
                        result.put("quickRatio", (currentAssets - inventory) / currentLiabilities);
                    }
                }
                if (inventory == null) missing.add("存货");
                visibleKeys.addAll(List.of("currentAssets", "currentLiabilities", "inventory", "currentRatio", "quickRatio"));
                putIfKnown(result, visibleKeys, "currentAssets", currentAssets);
                putIfKnown(result, visibleKeys, "currentLiabilities", currentLiabilities);
                result.put("healthReference", "流动比率 > 2.0, 速动比率 > 1.0");
                break;
            case "SOLVENCY":
                ratioName = "偿债能力";
                if (totalAssets == null) missing.add("总资产");
                if (totalLiabilities == null) missing.add("总负债");
                if (totalEquity == null) missing.add("股东权益");
                if (totalAssets != null && totalLiabilities != null) {
                    result.put("debtRatio", (totalLiabilities / totalAssets) * 100);
                }
                if (totalAssets != null && totalEquity != null) {
                    result.put("equityRatio", (totalEquity / totalAssets) * 100);
                }
                visibleKeys.addAll(List.of("totalAssets", "totalLiabilities", "totalEquity", "debtRatio", "equityRatio"));
                putIfKnown(result, visibleKeys, "totalLiabilities", totalLiabilities);
                putIfKnown(result, visibleKeys, "totalAssets", totalAssets);
                result.put("healthReference", "资产负债率 < 60%");
                break;
            case "DUPONT":
                ratioName = "杜邦分析";
                Double revenue = FinanceFigures.positive(totalRevenue);
                Double equityD = FinanceFigures.positive(totalEquity);
                Double profitMargin = netProfit != null && revenue != null ? (netProfit / revenue) * 100 : null;
                Double assetTurnover = revenue != null && totalAssets != null ? revenue / totalAssets : null;
                Double equityMultiplier = totalAssets != null && equityD != null ? totalAssets / equityD : null;
                if (netProfit == null) missing.add("净利润");
                if (revenue == null) missing.add("营业收入");
                if (totalAssets == null) missing.add("总资产");
                if (equityD == null) missing.add("股东权益");
                if (profitMargin != null) result.put("profitMargin", profitMargin);
                if (assetTurnover != null) result.put("assetTurnover", assetTurnover);
                if (equityMultiplier != null) result.put("equityMultiplier", equityMultiplier);
                if (missing.isEmpty()) {
                    result.put("dupontROE", (profitMargin / 100) * assetTurnover * equityMultiplier * 100);
                }
                visibleKeys.addAll(FinanceFigures.keys(FinanceFigures.profitInputKeys(finance),
                        List.of("totalRevenue", "totalAssets", "totalEquity",
                                "profitMargin", "assetTurnover", "equityMultiplier", "dupontROE")));
                break;
            default:
                ratioName = ratioType;
                break;
        }
        // 只登记本比率用到的报表键与输出键; financeData 其余键 (分项成本、应收 / 应付 / 预收等) 出口按 web 同一判定处理
        FinanceFigures.sameVisibilityForAmounts(visibleKeys);

        result.put("ratioName", ratioName);
        result.put("dataSufficient", missing.isEmpty());
        if (!missing.isEmpty()) {
            result.put("missingFields", missing);
            result.put("message", ratioName + ": " + FinanceFigures.INSUFFICIENT + "（缺少" + String.join("、", missing)
                    + "），分析周期: " + startDate + " ~ " + endDate);
        } else {
            result.put("message", ratioName + "分析完成，分析周期: " + startDate + " ~ " + endDate);
        }

        return result;
    }

    private static void putIfKnown(Map<String, Object> result, List<String> keys, String key, Double value) {
        if (value != null) {
            result.put(key, value);
            keys.add(key);
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "ratioType", "请问您要查看哪个财务指标？可选：ROA(资产收益率)、ROE(净资产收益率)、LIQUIDITY(流动比率)、SOLVENCY(偿债能力)、DUPONT(杜邦分析)。",
            "startDate", "请问分析的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问分析的结束日期是？（格式：yyyy-MM-dd）"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "ratioType", "财务指标类型",
            "startDate", "开始日期",
            "endDate", "结束日期"
        );
        return displayNames.getOrDefault(paramName, paramName);
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
