package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Trends Report Tool
 *
 * Provides trend analysis reports including historical data trends,
 * forecasting, pattern recognition, and comparative analysis.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ReportTrendsTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_trends";
    }

    @Override
    public String getDescription() {
        return "获取趋势分析报表，包含历史数据趋势、预测分析、模式识别、同比环比对比等数据。" +
                "适用场景：趋势分析、预测决策、历史对比、周期性分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // period: 时间周期（可选）
        Map<String, Object> period = new HashMap<>();
        period.put("type", "string");
        period.put("description", "时间周期：today(今日), week(本周), month(本月), quarter(本季度), year(本年)");
        period.put("enum", Arrays.asList("today", "week", "month", "quarter", "year"));
        period.put("default", "month");
        properties.put("period", period);

        // startDate: 开始日期（可选）
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式: yyyy-MM-dd");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        // endDate: 结束日期（可选）
        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式: yyyy-MM-dd");
        endDate.put("format", "date");
        properties.put("endDate", endDate);

        // metric: 指标类型（可选）
        Map<String, Object> metric = new HashMap<>();
        metric.put("type", "string");
        metric.put("description", "趋势指标类型");
        metric.put("enum", Arrays.asList("PRODUCTION", "QUALITY", "EFFICIENCY", "INVENTORY", "COST", "REVENUE", "ALL"));
        properties.put("metric", metric);

        // days: 分析天数（可选）
        Map<String, Object> days = new HashMap<>();
        days.put("type", "integer");
        days.put("description", "趋势分析的天数范围");
        days.put("default", 30);
        days.put("minimum", 7);
        days.put("maximum", 365);
        properties.put("days", days);

        Map<String, Object> comparisonType = new HashMap<>();
        comparisonType.put("type", "string");
        comparisonType.put("description", "对比方式：MOM=环比（默认），YOY=同比（去年同期）。只要日序列时可省略。");
        comparisonType.put("enum", Arrays.asList("MOM", "YOY"));
        properties.put("comparisonType", comparisonType);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        // 只有可选参数, 但「这周 / 同比 / 不比较」要靠抽参才能读出来 (#967)
        schema.put(com.cretas.aims.service.execution.ToolDispatchService.EXTRACT_OPTIONAL_FLAG, true);

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行趋势分析报表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析时间参数
        String period = getString(params, "period", "month");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        String metric = getString(params, "metric", "PRODUCTION");
        Integer days = getInteger(params, "days", 30);
        String comparisonType = getString(params, "comparisonType");
        String userInput = firstNonBlank(getString(params, "userInput"), getString(context, "userInput"), getString(context, "input"),
                getString(context, "message"));
        boolean explicitComparison = containsAny(userInput, "同比", "去年同期", "上年同期", "环比", "对比", "比上期");
        boolean dailyOnly = containsAny(userInput, "不比较", "只要日序列")
                || (!explicitComparison && containsAny(userInput, "日序列", "每日", "每天", "逐日", "按天"));
        if (containsAny(userInput, "同比", "去年同期", "上年同期")) comparisonType = "YOY";
        else if (containsAny(userInput, "环比")) comparisonType = "MOM";

        // 调用服务获取趋势分析报表数据
        Map<String, Object> trendsData = reportService.getTrendsDashboard(factoryId, period, metric, days);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "TRENDS");
        result.put("period", period);
        result.put("days", days);
        result.put("metric", metric);
        result.put("generatedAt", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        result.put("data", trendsData);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("period", period);
        queryConditions.put("days", days);
        queryConditions.put("metric", metric);
        if (startDateStr != null) queryConditions.put("startDate", startDateStr);
        if (endDateStr != null) queryConditions.put("endDate", endDateStr);
        result.put("queryConditions", queryConditions);

        if (!dailyOnly && Set.of("PRODUCTION", "COST", "ALL").contains(metric.toUpperCase(Locale.ROOT))) {
            String type = comparisonType == null ? "MOM" : comparisonType.toUpperCase(Locale.ROOT);
            LocalDate[] current = resolvePeriod(period, startDateStr, endDateStr, days, params.containsKey("days") && !params.containsKey("period"));
            boolean customRange = startDateStr != null || endDateStr != null
                    || (params.containsKey("days") && !params.containsKey("period"));
            LocalDate[] previous = "YOY".equals(type)
                    ? new LocalDate[]{current[0].minusYears(1), current[1].minusYears(1)}
                    : previousPeriod(current, period, customRange);
            Map<String, Object> comparison = reportService.getPeriodComparisonReport(
                    factoryId, previous[0], previous[1], current[0], current[1]);
            comparison.put("comparisonType", type);
            comparison.put("comparisonLabel", "YOY".equals(type) ? "同比" : "环比");
            comparison.put("basePeriodStart", previous[0].toString());
            comparison.put("basePeriodEnd", previous[1].toString());
            comparison.put("currentPeriodStart", current[0].toString());
            comparison.put("currentPeriodEnd", current[1].toString());
            comparison.put("dataBasis", "按工厂和生产结束时间汇总：实际产量、物料成本、人工成本、设备成本及其他成本；工厂=" + factoryId);
            addUnavailableMessages(comparison, type);
            result.put("comparison", comparison);
        }

        log.info("趋势分析报表查询完成 - 工厂ID: {}, 指标: {}, 天数: {}", factoryId, metric, days);

        return result;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null) return false;
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private LocalDate[] resolvePeriod(String period, String startDateStr, String endDateStr, Integer days, boolean customDays) {
        LocalDate end = endDateStr == null ? LocalDate.now() : LocalDate.parse(endDateStr);
        LocalDate start = switch (period.toLowerCase(Locale.ROOT)) {
            case "today" -> end;
            case "week" -> end.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            case "quarter" -> end.with(end.getMonth().firstMonthOfQuarter()).withDayOfMonth(1);
            case "year" -> end.withDayOfYear(1);
            default -> end.withDayOfMonth(1);
        };
        if (customDays && days != null && days > 0) start = end.minusDays(days - 1L);
        if (startDateStr != null) start = LocalDate.parse(startDateStr);
        if (end.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于开始日期");
        return new LocalDate[]{start, end};
    }

    private LocalDate[] previousPeriod(LocalDate[] current, String period, boolean customRange) {
        if (customRange) {
            long length = java.time.temporal.ChronoUnit.DAYS.between(current[0], current[1]) + 1;
            return new LocalDate[]{current[0].minusDays(length), current[0].minusDays(1)};
        }
        return switch (period.toLowerCase(Locale.ROOT)) {
            case "today" -> new LocalDate[]{current[0].minusDays(1), current[1].minusDays(1)};
            case "week" -> new LocalDate[]{current[0].minusWeeks(1), current[1].minusWeeks(1)};
            case "quarter" -> new LocalDate[]{current[0].minusMonths(3), current[1].minusMonths(3)};
            case "year" -> new LocalDate[]{current[0].minusYears(1), current[1].minusYears(1)};
            default -> new LocalDate[]{current[0].minusMonths(1), current[1].minusMonths(1)};
        };
    }

    @SuppressWarnings("unchecked")
    private void addUnavailableMessages(Map<String, Object> comparison, String type) {
        Map<String, Object> values = (Map<String, Object>) comparison.get("comparison");
        if (values == null) return;
        String label = "YOY".equals(type) ? "同比" : "环比";
        Map<String, Object> base = (Map<String, Object>) comparison.get("period1");
        Map<String, Object> current = (Map<String, Object>) comparison.get("period2");
        if (values.get("outputChangeRate") == null) values.put("outputChangeMessage", missingMessage(base, current, "output", label));
        if (values.get("costChangeRate") == null) values.put("costChangeMessage", missingMessage(base, current, "cost", label));
    }

    private String missingMessage(Map<String, Object> base, Map<String, Object> current, String key, String label) {
        if (base == null || base.get(key) == null
                || new java.math.BigDecimal(base.get(key).toString()).signum() <= 0) {
            return "上期无数据，无法计算" + label;
        }
        if (current == null || current.get(key) == null) return "本期无数据，无法计算" + label;
        return "上期无数据，无法计算" + label;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "period", "请问您想查看哪个时间段的趋势数据？可选：今日、本周、本月、本季度、本年。",
            "startDate", "请问报表的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问报表的结束日期是？（格式：yyyy-MM-dd）",
            "metric", "请问要分析哪个指标的趋势？可选：生产、质量、效率、库存、成本、收入、全部。",
            "days", "请问要分析多少天的趋势数据？（7-365天）"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "period", "时间周期",
            "startDate", "开始日期",
            "endDate", "结束日期",
            "metric", "指标类型",
            "days", "分析天数"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
