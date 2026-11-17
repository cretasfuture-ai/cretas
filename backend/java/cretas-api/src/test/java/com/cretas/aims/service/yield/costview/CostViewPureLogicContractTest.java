package com.cretas.aims.service.yield.costview;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CostViewPureLogicContractTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static LaborProcessAllocator.ProcessHours ph(int o, String h) {
        return new LaborProcessAllocator.ProcessHours(o, "P" + o, h == null ? null : bd(h));
    }

    // ---- 人工分摊 ----
    @Test
    void allocation_tailGoesToLastProcessWithHours_andSumsToTotal() {
        var r = LaborProcessAllocator.allocate(bd("100.00"), List.of(ph(1, "1"), ph(2, "1"), ph(3, "1"), ph(4, "0")));
        assertEquals(LaborProcessAllocator.Status.ALLOCATED, r.status());
        assertEquals(bd("33.33"), r.shares().get(0).amount());
        assertEquals(bd("33.33"), r.shares().get(1).amount());
        assertEquals(bd("33.34"), r.shares().get(2).amount()); // 尾差归第 3 道 (最后一道有人时)
        assertEquals(0, r.shares().get(3).amount().signum()); // 0 人时的末道不吃尾差
        BigDecimal sum = r.shares().stream().map(LaborProcessAllocator.ProcessShare::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, bd("100.00").compareTo(sum));
    }

    @Test
    void allocation_unknownOrNegativeHours_yieldsNullNotZero() {
        var missing = LaborProcessAllocator.allocate(bd("10"), List.of(ph(1, "1"), ph(2, null)));
        assertEquals(LaborProcessAllocator.Status.HOURS_UNKNOWN, missing.status());
        assertTrue(missing.shares().stream().allMatch(s -> s.amount() == null));
        var neg = LaborProcessAllocator.allocate(bd("10"), List.of(ph(1, "2"), ph(2, "-1")));
        assertEquals(LaborProcessAllocator.Status.HOURS_NEGATIVE, neg.status());
        var zero = LaborProcessAllocator.allocate(bd("10"), List.of(ph(1, "0")));
        assertEquals(LaborProcessAllocator.Status.NO_POSITIVE_HOURS, zero.status());
        var noTotal = LaborProcessAllocator.allocate(null, List.of(ph(1, "1")));
        assertEquals(LaborProcessAllocator.Status.LABOR_TOTAL_UNKNOWN, noTotal.status());
    }

    // ---- 加权单位成本 ----
    private static ProductUnitCostAggregator.OrderPoint op(String no, String unit, String m, String l, String g) {
        return new ProductUnitCostAggregator.OrderPoint(no, no, "P1", "产品", unit,
                m == null ? null : bd(m), l == null ? null : bd(l), g == null ? null : bd(g));
    }

    @Test
    void weighted_isSumOverSum_notAverageOfUnitCosts() {
        // 单位成本 10 与 2; 简单平均 6, 加权 (100+20)/(10+10)=6 → 换权重区分: 良品 10 与 90
        var g = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "60", "40", "10"), op("B", "kg", "100", "80", "90"))).get(0);
        assertEquals(ProductUnitCostAggregator.BASIS, g.basis());
        assertEquals(bd("2.8000"), g.weightedUnitCost()); // 280/100, 而非 (10+2)/2=6
        assertEquals(2, g.eligibleCount());
    }

    @Test
    void differentUnits_areSeparateGroups_andIneligibleOrdersExcluded() {
        var groups = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "10", "10", "10"), op("B", "箱", "10", "10", "10"),
                op("C", "kg", null, "10", "10"), op("D", "kg", "10", "10", "0")));
        assertEquals(2, groups.size());
        var kg = groups.get(0);
        assertEquals(1, kg.eligibleCount());
        assertEquals(bd("2.0000"), kg.weightedUnitCost());
        assertEquals("MATERIAL_UNKNOWN", kg.orders().get(1).ineligibleReason());
        assertEquals("NO_POSITIVE_GOOD_OUTPUT", kg.orders().get(2).ineligibleReason());
    }

    @Test
    void noEligibleOrders_weightedIsNullNotZero() {
        var g = ProductUnitCostAggregator.aggregate(List.of(op("A", "kg", "10", null, "10"))).get(0);
        assertNull(g.weightedUnitCost());
    }

    // ---- 信号 ----
    @Test
    void spread_needsThreeSamples_andBoundaryIsInclusive() {
        // 单位成本 1.00 / 1.20 / 1.10 → 差 20% 恰好报
        var three = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "50", "50", "100"), op("B", "kg", "60", "60", "100"), op("C", "kg", "55", "55", "100")))
                .get(0);
        assertTrue(three.signals().stream().anyMatch(s -> s.code().equals("UNIT_COST_SPREAD")));
        // 只有 2 个样本不报
        var two = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "50", "50", "100"), op("B", "kg", "100", "100", "100"))).get(0);
        assertTrue(two.signals().stream().noneMatch(s -> s.code().equals("UNIT_COST_SPREAD")));
        // 差 19.99% 不报
        var below = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "50", "50", "100"), op("B", "kg", "59.99", "59.99", "100"), op("C", "kg", "55", "55", "100")))
                .get(0);
        assertTrue(below.signals().stream().noneMatch(s -> s.code().equals("UNIT_COST_SPREAD")));
    }

    @Test
    void shareSignals_boundaries() {
        // 材料恰 10% 不报; 9% 报
        var atTen = CostSignalEvaluator.evaluate(List.of(
                new CostSignalEvaluator.Sample("1", "1", bd("10"), bd("90"), bd("1"))));
        assertTrue(atTen.stream().noneMatch(s -> s.code().equals("MATERIAL_SHARE_LOW")));
        var nine = CostSignalEvaluator.evaluate(List.of(
                new CostSignalEvaluator.Sample("1", "1", bd("9"), bd("91"), bd("1"))));
        assertTrue(nine.stream().anyMatch(s -> s.code().equals("MATERIAL_SHARE_LOW")));
        // 人工恰 65% 不报; 66% 报
        var l65 = CostSignalEvaluator.evaluate(List.of(
                new CostSignalEvaluator.Sample("1", "1", bd("35"), bd("65"), bd("1"))));
        assertTrue(l65.stream().noneMatch(s -> s.code().equals("LABOR_SHARE_HIGH")));
        var l66 = CostSignalEvaluator.evaluate(List.of(
                new CostSignalEvaluator.Sample("1", "1", bd("34"), bd("66"), bd("1"))));
        assertTrue(l66.stream().anyMatch(s -> s.code().equals("LABOR_SHARE_HIGH")));
        // 人工为 0
        var zero = CostSignalEvaluator.evaluate(List.of(
                new CostSignalEvaluator.Sample("1", "1", bd("50"), BigDecimal.ZERO, bd("1"))));
        assertTrue(zero.stream().anyMatch(s -> s.code().equals("LABOR_ZERO")));
    }

    // ---- 工序人时汇总 ----
    @Test
    void processHours_nullMinutesMakesThatProcessUnknown() {
        var details = List.of(
                OrderCostBreakdownDTO.LaborDetail.builder().processOrder(1).processName("切").laborMinutes(120).build(),
                OrderCostBreakdownDTO.LaborDetail.builder().processOrder(1).processName("切").laborMinutes(60).build(),
                OrderCostBreakdownDTO.LaborDetail.builder().processOrder(2).processName("炒").laborMinutes(null).build());
        var hours = ProductCostViewService.processHours(details);
        assertEquals(0, bd("3").compareTo(hours.get(0).hours()));
        assertNull(hours.get(1).hours());
        var r = LaborProcessAllocator.allocate(bd("30"), hours);
        assertEquals(LaborProcessAllocator.Status.HOURS_UNKNOWN, r.status());
    }
}
