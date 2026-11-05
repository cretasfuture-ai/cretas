package com.cretas.aims.dto.decision;

/** A comparison of recorded snapshots, never a re-execution or an approval. */
public record DecisionSnapshotComparison(
        String decisionId, String baselineId, Status status, String reason,
        String currentOutputChecksum, String baselineOutputChecksum,
        String currentRuleConfigId, Integer currentRuleConfigVersion,
        String baselineRuleConfigId, Integer baselineRuleConfigVersion,
        boolean businessExecutionPerformed) {
    public enum Status { SAME_RESULT, DIFFERENT_RESULT, NOT_COMPARABLE }
}
