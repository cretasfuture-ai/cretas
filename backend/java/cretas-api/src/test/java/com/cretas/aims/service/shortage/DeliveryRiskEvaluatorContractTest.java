package com.cretas.aims.service.shortage;

import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.Facts;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.Level;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.MaterialGap;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.MaterialStatus;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.PlanFact;
import com.cretas.aims.service.shortage.DeliveryRiskEvaluator.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 判定口径: 风险要有信号, 缺事实是未知, 没有依据不算正常。 */
class DeliveryRiskEvaluatorContractTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private static PlanFact plan(String remaining, String perDay) {
        return new PlanFact("p", "PLAN-1", "公斤", new BigDecimal("100"), BigDecimal.ZERO,
                remaining == null ? null : new BigDecimal(remaining), perDay == null ? null : new BigDecimal(perDay),
                "人工填写", 2, 3);
    }

    private static Facts facts(LocalDate due, List<PlanFact> plans, int live, int late, MaterialStatus m, List<MaterialGap> gaps) {
        return new Facts(TODAY, due, true, plans, live, 0, live > 0 ? TODAY.plusDays(2).atStartOfDay() : null, late, m, null, gaps);
    }

    @Test
    void onTimeScheduleAndEnoughCapacityIsOk() {
        var r = DeliveryRiskEvaluator.evaluate(facts(TODAY.plusDays(10), List.of(plan("100", "50")), 1, 0, MaterialStatus.CLEAR, List.of()));
        assertThat(r.level()).isEqualTo(Level.OK);
        assertThat(r.signals()).isEmpty();
        assertThat(r.estimatedDaysNeeded()).isEqualTo(2);
    }

    @Test
    void lateScheduleIsRiskWithEvidence() {
        var r = DeliveryRiskEvaluator.evaluate(facts(TODAY.plusDays(1), List.of(plan("10", "50")), 1, 1, MaterialStatus.CLEAR, List.of()));
        assertThat(r.level()).isEqualTo(Level.RISK);
        assertThat(r.signals()).containsExactly(Signal.LATE_SCHEDULE);
        assertThat(r.suggestions()).isNotEmpty();
    }

    @Test
    void capacityBoundaryCountsDueDateItself() {
        // 离交期 1 天 = 今天和交期当天 2 天可做
        var fits = DeliveryRiskEvaluator.evaluate(facts(TODAY.plusDays(1), List.of(plan("100", "50")), 0, 0, MaterialStatus.CLEAR, List.of()));
        var short1 = DeliveryRiskEvaluator.evaluate(facts(TODAY.plusDays(1), List.of(plan("101", "50")), 0, 0, MaterialStatus.CLEAR, List.of()));
        assertThat(fits.level()).isEqualTo(Level.OK);
        assertThat(short1.signals()).containsExactly(Signal.CAPACITY_SHORT);
    }

    @Test
    void overdueWithPendingQuantityIsRisk() {
        var r = DeliveryRiskEvaluator.evaluate(facts(TODAY.minusDays(1), List.of(plan("0", "50")), 0, 0, MaterialStatus.CLEAR, List.of()));
        assertThat(r.signals()).contains(Signal.OVERDUE);
    }

    @Test
    void noPlanIsRiskOnlyInsideTheWindow() {
        var near = DeliveryRiskEvaluator.evaluate(facts(TODAY.plusDays(7), List.of(), 0, 0, MaterialStatus.CLEAR, List.of()));
        var far = DeliveryRiskEvaluator.evaluate(facts(TODAY.plusDays(8), List.of(), 0, 0, MaterialStatus.CLEAR, List.of()));
        assertThat(near.signals()).containsExactly(Signal.NO_PLAN);
        assertThat(far.level()).isEqualTo(Level.UNKNOWN);
    }

    @Test
    void missingDueDateIsUnknownNotOk() {
        var r = DeliveryRiskEvaluator.evaluate(facts(null, List.of(plan("10", "50")), 1, 0, MaterialStatus.CLEAR, List.of()));
        assertThat(r.level()).isEqualTo(Level.UNKNOWN);
    }

    @Test
    void missingCapacityIsNotTreatedAsZeroDays() {
        var r = DeliveryRiskEvaluator.evaluate(facts(TODAY.plusDays(10), List.of(plan("10", null)), 0, 0, MaterialStatus.CLEAR, List.of()));
        assertThat(r.level()).isEqualTo(Level.UNKNOWN);
        assertThat(r.estimatedDaysNeeded()).isNull();
    }

    @Test
    void missingRemainingIsNotTreatedAsDone() {
        var r = DeliveryRiskEvaluator.evaluate(facts(TODAY.plusDays(10), List.of(plan(null, "50")), 0, 0, MaterialStatus.CLEAR, List.of()));
        assertThat(r.level()).isEqualTo(Level.UNKNOWN);
    }

    @Test
    void unknownMaterialBlocksOk() {
        var r = DeliveryRiskEvaluator.evaluate(facts(TODAY.plusDays(10), List.of(plan("10", "50")), 1, 0, MaterialStatus.UNKNOWN, List.of()));
        assertThat(r.level()).isEqualTo(Level.UNKNOWN);
    }

    @Test
    void materialGapJudgedByArrival() {
        LocalDate due = TODAY.plusDays(10);
        var early = DeliveryRiskEvaluator.evaluate(facts(due, List.of(plan("10", "50")), 1, 0, MaterialStatus.GAP,
                List.of(new MaterialGap("牛肉", true, due.minusDays(1)))));
        var late = DeliveryRiskEvaluator.evaluate(facts(due, List.of(plan("10", "50")), 1, 0, MaterialStatus.GAP,
                List.of(new MaterialGap("牛肉", true, due))));
        var noOrder = DeliveryRiskEvaluator.evaluate(facts(due, List.of(plan("10", "50")), 1, 0, MaterialStatus.GAP,
                List.of(new MaterialGap("牛肉", false, null))));
        var undated = DeliveryRiskEvaluator.evaluate(facts(due, List.of(plan("10", "50")), 1, 0, MaterialStatus.GAP,
                List.of(new MaterialGap("牛肉", true, null))));
        assertThat(early.level()).isEqualTo(Level.OK);
        assertThat(late.signals()).containsExactly(Signal.MATERIAL_LATE);
        assertThat(noOrder.signals()).containsExactly(Signal.MATERIAL_LATE);
        assertThat(undated.level()).isEqualTo(Level.UNKNOWN);
    }

    @Test
    void nothingPendingIsOkEvenWithoutDueDate() {
        var r = DeliveryRiskEvaluator.evaluate(new Facts(TODAY, null, false, List.of(), 0, 0, (LocalDateTime) null, 0,
                MaterialStatus.CLEAR, null, List.of()));
        assertThat(r.level()).isEqualTo(Level.OK);
    }
}
