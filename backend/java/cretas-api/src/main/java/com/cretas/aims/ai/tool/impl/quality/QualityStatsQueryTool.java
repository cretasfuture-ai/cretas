package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.repository.QualityInspectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 质检统计查询工具
 *
 * 提供质检统计数据查询功能，支持按时间周期、产品类型筛选。
 * 返回合格率、不合格率、质量问题数等统计指标。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class QualityStatsQueryTool extends AbstractBusinessTool {

    @Autowired
    private QualityInspectionRepository qualityInspectionRepository;

    @Autowired(required = false)
    private com.cretas.aims.repository.ProductionBatchRepository productionBatchRepository;

    /** 测试可覆盖; 生产用服务器本地日期。 */
    protected LocalDate today() {
        return LocalDate.now();
    }

    @Override
    public String getToolName() {
        return "quality_stats_query";
    }

    @Override
    public String getDescription() {
        return "查询质检统计数据。返回指定时间段内的质检合格率、不合格率、质量问题数等统计指标。" +
                "适用场景：查看今日/本周/本月质检统计、生成质量报表、分析质量趋势。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // period: 统计周期（可选）
        Map<String, Object> period = new HashMap<>();
        period.put("type", "string");
        period.put("description", "统计周期：today-今日，week-本周，month-本月，year-本年，custom-自定义");
        period.put("enum", Arrays.asList("today", "week", "month", "year", "custom"));
        period.put("default", "today");
        properties.put("period", period);

        // dateFrom: 自定义开始日期（可选）
        Map<String, Object> dateFrom = new HashMap<>();
        dateFrom.put("type", "string");
        dateFrom.put("format", "date");
        dateFrom.put("description", "自定义查询开始日期，格式：yyyy-MM-dd（period=custom时使用）");
        properties.put("dateFrom", dateFrom);

        // startDate / endDate: 对话预处理把原话里的时间 (本月 / 上周 ...) 放进这两个参数; 声明了就必须真的按它过滤
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("format", "date");
        startDate.put("description", "起始日期（用户说了时间范围时使用，优先于统计周期）");
        properties.put("startDate", startDate);
        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("format", "date");
        endDate.put("description", "结束日期");
        properties.put("endDate", endDate);

        // dateTo: 自定义结束日期（可选）
        Map<String, Object> dateTo = new HashMap<>();
        dateTo.put("type", "string");
        dateTo.put("format", "date");
        dateTo.put("description", "自定义查询结束日期，格式：yyyy-MM-dd（period=custom时使用）");
        properties.put("dateTo", dateTo);

        // productTypeId: 产品类型ID（可选）
        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型ID，用于筛选特定产品的质检统计");
        properties.put("productTypeId", productTypeId);

        // includeDetails: 是否包含详细数据（可选）
        Map<String, Object> includeDetails = new HashMap<>();
        includeDetails.put("type", "boolean");
        includeDetails.put("description", "是否包含检验员统计等详细数据");
        includeDetails.put("default", false);
        properties.put("includeDetails", includeDetails);

        schema.put("properties", properties);
        // 统计查询类Tool无必需参数
        schema.put("required", Collections.emptyList());

        return schema;
    }

    /**
     * 统计查询类Tool无必需参数
     */
    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行质检统计查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析参数
        String period = getString(params, "period", "today");
        String dateFromStr = getString(params, "dateFrom");
        String dateToStr = getString(params, "dateTo");
        String saidStart = getString(params, "startDate");
        String saidEnd = getString(params, "endDate");
        if (saidStart != null && !saidStart.isBlank()) {
            period = "custom";
            dateFromStr = saidStart;
            if (saidEnd != null && !saidEnd.isBlank()) dateToStr = saidEnd;
        }
        String productTypeId = getString(params, "productTypeId");
        Boolean includeDetails = getBoolean(params, "includeDetails", false);

        // 计算日期范围
        LocalDate[] dateRange = calculateDateRange(period, dateFromStr, dateToStr);
        LocalDate startDate = dateRange[0];
        LocalDate endDate = dateRange[1];

        log.info("统计日期范围: {} ~ {}", startDate, endDate);

        // 获取统计数据
        Map<String, Object> stats = new HashMap<>();

        // 基础统计
        long totalCount = qualityInspectionRepository.findByFactoryIdAndDateRange(factoryId, startDate, endDate).size();
        long passCountNum = qualityInspectionRepository.countByFactoryIdAndResult(factoryId, "PASS");
        long failCountNum = qualityInspectionRepository.countByFactoryIdAndResult(factoryId, "FAIL");

        // 使用 Repository 的统计方法
        BigDecimal totalSampleSize = qualityInspectionRepository.calculateTotalSampleSize(factoryId, startDate, endDate);
        BigDecimal totalPassCount = qualityInspectionRepository.calculateTotalPassCount(factoryId, startDate, endDate);
        BigDecimal totalFailCount = qualityInspectionRepository.calculateTotalFailCount(factoryId, startDate, endDate);
        BigDecimal avgPassRate = qualityInspectionRepository.calculateAveragePassRate(factoryId, startDate, endDate);
        long qualityIssues = qualityInspectionRepository.countQualityIssues(factoryId, startDate, endDate);
        long resolvedIssues = qualityInspectionRepository.countResolvedIssues(factoryId, startDate, endDate);
        Double firstPassRate = qualityInspectionRepository.calculateFirstPassRate(factoryId, startDate, endDate);

        // 统计周期信息
        Map<String, Object> periodInfo = new HashMap<>();
        periodInfo.put("type", period);
        periodInfo.put("startDate", startDate.toString());
        periodInfo.put("endDate", endDate.toString());
        periodInfo.put("days", java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1);
        stats.put("period", periodInfo);

        // 检验数量统计
        Map<String, Object> countStats = new HashMap<>();
        countStats.put("totalInspections", totalCount);
        countStats.put("totalSampleSize", totalSampleSize != null ? totalSampleSize : BigDecimal.ZERO);
        countStats.put("totalPassCount", totalPassCount != null ? totalPassCount : BigDecimal.ZERO);
        countStats.put("totalFailCount", totalFailCount != null ? totalFailCount : BigDecimal.ZERO);
        stats.put("counts", countStats);

        // 合格率统计
        Map<String, Object> rateStats = new HashMap<>();
        rateStats.put("averagePassRate", avgPassRate != null ?
                avgPassRate.setScale(2, RoundingMode.HALF_UP).toString() + "%" : "N/A");
        rateStats.put("firstPassRate", firstPassRate != null ?
                String.format("%.2f%%", firstPassRate) : "N/A");

        // 计算不合格率
        if (totalSampleSize != null && totalSampleSize.compareTo(BigDecimal.ZERO) > 0 && totalFailCount != null) {
            BigDecimal defectRate = totalFailCount.multiply(BigDecimal.valueOf(100))
                    .divide(totalSampleSize, 2, RoundingMode.HALF_UP);
            rateStats.put("defectRate", defectRate.toString() + "%");
        } else {
            rateStats.put("defectRate", "N/A");
        }
        stats.put("rates", rateStats);

        // 质量问题统计
        Map<String, Object> issueStats = new HashMap<>();
        issueStats.put("totalIssues", qualityIssues);
        issueStats.put("resolvedIssues", resolvedIssues);
        issueStats.put("openIssues", qualityIssues - resolvedIssues);
        if (qualityIssues > 0) {
            issueStats.put("resolutionRate", String.format("%.2f%%", (resolvedIssues * 100.0 / qualityIssues)));
        } else {
            issueStats.put("resolutionRate", "N/A");
        }
        stats.put("issues", issueStats);

        // 详细数据（可选）
        if (Boolean.TRUE.equals(includeDetails)) {
            Map<String, Object> details = new HashMap<>();

            // 按检验员统计
            List<Object[]> inspectorStats = qualityInspectionRepository.countByFactoryIdAndDateRangeGroupByInspector(
                    factoryId, startDate, endDate);
            List<Map<String, Object>> inspectorList = new ArrayList<>();
            for (Object[] row : inspectorStats) {
                Map<String, Object> inspectorData = new HashMap<>();
                inspectorData.put("inspectorId", row[0]);
                inspectorData.put("totalCount", row[1]);
                inspectorData.put("passedCount", row[2]);
                inspectorList.add(inspectorData);
            }
            details.put("byInspector", inspectorList);

            stats.put("details", details);
        }

        // 添加查询条件
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("period", period);
        if (productTypeId != null) queryConditions.put("productTypeId", productTypeId);
        queryConditions.put("includeDetails", includeDetails);
        stats.put("queryConditions", queryConditions);

        // 生成摘要消息
        String summary = generateSummary(period, totalCount, avgPassRate, qualityIssues);
        stats.put("summary", summary);

        // 「本月生产了多少批次，合格率多少」: 批次数 (已完成, 按结束日落期) + 合格率 (检验结果合格次数 ÷ 检验次数); 没数据如实说缺什么
        Integer completedBatches = countCompletedBatches(factoryId, startDate, endDate);
        stats.put("completedBatchCount", completedBatches);
        stats.put("inspectionPassRatePct", totalCount > 0 && firstPassRate != null
                ? BigDecimal.valueOf(firstPassRate).setScale(1, RoundingMode.HALF_UP) : null);
        stats.put("message", humanMessage(startDate, endDate, completedBatches, totalCount, firstPassRate));

        log.info("质检统计查询完成 - 周期: {}, 总检验数: {}, 平均合格率: {}",
                period, totalCount, avgPassRate);

        return stats;
    }

    /** 已完成生产批次数 (按结束日落期, 与成本分析页同口径; 排除文员在制批)。仓库不可用时 null = 没量到, ⛔ 不写 0。 */
    private Integer countCompletedBatches(String factoryId, LocalDate start, LocalDate end) {
        if (productionBatchRepository == null) return null;
        List<com.cretas.aims.entity.ProductionBatch> batches =
                productionBatchRepository.findByFactoryIdAndEndTimeGreaterThanEqualAndEndTimeLessThan(
                        factoryId, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        int n = 0;
        if (batches != null) {
            for (com.cretas.aims.entity.ProductionBatch b : batches) {
                if (b.getStatus() != com.cretas.aims.entity.enums.ProductionBatchStatus.COMPLETED) continue;
                if ("CLERK_WIP".equals(b.getBatchType())) continue;
                n++;
            }
        }
        return n;
    }

    static String humanMessage(LocalDate start, LocalDate end, Integer completedBatches, long inspections, Double passRatePct) {
        StringBuilder sb = new StringBuilder().append(start).append(" 至 ").append(end).append("：");
        if (completedBatches == null) {
            sb.append("生产批次数暂时查不到；");
        } else {
            sb.append("已完成生产批次 ").append(completedBatches).append(" 个；");
        }
        if (inspections <= 0 || passRatePct == null) {
            sb.append("这段时间没有质检记录，合格率算不出（需要先在质检环节录入检验结果），不估算。");
        } else {
            sb.append("质检 ").append(inspections).append(" 次，合格率 ")
                    .append(BigDecimal.valueOf(passRatePct).setScale(1, RoundingMode.HALF_UP))
                    .append("%（合格次数 ÷ 检验次数）。");
        }
        return sb.toString();
    }

    /**
     * 根据周期计算日期范围
     */
    private LocalDate[] calculateDateRange(String period, String dateFromStr, String dateToStr) {
        LocalDate today = today();
        LocalDate startDate;
        LocalDate endDate = today;

        switch (period) {
            case "today":
                startDate = today;
                break;
            case "week":
                // 本周一开始
                startDate = today.minusDays(today.getDayOfWeek().getValue() - 1);
                break;
            case "month":
                // 本月1号开始
                startDate = today.withDayOfMonth(1);
                break;
            case "year":
                // 本年1月1号开始
                startDate = today.withDayOfYear(1);
                break;
            case "custom":
                // 自定义日期范围
                if (dateFromStr != null && !dateFromStr.trim().isEmpty()) {
                    try {
                        startDate = LocalDate.parse(dateFromStr);
                    } catch (Exception e) {
                        startDate = today.minusDays(30);
                    }
                } else {
                    startDate = today.minusDays(30);
                }
                if (dateToStr != null && !dateToStr.trim().isEmpty()) {
                    try {
                        endDate = LocalDate.parse(dateToStr);
                    } catch (Exception e) {
                        endDate = today;
                    }
                }
                break;
            default:
                startDate = today;
        }

        return new LocalDate[]{startDate, endDate};
    }

    /**
     * 生成统计摘要消息
     */
    private String generateSummary(String period, long totalCount, BigDecimal avgPassRate, long qualityIssues) {
        String periodDesc;
        switch (period) {
            case "today":
                periodDesc = "今日";
                break;
            case "week":
                periodDesc = "本周";
                break;
            case "month":
                periodDesc = "本月";
                break;
            case "year":
                periodDesc = "本年";
                break;
            default:
                periodDesc = "所选时段";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(periodDesc).append("质检统计：");
        sb.append("共完成 ").append(totalCount).append(" 次检验，");

        if (avgPassRate != null) {
            sb.append("平均合格率 ").append(avgPassRate.setScale(2, RoundingMode.HALF_UP)).append("%");
        } else {
            sb.append("暂无合格率数据");
        }

        if (qualityIssues > 0) {
            sb.append("，发现 ").append(qualityIssues).append(" 个质量问题");
        } else {
            sb.append("，无质量问题");
        }

        return sb.toString();
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "period":
                return "请问要查询哪个时间段的统计？今日(today)、本周(week)、本月(month)还是本年(year)？";
            case "dateFrom":
                return "请问自定义查询的开始日期是？";
            case "dateTo":
                return "请问自定义查询的结束日期是？";
            case "productTypeId":
                return "请问要查询哪种产品类型的统计？";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "period":
                return "统计周期";
            case "dateFrom":
                return "开始日期";
            case "dateTo":
                return "结束日期";
            case "productTypeId":
                return "产品类型ID";
            case "includeDetails":
                return "包含详细数据";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
