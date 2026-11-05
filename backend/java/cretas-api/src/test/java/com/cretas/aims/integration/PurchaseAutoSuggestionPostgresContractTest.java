package com.cretas.aims.integration;

import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL behavior for tenant/date filtering and soft-delete number occupancy. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.generate-ddl=false",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class PurchaseAutoSuggestionPostgresContractTest {

    private static final String FACTORY = "PG-AUTO-F1";
    private static final String OTHER_FACTORY = "PG-AUTO-F2";
    private static final String DATE = "20261105";
    private static final String SCHEMA = "purchase_auto_suggestion_"
            + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String baseUrl = DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        createSchema(baseUrl);
        String isolatedUrl = withSchema(baseUrl);
        registry.add("spring.datasource.url", () -> isolatedUrl);
        registry.add("spring.datasource.username", () -> required("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropIsolatedSchema() {
        String rawUrl = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (rawUrl == null || rawUrl.isBlank()) return;
        String baseUrl = DisposablePostgresTargetGuard.requireSafeUrl(rawUrl);
        new JdbcTemplate(new DriverManagerDataSource(baseUrl, required("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "")))
                .execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void countIsTenantAndDateScopedWhileSoftDeletedRowsStillOccupyNumber() {
        createPurchaseOrdersTable();
        insert("same-live-1", FACTORY, "PO-AUTO-" + DATE + "-1", null);
        insert("same-live-2", FACTORY, "PO-AUTO-" + DATE + "-2", null);
        insert("same-soft-deleted", FACTORY, "PO-AUTO-" + DATE + "-3", "2026-11-05 08:00:00");
        insert("foreign-same-date", OTHER_FACTORY, "PO-AUTO-" + DATE + "-9", null);
        insert("same-factory-other-date", FACTORY, "PO-AUTO-20261104-1", null);

        assertThat(purchaseOrderRepository.countAutoSuggestionsOn(FACTORY, DATE)).isEqualTo(3L);
        assertThat(purchaseOrderRepository.countAutoSuggestionsOn(OTHER_FACTORY, DATE)).isEqualTo(1L);
        assertThat(purchaseOrderRepository.countAutoSuggestionsOn(FACTORY, "20261104")).isEqualTo(1L);
    }

    private void createPurchaseOrdersTable() {
        jdbcTemplate.execute("CREATE TABLE purchase_orders ("
                + "id varchar(191) primary key,"
                + "factory_id varchar(255) not null,"
                + "order_number varchar(50) not null,"
                + "deleted_at timestamp null"
                + ")");
    }

    private void insert(String id, String factoryId, String orderNumber, String deletedAt) {
        jdbcTemplate.update("INSERT INTO purchase_orders(id, factory_id, order_number, deleted_at) VALUES (?, ?, ?, ?)",
                id, factoryId, orderNumber, deletedAt == null ? null : java.sql.Timestamp.valueOf(deletedAt));
    }

    private static void createSchema(String baseUrl) {
        new JdbcTemplate(new DriverManagerDataSource(baseUrl, required("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "")))
                .execute("CREATE SCHEMA IF NOT EXISTS \"" + SCHEMA + "\"");
    }

    private static String withSchema(String baseUrl) {
        return baseUrl + (baseUrl.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + "&stringtype=unspecified";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
