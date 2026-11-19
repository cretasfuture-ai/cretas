package com.cretas.aims.service.impl;

import com.cretas.aims.dto.analytics.*;
import com.cretas.aims.repository.ProductionReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionAnalyticsServiceImpl {

    private final ProductionReportRepository reportRepo;

    // ==================== 生产分析 ====================

    public ProductionDashboardResponse getProductionDashboard(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> dailyTrend = reportRepo.getDailyProductionTrend(factoryId, startDate, endDate);
        List<Map<String, Object>> byProduct = reportRepo.getProductBreakdown(factoryId, startDate, endDate);
        List<Map<String, Object>> byProcess = mergeProcessRows(reportRepo.getProcessBreakdown(factoryId, startDate, endDate));
        Map<String, Object> summary = reportRepo.getProgressSummary(factoryId, startDate, endDate);

        // 计算环比
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        LocalDate prevEnd = startDate.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1);
        Map<String, Object> prevSummary = reportRepo.getProgressSummary(factoryId, prevStart, prevEnd);

        List<KPIItem> kpis = buildProductionKPIs(summary, prevSummary);

        return ProductionDashboardResponse.builder()
                .kpis(kpis)
                .dailyTrend(dailyTrend)
                .byProduct(byProduct)
                .byProcess(byProcess)
                .build();
    }

    public List<Map<String, Object>> getDailyTrend(String factoryId, LocalDate startDate, LocalDate endDate) {
        return reportRepo.getDailyProductionTrend(factoryId, startDate, endDate);
    }

    public List<Map<String, Object>> getByProduct(String factoryId, LocalDate startDate, LocalDate endDate) {
        return reportRepo.getProductBreakdown(factoryId, startDate, endDate);
    }

    /** 工序 × 单位一行 (见 {@link #mergeProcessRows})。 */
    public List<Map<String, Object>> getByProcess(String factoryId, LocalDate startDate, LocalDate endDate) {
        return mergeProcessRows(reportRepo.getProcessBreakdown(factoryId, startDate, endDate));
    }

    /** 不良率状态: 没人清点过不良 (不知道) / 已清点 / 清点过但产量为 0 (算不出)。 */
    public static final String DEFECT_NOT_COUNTED = "NOT_COUNTED";
    public static final String DEFECT_COUNTED = "COUNTED";
    public static final String DEFECT_NO_OUTPUT = "NO_OUTPUT";

    /**
     * 把仓库按 (来源, 工序, 单位) 拆的行合并成「工序 × 单位」一行 (不同单位永远不相加)。复审 M5:
     * <ul>
     *   <li>output = 各来源产出之和 (同单位), report_count / defect_counted_count 同理, sources 列出来源。</li>
     *   <li>defect 只算清点过不良的笔; 一笔都没清点 ⇒ defect = null、defectRate = null、
     *       defectStatus = NOT_COUNTED (「无数据」), 与「清点了、零不良」(defectRate = 0, COUNTED) 分开。</li>
     *   <li>不良率分母 = 清点过的笔的总产量: 称重 YIELD 的产出是良品 (不含不良) ⇒ 分母 = 良品 + 不良;
     *       进度 PROGRESS 沿用原口径 ⇒ 分母 = 产出。分母为 0 ⇒ null + NO_OUTPUT。</li>
     * </ul>
     */
    static List<Map<String, Object>> mergeProcessRows(List<Map<String, Object>> rows) {
        Map<List<Object>, Map<String, Object>> merged = new LinkedHashMap<>();
        Map<List<Object>, BigDecimal[]> rateParts = new HashMap<>();   // [defect, base]
        for (Map<String, Object> r : rows) {
            Object process = r.get("process_category");
            Object unit = r.get("unit");
            List<Object> key = Arrays.asList(process, unit);
            Map<String, Object> m = merged.computeIfAbsent(key, k -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("process_category", process);
                n.put("unit", unit);
                n.put("output", BigDecimal.ZERO);
                n.put("good", null);
                n.put("defect", null);
                n.put("report_count", 0L);
                n.put("defect_counted_count", 0L);
                n.put("sources", new ArrayList<String>());
                return n;
            });
            BigDecimal output = dec(r.get("output"));
            BigDecimal good = dec(r.get("good"));
            BigDecimal defect = dec(r.get("defect"));
            BigDecimal countedOutput = dec(r.get("counted_output"));
            long counted = r.get("defect_counted_count") == null ? 0L : ((Number) r.get("defect_counted_count")).longValue();
            m.put("output", ((BigDecimal) m.get("output")).add(output == null ? BigDecimal.ZERO : output));
            if (good != null) m.put("good", m.get("good") == null ? good : ((BigDecimal) m.get("good")).add(good));
            m.put("report_count", (Long) m.get("report_count")
                    + (r.get("report_count") == null ? 0L : ((Number) r.get("report_count")).longValue()));
            m.put("defect_counted_count", (Long) m.get("defect_counted_count") + counted);
            @SuppressWarnings("unchecked")
            List<String> sources = (List<String>) m.get("sources");
            if (r.get("source") != null) sources.add(r.get("source").toString());
            if (counted > 0 && defect != null) {
                m.put("defect", m.get("defect") == null ? defect : ((BigDecimal) m.get("defect")).add(defect));
                BigDecimal base = countedOutput == null ? BigDecimal.ZERO : countedOutput;
                if ("YIELD".equals(r.get("source"))) base = base.add(defect);
                BigDecimal[] parts = rateParts.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                parts[0] = parts[0].add(defect);
                parts[1] = parts[1].add(base);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<List<Object>, Map<String, Object>> e : merged.entrySet()) {
            Map<String, Object> m = e.getValue();
            BigDecimal[] parts = rateParts.get(e.getKey());
            if (parts == null) {
                m.put("defectRate", null);
                m.put("defectStatus", DEFECT_NOT_COUNTED);
            } else {
                BigDecimal rate = defectRate(parts[0], parts[1]);
                m.put("defectRate", rate);
                m.put("defectStatus", rate == null ? DEFECT_NO_OUTPUT : DEFECT_COUNTED);
            }
            out.add(m);
        }
        return out;
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(v.toString());
    }

    /** 零分母 / 缺值 → null, 其余 4 位小数 (0.0125 = 1.25%)。 */
    static BigDecimal defectRate(Object defect, Object base) {
        if (defect == null || base == null) return null;
        BigDecimal d = new BigDecimal(defect.toString());
        BigDecimal o = new BigDecimal(base.toString());
        if (o.signum() <= 0) return null;
        return d.divide(o, 4, RoundingMode.HALF_UP);
    }

    // ==================== 人效分析 ====================

    public EfficiencyDashboardResponse getEfficiencyDashboard(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> workerRanking = reportRepo.getWorkerEfficiencyRanking(factoryId, startDate, endDate);
        List<Map<String, Object>> dailyTrend = reportRepo.getDailyEfficiencyTrend(factoryId, startDate, endDate);
        List<Map<String, Object>> hoursByProduct = reportRepo.getHoursBreakdownByProduct(factoryId, startDate, endDate);
        List<Map<String, Object>> workerProcessCross = reportRepo.getWorkerProcessCross(factoryId, startDate, endDate);

        Map<String, Object> summary = reportRepo.getProgressSummary(factoryId, startDate, endDate);
        Map<String, Object> hoursSummary = reportRepo.getHoursSummary(factoryId, startDate, endDate);

        // 计算环比
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        LocalDate prevEnd = startDate.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1);
        Map<String, Object> prevSummary = reportRepo.getProgressSummary(factoryId, prevStart, prevEnd);
        Map<String, Object> prevHoursSummary = reportRepo.getHoursSummary(factoryId, prevStart, prevEnd);

        List<KPIItem> kpis = buildEfficiencyKPIs(summary, hoursSummary, prevSummary, prevHoursSummary, workerRanking);

        // 为排名数据添加人效字段 (TupleBackedMap is immutable, so enrichWorkerRanking returns new list)
        List<Map<String, Object>> enrichedRanking = enrichWorkerRanking(workerRanking);

        return EfficiencyDashboardResponse.builder()
                .kpis(kpis)
                .workerRanking(enrichedRanking)
                .dailyTrend(enrichDailyEfficiencyTrend(dailyTrend))
                .hoursByProduct(hoursByProduct)
                .workerProcessCross(workerProcessCross)
                .build();
    }

    public List<Map<String, Object>> getWorkerRanking(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> ranking = reportRepo.getWorkerEfficiencyRanking(factoryId, startDate, endDate);
        return enrichWorkerRanking(ranking);
    }

    public List<Map<String, Object>> getEfficiencyTrend(String factoryId, LocalDate startDate, LocalDate endDate) {
        return enrichDailyEfficiencyTrend(reportRepo.getDailyEfficiencyTrend(factoryId, startDate, endDate));
    }

    public List<Map<String, Object>> getHoursByProduct(String factoryId, LocalDate startDate, LocalDate endDate) {
        return reportRepo.getHoursBreakdownByProduct(factoryId, startDate, endDate);
    }

    public List<Map<String, Object>> getWorkerProcessCross(String factoryId, LocalDate startDate, LocalDate endDate) {
        return reportRepo.getWorkerProcessCross(factoryId, startDate, endDate);
    }

    // ==================== 内部方法 ====================

    private List<KPIItem> buildProductionKPIs(Map<String, Object> current, Map<String, Object> previous) {
        double totalOutput = toDouble(current.get("total_output"));
        double totalGood = toDouble(current.get("total_good"));
        double totalDefect = toDouble(current.get("total_defect"));
        long reportCount = toLong(current.get("report_count"));

        double yieldRate = totalOutput > 0 ? (totalGood / totalOutput) * 100 : 0;
        double defectRate = totalOutput > 0 ? (totalDefect / totalOutput) * 100 : 0;

        double prevOutput = toDouble(previous.get("total_output"));
        double prevGood = toDouble(previous.get("total_good"));
        double prevDefect = toDouble(previous.get("total_defect"));
        long prevCount = toLong(previous.get("report_count"));

        double prevYield = prevOutput > 0 ? (prevGood / prevOutput) * 100 : 0;
        double prevDefectRate = prevOutput > 0 ? (prevDefect / prevOutput) * 100 : 0;

        return List.of(
                buildKPI("total_output", "总产出量", totalOutput, "", prevOutput, "purple"),
                buildKPI("yield_rate", "综合良率", round(yieldRate, 1), "%", prevYield, "pink"),
                buildKPI("defect_rate", "综合不良率", round(defectRate, 1), "%", prevDefectRate, "blue"),
                buildKPI("report_count", "报工总数", (double) reportCount, "", (double) prevCount, "green")
        );
    }

    private List<KPIItem> buildEfficiencyKPIs(
            Map<String, Object> progress, Map<String, Object> hours,
            Map<String, Object> prevProgress, Map<String, Object> prevHours,
            List<Map<String, Object>> workerRanking) {

        double totalOutput = toDouble(progress.get("total_output"));
        double totalMinutes = toDouble(hours.get("total_minutes"));
        double totalHours = totalMinutes / 60.0;
        double avgEfficiency = totalHours > 0 ? totalOutput / totalHours : 0;

        // 参与人数 (distinct workers from ranking)
        int workerCount = workerRanking != null ? workerRanking.size() : 0;

        // 平均不良率
        double totalGood = toDouble(progress.get("total_good"));
        double defectRate = totalOutput > 0 ? ((totalOutput - totalGood) / totalOutput) * 100 : 0;

        // 环比
        double prevOutput = toDouble(prevProgress.get("total_output"));
        double prevMinutes = toDouble(prevHours.get("total_minutes"));
        double prevHoursVal = prevMinutes / 60.0;
        double prevEfficiency = prevHoursVal > 0 ? prevOutput / prevHoursVal : 0;
        double prevGood = toDouble(prevProgress.get("total_good"));
        double prevDefectRate = prevOutput > 0 ? ((prevOutput - prevGood) / prevOutput) * 100 : 0;

        return List.of(
                buildKPI("avg_efficiency", "平均人效(产出/时)", round(avgEfficiency, 1), "产出/时", prevEfficiency, "purple"),
                buildKPI("total_hours", "总工时", round(totalHours, 1), "小时", prevHoursVal, "pink"),
                buildKPI("worker_count", "参与人数", (double) workerCount, "人", 0.0, "blue"),
                buildKPI("defect_rate", "平均不良率", round(defectRate, 1), "%", prevDefectRate, "green")
        );
    }

    private KPIItem buildKPI(String key, String label, double value, String unit, double prevValue, String gradient) {
        double change = 0;
        String changeType = "flat";
        if (prevValue > 0) {
            change = round(((value - prevValue) / prevValue) * 100, 1);
            changeType = change > 0 ? "up" : (change < 0 ? "down" : "flat");
        } else if (value > 0) {
            change = 100.0;
            changeType = "up";
        }
        return KPIItem.builder()
                .key(key)
                .label(label)
                .value(value)
                .unit(unit)
                .change(change)
                .changeType(changeType)
                .gradient(gradient)
                .build();
    }

    private List<Map<String, Object>> enrichWorkerRanking(List<Map<String, Object>> ranking) {
        if (ranking == null) return Collections.emptyList();
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> row : ranking) {
            Map<String, Object> copy = new HashMap<>(row);
            double output = toDouble(row.get("total_output"));
            double minutes = toDouble(row.get("total_minutes"));
            double hours = minutes / 60.0;
            double efficiency = hours > 0 ? round(output / hours, 1) : 0;
            double good = toDouble(row.get("total_good"));
            double yieldRate = output > 0 ? round((good / output) * 100, 1) : 0;
            copy.put("efficiency", efficiency);
            copy.put("total_hours", round(hours, 1));
            copy.put("yield_rate", yieldRate);
            enriched.add(copy);
        }
        return enriched;
    }

    private List<Map<String, Object>> enrichDailyEfficiencyTrend(List<Map<String, Object>> trend) {
        if (trend == null) return Collections.emptyList();
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> row : trend) {
            Map<String, Object> copy = new HashMap<>(row);
            double output = toDouble(row.get("total_output"));
            double minutes = toDouble(row.get("total_minutes"));
            double hours = minutes / 60.0;
            double efficiency = hours > 0 ? round(output / hours, 1) : 0;
            copy.put("efficiency", efficiency);
            copy.put("total_hours", round(hours, 1));
            enriched.add(copy);
        }
        return enriched;
    }

    private double toDouble(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long toLong(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double round(double value, int places) {
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }
}
