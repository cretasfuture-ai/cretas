package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.reports.OperatingReportAssembler;
import com.cretas.aims.service.reports.OperatingReportGrossMarginQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 本月销售额与毛利 (只读, 对应意图 REPORT_SALES_MARGIN_SUMMARY) —— 「本月销售额多少、毛利多少」。
 *
 * <p>口径 = {@link SkuGrossMarginTool} 的「实际销售毛利」(同一条 {@link OperatingReportGrossMarginQuery} SQL):
 * 销售额 = 折后未税收入 (有售价的明细); 毛利 = 已回填成本明细的收入 − 成本, 并给成本覆盖率;
 * 缺成本的明细不当 0 成本进毛利。工艺保密产品对无权调用者整行剔除后汇总在剩余行上重算, 并说明。
 * 可见性与 SKU 毛利率、web SKU 毛利同一个判据 ({@link SkuGrossMarginTool#MARGIN_RATE_SPEC} / {@link SkuGrossMarginTool#MARGIN_AMOUNT_KEYS}):
 * 毛利率看不到 ⇒ 整份不给 (不查库); 毛利率看得到、金额看不到 ⇒ 只给毛利率, 金额字段 / 金额图 / 文字都不出;
 * 成本覆盖率与遮挡后的字段同进同出。
 */
@Component
public class ReportSalesMarginSummaryTool extends AbstractBusinessTool {

    static final int CHART_PRODUCTS = 8;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Autowired
    private OperatingReportGrossMarginQuery marginQuery;

    @Autowired(required = false)
    private ProcessSecretLlmCostScope llmCostScope = ProcessSecretLlmCostScope.standalone();

    @Override
    public String getToolName() {
        return "report_sales_margin_summary";
    }

    @Override
    public String getDescription() {
        return "查询销售额与毛利汇总（只读，默认本月）：期间销售额(折后未税)、毛利、毛利率、成本覆盖率，"
                + "并按产品给销售额/毛利柱图。毛利只算已回填成本的明细，缺成本会明说；口径同 SKU 毛利率。"
                + "适用：本月销售额多少、本月毛利多少、这个月卖了多少赚了多少。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "startDate", Map.of("type", "string", "description", "开始日期 yyyy-MM-dd，默认本月 1 号"),
                        "endDate", Map.of("type", "string", "description", "结束日期 yyyy-MM-dd，默认今天"),
                        "period", Map.of("type", "string", "description", "快捷周期: last_week / this_week / this_month / last_month；与日期同时给时以日期为准")
                ),
                "required", List.of());
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    protected LocalDate today() {
        return LocalDate.now();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        LocalDate today = today();
        String startText = blank(getString(params, "startDate"));
        String endText = blank(getString(params, "endDate"));
        String period = blank(getString(params, "period"));
        LocalDate start;
        LocalDate end;
        if (startText != null || endText != null) {
            end = endText != null ? parse(endText) : today;
            start = startText != null ? parse(startText) : end.withDayOfMonth(1);
        } else if (period != null) {
            LocalDate[] r = ReportCostQueryTool.periodRange(period, today);
            start = r[0];
            end = r[1];
        } else {
            start = today.withDayOfMonth(1);
            end = today;
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        boolean showAmounts = replyShowsAmounts(context, SkuGrossMarginTool.MARGIN_AMOUNT_KEYS);
        boolean showCoverage = replyShowsAmount(context, "costCoverage");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", Map.of("startDate", start.toString(), "endDate", end.toString()));
        data.put("basis", "销售额 = 销售订单明细折后未税收入（有售价的明细）；毛利 = 已回填成本明细的收入 − 成本；与 SKU 毛利率「实际销售毛利」同口径");
        boolean marginHidden = !replyShowsPriceSpec(context, SkuGrossMarginTool.MARGIN_RATE_SPEC) || !replyShowsAmount(context, "marginRate");
        // 「销售额是多少」也走本工具 (#1853): 看不到毛利的角色, 销售额只要「revenue」本身看得见就照给, 毛利 / 成本 / 覆盖率一个字不写
        boolean salesOnly = marginHidden && replyShowsAmount(context, "revenue");
        if (marginHidden && !salesOnly) {
            data.put("marginHidden", true);
            return buildSimpleResult(start + " 至 " + end + " 的" + SkuGrossMarginTool.MARGIN_RATE_HIDDEN_MESSAGE, data);
        }

        ProcessSecretLlmCostScope.Excluded secret = llmCostScope == null
                ? ProcessSecretLlmCostScope.Excluded.NONE : llmCostScope.forCurrentCaller(factoryId);
        Map<String, Object> payload = marginQuery.fetch(factoryId, start, end);
        Map<String, Object> view = OperatingReportGrossMarginQuery.withoutProducts(
                payload, secret.withheldProducts(), secret.protectedProductTypeIds(), false);
        Map<String, Object> summary = (Map<String, Object>) view.get("summary");
        List<Map<String, Object>> rank = (List<Map<String, Object>>) view.get("rank");
        int excludedRows = ((Number) view.get("excludedRowCount")).intValue();
        // 对外只报真正受保护的产品个数 (行数含计划锚点 / 同计划联产的普通产品, 会暴露联产结构)
        int excludedProtected = ((Number) view.get("excludedProtectedProductCount")).intValue();

        if (rank.isEmpty()) {
            return buildSimpleResult(start + " 至 " + end + " 没有可统计的销售记录"
                    + (excludedProtected > 0 ? "（" + ProcessSecretLlmCostScope.note(excludedProtected) + "）" : "")
                    + "，销售额和毛利无从计算。", data);
        }

        BigDecimal revenue = OperatingReportAssembler.number(summary.get("revenue"));
        BigDecimal costedRevenue = OperatingReportAssembler.number(summary.get("costedRevenue"));
        BigDecimal cost = OperatingReportAssembler.number(summary.get("cost"));
        BigDecimal profit = OperatingReportAssembler.number(summary.get("grossProfit"));
        BigDecimal margin = pct(OperatingReportAssembler.number(summary.get("marginRate")));
        BigDecimal coverage = pct(OperatingReportAssembler.number(summary.get("costCoverage")));

        data.put("productCount", summary.get("productCount"));
        data.put("excludedProductCount", excludedProtected);
        if (salesOnly) {
            data.put("marginHidden", true);
            data.put("revenue", money(revenue));
            StringBuilder only = new StringBuilder();
            only.append(start).append(" 至 ").append(end).append(" 销售额 ¥").append(money(revenue)).append("（共 ")
                    .append(summary.get("productCount")).append(" 个产品）。毛利按当前账号的权限配置不显示。");
            if (excludedProtected > 0) {
                only.append("\n").append(ProcessSecretLlmCostScope.note(excludedProtected));
            }
            return buildSimpleResult(only.toString(), data);
        }
        if (!showAmounts) {
            return amountsHiddenResult(start, end, summary, margin, showCoverage ? coverage : null, excludedProtected, rank, data);
        }
        data.put("revenue", money(revenue));
        data.put("costedRevenue", money(costedRevenue));
        data.put("cost", money(cost));
        data.put("grossProfit", money(profit));
        data.put("marginRate", margin);
        data.put("costCoverage", coverage);

        List<Map<String, Object>> byRevenue = new ArrayList<>(rank);
        byRevenue.sort((a, b) -> zero(OperatingReportAssembler.number(b.get("revenue")))
                .compareTo(zero(OperatingReportAssembler.number(a.get("revenue")))));
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> r : byRevenue) {
            if (top.size() >= CHART_PRODUCTS) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productTypeId", r.get("productTypeId"));
            row.put("productName", r.get("productName"));
            row.put("revenue", money(zero(OperatingReportAssembler.number(r.get("revenue")))));
            row.put("grossProfit", money(OperatingReportAssembler.number(r.get("grossProfit"))));
            row.put("marginRate", pct(OperatingReportAssembler.number(r.get("marginRate"))));
            top.add(row);
        }
        data.put("products", top);
        data.put("charts", List.of(chart(top)));

        StringBuilder sb = new StringBuilder();
        sb.append(start).append(" 至 ").append(end).append(" 销售额 ¥").append(money(revenue)).append("（共 ")
                .append(summary.get("productCount")).append(" 个产品）。");
        if (cost == null || margin == null) {
            sb.append("\n没有已回填成本的销售明细，毛利和毛利率算不出，不估算。");
        } else {
            sb.append("\n毛利 ¥").append(money(profit)).append("，毛利率 ").append(margin).append("%（已回填成本明细的收入 ¥")
                    .append(money(costedRevenue)).append("，成本 ¥").append(money(cost)).append("，成本覆盖 ")
                    .append(coverage).append("% 的销售额）。");
            if (coverage != null && coverage.compareTo(HUNDRED) < 0) {
                sb.append("未回填成本的明细没有计入毛利，实际毛利可能与此不同。");
            }
        }
        if (excludedProtected > 0) {
            sb.append("\n").append(ProcessSecretLlmCostScope.note(excludedProtected));
        }
        sb.append("\n口径：与 SKU 毛利率「实际销售毛利」一致（折后未税收入；毛利只算已回填成本的明细）。");
        return buildSimpleResult(sb.toString(), data);
    }

    /**
     * 过了工具门、但金额按权限配置看不到: 与 SKU 毛利率给同样多 —— 毛利率、成本覆盖率、各产品毛利率照给,
     * 销售额 / 成本 / 毛利金额字段不放、文字里不写、金额图不出。
     */
    private Map<String, Object> amountsHiddenResult(LocalDate start, LocalDate end, Map<String, Object> summary,
                                                    BigDecimal margin, BigDecimal coverage, int excludedProtected,
                                                    List<Map<String, Object>> rank, Map<String, Object> data) {
        data.put("marginRate", margin);
        if (coverage != null) {
            data.put("costCoverage", coverage);
        }
        data.put("amountsHidden", true);
        List<Map<String, Object>> products = new ArrayList<>();
        for (Map<String, Object> r : rank) {
            if (products.size() >= CHART_PRODUCTS) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productTypeId", r.get("productTypeId"));
            row.put("productName", r.get("productName"));
            row.put("marginRate", pct(OperatingReportAssembler.number(r.get("marginRate"))));
            products.add(row);
        }
        data.put("products", products);

        StringBuilder sb = new StringBuilder();
        sb.append(start).append(" 至 ").append(end).append(" 共 ").append(summary.get("productCount")).append(" 个产品有销售。");
        if (margin == null) {
            sb.append("\n没有已回填成本的销售明细，毛利率算不出，不估算。");
        } else {
            sb.append("\n毛利率 ").append(margin).append("%（只算已回填成本的明细");
            if (coverage != null) {
                // 成本覆盖率只在字段经遮挡层后仍在时才写 (文字与字段同进同出, #1788 审查 M2)
                sb.append("，成本覆盖 ").append(coverage).append("% 的销售额");
            }
            sb.append("）。未回填成本的明细不计入毛利。");
        }
        sb.append("\n销售额、成本、毛利金额按当前账号的权限配置不显示（毛利率与 web「SKU 毛利」同一判定照给）。");
        if (excludedProtected > 0) {
            sb.append("\n").append(ProcessSecretLlmCostScope.note(excludedProtected));
        }
        sb.append("\n口径：与 SKU 毛利率「实际销售毛利」一致（折后未税收入；毛利只算已回填成本的明细）。");
        return buildSimpleResult(sb.toString(), data);
    }

    private static Map<String, Object> chart(List<Map<String, Object>> top) {
        List<String> names = new ArrayList<>();
        List<BigDecimal> revenue = new ArrayList<>();
        List<BigDecimal> profit = new ArrayList<>();
        for (Map<String, Object> r : top) {
            names.add(String.valueOf(r.get("productName")));
            revenue.add((BigDecimal) r.get("revenue"));
            profit.add((BigDecimal) r.get("grossProfit"));
        }
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("legend", Map.of("bottom", 0));
        option.put("grid", Map.of("left", 60, "right", 20, "bottom", 80, "containLabel", true));
        option.put("xAxis", Map.of("type", "category", "data", names, "axisLabel", Map.of("interval", 0, "rotate", 25)));
        option.put("yAxis", Map.of("type", "value", "name", "元"));
        option.put("series", List.of(
                Map.of("type", "bar", "name", "销售额", "data", revenue),
                Map.of("type", "bar", "name", "毛利", "data", profit)));
        return Map.of("type", "bar", "title", "产品销售额与毛利（元，销售额前 " + top.size() + "）", "option", option);
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDate parse(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd: " + s);
        }
    }

    /** 与 SKU 毛利率同口径 (web 财务模块门): finance:read | finance:read_write, 中央 ToolRbacEnforcer 判定。 */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("finance:read", "finance:read_write");
    }

    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("report", "sales");
    }
}
