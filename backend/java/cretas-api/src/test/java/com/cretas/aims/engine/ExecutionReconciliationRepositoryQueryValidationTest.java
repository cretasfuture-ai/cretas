package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real Hibernate parsing and execution of the read-only reconciliation queries (H2 by default;
 * PostgreSQL when CRETAS_WORKFLOW_PG_URL is set): only stale EXECUTING tokens, IN_DOUBT records,
 * stale IN_PROGRESS records and unfinished audits are reported, and nothing is changed.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {IntentPreviewToken.class, ToolExecutionAuditEvent.class})
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.ai")
class ExecutionReconciliationRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    @Autowired TestEntityManager testEntityManager;
    @Autowired EntityManager entityManager;

    @Test void reportsOnlyStaleUnresolvedExecutionsAndChangesNothing() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime old = now.minusHours(2);
        Long staleToken = token("stale", IntentPreviewToken.TokenStatus.EXECUTING, old);
        token("fresh", IntentPreviewToken.TokenStatus.EXECUTING, now);
        token("pending", IntentPreviewToken.TokenStatus.PENDING, old);
        record("doubt", "IN_DOUBT", now);
        record("stuck", "IN_PROGRESS", old);
        record("busy", "IN_PROGRESS", now);
        record("done", "SUCCEEDED", old);
        audit("open-old", "STARTED", old);
        audit("open-new", "STARTED", now);
        audit("closed", "COMPLETED", old);
        testEntityManager.flush();
        testEntityManager.clear();

        var snapshot = new ExecutionReconciliationReporter(entityManager, 30).report(now);

        assertThat(snapshot.status()).isEqualTo("FOUND");
        assertThat(snapshot.executingTokens()).isEqualTo(1L);
        assertThat(snapshot.inDoubtRecords()).isEqualTo(1L);
        assertThat(snapshot.staleInProgressRecords()).isEqualTo(1L);
        assertThat(snapshot.unfinishedAudits()).isEqualTo(1L);
        assertThat(snapshot.samples()).containsExactlyInAnyOrder("F-RC:token:" + staleToken,
                "F-RC:idempotency:doubt", "F-RC:idempotency:stuck", "F-RC:audit:open-old");
        // Read-only: nothing it reported was resolved or rewritten.
        assertThat(entityManager.createNativeQuery("SELECT status FROM intent_preview_tokens WHERE id = :id")
                .setParameter("id", staleToken).getSingleResult()).isEqualTo("EXECUTING");
        assertThat(entityManager.createNativeQuery("SELECT state FROM tool_execution_idempotency WHERE id = 'doubt'")
                .getSingleResult()).isEqualTo("IN_DOUBT");
    }

    @Test void nothingStaleIsClean() {
        token("fresh", IntentPreviewToken.TokenStatus.EXECUTING, LocalDateTime.now());
        testEntityManager.flush();
        var snapshot = new ExecutionReconciliationReporter(entityManager, 30).report(LocalDateTime.now());
        assertThat(snapshot.status()).isEqualTo("CLEAN");
        assertThat(snapshot.executingTokens()).isZero();
    }

    @Test void aFailedQueryIsUnmeasuredNeverClean() {
        EntityManager broken = mock(EntityManager.class);
        when(broken.createQuery(anyString(), any())).thenThrow(new IllegalStateException("db down"));
        var snapshot = new ExecutionReconciliationReporter(broken, 30).report(LocalDateTime.now());
        assertThat(snapshot.status()).isEqualTo("UNMEASURED");
        assertThat(snapshot.executingTokens()).isNull();
        assertThat(snapshot.error()).contains("db down");
    }

    private Long token(String name, IntentPreviewToken.TokenStatus status, LocalDateTime claimedAt) {
        var parameters = JsonNodeFactory.instance.objectNode();
        IntentPreviewToken token = IntentPreviewToken.builder()
                .token("opaque-" + name).factoryId("F-RC").tenantId("F-RC").userId(42L)
                .intentCode("MATERIAL_EXPIRED_QUERY").toolName("material_expired_query").descriptorVersion("1.0.0")
                .executionMode(ToolExecutionMode.EXECUTE).parametersHash(ToolCommandDigest.parametersHash(parameters))
                .commandDigest("digest-" + name).previewData("{}").status(status)
                .claimId(status == IntentPreviewToken.TokenStatus.EXECUTING ? "claim-" + name : null)
                .claimedAt(status == IntentPreviewToken.TokenStatus.EXECUTING ? claimedAt : null)
                .expiresAt(claimedAt.plusMinutes(5)).build();
        testEntityManager.persist(token);
        testEntityManager.flush();
        entityManager.createNativeQuery("UPDATE intent_preview_tokens SET created_at = :at WHERE id = :id")
                .setParameter("at", claimedAt).setParameter("id", token.getId()).executeUpdate();
        return token.getId();
    }

    private void record(String id, String state, LocalDateTime updatedAt) {
        testEntityManager.persist(ToolExecutionIdempotencyRecord.reserve(id, "F-RC", "FACTORY", PrincipalType.USER, "42",
                "material_expired_query", "1.0.0", hash("idem-" + id), hash("command-" + id), hash("token-" + id),
                "audit-" + id, updatedAt));
        testEntityManager.flush();
        entityManager.createNativeQuery("UPDATE tool_execution_idempotency SET state = :state, updated_at = :at WHERE id = :id")
                .setParameter("state", state).setParameter("at", updatedAt).setParameter("id", id).executeUpdate();
    }

    private void audit(String id, String state, LocalDateTime startedAt) {
        testEntityManager.persist(ToolExecutionAuditEvent.start(id, hash("request-" + id), hash("correlation-" + id),
                hash("trace-" + id), "F-RC", "FACTORY", PrincipalType.USER, "42", "material_expired_query", "1.0.0",
                ToolExecutionSource.SCHEDULER, ToolExecutionMode.EXECUTE, startedAt));
        testEntityManager.flush();
        entityManager.createNativeQuery("UPDATE tool_execution_audit_events SET state = :state WHERE id = :id")
                .setParameter("state", state).setParameter("id", id).executeUpdate();
    }

    private static String hash(String value) {
        return ToolCommandDigest.persistentSecretFingerprint(value);
    }
}
