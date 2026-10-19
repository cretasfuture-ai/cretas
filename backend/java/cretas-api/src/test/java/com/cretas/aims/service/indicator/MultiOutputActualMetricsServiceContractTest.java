package com.cretas.aims.service.indicator;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static com.cretas.aims.service.indicator.MultiOutputActualMetricsService.*;

class MultiOutputActualMetricsServiceContractTest {
    private final MultiOutputActualMetricsService service = new MultiOutputActualMetricsService();
    private static BigDecimal n(String value) { return new BigDecimal(value); }

    @Test void sharedPlanInputAndCostAreCountedOnceAndSkuUsesSharedDenominator() {
        var plans = List.of(new Plan("P", n("6"), n("31")), new Plan("P", n("6"), n("31")));
        var outputs = List.of(
                new Output("P", "A1", "A", n("1"), null, n("0"), QualityStatus.NONE_CONFIRMED, n("11.7")),
                new Output("P", "B1", "B", n("2"), null, n("0"), QualityStatus.NONE_CONFIRMED, n("19.3")));
        assertThat(service.aggregate(plans, outputs, Metric.YIELD_RATE)).isEqualByComparingTo("50");
        assertThat(service.byProduct(plans, outputs, "A", Metric.YIELD_RATE)).isEqualByComparingTo("16.6667");
        assertThat(service.byProduct(plans, outputs, "A", Metric.UNIT_COST)).isEqualByComparingTo("11.7");
    }

    @Test void incompleteQualityAndMismatchedPlanCostFailClosed() {
        var plans = List.of(new Plan("P", n("6"), n("31")));
        var unknown = List.of(new Output("P", "A", "A", n("1"), null, n("0"), null, n("31")));
        assertThat(service.aggregate(plans, unknown, Metric.GOOD_OUTPUT_RATE)).isNull();
        var wrongCost = List.of(new Output("P", "A", "A", n("1"), null, n("0"), QualityStatus.NONE_CONFIRMED, n("30")));
        assertThat(service.aggregate(plans, wrongCost, Metric.TOTAL_COST)).isNull();
    }

    @Test void countedQualityMustBalanceGoodAndDefect() {
        var plans = List.of(new Plan("P", n("2"), n("10")));
        var output = List.of(new Output("P", "A", "A", n("1.5"), n("1.5"), n("0.5"), QualityStatus.COUNTED, n("10")));
        assertThat(service.aggregate(plans, output, Metric.GOOD_OUTPUT_RATE)).isEqualByComparingTo("75");
    }

    @Test void differentPlansCannotOffsetEachOthersWrongAllocatedCost() {
        var plans = List.of(new Plan("P1", n("1"), n("10")), new Plan("P2", n("1"), n("20")));
        var outputs = List.of(new Output("P1","A","A",n("1"),null,n("0"),QualityStatus.NONE_CONFIRMED,n("15")),
                new Output("P2","B","B",n("1"),null,n("0"),QualityStatus.NONE_CONFIRMED,n("15")));
        assertThat(service.aggregate(plans, outputs, Metric.TOTAL_COST)).isNull();
        assertThat(service.aggregate(plans, outputs, Metric.YIELD_RATE)).isEqualByComparingTo("100");
    }
}
