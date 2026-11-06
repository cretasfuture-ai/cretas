package com.cretas.aims.dto.decision;

import java.util.List;

public record ProposalComparisonResult(
        ComparisonStatus status, String comparisonBasisId, String reason,
        List<CandidateEvaluation> candidates, List<String> nonDominatedCandidateIds,
        boolean factsVerified, boolean businessExecutionPerformed, boolean resourcesReserved) {
    public ProposalComparisonResult {
        candidates = List.copyOf(candidates);
        nonDominatedCandidateIds = List.copyOf(nonDominatedCandidateIds);
    }
    public enum ComparisonStatus { SUPPLIED_FACTS_COMPARABLE, NOT_COMPARABLE }
    public enum CandidateStatus { ELIGIBLE_BY_SUPPLIED_FACTS, EXCLUDED_BY_SUPPLIED_FACTS, NOT_COMPARABLE }
    public enum Reason {
        BASIS_MISMATCH, CURRENCY_MISMATCH, COST_UNIT_MISMATCH, RESOURCE_UNIT_MISMATCH,
        MISSING_COST, MISSING_COMPLETION_TIME, MISSING_RESOURCE_DEMAND, MISSING_RESOURCE_CAPACITY,
        COMPLETION_OUTSIDE_WINDOW, RESOURCE_SHORTAGE, DEADLINE_MISSED
    }
    public record CandidateEvaluation(String id, CandidateStatus status, List<Reason> reasons,
                                      List<String> dominatedByCandidateIds) {
        public CandidateEvaluation {
            reasons = List.copyOf(reasons);
            dominatedByCandidateIds = List.copyOf(dominatedByCandidateIds);
        }
    }
}
