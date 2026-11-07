package com.cretas.aims.service.production;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.cretas.aims.service.scheduling.daily.CapacityUnits;

/**
 * Pure reference math for concurrent plan material readiness.
 *
 * <p>The reader which supplies this calculator is responsible for the date window,
 * ownership pool and unit normalization. This class deliberately does not allocate,
 * reserve, schedule or infer actual production progress.</p>
 */
public final class PlanConcurrentDemandCalculator {
    private static final MathContext DIVISION_CONTEXT = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "CANCELLED");

    private PlanConcurrentDemandCalculator() {
    }

    public enum Status {
        VERIFIED,
        UNVERIFIED
    }

    public enum Reason {
        OK,
        UNKNOWN_COVERAGE,
        NO_TARGET_DEMAND,
        NO_REMAINING_STANDARD_DEMAND,
        INVALID_FACT,
        MISSING_STOCK,
        DUPLICATE_DEMAND_FACT,
        DUPLICATE_STOCK_FACT,
        UNIT_CONFLICT
    }

    public record Input(
            String targetPlanId,
            List<DemandFact> demands,
            List<StockFact> stock,
            boolean coverageComplete) {
    }

    public record DemandFact(
            String planId,
            String materialId,
            String ownershipPoolId,
            String unit,
            String status,
            BigDecimal required,
            BigDecimal consumed,
            BigDecimal heldReserved) {
    }

    /** stock.free is already net of every reservation in the pool. */
    public record StockFact(
            String materialId,
            String ownershipPoolId,
            String unit,
            BigDecimal free) {
    }

    public record MaterialPoolKey(String materialId, String ownershipPoolId) {
    }

    private record DemandFactKey(String planId, String materialId, String ownershipPoolId) {
    }

    public record MaterialReadiness(
            MaterialPoolKey key,
            String unit,
            BigDecimal required,
            BigDecimal consumed,
            BigDecimal ownHeldReserved,
            BigDecimal otherUnoccupied,
            BigDecimal stockFree,
            BigDecimal lowerPercent,
            BigDecimal upperPercent) {
    }

    public record Result(
            Status status,
            Reason reason,
            String targetPlanId,
            BigDecimal lowerPercent,
            BigDecimal upperPercent,
            List<MaterialReadiness> materials) {
        public Result {
            materials = materials == null ? List.of() : List.copyOf(materials);
        }

        public static Result unverified(Reason reason, String targetPlanId) {
            return new Result(Status.UNVERIFIED, reason, targetPlanId, null, null, List.of());
        }
    }

    public static Result calculate(Input input) {
        if (input == null || blank(input.targetPlanId()) || !input.coverageComplete()
                || input.demands() == null || input.stock() == null) {
            return Result.unverified(input == null ? Reason.INVALID_FACT : Reason.UNKNOWN_COVERAGE,
                    input == null ? null : input.targetPlanId());
        }

        Map<MaterialPoolKey, List<DemandFact>> demandsByKey = new HashMap<>();
        Set<DemandFactKey> demandFactKeys = new HashSet<>();
        Map<MaterialPoolKey, StockFact> stockByKey = new HashMap<>();
        Set<MaterialPoolKey> stockFactKeys = new HashSet<>();

        for (DemandFact fact : input.demands()) {
            if (!validDemand(fact)) return Result.unverified(Reason.INVALID_FACT, input.targetPlanId());
            DemandFactKey factKey = new DemandFactKey(fact.planId(), fact.materialId(), fact.ownershipPoolId());
            if (!demandFactKeys.add(factKey)) {
                return Result.unverified(Reason.DUPLICATE_DEMAND_FACT, input.targetPlanId());
            }
            MaterialPoolKey key = new MaterialPoolKey(fact.materialId(), fact.ownershipPoolId());
            demandsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fact);
        }
        for (StockFact fact : input.stock()) {
            if (!validStock(fact)) return Result.unverified(Reason.INVALID_FACT, input.targetPlanId());
            MaterialPoolKey factKey = new MaterialPoolKey(fact.materialId(), fact.ownershipPoolId());
            if (!stockFactKeys.add(factKey)) {
                return Result.unverified(Reason.DUPLICATE_STOCK_FACT, input.targetPlanId());
            }
            stockByKey.put(new MaterialPoolKey(fact.materialId(), fact.ownershipPoolId()), fact);
        }

        List<MaterialReadiness> results = new ArrayList<>();
        for (Map.Entry<MaterialPoolKey, List<DemandFact>> entry : demandsByKey.entrySet()) {
            List<DemandFact> facts = entry.getValue();
            List<DemandFact> active = facts.stream()
                    .filter(fact -> !TERMINAL_STATUSES.contains(fact.status()))
                    .toList();
            DemandFact own = active.stream().filter(fact -> input.targetPlanId().equals(fact.planId())).findFirst().orElse(null);
            if (own == null) continue;
            BigDecimal ownRemaining = max(own.required().subtract(own.consumed()), ZERO);
            if (ownRemaining.signum() == 0) continue;
            StockFact available = stockByKey.get(entry.getKey());
            if (available == null) return Result.unverified(Reason.MISSING_STOCK, input.targetPlanId());
            if (!sameNormalizedUnit(own.unit(), available.unit())) return Result.unverified(Reason.UNIT_CONFLICT, input.targetPlanId());
            for (DemandFact fact : active) {
                if (!sameNormalizedUnit(own.unit(), fact.unit())) return Result.unverified(Reason.UNIT_CONFLICT, input.targetPlanId());
            }

            BigDecimal otherUnoccupied = active.stream()
                    .filter(fact -> !input.targetPlanId().equals(fact.planId()))
                    .map(fact -> max(fact.required().subtract(fact.consumed()).subtract(fact.heldReserved()), ZERO))
                    .reduce(ZERO, BigDecimal::add);
            BigDecimal lowerNumerator = own.heldReserved().add(max(available.free().subtract(otherUnoccupied), ZERO));
            BigDecimal upperNumerator = own.heldReserved().add(available.free());
            results.add(new MaterialReadiness(entry.getKey(), own.unit(), own.required(), own.consumed(), own.heldReserved(),
                    otherUnoccupied, available.free(), percent(lowerNumerator, ownRemaining), percent(upperNumerator, ownRemaining)));
        }

        if (results.isEmpty()) {
            boolean hasTarget = demandsByKey.values().stream()
                    .flatMap(List::stream)
                    .anyMatch(fact -> input.targetPlanId().equals(fact.planId())
                            && !TERMINAL_STATUSES.contains(fact.status()));
            return Result.unverified(hasTarget ? Reason.NO_REMAINING_STANDARD_DEMAND : Reason.NO_TARGET_DEMAND,
                    input.targetPlanId());
        }
        results.sort(Comparator.comparing((MaterialReadiness value) -> value.key().materialId())
                .thenComparing(value -> value.key().ownershipPoolId()));
        BigDecimal lower = results.stream().map(MaterialReadiness::lowerPercent).min(BigDecimal::compareTo).orElse(null);
        BigDecimal upper = results.stream().map(MaterialReadiness::upperPercent).min(BigDecimal::compareTo).orElse(null);
        return new Result(Status.VERIFIED, Reason.OK, input.targetPlanId(), lower, upper, results);
    }

    private static boolean validDemand(DemandFact fact) {
        return fact != null && !blank(fact.planId()) && !blank(fact.materialId()) && !blank(fact.ownershipPoolId())
                && !blank(fact.unit()) && !blank(fact.status())
                && nonNegative(fact.required()) && nonNegative(fact.consumed()) && nonNegative(fact.heldReserved());
    }

    private static boolean validStock(StockFact fact) {
        return fact != null && !blank(fact.materialId()) && !blank(fact.ownershipPoolId())
                && !blank(fact.unit()) && nonNegative(fact.free());
    }

    private static boolean nonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }

    /** The reader already normalized quantities; only unit identities with factor exactly one may coincide. */
    private static boolean sameNormalizedUnit(String from, String to) {
        return CapacityUnits.factor(from, to).filter(factor -> factor.compareTo(BigDecimal.ONE) == 0).isPresent();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator.signum() <= 0) return ZERO.setScale(6, RoundingMode.HALF_UP);
        BigDecimal value = numerator.multiply(ONE_HUNDRED).divide(denominator, DIVISION_CONTEXT);
        return value.min(ONE_HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }
}
