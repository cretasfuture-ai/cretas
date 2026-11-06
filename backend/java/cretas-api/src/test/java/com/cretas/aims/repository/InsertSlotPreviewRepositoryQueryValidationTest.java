package com.cretas.aims.repository;

import com.cretas.aims.entity.InsertSlotPreview;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots actual Hibernate repositories and executes every new preview query. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
class InsertSlotPreviewRepositoryQueryValidationTest {
    @Autowired InsertSlotPreviewRepository previews;
    @Autowired EntityManager em;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url == null || url.isBlank()) return;
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]*test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only an owned loopback *_test database is permitted");
        }
        registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    private InsertSlotPreview fixture(String factory, int candidate, LocalDateTime created) {
        InsertSlotPreview row = InsertSlotPreview.builder().id(UUID.randomUUID().toString()).factoryId(factory)
                .slotId("SLOT").productTypeId("PRODUCT").previewQuantity(new BigDecimal("17.00"))
                .quantityUnit("PIECE").plannedUnit("个").productionLineId("LINE")
                .windowStart(created.plusDays(2)).windowEnd(created.plusDays(2).plusHours(2))
                .slotUpdatedAt(created).impactLevel("none").requiresApproval(false).affectedPlanCount(0)
                .candidateNumber(candidate).build();
        row.setCreatedAt(created); row.setUpdatedAt(created);
        InsertSlotPreview stored = previews.saveAndFlush(row);
        em.createNativeQuery("UPDATE insert_slot_previews SET created_at=:created WHERE id=:id")
                .setParameter("created", created).setParameter("id", stored.getId()).executeUpdate();
        em.detach(stored);
        return previews.findByIdAndFactoryId(stored.getId(), factory).orElseThrow();
    }

    @Test
    void tenantAndRecentQueriesExecuteAndOrderOnlyOwnLivePreviews() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        InsertSlotPreview older = fixture("PREVIEW-A", 1, now.minusMinutes(1));
        InsertSlotPreview newer = fixture("PREVIEW-A", 2, now);
        fixture("PREVIEW-A", 3, now.minusMinutes(30));
        InsertSlotPreview other = fixture("PREVIEW-B", 1, now);
        em.clear();
        assertThat(previews.findByIdAndFactoryId(newer.getId(), "PREVIEW-A")).isPresent();
        assertThat(previews.findByIdAndFactoryId(other.getId(), "PREVIEW-A")).isEmpty();
        assertThat(previews.countByFactoryIdAndSlotId("PREVIEW-A", "SLOT")).isEqualTo(3);
        assertThat(previews.findByFactoryIdAndCreatedAtAfterOrderByCreatedAtDesc("PREVIEW-A", now.minusMinutes(15)))
                .extracting(InsertSlotPreview::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void persistedSnapshotCannotBeRewrittenThroughHibernateDirtyChecking() {
        InsertSlotPreview row = fixture("PREVIEW-IMMUTABLE", 1, LocalDateTime.now().withNano(0));
        ReflectionTestUtils.setField(row, "quantityUnit", "KG");
        ReflectionTestUtils.setField(row, "plannedUnit", "kg");
        em.flush(); em.clear();
        InsertSlotPreview stored = previews.findByIdAndFactoryId(row.getId(), "PREVIEW-IMMUTABLE").orElseThrow();
        assertThat(stored.getQuantityUnit()).isEqualTo("PIECE");
        assertThat(stored.getPlannedUnit()).isEqualTo("个");
        assertThat(stored.getPreviewQuantity()).isEqualByComparingTo("17");
    }
}
