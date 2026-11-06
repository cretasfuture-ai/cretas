package com.cretas.aims.service;

import com.cretas.aims.dto.decision.DecisionRecommendationReplay;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.cretas.aims.service.decision.QualityRecommendationEvaluatorV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Pure readonly replay is evidence-bound and cannot fall back to current rules or write anything. */
class DecisionRecommendationReplayContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sameSnapshotReplaysAndCanonicalHashSurvivesKeyOrder() throws Exception {
        DecisionAuditLogRepository repository = mock(DecisionAuditLogRepository.class);
        DecisionRecommendationReplayService service = new DecisionRecommendationReplayService(repository, mapper);
        Map<String, Object> snapshot = snapshot("RELEASE");
        snapshot.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(snapshot));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("callerNoise", "ignored");
        context.put(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY, snapshot);
        DecisionAuditLog row = row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                mapper.writeValueAsString(context));
        when(repository.findByIdAndFactoryId("D-1", "F006")).thenReturn(Optional.of(row));

        DecisionRecommendationReplay result = service.replay("F006", "D-1");

        assertThat(result.status()).isEqualTo(DecisionRecommendationReplay.Status.REPLAY_MATCH);
        assertThat(result.recordedRecommendation()).isEqualTo("RELEASE");
        assertThat(result.replayedRecommendation()).isEqualTo("RELEASE");
        assertThat(result.recordedConfidence()).isEqualTo("98");
        assertThat(result.replayedConfidence()).isEqualTo("98");
        assertThat(result.snapshotConsistencyVerified()).isTrue();
        assertThat(result.ruleEvaluationPerformed()).isTrue();
        assertThat(result.businessExecutionPerformed()).isFalse();
        assertThat(result.approvalEvaluated()).isFalse();
        verify(repository).findByIdAndFactoryId("D-1", "F006");
        verifyNoMoreInteractions(repository);
    }

    @Test void changedRecordedActionAndCorruptForeignOrLegacyEvidenceFailClosed() throws Exception {
        DecisionAuditLogRepository repository = mock(DecisionAuditLogRepository.class);
        DecisionRecommendationReplayService service = new DecisionRecommendationReplayService(repository, mapper);

        Map<String, Object> changed = snapshot("REWORK");
        changed.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(changed));
        DecisionAuditLog different = row(DecisionAuditLog.DecisionType.APPROVAL,
                mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY, changed)));
        when(repository.findByIdAndFactoryId("D-2", "F006")).thenReturn(Optional.of(different));
        assertThat(service.replay("F006", "D-2").status())
                .isEqualTo(DecisionRecommendationReplay.Status.REPLAY_DIFFERENT);

        Map<String, Object> confidenceChanged = snapshot("RELEASE");
        confidenceChanged.put("recordedConfidence", "97.00");
        confidenceChanged.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(confidenceChanged));
        DecisionAuditLog confidenceRow = row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY,
                        confidenceChanged)));
        when(repository.findByIdAndFactoryId("D-5", "F006")).thenReturn(Optional.of(confidenceRow));
        DecisionRecommendationReplay confidenceResult = service.replay("F006", "D-5");
        assertThat(confidenceResult.status()).isEqualTo(DecisionRecommendationReplay.Status.REPLAY_DIFFERENT);
        assertThat(confidenceResult.reason()).isEqualTo("RECORDED_CONFIDENCE_DIFFERS_FROM_REPLAY");
        assertThat(confidenceResult.recordedConfidence()).isEqualTo("97");
        assertThat(confidenceResult.replayedConfidence()).isEqualTo("98");

        Map<String, Object> equivalentScale = snapshot("RELEASE");
        equivalentScale.put("recordedConfidence", "98.000");
        equivalentScale.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(equivalentScale));
        DecisionAuditLog scaleRow = row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY,
                        equivalentScale)));
        when(repository.findByIdAndFactoryId("D-7", "F006")).thenReturn(Optional.of(scaleRow));
        DecisionRecommendationReplay scaleResult = service.replay("F006", "D-7");
        assertThat(scaleResult.status()).isEqualTo(DecisionRecommendationReplay.Status.REPLAY_MATCH);
        assertThat(scaleResult.recordedConfidence()).isEqualTo("98");
        assertThat(scaleResult.replayedConfidence()).isEqualTo("98");

        DecisionAuditLog corrupt = row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                "{\"" + QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY + "\":{\"factoryId\":\"OTHER\"}}");
        when(repository.findByIdAndFactoryId("D-3", "F006")).thenReturn(Optional.of(corrupt));
        assertThat(service.replay("F006", "D-3").status())
                .isEqualTo(DecisionRecommendationReplay.Status.UNAVAILABLE);

        DecisionAuditLog legacy = row(DecisionAuditLog.DecisionType.STATE_TRANSITION, "{}");
        when(repository.findByIdAndFactoryId("D-4", "F006")).thenReturn(Optional.of(legacy));
        assertThat(service.replay("F006", "D-4").status())
                .isEqualTo(DecisionRecommendationReplay.Status.UNAVAILABLE);

        DecisionAuditLog disabled = row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY,
                        snapshot("RELEASE"))));
        disabled.setIsReplayable(false);
        when(repository.findByIdAndFactoryId("D-6", "F006")).thenReturn(Optional.of(disabled));
        assertThat(service.replay("F006", "D-6").reason()).isEqualTo("SNAPSHOT_NOT_REPLAYABLE");
        verify(repository, never()).save(any());
    }

    @Test void snapshotShapeAndBoundedDecimalsFailClosedWithoutParsingUntrustedNumbers() throws Exception {
        DecisionAuditLogRepository repository = mock(DecisionAuditLogRepository.class);
        DecisionRecommendationReplayService service = new DecisionRecommendationReplayService(repository, mapper);

        Map<String, Object> extra = snapshot("RELEASE");
        extra.put("callerOnly", "must-not-be-replayed");
        extra.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(extra));
        when(repository.findByIdAndFactoryId("D-8", "F006"))
                .thenReturn(Optional.of(row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                        mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY, extra)))));
        assertThat(service.replay("F006", "D-8").reason()).isEqualTo("SNAPSHOT_MISSING_OR_INVALID");

        Map<String, Object> exponent = snapshot("RELEASE");
        exponent.put("passRate", "1E2147483647");
        exponent.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(exponent));
        when(repository.findByIdAndFactoryId("D-9", "F006"))
                .thenReturn(Optional.of(row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                        mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY, exponent)))));
        assertThat(service.replay("F006", "D-9").reason()).isEqualTo("SNAPSHOT_MISSING_OR_INVALID");

        Map<String, Object> negativeRate = snapshot("RELEASE");
        negativeRate.put("passRate", "-0.01");
        negativeRate.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(negativeRate));
        when(repository.findByIdAndFactoryId("D-10", "F006"))
                .thenReturn(Optional.of(row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                        mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY, negativeRate)))));
        assertThat(service.replay("F006", "D-10").reason()).isEqualTo("SNAPSHOT_MISSING_OR_INVALID");

        Map<String, Object> nonText = snapshot("RELEASE");
        nonText.put("passRate", new BigDecimal("98.00"));
        nonText.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(nonText));
        when(repository.findByIdAndFactoryId("D-11", "F006"))
                .thenReturn(Optional.of(row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                        mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY, nonText)))));
        assertThat(service.replay("F006", "D-11").reason()).isEqualTo("SNAPSHOT_MISSING_OR_INVALID");

        Map<String, Object> missing = snapshot("RELEASE");
        missing.remove("recordedConfidence");
        missing.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(missing));
        when(repository.findByIdAndFactoryId("D-12", "F006"))
                .thenReturn(Optional.of(row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                        mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY, missing)))));
        assertThat(service.replay("F006", "D-12").reason()).isEqualTo("SNAPSHOT_MISSING_OR_INVALID");
    }

    @Test void decimalCountsKeepFractionalSamplingAndConfidencePreservesLive105Boundary() throws Exception {
        DecisionAuditLogRepository repository = mock(DecisionAuditLogRepository.class);
        DecisionRecommendationReplayService service = new DecisionRecommendationReplayService(repository, mapper);

        Map<String, Object> fractionalCounts = snapshot("RELEASE");
        fractionalCounts.put("sampleSize", "1.25");
        fractionalCounts.put("passCount", "1.00");
        fractionalCounts.put("failCount", "0.25");
        fractionalCounts.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(fractionalCounts));
        when(repository.findByIdAndFactoryId("D-13", "F006"))
                .thenReturn(Optional.of(row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                        mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY,
                                fractionalCounts)))));
        assertThat(service.replay("F006", "D-13").status())
                .isEqualTo(DecisionRecommendationReplay.Status.REPLAY_MATCH);

        Map<String, Object> liveBoundary = snapshot("CONDITIONAL_RELEASE");
        liveBoundary.put("passRate", "100.00");
        liveBoundary.put("defectRate", "0.00");
        liveBoundary.put("result", "FAIL");
        liveBoundary.put("recordedConfidence", "105.00");
        liveBoundary.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(liveBoundary));
        when(repository.findByIdAndFactoryId("D-14", "F006"))
                .thenReturn(Optional.of(row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                        mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY,
                                liveBoundary)))));
        DecisionRecommendationReplay boundary = service.replay("F006", "D-14");
        assertThat(boundary.status()).isEqualTo(DecisionRecommendationReplay.Status.REPLAY_MATCH);
        assertThat(boundary.recordedConfidence()).isEqualTo("105");
    }

    @Test void inspectionIdentityMismatchHasBusinessScopeReasonAfterShapeValidation() throws Exception {
        DecisionAuditLogRepository repository = mock(DecisionAuditLogRepository.class);
        DecisionRecommendationReplayService service = new DecisionRecommendationReplayService(repository, mapper);
        Map<String, Object> mismatch = snapshot("RELEASE");
        mismatch.put("inspectionId", "QI-OTHER");
        mismatch.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(mismatch));
        when(repository.findByIdAndFactoryId("D-15", "F006"))
                .thenReturn(Optional.of(row(DecisionAuditLog.DecisionType.RULE_EXECUTION,
                        mapper.writeValueAsString(Map.of(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY,
                                mismatch)))));
        assertThat(service.replay("F006", "D-15").reason()).isEqualTo("BUSINESS_SCOPE_MISMATCH");
    }

    private static DecisionAuditLog row(DecisionAuditLog.DecisionType type, String context) {
        return DecisionAuditLog.builder().id("D-1").decisionType(type)
                .factoryId("F006").entityType("QualityInspection").entityId("QI-1")
                .inputContext(context).build();
    }

    private static Map<String, Object> snapshot(String recommendation) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", QualityRecommendationEvaluatorV1.SNAPSHOT_SCHEMA_VERSION);
        snapshot.put("evaluatorId", QualityRecommendationEvaluatorV1.EVALUATOR_ID);
        snapshot.put("evaluatorVersion", QualityRecommendationEvaluatorV1.EVALUATOR_VERSION);
        snapshot.put("ruleBundleHash", QualityRecommendationEvaluatorV1.RULE_BUNDLE_HASH);
        snapshot.put("factoryId", "F006");
        snapshot.put("inspectionId", "QI-1");
        snapshot.put("productionBatchId", "17");
        snapshot.put("inspectionDate", "2026-11-07");
        snapshot.put("passRate", "98.00");
        snapshot.put("defectRate", "2.00");
        snapshot.put("result", "PASS");
        snapshot.put("qualityGrade", "A");
        snapshot.put("sampleSize", "100.00");
        snapshot.put("passCount", "98.00");
        snapshot.put("failCount", "2.00");
        snapshot.put("recordedRecommendation", recommendation);
        snapshot.put("recordedConfidence", "98.00");
        snapshot.put("rules", Map.of("releasePassRateMin", "95.00", "conditionalPassRateMin", "85.00",
                "reworkPassRateMin", "70.00", "scrapDefectRateMin", "50.00", "nullPassRateAction", "HOLD"));
        return snapshot;
    }
}
