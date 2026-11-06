package com.cretas.aims.dto.decision;

/** Read-only quality recommendation replay result.  It never approves or executes a decision. */
public record DecisionRecommendationReplay(
        String decisionId,
        Status status,
        String reason,
        String evaluationScope,
        String evaluatorId,
        String evaluatorVersion,
        String ruleBundleHash,
        String snapshotHash,
        String recordedRecommendation,
        String replayedRecommendation,
        String recordedConfidence,
        String replayedConfidence,
        boolean snapshotConsistencyVerified,
        boolean ruleEvaluationPerformed,
        boolean businessExecutionPerformed,
        boolean approvalEvaluated) {

    public enum Status { REPLAY_MATCH, REPLAY_DIFFERENT, UNAVAILABLE }

    public static DecisionRecommendationReplay unavailable(String decisionId, String reason) {
        return new DecisionRecommendationReplay(decisionId, Status.UNAVAILABLE, reason,
                "QUALITY_RECOMMENDATION_ONLY", null, null, null, null,
                null, null, null, null, false, false, false, false);
    }
}
