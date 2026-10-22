package com.cretas.aims.engine;

import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.repository.ai.AutonomousToolGrantRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Hibernate mapping and queries behind delegated autonomous grants: consumption happens in its
 * own transaction against the locked bound grants, so each test commits its fixtures. H2 by
 * default; set CRETAS_WORKFLOW_PG_URL to run the same assertions on PostgreSQL.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = AutonomousToolGrant.class)
@EnableJpaRepositories(basePackageClasses = AutonomousToolGrantRepository.class)
@Import(AutonomousGrantService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutonomousGrantRepositoryQueryValidationTest {

    private static final String DIGEST = "digest-a";
    private static final String V1 = "2.0.0";

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

    @Autowired AutonomousGrantService grants;
    @Autowired AutonomousToolGrantRepository repository;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    // PostgreSQL keeps microseconds (rounded): boundary checks compare against what the row stores.
    private final LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

    @BeforeEach void clean() {
        tx.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM autonomous_tool_grant_runs").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM autonomous_tool_grants").executeUpdate();
        });
    }

    @Test void oneRunKeyConsumesOnceAndThenReplaysTheSameIdempotencyKey() {
        AutonomousToolGrant grant = issue("F1", 5);
        var first = consume("F1", 7L, DIGEST, "fire:1").orElseThrow();
        var again = consume("F1", 7L, DIGEST, "fire:1").orElseThrow();

        assertThat(first.replay()).isFalse();
        assertThat(again.replay()).isTrue();
        assertThat(again.idempotencyKey()).isEqualTo(first.idempotencyKey())
                .isEqualTo("autonomous:" + grant.getId() + ":fire:1");
        assertThat(repository.findById(grant.getId()).orElseThrow().getUsedRuns()).isEqualTo(1);
    }

    @Test void replayKeepsTheDescriptorVersionOfTheFirstConsumptionAfterAToolUpgrade() {
        issue("F1", 5);
        var first = consume("F1", 7L, DIGEST, "fire:1").orElseThrow();
        // The tool was upgraded before the replay: the replay still names the version the first run used.
        var replay = grants.consume("F1", "SCHEDULER", "78", "user_disable", 7L, DIGEST, "3.0.0", "fire:1", now)
                .orElseThrow();
        assertThat(first.descriptorVersion()).isEqualTo(V1);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.descriptorVersion()).isEqualTo(V1);
        assertThat(((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM autonomous_tool_grant_runs WHERE descriptor_version = '2.0.0'")
                .getSingleResult()).longValue()).isEqualTo(1);
    }

    @Test void distinctRunKeysStopAtTheBudget() {
        AutonomousToolGrant grant = issue("F1", 2);
        assertThat(consume("F1", 7L, DIGEST, "fire:1")).isPresent();
        assertThat(consume("F1", 7L, DIGEST, "fire:2")).isPresent();
        assertThat(consume("F1", 7L, DIGEST, "fire:3")).isEmpty();
        // An already consumed run key still replays after the budget is used up.
        assertThat(consume("F1", 7L, DIGEST, "fire:2")).hasValueSatisfying(c -> assertThat(c.replay()).isTrue());
        assertThat(repository.findById(grant.getId()).orElseThrow().getUsedRuns()).isEqualTo(2);
    }

    @Test void revokedOrExpiredGrantsAreNotConsumed() {
        AutonomousToolGrant revoked = issue("F1", 5);
        assertThat(grants.revoke("F1", revoked.getId(), 7L, now)).isPresent();
        assertThat(consume("F1", 7L, DIGEST, "fire:1")).isEmpty();

        clean();
        AutonomousToolGrant live = issue("F1", 5);
        assertThat(grants.consume("F1", "SCHEDULER", "78", "user_disable", 7L, DIGEST, V1, "fire:1",
                live.getExpiresAt())).isEmpty();
        assertThat(grants.consume("F1", "SCHEDULER", "78", "user_disable", 7L, DIGEST, V1, "fire:1",
                live.getExpiresAt().minusNanos(1000))).isPresent();
    }

    @Test void grantOnlyAppliesToItsExactFactorySourceToolUserAndParameters() {
        issue("F1", 5);
        assertThat(consume("F2", 7L, DIGEST, "fire:1")).isEmpty();
        assertThat(consume("F1", 8L, DIGEST, "fire:1")).isEmpty();
        assertThat(consume("F1", 7L, "digest-b", "fire:1")).isEmpty();
        assertThat(grants.consume("F1", "TRIGGER", "78", "user_disable", 7L, DIGEST, V1, "fire:1", now)).isEmpty();
        assertThat(grants.consume("F1", "SCHEDULER", "79", "user_disable", 7L, DIGEST, V1, "fire:1", now)).isEmpty();
        assertThat(grants.consume("F1", "SCHEDULER", "78", "product_create", 7L, DIGEST, V1, "fire:1", now)).isEmpty();
        assertThat(consume("F1", 7L, DIGEST, "fire:1")).isPresent();
    }

    @Test void revokeAndListAreScopedToTheFactory() {
        AutonomousToolGrant grant = issue("F1", 5);
        assertThat(grants.revoke("F2", grant.getId(), 9L, now)).isEmpty();
        assertThat(grants.list("F2")).isEmpty();
        assertThat(grants.list("F1")).extracting(AutonomousToolGrant::getId).containsExactly(grant.getId());
        assertThat(repository.findById(grant.getId()).orElseThrow().getRevokedAt()).isNull();
    }

    @Test void issueBoundsBudgetAndExpiry() {
        assertThatThrownBy(() -> grants.issue("F1", "SCHEDULER", "78", "user_disable", 7L, DIGEST, 0,
                now.plusDays(1), 7L, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grants.issue("F1", "SCHEDULER", "78", "user_disable", 7L, DIGEST,
                AutonomousGrantService.MAX_RUNS_LIMIT + 1, now.plusDays(1), 7L, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grants.issue("F1", "SCHEDULER", "78", "user_disable", 7L, DIGEST, 1,
                now, 7L, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grants.issue("F1", "SCHEDULER", "78", "user_disable", 7L, DIGEST, 1,
                now.plusDays(AutonomousGrantService.MAX_DAYS).plusMinutes(1), 7L, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(grants.list("F1")).isEmpty();
    }

    private AutonomousToolGrant issue(String factoryId, int maxRuns) {
        return grants.issue(factoryId, "SCHEDULER", "78", "user_disable", 7L, DIGEST, maxRuns,
                now.plusDays(1), 7L, now);
    }

    private Optional<AutonomousGrantService.Consumption> consume(String factoryId, Long user, String digest,
            String runKey) {
        return grants.consume(factoryId, "SCHEDULER", "78", "user_disable", user, digest, V1, runKey, now);
    }
}
