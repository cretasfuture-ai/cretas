package com.cretas.aims.service.production;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlanConcurrentDemandCalculatorContractTest {
    private static final String POOL = "COMPANY";

    @Test
    void stockFreeAlreadyNetOfOtherHeldReservationIsNotDeductedTwice() {
        var result = calculate(
                demand("own", "RAW", 75, 0, 0),
                demand("other", "RAW", 15, 0, 15),
                stock("RAW", 45));

        assertVerified(result, "60.000000", "60.000000");
        assertEquals(0, BigDecimal.ZERO.compareTo(result.materials().get(0).otherUnoccupied()));
    }

    @Test
    void returnsOtherFirstLowerBoundAndOwnFirstUpperBound() {
        var result = calculate(
                demand("own", "RAW", 80, 0, 0),
                demand("other", "RAW", 80, 0, 0),
                stock("RAW", 100));

        assertVerified(result, "25.000000", "100.000000");
    }

    @Test
    void ownHeldReservationSupportsThePlanAndConsumptionIsNotDoubleDeducted() {
        var held = calculate(demand("own", "RAW", 100, 0, 20), stock("RAW", 0));
        assertVerified(held, "20.000000", "20.000000");

        var consumed = calculate(demand("own", "RAW", 100, 40, 0), stock("RAW", 60));
        assertVerified(consumed, "100.000000", "100.000000");
    }

    @Test
    void overConsumedAndOverReservedFactsRemainValid() {
        var result = calculate(
                demand("own", "RAW", 100, 20, 100),
                demand("other", "RAW", 10, 20, 0),
                stock("RAW", 0));

        assertVerified(result, "100.000000", "100.000000");
    }

    @Test
    void allTargetDemandAlreadyConsumedReturnsNoRemainingStandardDemand() {
        var result = calculate(demand("own", "RAW", 100, 100, 0), stock("RAW", 0));

        assertUnverified(result, PlanConcurrentDemandCalculator.Reason.NO_REMAINING_STANDARD_DEMAND);
    }

    @Test
    void consumedMaterialIsSkippedWhileRemainingMaterialStillConstrainsReadiness() {
        var result = calculate(
                demand("own", "RAW", 100, 100, 0),
                demand("own", "PACK", 100, 0, 0),
                stock("RAW", 0), stock("PACK", 50));

        assertVerified(result, "50.000000", "50.000000");
        assertEquals(List.of("PACK"), result.materials().stream()
                .map(material -> material.key().materialId()).toList());
    }

    @Test
    void terminalPlansDoNotCreateConcurrentDemand() {
        var result = calculate(
                demand("own", "RAW", 50, 0, 0),
                new PlanConcurrentDemandCalculator.DemandFact("completed", "RAW", POOL, "kg", "COMPLETED", bd(50), bd(0), bd(0)),
                stock("RAW", 50));

        assertVerified(result, "100.000000", "100.000000");
    }

    @Test
    void bottleneckUsesTheMinimumAcrossMaterialPools() {
        var result = calculate(
                demand("own", "RAW", 100, 0, 0), demand("own", "PACK", 50, 0, 0),
                stock("RAW", 80), stock("PACK", 10));

        assertVerified(result, "20.000000", "20.000000");
    }

    @Test
    void unknownCoverageUnitsMissingStockAndNoTargetFailClosed() {
        var unknown = PlanConcurrentDemandCalculator.calculate(new PlanConcurrentDemandCalculator.Input(
                "own", List.of(demand("own", "RAW", 1, 0, 0)), List.of(stock("RAW", 1)), false));
        assertUnverified(unknown, PlanConcurrentDemandCalculator.Reason.UNKNOWN_COVERAGE);

        var missingUnit = PlanConcurrentDemandCalculator.calculate(new PlanConcurrentDemandCalculator.Input(
                "own", List.of(new PlanConcurrentDemandCalculator.DemandFact("own", "RAW", POOL, null, "PLANNED", bd(1), bd(0), bd(0))), List.of(stock("RAW", 1)), true));
        assertUnverified(missingUnit, PlanConcurrentDemandCalculator.Reason.INVALID_FACT);

        var missingStock = PlanConcurrentDemandCalculator.calculate(new PlanConcurrentDemandCalculator.Input(
                "own", List.of(demand("own", "RAW", 1, 0, 0)), List.of(), true));
        assertUnverified(missingStock, PlanConcurrentDemandCalculator.Reason.MISSING_STOCK);

        var noTarget = PlanConcurrentDemandCalculator.calculate(new PlanConcurrentDemandCalculator.Input(
                "own", List.of(demand("other", "RAW", 1, 0, 0)), List.of(stock("RAW", 1)), true));
        assertUnverified(noTarget, PlanConcurrentDemandCalculator.Reason.NO_TARGET_DEMAND);
    }

    @Test
    void unitConflictNegativeAndDuplicateFactsFailClosed() {
        var conflict = calculate(
                demand("own", "RAW", 10, 0, 0),
                new PlanConcurrentDemandCalculator.DemandFact("other", "RAW", POOL, "g", "PLANNED", bd(1), bd(0), bd(0)),
                stock("RAW", 10));
        assertUnverified(conflict, PlanConcurrentDemandCalculator.Reason.UNIT_CONFLICT);

        var duplicateDemand = calculate(
                demand("own", "RAW", 10, 0, 0), demand("own", "RAW", 10, 0, 0), stock("RAW", 10));
        assertUnverified(duplicateDemand, PlanConcurrentDemandCalculator.Reason.DUPLICATE_DEMAND_FACT);

        var duplicateStock = PlanConcurrentDemandCalculator.calculate(new PlanConcurrentDemandCalculator.Input(
                "own", List.of(demand("own", "RAW", 10, 0, 0)), List.of(stock("RAW", 10), stock("RAW", 10)), true));
        assertUnverified(duplicateStock, PlanConcurrentDemandCalculator.Reason.DUPLICATE_STOCK_FACT);

        var negative = calculate(demand("own", "RAW", 10, -1, 0), stock("RAW", 10));
        assertUnverified(negative, PlanConcurrentDemandCalculator.Reason.INVALID_FACT);
    }

    private static PlanConcurrentDemandCalculator.Result calculate(Object... facts) {
        var demands = new java.util.ArrayList<PlanConcurrentDemandCalculator.DemandFact>();
        var stock = new java.util.ArrayList<PlanConcurrentDemandCalculator.StockFact>();
        for (Object fact : facts) {
            if (fact instanceof PlanConcurrentDemandCalculator.DemandFact demand) demands.add(demand);
            if (fact instanceof PlanConcurrentDemandCalculator.StockFact stockFact) stock.add(stockFact);
        }
        return PlanConcurrentDemandCalculator.calculate(new PlanConcurrentDemandCalculator.Input("own", demands, stock, true));
    }

    private static PlanConcurrentDemandCalculator.DemandFact demand(String plan, String material, double required, double consumed, double held) {
        return new PlanConcurrentDemandCalculator.DemandFact(plan, material, POOL, "kg", "PLANNED", bd(required), bd(consumed), bd(held));
    }

    private static PlanConcurrentDemandCalculator.StockFact stock(String material, double free) {
        return new PlanConcurrentDemandCalculator.StockFact(material, POOL, "kg", bd(free));
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }

    private static void assertVerified(PlanConcurrentDemandCalculator.Result result, String lower, String upper) {
        assertEquals(PlanConcurrentDemandCalculator.Status.VERIFIED, result.status());
        assertEquals(PlanConcurrentDemandCalculator.Reason.OK, result.reason());
        assertEquals(new BigDecimal(lower), result.lowerPercent());
        assertEquals(new BigDecimal(upper), result.upperPercent());
    }

    private static void assertUnverified(PlanConcurrentDemandCalculator.Result result, PlanConcurrentDemandCalculator.Reason reason) {
        assertEquals(PlanConcurrentDemandCalculator.Status.UNVERIFIED, result.status());
        assertEquals(reason, result.reason());
        assertNull(result.lowerPercent());
        assertNull(result.upperPercent());
    }
}
