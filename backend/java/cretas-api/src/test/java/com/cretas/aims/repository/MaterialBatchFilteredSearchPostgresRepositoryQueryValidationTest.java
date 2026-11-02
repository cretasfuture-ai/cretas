package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL proof for {@link MaterialBatchRepository#searchFiltered} (ERP-S6-02).
 *
 * <p>Every filter is optional and bound as a typed null when absent. H2 accepts untyped nulls,
 * PostgreSQL does not ("could not determine data type of parameter $n"), so the H2 entry test
 * cannot prove this. Runs in java-gate-pr's PostgreSQL job, in a disposable schema, with:
 * all filters null, all filters set, dates only (and a status/material-only pair).
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class MaterialBatchFilteredSearchPostgresRepositoryQueryValidationTest {

    private static final String FACTORY = "IT-MB-FILTER-PG";
    private static final String OTHER_FACTORY = "IT-MB-FILTER-PG-OTHER";
    private static final String SCHEMA = "mb_filter_" + UUID.randomUUID().toString().replace("-", "");
    private static final PageRequest PAGE = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt"));
    private static final List<Long> UNSCOPED = List.of(-1L);

    @Autowired private MaterialBatchRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO \"" + SCHEMA + "\"");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void seed() {
        // Only batches are seeded, not their FK targets (users / raw_material_types / ...): drop the FKs
        // Hibernate generated in this disposable schema. Owner-level DDL, no superuser needed.
        jdbcTemplate.execute("""
                DO $$ DECLARE r record; BEGIN
                  FOR r IN SELECT conname FROM pg_constraint
                           WHERE conrelid = 'material_batches'::regclass AND contype = 'f' LOOP
                    EXECUTE 'ALTER TABLE material_batches DROP CONSTRAINT ' || quote_ident(r.conname);
                  END LOOP;
                END $$""");
        jdbcTemplate.update("DELETE FROM material_batches");
        save("MB-PG-1", FACTORY, MaterialBatchStatus.USED_UP, "MT-A", LocalDate.of(2026, 1, 10), 7L);
        save("MB-PG-2", FACTORY, MaterialBatchStatus.USED_UP, "MT-B", LocalDate.of(2026, 1, 20), 8L);
        save("MB-PG-3", FACTORY, MaterialBatchStatus.AVAILABLE, "MT-A", LocalDate.of(2026, 3, 5), 7L);
        save("MB-PG-4", FACTORY, MaterialBatchStatus.AVAILABLE, "MT-A", LocalDate.of(2026, 3, 6), 7L);
        save("MB-PG-5", FACTORY, MaterialBatchStatus.EXPIRED, "MT-B", LocalDate.of(2026, 2, 1), 7L);
        save("MB-PG-X", OTHER_FACTORY, MaterialBatchStatus.USED_UP, "MT-A", LocalDate.of(2026, 1, 10), 7L);
    }

    @Test
    void allFiltersNullBindAsTypedNulls() {
        Page<MaterialBatch> page = repository.searchFiltered(
                FACTORY, false, UNSCOPED, null, null, null, null, null, PAGE);

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).extracting(MaterialBatch::getFactoryId).containsOnly(FACTORY);
    }

    @Test
    void allFiltersSetApplyTogether() {
        Page<MaterialBatch> page = repository.searchFiltered(
                FACTORY, true, List.of(7L), MaterialBatchStatus.USED_UP, "MT-A",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "BN-MB-PG", PAGE);

        assertThat(page.getContent()).extracting(MaterialBatch::getId).containsExactly("MB-PG-1");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void datesOnlyFilterBeforePaging() {
        Page<MaterialBatch> page = repository.searchFiltered(
                FACTORY, false, UNSCOPED, null, null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), null, PAGE);

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).extracting(MaterialBatch::getReceiptDate)
                .allSatisfy(d -> assertThat(d).isBefore(LocalDate.of(2026, 3, 1)));
    }

    @Test
    void statusAndMaterialOnlyWithOpenDateBounds() {
        Page<MaterialBatch> page = repository.searchFiltered(
                FACTORY, false, UNSCOPED, MaterialBatchStatus.AVAILABLE, "MT-A", null, null, null, PAGE);

        assertThat(page.getContent()).extracting(MaterialBatch::getId)
                .containsExactlyInAnyOrder("MB-PG-3", "MB-PG-4");
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    private void save(String id, String factoryId, MaterialBatchStatus status, String materialTypeId,
                      LocalDate receiptDate, long createdBy) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(factoryId);
        b.setBatchNumber("BN-" + id);
        b.setMaterialTypeId(materialTypeId);
        b.setWarehouseId("WH-MB-PG");
        b.setReceiptDate(receiptDate);
        b.setReceiptQuantity(new BigDecimal("10"));
        b.setQuantityUnit("kg");
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setStatus(status);
        b.setCreatedBy(createdBy);
        repository.saveAndFlush(b);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }
}
