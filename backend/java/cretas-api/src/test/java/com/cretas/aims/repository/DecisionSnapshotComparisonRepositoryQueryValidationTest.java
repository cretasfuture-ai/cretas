package com.cretas.aims.repository;

import com.cretas.aims.dto.decision.DecisionSnapshotComparison.RecordedOutputRelation;
import com.cretas.aims.dto.decision.DecisionSnapshotComparison.Status;
import com.cretas.aims.entity.DecisionAuditLog;
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

import static org.assertj.core.api.Assertions.*;

/** Hibernate startup always runs; jsonb round trips run against a guarded real PostgreSQL only. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Import({DecisionSnapshotComparisonService.class, DecisionHistoryQueryService.class, DecisionAuditServiceImpl.class,
        DecisionSnapshotComparisonRepositoryQueryValidationTest.JsonConfig.class})
class DecisionSnapshotComparisonRepositoryQueryValidationTest {
    @Autowired TestEntityManager em;
    @Autowired DecisionAuditLogRepository repository;
    @Autowired DecisionAuditServiceImpl writer;
    @Autowired DecisionSnapshotComparisonService comparison;
    @Autowired DecisionHistoryQueryService history;

    @TestConfiguration static class JsonConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
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

    private DecisionAuditLog write(String factory, Map<String, Object> output) {
        return writer.logRuleExecution(factory, "ProductionPlan", "plan", Map.of("qty", 1), output,
                List.of("rule"), "recorded", 1L, "test", "factory_super_admin");
    }

    private void earlier(DecisionAuditLog baseline, DecisionAuditLog current) {
        baseline.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        current.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 1));
    }
}
