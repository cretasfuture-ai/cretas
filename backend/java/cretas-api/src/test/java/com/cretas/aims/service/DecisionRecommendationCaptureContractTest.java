package com.cretas.aims.service;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.decision.QualityRecommendationEvaluatorV1;
import com.cretas.aims.service.impl.QualityDispositionRuleServiceImpl;
import com.cretas.aims.service.quality.QualityDispositionDecisionGuard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/** The existing authorized rule context must contain server-owned future replay evidence. */
class DecisionRecommendationCaptureContractTest {
    @Test void authorizedEvaluationOverwritesCallerReservedKeyWithCompleteServerSnapshot() {
        ApprovalChainService approval = mock(ApprovalChainService.class);
        when(approval.requiresApproval(anyString(), any(), anyMap())).thenReturn(false);
        var audit = mock(DecisionAuditService.class);
        var service = new QualityDispositionRuleServiceImpl(approval, audit,
                mock(UserRepository.class), mock(QualityDispositionDecisionGuard.class));
        QualityInspection inspection = inspection();
        Map<String, Object> caller = new HashMap<>();
        caller.put(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY,
                Map.of("factoryId", "ATTACKER", "recordedRecommendation", "RELEASE"));

        var result = service.evaluateDisposition("F006", inspection, caller);
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) result.getEvaluationContext()
                .get(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY);

        assertThat(snapshot).containsEntry("schemaVersion", QualityRecommendationEvaluatorV1.SNAPSHOT_SCHEMA_VERSION)
                .containsEntry("evaluatorId", QualityRecommendationEvaluatorV1.EVALUATOR_ID)
                .containsEntry("evaluatorVersion", QualityRecommendationEvaluatorV1.EVALUATOR_VERSION)
                .containsEntry("ruleBundleHash", QualityRecommendationEvaluatorV1.RULE_BUNDLE_HASH)
                .containsEntry("factoryId", "F006")
                .containsEntry("inspectionId", "QI-1")
                .containsEntry("recordedRecommendation", "RELEASE")
                .containsEntry("passRate", "98.00")
                .containsEntry("defectRate", "2.00")
                .containsKey("rules").containsKey("snapshotHash");
        assertThat(snapshot.get("snapshotHash").toString()).matches("[0-9a-f]{64}");
        assertThat(caller.get(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY)).isNotSameAs(snapshot);
    }

    @Test void nullAndBoundaryRecommendationSemanticsRemainV1() {
        var evaluator = new QualityRecommendationEvaluatorV1();
        assertThat(evaluator.evaluate(new QualityRecommendationEvaluatorV1.Input(null, null, null))
                .recommendedAction()).isEqualTo(QualityDispositionRuleService.DispositionAction.HOLD);
        assertThat(evaluator.evaluate(new QualityRecommendationEvaluatorV1.Input(
                new BigDecimal("95.00"), new BigDecimal("0"), "PASS")).recommendedAction())
                .isEqualTo(QualityDispositionRuleService.DispositionAction.RELEASE);
        assertThat(evaluator.evaluate(new QualityRecommendationEvaluatorV1.Input(
                new BigDecimal("69.99"), new BigDecimal("51.00"), "FAIL")).recommendedAction())
                .isEqualTo(QualityDispositionRuleService.DispositionAction.SCRAP);
    }

    private static QualityInspection inspection() {
        return QualityInspection.builder().id("QI-1").factoryId("F006").productionBatchId(17L)
                .inspectorId(7L).inspectionDate(LocalDate.of(2026, 11, 7))
                .sampleSize(new BigDecimal("100.00")).passCount(new BigDecimal("98.00"))
                .failCount(new BigDecimal("2.00")).passRate(new BigDecimal("98.00"))
                .result("PASS").build();
    }
}
