package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryAssemblyActivationController.Recoverability;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ActivationPlan;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleActivationPlan;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.PlanStatus;
import com.cretas.aims.entity.blueprintapproval.FactoryAssemblyApproval;
import com.cretas.aims.repository.blueprintapproval.FactoryAssemblyApprovalRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recovering formal-activation approvals without typing their number. The real Hibernate query
 * isolates factory, blueprint, status and soft deletion; the blueprint version lives in the stored
 * plan snapshot, so whether a listed request may continue is judged from that snapshot against the
 * server's current blueprint. Listing never authorizes: approve and execute keep every server check.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class FactoryAssemblyApprovalListRepositoryQueryValidationTest {

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

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");

    @Autowired TestEntityManager entityManager;
    @Autowired FactoryAssemblyApprovalRepository approvals;

    @Test
    @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
    void theRealQueryReturnsOnlyThisFactorysBlueprintInRecoverableStatesNewestFirst() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "FA-" + suffix, other = "FB-" + suffix, blueprint = "bp-" + suffix, otherBlueprint = "bq-" + suffix;
        String requested = persist(factory, blueprint, "modules", "REQUESTED", LocalDateTime.of(2026, 10, 24, 9, 0));
        String approved = persist(factory, blueprint, "workflow", "APPROVED", LocalDateTime.of(2026, 10, 24, 10, 0));
        String executed = persist(factory, blueprint, "modules", "EXECUTED", LocalDateTime.of(2026, 10, 24, 11, 0));
        persist(factory, blueprint, "modules", "REJECTED", LocalDateTime.of(2026, 10, 24, 12, 0));
        persist(factory, otherBlueprint, "modules", "REQUESTED", LocalDateTime.of(2026, 10, 24, 13, 0));
        persist(other, blueprint, "modules", "REQUESTED", LocalDateTime.of(2026, 10, 24, 14, 0));
        String deleted = persist(factory, blueprint, "modules", "REQUESTED", LocalDateTime.of(2026, 10, 24, 15, 0));
        entityManager.flush();
        entityManager.getEntityManager().createNativeQuery(
                "UPDATE factory_assembly_approvals SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id")
                .setParameter("id", deleted).executeUpdate();
        entityManager.clear();

        List<FactoryAssemblyApproval> rows = approvals.findRecoverable(factory, blueprint,
                FactoryAssemblyActivationController.RECOVERABLE_STATUSES, PageRequest.of(0, 50));

        assertThat(rows).extracting(FactoryAssemblyApproval::getId).containsExactly(executed, approved, requested);
        // The other factory's and the other blueprint's requests exist and are reachable by their own scope.
        assertThat(approvals.findRecoverable(other, blueprint, Set.of("REQUESTED"), PageRequest.of(0, 50))).hasSize(1);
        assertThat(approvals.findRecoverable(factory, otherBlueprint, Set.of("REQUESTED"), PageRequest.of(0, 50))).hasSize(1);
        assertThat(approvals.findRecoverable(factory, blueprint, FactoryAssemblyActivationController.RECOVERABLE_STATUSES,
                PageRequest.of(0, 2))).extracting(FactoryAssemblyApproval::getId).containsExactly(executed, approved);
    }

    @Test
    void aRequestAwaitingApprovalHasNoExpiryYetAndContinuesOnTheCurrentVersion() {
        var row = row("REQUESTED", null);
        assertThat(classify(row, plan(row, 3, "digest-3"), 3, "digest-3")).isEqualTo(Recoverability.CONTINUE_APPROVAL);
    }

    @Test
    void anApprovalContinuesOnlyWhileItsExpiryExistsAndIsInTheFuture() {
        var live = row("APPROVED", NOW.plus(Duration.ofMinutes(30)));
        assertThat(classify(live, plan(live, 3, "digest-3"), 3, "digest-3")).isEqualTo(Recoverability.CONTINUE_EXECUTION);
        var lapsed = row("APPROVED", NOW);
        assertThat(classify(lapsed, plan(lapsed, 3, "digest-3"), 3, "digest-3")).isEqualTo(Recoverability.EXPIRED);
        // An approval without an expiry is not open-ended.
        var unbounded = row("APPROVED", null);
        assertThat(classify(unbounded, plan(unbounded, 3, "digest-3"), 3, "digest-3")).isEqualTo(Recoverability.EXPIRED);
    }

    @Test
    void anotherBlueprintVersionOrAChangedBlueprintIsHistoryAndNeverContinues() {
        var older = row("REQUESTED", null);
        assertThat(classify(older, plan(older, 2, "digest-2"), 3, "digest-3")).isEqualTo(Recoverability.HISTORICAL_VERSION);
        var noCurrent = row("APPROVED", NOW.plusSeconds(600));
        assertThat(classify(noCurrent, plan(noCurrent, 3, "digest-3"), null, null)).isEqualTo(Recoverability.HISTORICAL_VERSION);
        var changed = row("APPROVED", NOW.plusSeconds(600));
        assertThat(classify(changed, plan(changed, 3, "digest-old"), 3, "digest-3")).isEqualTo(Recoverability.BLUEPRINT_CHANGED);
    }

    @Test
    void anExecutedRequestOnlyShowsItsReceipt() {
        var done = row("EXECUTED", NOW.minusSeconds(60));
        assertThat(classify(done, plan(done, 2, "digest-2"), 3, "digest-3")).isEqualTo(Recoverability.RECEIPT_ONLY);
    }

    @Test
    void anUnreadableOrForeignSnapshotIsNeverFilledInFromTheCurrentVersion() throws Exception {
        java.util.function.Function<FactoryAssemblyApproval, ActivationPlan> controllerSnapshot =
                row -> FactoryAssemblyActivationController.snapshot(row, JSON);
        var broken = row("REQUESTED", null);
        broken.setPlanJson("{not json");
        assertThat(controllerSnapshot.apply(broken)).isNull();
        var foreign = row("REQUESTED", null);
        foreign.setPlanJson(JSON.writeValueAsString(planFor("OTHER-FACTORY", foreign.getBlueprintId(), foreign.getPlanDigest(),
                foreign.getCapabilityId(), 3, "digest-3")));
        assertThat(controllerSnapshot.apply(foreign)).isNull();
        var wrongDigest = row("REQUESTED", null);
        wrongDigest.setPlanJson(JSON.writeValueAsString(planFor(wrongDigest.getFactoryId(), wrongDigest.getBlueprintId(),
                "some-other-digest", wrongDigest.getCapabilityId(), 3, "digest-3")));
        assertThat(controllerSnapshot.apply(wrongDigest)).isNull();
        var own = row("REQUESTED", null);
        own.setPlanJson(JSON.writeValueAsString(plan(own, 3, "digest-3")));
        assertThat(controllerSnapshot.apply(own)).isNotNull().extracting(ActivationPlan::blueprintVersion).isEqualTo(3);
        // A null snapshot is SNAPSHOT_UNREADABLE whatever the row's status, never a current-version continuation.
        assertThat(FactoryAssemblyActivationController.classify(broken, null, 3, "digest-3", NOW))
                .isEqualTo(Recoverability.SNAPSHOT_UNREADABLE);
    }

    private static Recoverability classify(FactoryAssemblyApproval row, ActivationPlan plan, Integer version, String digest) {
        return FactoryAssemblyActivationController.classify(row, plan, version, digest, NOW);
    }

    private static FactoryAssemblyApproval row(String status, Instant expiresAt) {
        var row = new FactoryAssemblyApproval();
        row.setId(UUID.randomUUID().toString());
        row.setFactoryId("FA");
        row.setBlueprintId("bp-1");
        row.setCapabilityId("modules");
        row.setDraftId("draft-1");
        row.setPlanDigest("plan-" + row.getId().substring(0, 8));
        row.setStatus(status);
        row.setExpiresAt(expiresAt);
        return row;
    }

    private static ActivationPlan plan(FactoryAssemblyApproval row, int blueprintVersion, String blueprintDigest) {
        return planFor(row.getFactoryId(), row.getBlueprintId(), row.getPlanDigest(), row.getCapabilityId(),
                blueprintVersion, blueprintDigest);
    }

    private static ActivationPlan planFor(String factoryId, String blueprintId, String planDigest, String capabilityId,
            int blueprintVersion, String blueprintDigest) {
        var module = new ModuleActivationPlan(capabilityId, "owner", "command", "draft-1", 1L, "draft-digest",
                List.of(), "factory-blueprint:activation:" + capabilityId, List.of(), "retry");
        return new ActivationPlan("factory-activation/v1", "plan-1", factoryId, blueprintId, blueprintVersion,
                blueprintDigest, planDigest, 7L, PlanStatus.READY_FOR_APPROVAL, List.of(module), List.of(), 1, 0,
                "NOT_REQUESTED", "NOT_EXECUTED", new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7L));
    }

    private String persist(String factoryId, String blueprintId, String capabilityId, String status, LocalDateTime createdAt)
            throws Exception {
        var row = row(status, "APPROVED".equals(status) ? NOW.plusSeconds(600) : null);
        row.setFactoryId(factoryId);
        row.setBlueprintId(blueprintId);
        row.setCapabilityId(capabilityId);
        row.setPlanRequestJson("{}");
        row.setPlanJson(JSON.writeValueAsString(plan(row, 1, "digest-1")));
        row.setRequestedActorType(AuthenticatedActorType.PLATFORM_ADMIN);
        row.setRequestedActorId(7L);
        entityManager.persist(row);
        entityManager.flush();
        entityManager.getEntityManager().createNativeQuery(
                "UPDATE factory_assembly_approvals SET created_at = :at WHERE id = :id")
                .setParameter("at", createdAt).setParameter("id", row.getId()).executeUpdate();
        return row.getId();
    }
}
