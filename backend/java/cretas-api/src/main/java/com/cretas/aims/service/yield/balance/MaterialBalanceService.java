package com.cretas.aims.service.yield.balance;

import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator.Qty;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator.Result;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator.Thresholds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 物料平衡表: 在 {@code production_reports} (YIELD) 上按「计划 → 工序」汇合, 每个<b>工序任务</b>先单独过
 * {@link MaterialBalanceCalculator} (三阶段 INPUT/OUTPUT 报工要合到同一任务才有完整的投入与去向),
 * 再按工序合计、同一套阈值判色。App 报工与 web 逐道录入都落这张表, 所以两路天然同一口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialBalanceService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final TypeReference<List<Map<String, Object>>> BYPRODUCT_TYPE = new TypeReference<>() {};

    private final ProductionReportRepository reportRepo;
    private final ProcessSecretAggregateExclusions aggregateExclusions;
    private final MaterialBalanceThresholdProvider thresholdProvider;
    private final ObjectMapper objectMapper;

    public MaterialBalanceTableDTO table(String factoryId, LocalDate startDate, LocalDate endDate, String planId) {
        LocalDate start = startDate != null ? startDate : LocalDate.of(1900, 1, 1);
        LocalDate end = endDate != null ? endDate : LocalDate.of(2999, 12, 31);
        ProcessSecretAggregateExclusions.Scope excluded =
                aggregateExclusions.forCurrentCaller(factoryId, "material balance table");
        List<Map<String, Object>> rows = reportRepo.findYieldReportsForBalance(factoryId, start, end,
                planId == null ? "" : planId, excluded.productTypeIdsOrSentinel(), excluded.planIdsOrSentinel());
        Thresholds t = thresholdProvider.forFactory(factoryId);
        return build(rows, t);
    }

    /** 按批次拆开的物料平衡: 每个块只含一个批次 (批次号原样), 同一套阈值与保密排除。 */
    public record BatchBalance(BigDecimal warnPct, BigDecimal redPct, List<MaterialBalanceTableDTO.PlanBlock> blocks) {}

    /** 已按工厂、日期和工艺保密范围读取的 YIELD 报工。 */
    public static final class BatchReports {
        private final String factoryId;
        private final List<Map<String, Object>> rows;

        private BatchReports(String factoryId, List<Map<String, Object>> rows) {
            this.factoryId = factoryId;
            this.rows = rows;
        }

        public int size() {
            return rows.size();
        }

        public BatchReports withBatchNumber() {
            return new BatchReports(factoryId, rows.stream().filter(MaterialBalanceService::hasBatchNumber).toList());
        }

        public BatchReports forProducts(Predicate<String> matches) {
            return new BatchReports(factoryId, rows.stream().filter(row -> matches.test(str(row.get("product_name")))).toList());
        }

        public List<String> processNames() {
            return rows.stream().map(row -> str(row.get("process_name"))).distinct().toList();
        }

        public BatchReports forProcesses(Predicate<String> matches) {
            return new BatchReports(factoryId, rows.stream().filter(row -> matches.test(str(row.get("process_name")))).toList());
        }

        public BatchReports since(LocalDate start) {
            return new BatchReports(factoryId, rows.stream().filter(row -> {
                LocalDate date = toDate(row.get("report_date"));
                return date != null && !date.isBefore(start);
            }).toList());
        }

        public LocalDate latestDate() {
            LocalDate latest = null;
            for (Map<String, Object> row : rows) {
                LocalDate date = toDate(row.get("report_date"));
                if (date != null && (latest == null || date.isAfter(latest))) latest = date;
            }
            return latest;
        }
    }

    private static LocalDate toDate(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        return null;
    }

    public BatchBalance blocksByBatch(String factoryId, LocalDate startDate, LocalDate endDate) {
        return blocksByBatch(factoryId, startDate, endDate, null);
    }

    /**
     * @param warnOverridePct 黄线覆盖值 (百分数, 如 3): 非空时用它作黄线 (红线取原红线与它的较大者), 其余同上。
     *                        供对话「损耗异常」与「发现的问题」页用同一个本厂告警阈值 (BP 问题 28)。
     */
    public BatchBalance blocksByBatch(String factoryId, LocalDate startDate, LocalDate endDate,
                                      BigDecimal warnOverridePct) {
        return blocksByBatch(batchReports(factoryId, startDate, endDate), warnOverridePct);
    }

    public BatchReports batchReports(String factoryId, LocalDate startDate, LocalDate endDate) {
        ProcessSecretAggregateExclusions.Scope excluded =
                aggregateExclusions.forCurrentCaller(factoryId, "material balance by batch");
        List<Map<String, Object>> rows = reportRepo.findYieldReportsForBalance(factoryId, startDate, endDate,
                "", excluded.productTypeIdsOrSentinel(), excluded.planIdsOrSentinel());
        return new BatchReports(factoryId, rows);
    }

    private static boolean hasBatchNumber(Map<String, Object> row) {
        String batchNumber = str(row.get("batch_number"));
        return batchNumber != null && !batchNumber.isBlank();
    }

    public BatchBalance blocksByBatch(BatchReports reports, BigDecimal warnOverridePct) {
        Thresholds t = thresholdProvider.forFactory(reports.factoryId);
        if (warnOverridePct != null && warnOverridePct.signum() > 0) {
            BigDecimal warn = warnOverridePct.divide(HUNDRED);
            t = new Thresholds(warn, t.red().max(warn));
        }
        Map<String, List<Map<String, Object>>> byBatch = new LinkedHashMap<>();
        for (Map<String, Object> r : reports.rows) {
            if (!hasBatchNumber(r)) continue;
            String no = str(r.get("batch_number"));
            byBatch.computeIfAbsent(no, k -> new ArrayList<>()).add(r);
        }
        List<MaterialBalanceTableDTO.PlanBlock> blocks = new ArrayList<>();
        for (List<Map<String, Object>> group : byBatch.values()) {
            blocks.addAll(build(group, t).getPlans());
        }
        return new BatchBalance(t.warn().multiply(HUNDRED).stripTrailingZeros(),
                t.red().multiply(HUNDRED).stripTrailingZeros(), blocks);
    }

    public MaterialBalanceTableDTO build(List<Map<String, Object>> rows, Thresholds t) {
        // plan -> process -> task -> reports
        Map<String, Map<String, Map<Object, List<Map<String, Object>>>>> tree = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String plan = str(r.get("plan_id"));
            String planKey = plan == null ? "" : plan;
            String processKey = str(r.get("process_id"));
            tree.computeIfAbsent(planKey, k -> new LinkedHashMap<>())
                    .computeIfAbsent(processKey, k -> new LinkedHashMap<>())
                    .computeIfAbsent(taskKey(r), k -> new ArrayList<>())
                    .add(r);
        }
        List<MaterialBalanceTableDTO.PlanBlock> plans = new ArrayList<>();
        for (Map<String, Map<Object, List<Map<String, Object>>>> processes : tree.values()) {
            Map<String, Object> first = processes.values().iterator().next().values().iterator().next().get(0);
            Set<String> batches = new LinkedHashSet<>();
            List<MaterialBalanceTableDTO.Row> out = new ArrayList<>();
            for (Map<Object, List<Map<String, Object>>> tasks : processes.values()) {
                List<Result> parts = new ArrayList<>();
                Map<String, Object> head = tasks.values().iterator().next().get(0);
                for (List<Map<String, Object>> reports : tasks.values()) {
                    parts.add(taskResult(reports, t));
                    for (Map<String, Object> r : reports) {
                        if (str(r.get("batch_number")) != null) batches.add(str(r.get("batch_number")));
                    }
                }
                out.add(toRow(head, MaterialBalanceCalculator.aggregate(parts, t), tasks.size()));
            }
            out.sort((a, b) -> Integer.compare(
                    a.getProcessOrder() == null ? 0 : a.getProcessOrder(),
                    b.getProcessOrder() == null ? 0 : b.getProcessOrder()));
            plans.add(MaterialBalanceTableDTO.PlanBlock.builder()
                    .planId(str(first.get("plan_id")))
                    .planNumber(str(first.get("plan_number")))
                    .productName(str(first.get("product_name")))
                    .batchNumbers(new ArrayList<>(batches))
                    .rows(out)
                    .build());
        }
        return MaterialBalanceTableDTO.builder()
                .warnPct(t.warn().multiply(HUNDRED).stripTrailingZeros())
                .redPct(t.red().multiply(HUNDRED).stripTrailingZeros())
                .plans(plans)
                .build();
    }

    /**
     * 同一道工序的归并键。App 路的报工带工序任务 id; web 逐道录入的报工没有任务, 同一批次同一道工序
     * (序号 + 工序名) 的报工 (含三阶段 INPUT / OUTPUT) 算同一份。
     */
    private static Object taskKey(Map<String, Object> r) {
        if (r.get("task_id") != null) return "T" + r.get("task_id");
        return "B" + r.get("batch_id") + "|" + r.get("process_order") + "|" + r.get("process_name");
    }

    /** 一个工序任务下的全部报工合成一份投入 / 去向, 过计算器。 */
    private Result taskResult(List<Map<String, Object>> reports, Thresholds t) {
        Map<String, Object> head = reports.get(0);
        BigDecimal input = null;
        BigDecimal output = null;
        BigDecimal waste = null;
        BigDecimal sample = null;
        String inUnit = null;
        String outUnit = null;
        List<Qty> byproducts = new ArrayList<>();
        for (Map<String, Object> r : reports) {
            input = add(input, dec(r.get("input_quantity")));
            output = add(output, dec(r.get("output_quantity")));
            waste = add(waste, dec(r.get("waste_quantity")));
            sample = add(sample, dec(r.get("sample_retain_quantity")));
            if (inUnit == null) inUnit = str(r.get("input_unit"));
            if (outUnit == null) outUnit = str(r.get("output_unit"));
            byproducts.addAll(parseByproducts(str(r.get("byproducts_json"))));
        }
        if (inUnit == null) inUnit = str(head.get("wp_unit"));
        if (outUnit == null) outUnit = str(head.get("wp_output_unit"));
        return MaterialBalanceCalculator.compute(input, inUnit, output, outUnit, byproducts, waste, sample, t);
    }

    private List<Qty> parseByproducts(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) return List.of();
        try {
            List<Qty> out = new ArrayList<>();
            for (Map<String, Object> m : objectMapper.readValue(json, BYPRODUCT_TYPE)) {
                if (m == null) continue;
                out.add(new Qty(dec(m.get("quantity")), str(m.get("unit"))));
            }
            return out;
        } catch (Exception e) {
            // 读不懂的副产明细不能当 0 往下算 (会把差额算成「未解释」), 如实抛出让这张表标出来
            throw new IllegalStateException("副产物明细无法解析: " + json, e);
        }
    }

    private static MaterialBalanceTableDTO.Row toRow(Map<String, Object> head, Result r, int taskCount) {
        String note = switch (r.level()) {
            case UNIT_INCOMPARABLE -> "单位不可比: 投入与产出(或副产)单位换算不了, 不猜";
            case NO_INPUT -> "有产出但没登记任何投入";
            default -> null;
        };
        boolean cmp = r.comparable();
        return MaterialBalanceTableDTO.Row.builder()
                .processName(str(head.get("process_name")))
                .processOrder(head.get("process_order") == null ? null : ((Number) head.get("process_order")).intValue())
                .level(r.level().name())
                .unit(r.unit())
                .input(cmp ? scale(r.input()) : null)
                .output(cmp ? scale(r.output()) : null)
                .byproducts(cmp ? scale(r.byproducts()) : null)
                .confirmedLoss(cmp ? scale(r.confirmedLoss()) : null)
                .unexplained(cmp ? scale(r.unexplained()) : null)
                .unexplainedPct(cmp ? r.unexplainedPct().multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP) : null)
                .taskCount(taskCount)
                .note(note)
                .build();
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        if (b == null) return a;
        return a == null ? b : a.add(b);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return null;
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
