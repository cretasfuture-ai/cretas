package com.cretas.aims.service;

import com.cretas.aims.dto.decision.ProposalComparisonRequest;
import com.cretas.aims.dto.decision.ProposalComparisonRequest.ResourceBudgetUnit;
import com.cretas.aims.dto.decision.ProposalComparisonRequest.Candidate;
import com.cretas.aims.dto.decision.ProposalComparisonResult;
import com.cretas.aims.dto.decision.ProposalComparisonResult.*;
import com.cretas.aims.exception.BusinessException;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProposalComparisonContractTest {
    private static ValidatorFactory validators;
    private static ProposalComparisonService service;
    private static final OffsetDateTime START = time(8), END = time(20), DEADLINE = time(18);
    @BeforeAll static void setup() { validators = Validation.buildDefaultValidatorFactory(); service = new ProposalComparisonService(validators.getValidator()); }
    @AfterAll static void close() { validators.close(); }

    @Test void genuineTradeoffsRemainOnFrontierAndWorseCandidateListsBothDominators() {
        var result = compare(candidate("c", "30", 14, "3"), candidate("b", "20", 10, "1"), candidate("a", "10", 12, "2"));
        assertThat(result.status()).isEqualTo(ComparisonStatus.SUPPLIED_FACTS_COMPARABLE);
        assertThat(result.nonDominatedCandidateIds()).containsExactly("a", "b");
        assertThat(card(result, "c").dominatedByCandidateIds()).containsExactly("a", "b");
        assertThat(result.factsVerified()).isFalse();
        assertThat(result.businessExecutionPerformed()).isFalse();
        assertThat(result.resourcesReserved()).isFalse();
    }

    @Test void cheapestLateAlternativeIsExcludedRatherThanRecommended() {
        var result = compare(candidate("late", "1", 19, "1"), candidate("a", "10", 10, "1"), candidate("b", "20", 11, "2"));
        assertThat(result.nonDominatedCandidateIds()).containsExactly("a");
        assertThat(card(result, "late").status()).isEqualTo(CandidateStatus.EXCLUDED_BY_SUPPLIED_FACTS);
        assertThat(card(result, "late").reasons()).containsExactly(Reason.DEADLINE_MISSED);
    }

    @Test void unavailableResourceAlternativeIsExcludedWithoutBookingAnything() {
        var result = compare(candidate("short", "1", 9, "11"), candidate("a", "10", 10, "1"), candidate("b", "20", 11, "2"));
        assertThat(card(result, "short").reasons()).containsExactly(Reason.RESOURCE_SHORTAGE);
        assertThat(result.nonDominatedCandidateIds()).doesNotContain("short");
    }

    @Test void unknownCostNeverBecomesZeroOrSilentlyDropsFromComparison() {
        var result = compare(candidate("unknown", null, 10, "1"), candidate("a", "10", 10, "1"), candidate("b", "20", 11, "2"));
        assertBlocked(result, "unknown", Reason.MISSING_COST);
        assertThat(result.candidates()).allSatisfy(item -> assertThat(item.dominatedByCandidateIds()).isEmpty());
    }

    @Test void missingCompletionIsNotAnEarlyDate() {
        Candidate unknown = new Candidate("unknown", "batch-1", "CNY", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, bd("1"), null, bd("1"));
        assertBlocked(compare(unknown, candidate("a", "10", 10, "1")), "unknown", Reason.MISSING_COMPLETION_TIME);
    }

    @Test void missingResourceDemandDoesNotBecomeResourceFree() {
        assertBlocked(compare(candidate("unknown", "1", 10, null), candidate("a", "10", 10, "1")), "unknown", Reason.MISSING_RESOURCE_DEMAND);
    }

    @Test void missingCapacityIsNotAssumedUnlimited() {
        var request = basis(null, List.of(candidate("a", "1", 10, "1"), candidate("b", "2", 11, "2")));
        var result = service.compare(request);
        assertBlocked(result, "a", Reason.MISSING_RESOURCE_CAPACITY);
        assertThat(card(result, "b").reasons()).contains(Reason.MISSING_RESOURCE_CAPACITY);
    }

    @Test void incompatibleBusinessBasisBlocksWholeFrontier() {
        assertMismatch(new Candidate("b", "another-batch", "CNY", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, bd("1"), time(10), bd("1")), Reason.BASIS_MISMATCH);
    }
    @Test void mixedCurrenciesAreNotConvertedImplicitly() {
        assertMismatch(new Candidate("b", "batch-1", "USD", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, bd("1"), time(10), bd("1")), Reason.CURRENCY_MISMATCH);
    }
    @Test void costPerUnitAndCostPerBatchCannotCompare() {
        assertMismatch(new Candidate("b", "batch-1", "CNY", "PER_KG", ResourceBudgetUnit.PERSON_HOUR, bd("1"), time(10), bd("1")), Reason.COST_UNIT_MISMATCH);
    }
    @Test void differentResourceUnitsCannotCompare() {
        assertMismatch(new Candidate("b", "batch-1", "CNY", "PER_BATCH", ResourceBudgetUnit.MACHINE_HOUR, bd("1"), time(10), bd("1")), Reason.RESOURCE_UNIT_MISMATCH);
    }
    @Test void outsideCapacityWindowIsUnknownRatherThanFeasible() {
        assertBlocked(compare(candidate("outside", "1", 21, "1"), candidate("a", "10", 10, "1")), "outside", Reason.COMPLETION_OUTSIDE_WINDOW);
    }

    @Test void explicitZerosAreValidFactsRatherThanMissing() {
        var result = compare(candidate("zero", "0", 10, "0"), candidate("positive", "1", 11, "1"));
        assertThat(result.status()).isEqualTo(ComparisonStatus.SUPPLIED_FACTS_COMPARABLE);
        assertThat(result.nonDominatedCandidateIds()).containsExactly("zero");
    }
    @Test void equalValuesDifferentDecimalScalesAndTimeOffsetsAreTies() {
        Candidate b = new Candidate("b", "batch-1", "CNY", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, bd("1.000000"),
                OffsetDateTime.parse("2027-01-01T18:00:00+08:00"), bd("2.000000"));
        var result = compare(candidate("a", "1", 10, "2"), b);
        assertThat(result.nonDominatedCandidateIds()).containsExactly("a", "b");
    }
    @Test void deadlineAndCapacityEqualityAreInclusive() {
        var result = compare(candidate("a", "1", 18, "10"), candidate("b", "2", 18, "10"));
        assertThat(card(result, "a").status()).isEqualTo(CandidateStatus.ELIGIBLE_BY_SUPPLIED_FACTS);
        assertThat(result.nonDominatedCandidateIds()).containsExactly("a");
    }
    @Test void inputOrderDoesNotChangeOutputAndIdOrderIsNotPriority() {
        Candidate a = candidate("a", "20", 10, "1"), b = candidate("b", "10", 12, "2");
        assertThat(compare(a, b)).isEqualTo(compare(b, a));
    }
    @Test void duplicateCandidateIdsAreRejected() {
        Candidate a = candidate("a", "1", 10, "1");
        assertThatThrownBy(() -> compare(a, a)).isInstanceOf(BusinessException.class).hasMessageContaining("ID不能重复");
    }
    @Test void negativeCostsAreNotAcceptedAsSavings() {
        assertThatThrownBy(() -> compare(candidate("a", "-1", 10, "1"), candidate("b", "1", 10, "1")))
                .isInstanceOf(ConstraintViolationException.class);
    }
    @Test void candidateCountIsBounded() {
        List<Candidate> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) many.add(candidate("id" + i, "1", 10, "1"));
        assertThatThrownBy(() -> service.compare(basis(bd("10"), many))).isInstanceOf(BusinessException.class).hasMessageContaining("2至20");
    }
    @Test void invalidWindowIsRejected() {
        var request = new ProposalComparisonRequest("batch-1", "CNY", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, END, START,
                DEADLINE, bd("10"), List.of(candidate("a", "1", 10, "1"), candidate("b", "2", 11, "1")));
        assertThatThrownBy(() -> service.compare(request)).isInstanceOf(BusinessException.class).hasMessageContaining("结束时间");
    }
    @Test void invalidDeadlineBeforeWindowIsRejected() {
        var request = new ProposalComparisonRequest("batch-1", "CNY", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, START, END,
                time(7), bd("10"), List.of(candidate("a", "1", 10, "1"), candidate("b", "2", 11, "1")));
        assertThatThrownBy(() -> service.compare(request)).isInstanceOf(BusinessException.class).hasMessageContaining("截止时间");
    }
    @Test void smallDecimalDifferencesAreComparedExactly() {
        assertThat(compare(candidate("a", "0.000001", 10, "1"), candidate("b", "0.000002", 10, "1"))
                .nonDominatedCandidateIds()).containsExactly("a");
    }
    @Test void responseListsCannotBeMutatedByCaller() {
        var result = compare(candidate("a", "1", 10, "1"), candidate("b", "2", 11, "2"));
        assertThatThrownBy(() -> result.nonDominatedCandidateIds().add("fake")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> card(result, "b").dominatedByCandidateIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void oneEligibleAlternativeDoesNotManufactureMultiProposalComparison() {
        var result = compare(candidate("a", "1", 10, "1"), candidate("short", "2", 11, "11"));
        assertThat(result.status()).isEqualTo(ComparisonStatus.NOT_COMPARABLE);
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_ELIGIBLE_PROPOSALS");
        assertThat(result.nonDominatedCandidateIds()).isEmpty();
    }
    @Test void noEligibleAlternativesDoesNotProduceAFrontier() {
        assertThat(compare(candidate("a", "1", 10, "11"), candidate("b", "2", 11, "12")).nonDominatedCandidateIds()).isEmpty();
    }

    @Test void oversizedListIsRejectedBeforeCascadingValidation() {
        Validator validator = org.mockito.Mockito.mock(Validator.class);
        var guarded = new ProposalComparisonService(validator);
        assertThatThrownBy(() -> guarded.compare(basis(bd("10"), Collections.nCopies(21, null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("2至20");
        org.mockito.Mockito.verifyNoInteractions(validator);
    }
    @Test void maximumTwentyCandidatesCanBeCompared() {
        List<Candidate> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) twenty.add(candidate("id" + i, "1", 10, "1"));
        assertThat(service.compare(basis(bd("10"), twenty)).nonDominatedCandidateIds()).hasSize(20);
    }
    @Test void resourceWindowUsesInclusiveInstantBoundariesAcrossOffsets() {
        Candidate atStart = new Candidate("start", "batch-1", "CNY", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, bd("2"),
                OffsetDateTime.parse("2027-01-01T16:00:00+08:00"), bd("2"));
        Candidate atEnd = new Candidate("end", "batch-1", "CNY", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, bd("1"),
                OffsetDateTime.parse("2027-01-02T04:00:00+08:00"), bd("1"));
        var withoutDeadline = new ProposalComparisonRequest("batch-1", "CNY", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, START, END,
                null, bd("10"), List.of(atStart, atEnd));
        var result = service.compare(withoutDeadline);
        assertThat(result.status()).isEqualTo(ComparisonStatus.SUPPLIED_FACTS_COMPARABLE);
        assertThat(result.candidates()).allSatisfy(item -> assertThat(item.status()).isEqualTo(CandidateStatus.ELIGIBLE_BY_SUPPLIED_FACTS));
    }

    private static void assertMismatch(Candidate mismatch, Reason reason) {
        assertBlocked(compare(candidate("a", "10", 10, "1"), mismatch), mismatch.id(), reason);
    }
    private static void assertBlocked(ProposalComparisonResult result, String id, Reason reason) {
        assertThat(result.status()).isEqualTo(ComparisonStatus.NOT_COMPARABLE);
        assertThat(result.nonDominatedCandidateIds()).isEmpty();
        assertThat(card(result, id).status()).isEqualTo(CandidateStatus.NOT_COMPARABLE);
        assertThat(card(result, id).reasons()).contains(reason);
    }
    private static CandidateEvaluation card(ProposalComparisonResult result, String id) {
        return result.candidates().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }
    private static ProposalComparisonResult compare(Candidate... candidates) {
        return service.compare(basis(bd("10"), List.of(candidates)));
    }
    private static ProposalComparisonRequest basis(BigDecimal capacity, List<Candidate> candidates) {
        return new ProposalComparisonRequest("batch-1", "CNY", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, START, END, DEADLINE, capacity, candidates);
    }
    private static Candidate candidate(String id, String cost, int hour, String resource) {
        return new Candidate(id, "batch-1", "CNY", "PER_BATCH", ResourceBudgetUnit.PERSON_HOUR, bd(cost), time(hour), bd(resource));
    }
    private static BigDecimal bd(String value) { return value == null ? null : new BigDecimal(value); }
    private static OffsetDateTime time(int hour) { return OffsetDateTime.parse("2027-01-01T" + String.format("%02d", hour) + ":00:00Z"); }
}
