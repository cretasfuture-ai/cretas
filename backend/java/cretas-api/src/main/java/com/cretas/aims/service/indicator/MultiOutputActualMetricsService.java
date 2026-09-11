package com.cretas.aims.service.indicator;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only calculator for a completed plan which has more than one terminal
 * output.  The input pool belongs to the plan, while output and settled cost
 * belong to an output line.  Keeping that distinction here prevents the
 * common (and very serious) error of charging a shared input once per SKU.
 */
@Service
public class MultiOutputActualMetricsService {
    public enum Metric { YIELD_RATE, GOOD_OUTPUT_RATE, UNIT_COST, TOTAL_COST }

    public enum QualityStatus { NONE_CONFIRMED, COUNTED }

    /** One authoritative, immutable output line from a completed settlement. */
    public record Output(String planId, String batchNumber, String productTypeId,
                         BigDecimal outputKg, BigDecimal goodOutputKg,
                         BigDecimal defectKg, QualityStatus qualityStatus,
                         BigDecimal allocatedCost) {
        public Output {
            outputKg = clean(outputKg);
            goodOutputKg = clean(goodOutputKg);
            defectKg = clean(defectKg);
            allocatedCost = clean(allocatedCost);
        }
    }

    /** The first process input for a plan, measured once per plan. */
    public record Plan(String planId, BigDecimal inputKg, BigDecimal authoritativeCost) {
        public Plan {
            inputKg = clean(inputKg);
            authoritativeCost = clean(authoritativeCost);
        }
    }

    /** Returns null when the evidence is incomplete or internally inconsistent. */
    public BigDecimal aggregate(Collection<Plan> plans, Collection<Output> outputs, Metric metric) {
        if (plans == null || outputs == null || plans.isEmpty() || outputs.isEmpty() || metric == null) return null;
        Map<String, Plan> uniquePlans = uniquePlans(plans);
        if (uniquePlans == null) return null;
        Totals totals = totals(uniquePlans, outputs);
        if (totals == null) return null;
        return value(totals, metric);
    }

    /** Same calculation grouped by terminal product, with the shared plan denominator retained. */
    public BigDecimal byProduct(Collection<Plan> plans, Collection<Output> outputs,
                                String productTypeId, Metric metric) {
        if (productTypeId == null || productTypeId.isBlank() || outputs == null) return null;
        var selected = outputs.stream().filter(o -> productTypeId.equals(o.productTypeId())).toList();
        if (selected.isEmpty()) return null;
        Map<String, Plan> uniquePlans = uniquePlans(plans);
        if (uniquePlans == null) return null;
        Totals totals = totals(uniquePlans, selected);
        return totals == null ? null : value(totals, metric);
    }

    private Map<String, Plan> uniquePlans(Collection<Plan> plans) {
        Map<String, Plan> result = new LinkedHashMap<>();
        for (Plan plan : plans) {
            if (plan == null || blank(plan.planId()) || !positive(plan.inputKg())
                    || plan.authoritativeCost() == null || plan.authoritativeCost().signum() < 0) return null;
            Plan prior = result.putIfAbsent(plan.planId(), plan);
            if (prior != null && (!same(prior.inputKg(), plan.inputKg())
                    || !same(prior.authoritativeCost(), plan.authoritativeCost()))) return null;
        }
        return result;
    }

    private Totals totals(Map<String, Plan> plans, Collection<Output> outputs) {
        BigDecimal input = BigDecimal.ZERO, output = BigDecimal.ZERO, good = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO, inspected = BigDecimal.ZERO;
        var seen = new java.util.HashSet<String>();
        for (Plan plan : plans.values()) input = input.add(plan.inputKg());
        for (Output row : outputs) {
            if (row == null || blank(row.planId()) || blank(row.batchNumber()) || blank(row.productTypeId())
                    || !plans.containsKey(row.planId()) || !positive(row.outputKg())
                    || row.allocatedCost() == null || row.allocatedCost().signum() < 0
                    || !seen.add(row.planId() + "\u0000" + row.batchNumber() + "\u0000" + row.productTypeId())) return null;
            if (row.qualityStatus() == null) return null;
            BigDecimal rowGood;
            BigDecimal rowDefect;
            if (row.qualityStatus() == QualityStatus.NONE_CONFIRMED) {
                if (row.defectKg() != null && row.defectKg().signum() != 0) return null;
                rowGood = row.outputKg(); rowDefect = BigDecimal.ZERO;
            } else {
                if (row.goodOutputKg() == null || row.defectKg() == null
                        || row.goodOutputKg().signum() < 0 || row.defectKg().signum() < 0
                        || !same(row.outputKg(), row.goodOutputKg().add(row.defectKg()))) return null;
                rowGood = row.goodOutputKg(); rowDefect = row.defectKg();
            }
            output = output.add(row.outputKg());
            good = good.add(rowGood);
            inspected = inspected.add(rowGood).add(rowDefect);
            cost = cost.add(row.allocatedCost());
        }
        // The authoritative plan pool is required even when the line costs happen to sum to it.
        BigDecimal planCost = plans.values().stream().map(Plan::authoritativeCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!same(planCost, cost)) return null;
        return new Totals(input, output, good, inspected, cost);
    }

    private BigDecimal value(Totals t, Metric metric) {
        return switch (metric) {
            case YIELD_RATE -> divide(t.output.multiply(BigDecimal.valueOf(100)), t.input);
            case GOOD_OUTPUT_RATE -> divide(t.good.multiply(BigDecimal.valueOf(100)), t.inspected);
            case UNIT_COST -> divide(t.cost, t.output);
            case TOTAL_COST -> t.cost.setScale(4, RoundingMode.HALF_UP);
        };
    }

    private record Totals(BigDecimal input, BigDecimal output, BigDecimal good,
                          BigDecimal inspected, BigDecimal cost) { }
    private static BigDecimal clean(BigDecimal v) { return v == null ? null : v.stripTrailingZeros(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean positive(BigDecimal v) { return v != null && v.signum() > 0; }
    private static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.subtract(b).abs().compareTo(new BigDecimal("0.000001")) <= 0;
    }
    private static BigDecimal divide(BigDecimal n, BigDecimal d) {
        return d.signum() > 0 ? n.divide(d, 4, RoundingMode.HALF_UP) : null;
    }
}
