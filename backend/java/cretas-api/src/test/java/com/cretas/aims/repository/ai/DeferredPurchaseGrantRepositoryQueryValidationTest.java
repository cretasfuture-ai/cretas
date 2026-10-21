package com.cretas.aims.repository.ai;

import com.cretas.aims.entity.ai.AiPurchaseTaskGrant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and repository CAS/tenant/actor lifecycle checks. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = AiPurchaseTaskGrant.class)
@EnableJpaRepositories(basePackageClasses = AiPurchaseTaskGrantRepository.class)
class DeferredPurchaseGrantRepositoryQueryValidationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            if (!url.startsWith("jdbc:postgresql://127.0.0.1:") || !url.endsWith("/cretas_workflow_verify_test")) {
                throw new IllegalStateException("Deferred grant repository test requires the disposable loopback database");
            }
            p.add("spring.datasource.url", () -> url);
            p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            p.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            p.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            p.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            p.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else p.add("spring.datasource.url", () -> "jdbc:h2:mem:deferred_grant;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        p.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired AiPurchaseTaskGrantRepository repository;

    @Test
    void lockQueryAndCasRequireTenantActorAndCurrentVersion() {
        AiPurchaseTaskGrant saved = repository.saveAndFlush(grant("deferred-query", 1L));
        assertThat(repository.findForUpdate(saved.getId(), "OTHER", 42L)).isEmpty();
        assertThat(repository.findForUpdate(saved.getId(), "F-1", 42L)).isPresent();
        LocalDateTime now = LocalDateTime.now();
        assertThat(repository.consumeIfCurrent(saved.getId(), "F-1", 42L, 0L, now, now,
                "deferred:wrong", "digest", "PO-X", new BigDecimal("2"), new BigDecimal("20"))).isZero();
        assertThat(repository.consumeIfCurrent(saved.getId(), "F-1", 42L, 1L, now, now,
                "deferred:right", "digest", "PO-1", new BigDecimal("2"), new BigDecimal("20"))).isEqualTo(1);
        assertThat(repository.consumeIfCurrent(saved.getId(), "F-1", 42L, 1L, now, now,
                "deferred:replay", "digest", "PO-2", new BigDecimal("2"), new BigDecimal("20"))).isZero();
    }

    @Test
    void revokeCasCannotRevokeConsumedOrWrongVersionGrant() {
        AiPurchaseTaskGrant saved = repository.saveAndFlush(grant("deferred-revoke", 1L));
        assertThat(repository.revokeIfCurrent(saved.getId(), "F-1", 42L, 99L,
                LocalDateTime.now(), 42L, "wrong")).isZero();
        assertThat(repository.revokeIfCurrent(saved.getId(), "F-1", 42L, 1L,
                LocalDateTime.now(), 42L, "cancelled")).isEqualTo(1);
        assertThat(repository.revokeIfCurrent(saved.getId(), "F-1", 42L, 1L,
                LocalDateTime.now(), 42L, "replay")).isZero();
    }

    private static AiPurchaseTaskGrant grant(String id, Long version) {
        return AiPurchaseTaskGrant.builder().id(id).factoryId("F-1").userId(42L)
                .toolName("purchase_order_create").descriptorVersion("2.0.0")
                .supplierId("SUP-1").materialTypeId("MAT-1").quantityCap(new BigDecimal("5.0000"))
                .quantityUnit("kg").unitPrice(new BigDecimal("10.0000")).amountCap(new BigDecimal("50.00"))
                .currency("CNY").taxRate(new BigDecimal("13.00")).inputDigest("digest")
                .approvedInputSnapshot("snapshot").expiresAt(LocalDateTime.now().plusMinutes(15)).version(version).build();
    }
}
