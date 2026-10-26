package com.cretas.aims.engine;

import com.cretas.aims.engine.AutonomousGrantService.Binding;
import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.repository.ai.AutonomousToolGrantRepository;
import java.time.LocalDateTime;
import java.util.List;
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
    private static final String REV = "rev-a";
    private static final Binding BOUND = new Binding("F1", "SCHEDULER", "78", "user_disable", 7L, DIGEST, REV, V1);

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
        AutonomousToolGrant grant = issue(BOUND, 5);
        var first = grants.consume(BOUND, "fire:1", now, grantor -> true).orElseThrow();
        var again = grants.consume(BOUND, "fire:1", now, grantor -> true).orElseThrow();

        assertThat(first.replay()).isFalse();
        assertThat(again.replay()).isTrue();
        assertThat(again.idempotencyKey()).isEqualTo(first.idempotencyKey())
                .isEqualTo("autonomous:" + grant.getId() + ":fire:1");
        assertThat(repository.findById(grant.getId()).orElseThrow().getUsedRuns()).isEqualTo(1);
    }

    @Test void toolUpgradeOrConfigEditStopsNewRunsButAConsumedRunStillReplaysWithItsVersion() {
        issue(BOUND, 5);
        var first = grants.consume(BOUND, "fire:1", now, grantor -> true).orElseThrow();
        Binding upgraded = with(BOUND, null, "3.0.0");
        Binding edited = with(BOUND, "rev-b", null);

        // New runs under an upgraded tool or an edited config find no usable grant.
        assertThat(grants.consume(upgraded, "fire:2", now, grantor -> true)).isEmpty();
        assertThat(grants.consume(edited, "fire:2", now, grantor -> true)).isEmpty();
        // The run already consumed replays, naming the version the first attempt used.
        var replay = grants.consume(upgraded, "fire:1", now, grantor -> true).orElseThrow();
        assertThat(first.descriptorVersion()).isEqualTo(V1);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.descriptorVersion()).isEqualTo(V1);
        assertThat(((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM autonomous_tool_grant_runs WHERE descriptor_version = '2.0.0'")
                .getSingleResult()).longValue()).isEqualTo(1);
    }

    @Test void configRevisionChangesWithEveryRunDefiningFieldAndOnlyThose() {
        String base = AutonomousGrantService.configRevision("expiry", "0 0 7 * * ?", "user_disable", DIGEST, 7L);
        assertThat(AutonomousGrantService.configRevision("expiry", "0 0 7 * * ?", "user_disable", DIGEST, 7L))
                .isEqualTo(base).hasSize(64);
        assertThat(List.of(
                AutonomousGrantService.configRevision("other", "0 0 7 * * ?", "user_disable", DIGEST, 7L),
                AutonomousGrantService.configRevision("expiry", "0 0 8 * * ?", "user_disable", DIGEST, 7L),
                AutonomousGrantService.configRevision("expiry", "0 0 7 * * ?", "product_create", DIGEST, 7L),
                AutonomousGrantService.configRevision("expiry", "0 0 7 * * ?", "user_disable", "digest-b", 7L),
                AutonomousGrantService.configRevision("expiry", "0 0 7 * * ?", "user_disable", DIGEST, 8L)))
                .doesNotContain(base).doesNotHaveDuplicates();
    }

    @Test void distinctRunKeysStopAtTheBudget() {
        AutonomousToolGrant grant = issue(BOUND, 2);
        assertThat(grants.consume(BOUND, "fire:1", now, grantor -> true)).isPresent();
        assertThat(grants.consume(BOUND, "fire:2", now, grantor -> true)).isPresent();
        assertThat(grants.consume(BOUND, "fire:3", now, grantor -> true)).isEmpty();
        // An already consumed run key still replays after the budget is used up.
        assertThat(grants.consume(BOUND, "fire:2", now, grantor -> true)).hasValueSatisfying(c -> assertThat(c.replay()).isTrue());
        assertThat(repository.findById(grant.getId()).orElseThrow().getUsedRuns()).isEqualTo(2);
    }

    @Test void aGrantWhoseGrantorFailsTheCheckChargesNoNewRunButStillReplays() {
        AutonomousToolGrant grant = issue(BOUND, 5);
        var first = grants.consume(BOUND, "fire:1", now, grantor -> true).orElseThrow();
        List<Long> asked = new java.util.ArrayList<>();

        assertThat(grants.consume(BOUND, "fire:2", now, grantor -> asked.add(grantor) && false)).isEmpty();
        assertThat(asked).containsExactly(7L);
        // A run already consumed is only reported, never executed again, whatever the grantor's status.
        assertThat(grants.consume(BOUND, "fire:1", now, grantor -> false))
                .hasValueSatisfying(c -> assertThat(c.idempotencyKey()).isEqualTo(first.idempotencyKey()));
        assertThat(repository.findById(grant.getId()).orElseThrow().getUsedRuns()).isEqualTo(1);
    }

    @Test void revokedOrExpiredGrantsAreNotConsumed() {
        AutonomousToolGrant revoked = issue(BOUND, 5);
        assertThat(grants.revoke("F1", revoked.getId(), 7L, now)).isPresent();
        assertThat(grants.consume(BOUND, "fire:1", now, grantor -> true)).isEmpty();

        clean();
        AutonomousToolGrant live = issue(BOUND, 5);
        assertThat(grants.consume(BOUND, "fire:1", live.getExpiresAt(), grantor -> true)).isEmpty();
        assertThat(grants.consume(BOUND, "fire:1", live.getExpiresAt().minusNanos(1000), grantor -> true)).isPresent();
    }

    @Test void grantOnlyAppliesToItsExactFactorySourceToolUserAndParameters() {
        issue(BOUND, 5);
        for (Binding other : List.of(
                new Binding("F2", "SCHEDULER", "78", "user_disable", 7L, DIGEST, REV, V1),
                new Binding("F1", "TRIGGER", "78", "user_disable", 7L, DIGEST, REV, V1),
                new Binding("F1", "SCHEDULER", "79", "user_disable", 7L, DIGEST, REV, V1),
                new Binding("F1", "SCHEDULER", "78", "product_create", 7L, DIGEST, REV, V1),
                new Binding("F1", "SCHEDULER", "78", "user_disable", 8L, DIGEST, REV, V1),
                new Binding("F1", "SCHEDULER", "78", "user_disable", 7L, "digest-b", REV, V1))) {
            assertThat(grants.consume(other, "fire:1", now, grantor -> true)).as(other.toString()).isEmpty();
        }
        assertThat(grants.consume(BOUND, "fire:1", now, grantor -> true)).isPresent();
    }

    @Test void revokeAndListAreScopedToTheFactory() {
        AutonomousToolGrant grant = issue(BOUND, 5);
        assertThat(grants.revoke("F2", grant.getId(), 9L, now)).isEmpty();
        assertThat(grants.list("F2")).isEmpty();
        assertThat(grants.list("F1")).extracting(AutonomousToolGrant::getId).containsExactly(grant.getId());
        assertThat(repository.findById(grant.getId()).orElseThrow().getRevokedAt()).isNull();
    }

    @Test void issueBoundsBudgetAndExpiry() {
        assertThatThrownBy(() -> grants.issue(BOUND, 0, now.plusDays(1), 7L, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grants.issue(BOUND, AutonomousGrantService.MAX_RUNS_LIMIT + 1, now.plusDays(1), 7L, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grants.issue(BOUND, 1, now, 7L, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grants.issue(BOUND, 1, now.plusDays(AutonomousGrantService.MAX_DAYS).plusMinutes(1),
                7L, now)).isInstanceOf(IllegalArgumentException.class);
        assertThat(grants.list("F1")).isEmpty();
    }

    private AutonomousToolGrant issue(Binding binding, int maxRuns) {
        return grants.issue(binding, maxRuns, now.plusDays(1), 7L, now);
    }

    private static Binding with(Binding b, String revision, String version) {
        return new Binding(b.factoryId(), b.sourceType(), b.sourceRef(), b.toolName(), b.runAsUserId(),
                b.parametersDigest(), revision == null ? b.configRevision() : revision,
                version == null ? b.descriptorVersion() : version);
    }
}
