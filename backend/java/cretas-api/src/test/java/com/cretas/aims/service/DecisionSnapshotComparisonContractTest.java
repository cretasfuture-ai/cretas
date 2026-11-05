package com.cretas.aims.service;

import com.cretas.aims.dto.decision.DecisionSnapshotComparison;
import com.cretas.aims.dto.decision.DecisionSnapshotComparison.Status;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.DecisionAuditLog.DecisionType;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionSnapshotComparisonContractTest {
    private final DecisionAuditLogRepository repository = mock(DecisionAuditLogRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DecisionSnapshotComparisonService service = new DecisionSnapshotComparisonService(repository, mapper);
    private DecisionAuditLog current;
    private DecisionAuditLog baseline;

    @BeforeEach void setup() throws Exception {
        current = snapshot("current", "{\"qty\":1}", "{\"a\":1,\"b\":{\"c\":2,\"d\":3}}");
        baseline = snapshot("baseline", "{\"qty\":1}", "{\"b\":{\"d\":3,\"c\":2},\"a\":1}");
        when(repository.findByIdAndFactoryId("current", "F006")).thenReturn(Optional.of(current));
        when(repository.findByIdAndFactoryId("baseline", "F006")).thenReturn(Optional.of(baseline));
    }

    @Test void sameSemanticResultIgnoresObjectKeyOrderingWithoutExecutingOrSaving() {
        DecisionSnapshotComparison result = compare();
        assertThat(result.status()).isEqualTo(Status.SAME_RESULT);
        assertThat(result.canonicalRecordedOutputHash()).isEqualTo(result.canonicalBaselineOutputHash()).hasSize(64);
        assertThat(result.businessExecutionPerformed()).isFalse();
        verify(repository).findByIdAndFactoryId("current", "F006"); verify(repository).findByIdAndFactoryId("baseline", "F006");
        verifyNoMoreInteractions(repository);
    }

    @Test void changedResultAcrossRuleVersionsIsReportedWithoutChoosingWinner() throws Exception {
        current = snapshot("current", "{\"qty\":1}", "{\"amount\":2}");
        current.setRuleConfigVersion(2);
        when(repository.findByIdAndFactoryId("current", "F006")).thenReturn(Optional.of(current));
        var result = compare();
        assertThat(result.status()).isEqualTo(Status.DIFFERENT_RESULT);
        assertThat(result.currentRuleConfigVersion()).isEqualTo(2);
        assertThat(result.baselineRuleConfigVersion()).isEqualTo(1);
        assertThat(result.canonicalRecordedOutputHash()).isNotEqualTo(result.canonicalBaselineOutputHash());
    }

    @Test void differentInputsAreNotComparable() throws Exception {
        current = snapshot("current", "{\"qty\":2}", baseline.getOutputResult());
        when(repository.findByIdAndFactoryId("current", "F006")).thenReturn(Optional.of(current));
        assertThat(compare().reason()).isEqualTo("INPUT_MISMATCH");
        assertThat(compare().canonicalRecordedOutputHash()).isNull();
    }

    @Test void foreignCurrentIdIsIndistinguishableFromMissingAndStopsBeforeBaselineRead() {
        when(repository.findByIdAndFactoryId("current", "F006")).thenReturn(Optional.empty());
        assertThatThrownBy(this::compare).isInstanceOf(EntityNotFoundException.class)
                .hasMessage("决策记录不存在或不可访问");
        verify(repository, never()).findByIdAndFactoryId("baseline", "F006");
        when(repository.findByIdAndFactoryId("current", "F006")).thenReturn(Optional.empty());
        assertThatThrownBy(this::compare).hasMessage("决策记录不存在或不可访问");
    }

    @Test void foreignBaselineIsAlsoRejected() {
        when(repository.findByIdAndFactoryId("baseline", "F006")).thenReturn(Optional.empty());
        assertThatThrownBy(this::compare).isInstanceOf(EntityNotFoundException.class);
    }

    @Test void differentEntityOrDecisionTypeCannotCompare() {
        baseline.setEntityId("another");
        assertThat(compare().reason()).isEqualTo("BUSINESS_SCOPE_MISMATCH");
        baseline.setEntityId("plan"); baseline.setDecisionType(DecisionType.APPROVAL);
        assertThat(compare().status()).isEqualTo(Status.NOT_COMPARABLE);
    }

    @Test void tamperedSnapshotCannotAppearEqual() {
        current.setReplayData(current.getReplayData().replace("qty", "bad"));
        assertInvalid();
    }

    @Test void snapshotCannotContradictRecordedColumns() {
        current.setOutputResult("{\"different\":1}");
        assertInvalid();
    }

    @Test void missingOutputIsUnknownRatherThanEqualNulls() throws Exception {
        current = snapshot("current", "{\"qty\":1}", "null");
        when(repository.findByIdAndFactoryId("current", "F006")).thenReturn(Optional.of(current));
        assertInvalid();
    }

    @Test void disabledReplayOrMissingChecksumCannotCompare() {
        current.setIsReplayable(false); assertInvalid();
        current.setIsReplayable(true); current.setChecksum(null); assertInvalid();
    }

    @Test void malformedJsonWithMatchingChecksumCannotCompare() throws Exception {
        current.setReplayData("not-json"); current.setChecksum(hash("not-json")); assertInvalid();
    }

    @Test void oversizedSnapshotCannotCompare() throws Exception {
        current.setReplayData(" ".repeat(262145));
        current.setChecksum(hash(current.getReplayData())); assertInvalid();
    }

    @Test void responseOmitsInputOutputAndPeopleDetails() throws Exception {
        String json = mapper.writeValueAsString(compare());
        assertThat(json).doesNotContain("inputContext", "outputResult", "executor", "approver", "replayData", "qty");
    }

    @Test void checksumMismatchNeverUpgradesSemanticEqualityToIntegrityEvidence() {
        current.setChecksum("0".repeat(64));
        var result = compare();
        assertThat(result.status()).isEqualTo(Status.NOT_COMPARABLE);
        assertThat(result.reason()).isEqualTo("CHECKSUM_MISMATCH_NO_INTEGRITY_PROOF");
        assertThat(result.integrityVerified()).isFalse();
        assertThat(result.recordedOutputRelation()).isEqualTo(DecisionSnapshotComparison.RecordedOutputRelation.SAME);
    }

    @Test void invalidChecksumFormatCannotYieldEvenSemanticRelation() {
        current.setChecksum("not-a-checksum"); assertInvalid();
    }

    @Test void selfOrFutureOrMissingDateIsNotHistoricalBaseline() {
        assertThat(service.compare("F006", "current", "current").reason()).isEqualTo("INVALID_HISTORICAL_BASELINE");
        baseline.setCreatedAt(current.getCreatedAt().plusDays(1));
        assertThat(compare().reason()).isEqualTo("INVALID_HISTORICAL_BASELINE");
        baseline.setCreatedAt(null);
        assertThat(compare().status()).isEqualTo(Status.NOT_COMPARABLE);
    }

    private DecisionSnapshotComparison compare() { return service.compare("F006", "current", "baseline"); }
    private void assertInvalid() {
        assertThat(compare().status()).isEqualTo(Status.NOT_COMPARABLE);
        assertThat(compare().reason()).isEqualTo("SNAPSHOT_MISSING_OR_INVALID");
        assertThat(compare().canonicalRecordedOutputHash()).isNull();
    }
    private DecisionAuditLog snapshot(String id, String input, String output) throws Exception {
        String replay = "{\"inputContext\":" + input + ",\"outputResult\":" + output + ",\"timestamp\":\"old\"}";
        return DecisionAuditLog.builder().id(id).factoryId("F006").entityType("ProductionPlan")
                .entityId("plan").decisionType(DecisionType.RULE_EXECUTION).inputContext(input)
                .outputResult(output).replayData(replay).checksum(hash(replay)).isReplayable(true)
                .ruleConfigId("rules").ruleConfigVersion(1).createdAt(java.time.LocalDateTime.of(2026, 1, 1, 0, id.equals("baseline") ? 0 : 1)).build();
    }
    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
