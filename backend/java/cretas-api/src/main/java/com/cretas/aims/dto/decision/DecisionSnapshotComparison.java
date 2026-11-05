package com.cretas.aims.dto.decision;

/** A comparison of recorded snapshots, never a re-execution or an approval. */
public record DecisionSnapshotComparison(
        String decisionId, String baselineId, Status status, String reason,
        String canonicalRecordedOutputHash, String canonicalBaselineOutputHash,
        String currentRuleConfigId, Integer currentRuleConfigVersion,
        String baselineRuleConfigId, Integer baselineRuleConfigVersion,
        boolean businessExecutionPerformed, boolean integrityVerified,
        RecordedOutputRelation recordedOutputRelation, String comparisonBasis) {
    public enum Status { SAME_RESULT, DIFFERENT_RESULT, NOT_COMPARABLE }
    public enum RecordedOutputRelation { SAME, DIFFERENT, NOT_EVALUATED }
}
