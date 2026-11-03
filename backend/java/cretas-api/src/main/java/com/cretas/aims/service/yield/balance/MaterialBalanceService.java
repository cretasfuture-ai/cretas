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
