package com.cretas.aims.repository;

import com.cretas.aims.dto.decision.DecisionSnapshotComparison.RecordedOutputRelation;
import com.cretas.aims.dto.decision.DecisionSnapshotComparison.Status;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.dto.decision.DecisionRecommendationReplay;
import com.cretas.aims.service.DecisionRecommendationReplayService;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.impl.QualityDispositionRuleServiceImpl;
import com.cretas.aims.service.quality.QualityDispositionDecisionGuard;
import com.cretas.aims.service.decision.QualityRecommendationEvaluatorV1;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.service.DecisionSnapshotComparisonService;
import com.cretas.aims.service.DecisionHistoryQueryService;
import com.cretas.aims.service.impl.DecisionAuditServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hibernate startup always runs; jsonb round trips run against a guarded real PostgreSQL only. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Import({DecisionSnapshotComparisonService.class, DecisionHistoryQueryService.class, DecisionAuditServiceImpl.class, DecisionRecommendationReplayService.class,
        DecisionSnapshotComparisonRepositoryQueryValidationTest.JsonConfig.class})
class DecisionSnapshotComparisonRepositoryQueryValidationTest {
    @Autowired TestEntityManager em;
    @Autowired DecisionAuditLogRepository repository;
    @Autowired DecisionAuditServiceImpl writer;
    @Autowired DecisionSnapshotComparisonService comparison;
    @Autowired DecisionHistoryQueryService history;
    @Autowired DecisionRecommendationReplayService recommendationReplay;
    @Autowired ObjectMapper mapper;

    @TestConfiguration static class JsonConfig {
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url == null || url.isBlank()) return;
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]*test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Decision comparison accepts only isolated loopback *_test databases");
        }
        String schema = "decision_history_recorded_review_test";
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        // This newly selected PG context must not recreate another test class's public tables.
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot initialize isolated recorded-history test schema", failure);
        }
        String isolatedUrl = url + (url.contains("?") ? "&" : "?")
                + "stringtype=unspecified&currentSchema=" + schema;
        properties.add("spring.datasource.url", () -> isolatedUrl);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Test void factoryScopedDerivedQueryStartsAndExecutesInRealJpaContext() {
        assertThat(repository.findByIdAndFactoryId(UUID.randomUUID().toString(), "DECISION-A")).isEmpty();
    }

    @Test @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = "jdbc:postgresql:.*")
    void unchangedWriterJsonbRoundTripReportsSemanticEqualityWithoutInventingIntegrity() {
        DecisionAuditLog baseline = write("DECISION-A", Map.of("amount", 2));
        DecisionAuditLog current = write("DECISION-A", Map.of("amount", 2));
        earlier(baseline, current);
        String originalReplay = current.getReplayData();
        em.flush(); em.clear();
        DecisionAuditLog reloaded = repository.findByIdAndFactoryId(current.getId(), "DECISION-A").orElseThrow();
        assertThat(reloaded.getReplayData()).isNotEqualTo(originalReplay); // PG jsonb changed the bytes.
        var result = comparison.compare("DECISION-A", current.getId(), baseline.getId());
        assertThat(result.status()).isEqualTo(Status.NOT_COMPARABLE);
        assertThat(result.recordedOutputRelation()).isEqualTo(RecordedOutputRelation.SAME);
        assertThat(result.integrityVerified()).isFalse();
        assertThat(result.reason()).isEqualTo("CHECKSUM_MISMATCH_NO_INTEGRITY_PROOF");
        assertThat(result.canonicalRecordedOutputHash()).isEqualTo(result.canonicalBaselineOutputHash());
        assertThat(result.businessExecutionPerformed()).isFalse();
    }

    @Test @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = "jdbc:postgresql:.*")
    void persistedChangedOutputIsVisibleAsUnverifiedRelationWithoutAuditWrites() {
        DecisionAuditLog baseline = write("DECISION-A", Map.of("amount", 2));
        DecisionAuditLog current = write("DECISION-A", Map.of("amount", 3));
        earlier(baseline, current); em.flush(); em.clear();
        long before = repository.count();
        var result = comparison.compare("DECISION-A", current.getId(), baseline.getId());
        assertThat(result.recordedOutputRelation()).isEqualTo(RecordedOutputRelation.DIFFERENT);
        assertThat(result.status()).isEqualTo(Status.NOT_COMPARABLE);
        em.flush(); em.clear();
        assertThat(repository.count()).isEqualTo(before);
        assertThat(repository.findByIdAndFactoryId(current.getId(), "DECISION-A").orElseThrow().getOutputResult())
                .contains("3");
    }

    @Test @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = "jdbc:postgresql:.*")
    void foreignRecordNeverLoadsThroughScopedQueryOrComparison() {
        DecisionAuditLog foreign = write("DECISION-B", Map.of("amount", 9));
        em.flush(); em.clear();
        assertThat(repository.findByIdAndFactoryId(foreign.getId(), "DECISION-A")).isEmpty();
        assertThatThrownBy(() -> comparison.compare("DECISION-A", foreign.getId(), "missing"))
                .isInstanceOf(EntityNotFoundException.class).hasMessage("决策记录不存在或不可访问");
    }

    @Test @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = "jdbc:postgresql:.*")
    void metadataPagingIsFactoryScopedAndTimestampTiesHaveStableIdOrdering() {
        var first = write("DECISION-A", Map.of("amount", 2));
        var second = write("DECISION-A", Map.of("amount", 3));
        var foreign = write("DECISION-B", Map.of("amount", 9));
        var tied = LocalDateTime.of(2026, 1, 1, 12, 0);
        first.setCreatedAt(tied); second.setCreatedAt(tied); foreign.setCreatedAt(tied.plusDays(1));
        em.flush(); em.clear();
        var expectedIds = List.of(first.getId(), second.getId()).stream().sorted(java.util.Comparator.reverseOrder()).toList();
        var page0 = history.list("DECISION-A", 0, 1);
        var page1 = history.list("DECISION-A", 1, 1);
        assertThat(page0.totalElements()).isEqualTo(2); assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.records()).extracting(row -> row.id()).containsExactly(expectedIds.getFirst());
        assertThat(page1.records()).extracting(row -> row.id()).containsExactly(expectedIds.get(1));
        assertThat(history.list("DECISION-A", 2, 1).records()).isEmpty();
        assertThat(history.list("DECISION-NONE", 0, 20).totalElements()).isZero();
    }

    @Test @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = "jdbc:postgresql:.*")
    void browsingMetadataDoesNotChangeRawPersistedEvidenceOrCreateAnAudit() {
        var row = write("DECISION-A", Map.of("privateAmount", 2));
        em.flush(); em.clear();
        var before = repository.findByIdAndFactoryId(row.getId(), "DECISION-A").orElseThrow();
        var beforeReplay = before.getReplayData(); var beforeChecksum = before.getChecksum();
        var beforeUpdated = before.getUpdatedAt(); long count = repository.count();
        var page = history.list("DECISION-A", 0, 20);
        assertThat(page.records()).hasSize(1);
        em.flush(); em.clear();
        var after = repository.findByIdAndFactoryId(row.getId(), "DECISION-A").orElseThrow();
        assertThat(repository.count()).isEqualTo(count);
        assertThat(after.getReplayData()).isEqualTo(beforeReplay);
        assertThat(after.getChecksum()).isEqualTo(beforeChecksum);
        assertThat(after.getUpdatedAt()).isEqualTo(beforeUpdated);
    }

    @Test @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = "jdbc:postgresql:.*")
    void actualQualityContextAndUnchangedWriterReplayAfterJsonbNormalizationWithoutWrites() throws Exception {
        ApprovalChainService approval = mock(ApprovalChainService.class);
        QualityInspection inspection = replayInspection();
        QualityDispositionRuleServiceImpl quality = dispositionService(approval, inspection);
        var executed = quality.executeDisposition(inspection.getFactoryId(), inspection,
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.HOLD, 1L, "synthetic PostgreSQL fixture");
        var row = repository.findByIdAndFactoryId(executed.getAuditLogId(), inspection.getFactoryId()).orElseThrow();
        String beforeJsonb = row.getInputContext();
        em.flush(); em.clear(); clearInvocations(approval);
        var persisted = repository.findByIdAndFactoryId(row.getId(), inspection.getFactoryId()).orElseThrow();
        assertThat(persisted.getInputContext()).isNotEqualTo(beforeJsonb);
        String beforeSnapshot = persisted.getInputContext();
        String beforeLegacyChecksum = persisted.getChecksum();
        var beforeUpdated = persisted.getUpdatedAt(); long count = repository.count();
        var result = recommendationReplay.replay(inspection.getFactoryId(), row.getId());
        assertThat(result.status()).isEqualTo(DecisionRecommendationReplay.Status.REPLAY_MATCH);
        assertThat(result.recordedRecommendation()).isEqualTo("RELEASE");
        assertThat(result.replayedRecommendation()).isEqualTo("RELEASE");
        assertThat(new BigDecimal(result.recordedConfidence())).isEqualByComparingTo("95");
        assertThat(new BigDecimal(result.replayedConfidence())).isEqualByComparingTo("95");
        assertThat(result.snapshotConsistencyVerified()).isTrue();
        assertThat(result.ruleEvaluationPerformed()).isTrue();
        assertThat(result.businessExecutionPerformed()).isFalse();
        assertThat(result.approvalEvaluated()).isFalse();
        em.flush(); em.clear();
        var after = repository.findByIdAndFactoryId(row.getId(), inspection.getFactoryId()).orElseThrow();
        assertThat(repository.count()).isEqualTo(count);
        assertThat(after.getInputContext()).isEqualTo(beforeSnapshot);
        assertThat(after.getChecksum()).isEqualTo(beforeLegacyChecksum);
        assertThat(after.getUpdatedAt()).isEqualTo(beforeUpdated);
        verifyNoInteractions(approval);
        assertThatThrownBy(() -> recommendationReplay.replay("DECISION-FOREIGN", row.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = "jdbc:postgresql:.*")
    void normalizedSnapshotWithSameActionButChangedConfidenceCannotClaimMatchingReplay() throws Exception {
        QualityInspection inspection = replayInspection();
        var quality = new QualityDispositionRuleServiceImpl(mock(ApprovalChainService.class), writer,
                mock(UserRepository.class), mock(QualityDispositionDecisionGuard.class));
        var evaluated = quality.evaluateDisposition(inspection.getFactoryId(), inspection);
        var context = mapper.valueToTree(evaluated.getEvaluationContext());
        var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) context.get(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY);
        snapshot.put("recordedConfidence", "94"); snapshot.remove("snapshotHash");
        snapshot.put("snapshotHash", QualityRecommendationEvaluatorV1.canonicalHash(snapshot));
        @SuppressWarnings("unchecked")
        Map<String, Object> changed = mapper.convertValue(context, Map.class);
        var row = writer.logRuleExecution(inspection.getFactoryId(), "QualityInspection", inspection.getId(), changed,
                Map.of("recommendation", "RELEASE"), List.of("quality-disposition"), "HOLD", 1L, "test", "factory_super_admin");
        em.flush(); em.clear(); long count = repository.count();
        var result = recommendationReplay.replay(inspection.getFactoryId(), row.getId());
        assertThat(result.status()).isEqualTo(DecisionRecommendationReplay.Status.REPLAY_DIFFERENT);
        assertThat(result.recordedRecommendation()).isEqualTo(result.replayedRecommendation());
        assertThat(new BigDecimal(result.recordedConfidence())).isEqualByComparingTo("94");
        assertThat(new BigDecimal(result.replayedConfidence())).isEqualByComparingTo("95");
        assertThat(result.businessExecutionPerformed()).isFalse();
        assertThat(result.approvalEvaluated()).isFalse();
        em.flush(); assertThat(repository.count()).isEqualTo(count);
    }

    @Test @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = "jdbc:postgresql:.*")
    void pendingApprovalSnapshotReplaysOnlyRecommendationAndCannotApproveTheRequest() {
        QualityInspection inspection = replayInspection();
        inspection.setPassRate(new BigDecimal("90")); inspection.setPassCount(new BigDecimal("18"));
        inspection.setFailCount(new BigDecimal("2")); inspection.setResult("FAIL");
        ApprovalChainService approval = mock(ApprovalChainService.class);
        when(approval.requiresApproval(anyString(), any(), anyMap())).thenReturn(true);
        var quality = dispositionService(approval, inspection);
        var executed = quality.executeDisposition(inspection.getFactoryId(), inspection,
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE,
                1L, "synthetic PostgreSQL fixture");
        var row = repository.findByIdAndFactoryId(executed.getAuditLogId(), inspection.getFactoryId()).orElseThrow();
        em.flush(); em.clear(); long count = repository.count();
        var result = recommendationReplay.replay(inspection.getFactoryId(), row.getId());
        assertThat(result.status()).isEqualTo(DecisionRecommendationReplay.Status.REPLAY_MATCH);
        assertThat(result.recordedRecommendation()).isEqualTo("CONDITIONAL_RELEASE");
        assertThat(new BigDecimal(result.replayedConfidence())).isEqualByComparingTo("85");
        assertThat(result.businessExecutionPerformed()).isFalse(); assertThat(result.approvalEvaluated()).isFalse();
        em.flush(); em.clear();
        var after = repository.findByIdAndFactoryId(row.getId(), inspection.getFactoryId()).orElseThrow();
        assertThat(after.getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.PENDING);
        assertThat(after.getNewState()).isEqualTo("PENDING_APPROVAL");
        assertThat(repository.count()).isEqualTo(count);
    }

    private QualityDispositionRuleServiceImpl dispositionService(ApprovalChainService approval, QualityInspection inspection) {
        UserRepository users = mock(UserRepository.class);
        var user = new com.cretas.aims.entity.User(); user.setId(1L); user.setFactoryId(inspection.getFactoryId());
        user.setRoleCode("factory_super_admin"); user.setFullName("test");
        when(users.findById(1L)).thenReturn(java.util.Optional.of(user));
        var guard = mock(QualityDispositionDecisionGuard.class);
        when(guard.lockInspection(inspection.getFactoryId(), inspection)).thenReturn(inspection);
        return new QualityDispositionRuleServiceImpl(approval, writer, users, guard);
    }

    private QualityInspection replayInspection() {
        QualityInspection inspection = new QualityInspection(); inspection.setId(UUID.randomUUID().toString());
        inspection.setFactoryId("DECISION-A"); inspection.setProductionBatchId(1L);
        inspection.setInspectionDate(java.time.LocalDate.of(2026, 11, 7));
        inspection.setSampleSize(new BigDecimal("20")); inspection.setPassCount(new BigDecimal("19"));
        inspection.setFailCount(BigDecimal.ONE); inspection.setPassRate(new BigDecimal("95")); inspection.setResult("PASS");
        return inspection;
    }

    private DecisionAuditLog write(String factory, Map<String, Object> output) {
        return writer.logRuleExecution(factory, "ProductionPlan", "plan", Map.of("qty", 1), output,
                List.of("rule"), "recorded", 1L, "test", "factory_super_admin");
    }

    private void earlier(DecisionAuditLog baseline, DecisionAuditLog current) {
        baseline.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        current.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 1));
    }
}
