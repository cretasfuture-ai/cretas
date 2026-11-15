package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 人工成本占比 (只读, 对应意图 REPORT_LABOR_COST_SHARE) —— 「人工成本占比多少」「哪个产品人工占比高」。
 *
 * <p>口径与成本分析页 / {@link ReportCostQueryTool} 完全一致 (直接复用同一个聚合): 已完成批次、按结束日半开区间落期、
 * 成本未算全的批次不进合计 (单独报个数)、工艺保密产品对无权调用者整批剔除并说明。
 * 人工占比 = 人工成本合计 ÷ 总成本合计 × 100, 分子分母来自同一批「成本已算全」的批次。
 *
 * <p>只出<b>金额汇总</b>: 不读、不出任何人员字段 (人名 / 工号 / 工时明细), 人工成本是批次上的一个金额分项。
 * 无成本查看权限的调用者: 金额、占比、图一律不出 (占比 × 总成本能反推, 故整份隐藏)。
 */
@Component
public class ReportLaborCostShareTool extends AbstractBusinessTool {

    static final int MAX_PRODUCTS = 8;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Autowired(required = false)
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private ProductionBatchRepository batchRepository;

    @Autowired(required = false)
    private ProcessSecretLlmCostScope llmCostScope = ProcessSecretLlmCostScope.standalone();

    @Override
    public String getToolName() {
        return "report_labor_cost_share";
    }

    @Override
    public String getDescription() {
        return "查询人工成本占比（只读）：期间内已完成批次的人工成本占总成本的比例，按产品拆分并附人工占比柱图，"
                + "与成本分析页同口径（成本未算全的批次、保密产品会明说，不估算）；只给金额汇总，不涉及具体人员。"
                + "适用：人工成本占比多少、人工成本占总成本多少、哪个产品人工占比高。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return ReportToolSchemas.withOptionalExtraction(Map.of(
                "type", "object",
                "properties", Map.of(
                        "startDate", Map.of("type", "string", "format", "date", "description", "开始日期 yyyy-MM-dd，默认本月 1 号"),
                        "endDate", Map.of("type", "string", "format", "date", "description", "结束日期 yyyy-MM-dd，默认今天"),
                        "period", Map.of("type", "string", "description", "快捷周期: last_week / this_week / this_month / last_month；与日期同时给时以日期为准"),
                        "productName", Map.of("type", "string", "description", "产品名（可选）；同名优先，多个包含匹配时给候选不合计")
                ),
                "required", List.of()));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    protected LocalDate today() {
        return LocalDate.now();
    }

    @Override
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
        String keyword = ReportToolSchemas.productKeyword(productTypeRepository, factoryId, getString(params, "productName"));
        boolean showAmounts = replyShowsAmounts(context, "cost", "totalCost", "laborCost");

        ProcessSecretLlmCostScope.Excluded secret = llmCostScope == null
                ? ProcessSecretLlmCostScope.Excluded.NONE : llmCostScope.forCurrentCaller(factoryId);
        Set<String> productIds = null;
        if (keyword != null && productTypeRepository != null) {
            List<ProductType> candidates = ReportToolSchemas.matchProducts(productTypeRepository, factoryId, keyword);
            String msg = ReportToolSchemas.productResolutionMessage(candidates, keyword, "统计人工成本占比");
            if (msg != null) {
                Map<String, Object> none = new LinkedHashMap<>();
                none.put("productFilter", keyword);
                none.put("candidates", candidates.stream().map(ProductType::getName).toList());
                return buildSimpleResult(msg, none);
            }
            productIds = new HashSet<>();
            productIds.add(candidates.get(0).getId());
        }
        ReportCostQueryTool.Aggregate agg = ReportCostQueryTool.aggregate(batchRepository, factoryId, start, end, productIds != null ? null : keyword, productIds, secret);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", Map.of("startDate", start.toString(), "endDate", end.toString()));
        data.put("productFilter", keyword);
        data.put("batchCount", agg.batchCount);
        data.put("costIncompleteBatchCount", agg.incompleteBatches);
        data.put("withheldBatchCount", agg.withheldBatches);
        data.put("basis", "人工占比 = 人工成本合计 ÷ 总成本合计，只统计成本已算全的已完成批次（按结束日落期，与成本分析页同口径）；只含金额汇总，不含人员明细");

        if (!showAmounts) {
            // 占比 × 总成本能反推金额: 无权看成本的账号整份不出 (不出占比、不出图)
            return buildSimpleResult(start + " 至 " + end + (keyword != null ? "「" + keyword + "」" : "")
                    + " 已完成批次 " + agg.batchCount + " 个。人工成本金额和占比按当前账号权限不显示；"
                    + "需要请用有成本查看权限的账号。", data);
        }
        if (agg.batchCount == 0) {
            return buildSimpleResult("「" + start + " 至 " + end + "」" + (keyword != null ? "没有「" + keyword + "」" : "没有")
                    + "已完成的生产批次，无从统计人工成本占比。"
                    + (agg.withheldBatches > 0 ? agg.withheldNote() : ""), data);
        }
        if (agg.costedBatches == 0 || agg.total.signum() <= 0) {
            return buildSimpleResult(start + " 至 " + end + " 已完成批次 " + agg.batchCount
                    + " 个，但没有成本已算全的批次（或总成本为 0），人工成本占比算不出，不估算。"
                    + (agg.withheldBatches > 0 ? agg.withheldNote() : ""), data);
        }

        BigDecimal sharePct = pct(agg.labor, agg.total);
        data.put("costedBatchCount", agg.costedBatches);
        data.put("laborCost", ReportCostQueryTool.money(agg.labor));
        data.put("totalCost", ReportCostQueryTool.money(agg.total));
        data.put("laborSharePct", sharePct);

        List<ReportCostQueryTool.ProductAgg> products = new ArrayList<>(agg.byProduct.values());
        // 按人工占比从高到低取前 N (按总成本取前 N 会漏掉占比最高的小产品)
        products.sort((a, b) -> {
            BigDecimal sa = pct(a.labor, a.total);
            BigDecimal sb2 = pct(b.labor, b.total);
            if (sa == null || sb2 == null) return sa == null ? (sb2 == null ? 0 : 1) : -1;
            return sb2.compareTo(sa);
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportCostQueryTool.ProductAgg p : products) {
            if (rows.size() >= MAX_PRODUCTS) break;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("productTypeId", p.productTypeId);
            r.put("productName", p.name);
            r.put("batchCount", p.batches);
            r.put("laborCost", ReportCostQueryTool.money(p.labor));
            r.put("totalCost", ReportCostQueryTool.money(p.total));
            r.put("laborSharePct", pct(p.labor, p.total));
            rows.add(r);
        }
        data.put("products", rows);
        data.put("charts", charts(sharePct, rows));
        return buildSimpleResult(message(agg, sharePct, rows, start, end, keyword), data);
    }

    private static List<Map<String, Object>> charts(BigDecimal sharePct, List<Map<String, Object>> rows) {
        List<Map<String, Object>> charts = new ArrayList<>();
        if (rows.isEmpty()) return charts;
        List<String> names = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            names.add(String.valueOf(r.get("productName")));
            values.add((BigDecimal) r.get("laborSharePct"));
        }
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("grid", Map.of("left", 50, "right", 20, "bottom", 70, "containLabel", true));
        option.put("xAxis", Map.of("type", "category", "data", names, "axisLabel", Map.of("interval", 0, "rotate", 25)));
        option.put("yAxis", Map.of("type", "value", "name", "%"));
        option.put("series", List.of(Map.of("type", "bar", "name", "人工成本占比(%)", "data", values)));
        charts.add(Map.of("type", "bar", "title", "各产品人工成本占比（%，按占比从高到低前 " + rows.size() + "）", "option", option));
        return charts;
    }

    private static String message(ReportCostQueryTool.Aggregate agg, BigDecimal sharePct, List<Map<String, Object>> rows,
                                  LocalDate start, LocalDate end, String keyword) {
        StringBuilder sb = new StringBuilder();
        sb.append(start).append(" 至 ").append(end).append(keyword != null ? "「" + keyword + "」" : "")
                .append(" 已完成批次 ").append(agg.batchCount).append(" 个，其中成本已算全 ").append(agg.costedBatches).append(" 个。");
        if (agg.incompleteBatches > 0) {
            sb.append("另有 ").append(agg.incompleteBatches).append(" 个批次成本未算全，不计入。");
        }
        if (agg.withheldBatches > 0) {
            sb.append(agg.withheldNote());
        }
        sb.append("\n人工成本 ¥").append(ReportCostQueryTool.money(agg.labor)).append("，占总成本 ¥")
                .append(ReportCostQueryTool.money(agg.total)).append(" 的 ").append(sharePct).append("%。");
        sb.append("\n各产品人工占比（从高到低）：");
        int i = 0;
        for (Map<String, Object> r : rows) {
            sb.append("\n").append(++i).append(". ").append(r.get("productName")).append("：人工 ¥").append(r.get("laborCost"))
                    .append(" / 总成本 ¥").append(r.get("totalCost")).append("，占 ").append(r.get("laborSharePct")).append("%");
        }
        sb.append("\n口径：只统计成本已算全的已完成批次（按结束日落期），与成本分析页一致；只给金额汇总，不含人员明细。");
        return sb.toString();
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() > 0 ? part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP) : null;
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

    /** 与成本分析同口径 (AIController#analyzeTimeRangeCost): analytics:read_write。 */
    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("analytics:read_write");
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
        return Set.of("report", "cost");
    }
}
