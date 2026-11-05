package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.DeliveryNoteSourceType;
import com.cretas.aims.entity.inventory.DeliveryNoteStatus;
import com.cretas.aims.entity.inventory.DeliveryPostingStatus;
import com.cretas.aims.entity.inventory.PriceAnomalyApprovalStatus;
import com.cretas.aims.entity.inventory.SupplierDeliveryNote;
import com.cretas.aims.entity.inventory.WastageRecord;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL/JPA proof for the two factory inventory entities fixed in this slice.
 * The test deliberately calls {@link EntityManager#remove(Object)}: setting BaseEntity.deletedAt
 * directly would not prove that Hibernate's entity-level {@code @SQLDelete} is wired.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false"}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = {
        SupplierDeliveryNoteRepository.class, WastageRecordRepository.class})
class FactoryInventorySoftDeletePostgresRepositoryQueryValidationTest {

    private static final String SCHEMA = "factory_inventory_soft_delete_"
            + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = com.cretas.aims.integration.DisposablePostgresTargetGuard
                .requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        try (var c = DriverManager.getConnection(url, required("CRETAS_WORKFLOW_PG_USER"), password());
             var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> required("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", FactoryInventorySoftDeletePostgresRepositoryQueryValidationTest::password);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @AfterAll
    static void dropOwnedSchema() throws Exception {
        String url = com.cretas.aims.integration.DisposablePostgresTargetGuard
                .requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        try (var c = DriverManager.getConnection(url, required("CRETAS_WORKFLOW_PG_USER"), password());
             var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Autowired EntityManager entityManager;
    @Autowired SupplierDeliveryNoteRepository supplierDeliveryNotes;
    @Autowired WastageRecordRepository wastageRecords;

    @Test
    @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
    void entityManagerRemoveKeepsRowsAsDeletedAndRepositoriesHideThem() {
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setId("soft-delete-sdn-factory");
        note.setFactoryId("F-SOFT-DELETE");
        note.setDeliveryDate(LocalDate.of(2026, 11, 6));
        note.setSourceType(DeliveryNoteSourceType.MANUAL);
        note.setStatus(DeliveryNoteStatus.DRAFT);
        note.setPostingStatus(DeliveryPostingStatus.UNPOSTED);
        note.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.NONE);

        WastageRecord wastage = new WastageRecord();
        wastage.setId("soft-delete-wastage-factory");
        wastage.setFactoryId("F-SOFT-DELETE");
        wastage.setWastageDate(LocalDate.of(2026, 11, 6));
        wastage.setType(WastageRecord.WastageType.DAMAGED);
        wastage.setStatus(WastageRecord.Status.DRAFT);
        wastage.setRawMaterialTypeId("raw-material-soft-delete");
        wastage.setQuantity(new BigDecimal("1.0000"));

        supplierDeliveryNotes.saveAndFlush(note);
        wastageRecords.saveAndFlush(wastage);

        entityManager.remove(entityManager.find(SupplierDeliveryNote.class, note.getId()));
        entityManager.remove(entityManager.find(WastageRecord.class, wastage.getId()));
        entityManager.flush();

        assertThat(nativeDeletedAt("supplier_delivery_notes", note.getId())).isNotNull();
        assertThat(nativeDeletedAt("wastage_records", wastage.getId())).isNotNull();

        entityManager.clear();
        assertThat(supplierDeliveryNotes.findById(note.getId())).isEmpty();
        assertThat(wastageRecords.findById(wastage.getId())).isEmpty();
    }

    private Object nativeDeletedAt(String table, String id) {
        return entityManager.createNativeQuery(
                        "SELECT deleted_at FROM " + table + " WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing " + key);
        }
        return value;
    }

    private static String password() {
        return System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
    }
}
