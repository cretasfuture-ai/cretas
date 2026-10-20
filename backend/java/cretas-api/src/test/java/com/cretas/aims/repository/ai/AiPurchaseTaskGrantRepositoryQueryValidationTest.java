package com.cretas.aims.repository.ai;

import com.cretas.aims.entity.ai.AiPurchaseTaskGrant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate query parsing and conditional consume/revoke gate. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = AiPurchaseTaskGrant.class)
@EnableJpaRepositories(basePackageClasses = AiPurchaseTaskGrantRepository.class)
class AiPurchaseTaskGrantRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:grant_repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        }
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired AiPurchaseTaskGrantRepository repository;

    @Test
    void lockAndConditionalConsumeQueriesEnforceTenantActorVersionAndSingleUse() {
        AiPurchaseTaskGrant grant = repository.saveAndFlush(grant("grant-query"));
        assertThat(repository.findForUpdate(grant.getId(), "OTHER", 42L)).isEmpty();
        assertThat(repository.findForUpdate(grant.getId(), "F-1", 42L)).isPresent();

        LocalDateTime now = LocalDateTime.now();
        assertThat(repository.consumeIfCurrent(grant.getId(), "F-1", 42L, 99L, now,
                now, "request", "digest-1", "PO-1", new BigDecimal("2"), new BigDecimal("20")))
                .isZero();
        assertThat(repository.consumeIfCurrent(grant.getId(), "F-1", 42L, grant.getVersion(), now,
                now, "request", "digest-1", "PO-1", new BigDecimal("2"), new BigDecimal("20")))
                .isEqualTo(1);
        assertThat(repository.consumeIfCurrent(grant.getId(), "F-1", 42L, grant.getVersion(), now,
                now, "request-2", "digest-1", "PO-2", new BigDecimal("2"), new BigDecimal("20")))
                .isZero();
        assertThat(repository.findById(grant.getId())).get()
                .satisfies(saved -> assertThat(saved.getResultPurchaseOrderId()).isEqualTo("PO-1"));
    }

    @Test
    void revokeQueryIsVersionedAndLeavesAReadbackReceipt() {
        AiPurchaseTaskGrant grant = repository.saveAndFlush(grant("grant-revoke"));
        assertThat(repository.revokeIfCurrent(grant.getId(), "F-1", 42L, grant.getVersion(),
                LocalDateTime.now(), 42L, "cancelled")).isEqualTo(1);
        assertThat(repository.revokeIfCurrent(grant.getId(), "F-1", 42L, grant.getVersion(),
                LocalDateTime.now(), 42L, "late")).isZero();
        assertThat(repository.findById(grant.getId())).get()
                .satisfies(saved -> {
                    assertThat(saved.isRevoked()).isTrue();
                    assertThat(saved.getRevokeReason()).isEqualTo("cancelled");
                });
    }

    @Test
    void constructorsBuildersAndPersistenceUseTheSchemaVersionDefault() {
        AiPurchaseTaskGrant noArg = new AiPurchaseTaskGrant();
        assertThat(noArg.getVersion()).isEqualTo(1L);

        AiPurchaseTaskGrant built = AiPurchaseTaskGrant.builder()
                .id("grant-default")
                .factoryId("F-1").userId(42L)
                .toolName("purchase_order_create").descriptorVersion("1.0.0")
                .supplierId("SUP-1").materialTypeId("MAT-1")
                .quantityCap(new BigDecimal("5.0000")).quantityUnit("kg")
                .unitPrice(new BigDecimal("10.0000")).amountCap(new BigDecimal("50.00"))
                .currency("CNY").taxRate(BigDecimal.ZERO).inputDigest("digest-default")
                .approvedInputSnapshot("approved-input")
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .build();
        assertThat(built.getVersion()).isEqualTo(1L);

        repository.saveAndFlush(built);
        assertThat(repository.findById("grant-default")).get()
                .satisfies(saved -> assertThat(saved.getVersion()).isEqualTo(1L));
    }

    @Test
    void migrationPinsTheFixedCurrencyUnitTaxAndTerminalStateContract() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/flyway/V20261103_69__ai_purchase_task_grants.sql"));
        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS ai_purchase_task_grants")
                .contains("quantity_unit = 'kg' AND currency = 'CNY' AND tax_rate = 0")
                .contains("NOT (revoked_at IS NOT NULL AND consumed_at IS NOT NULL)")
                .contains("uq_ai_purchase_grant_consumed_request")
                .contains("uq_ai_purchase_grant_preview_token");
    }

    @Test
    void migrationRunsOnOwnedPostgresSchemaAndJpaValidateReadsTheResult() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("CRETAS_WORKFLOW_PG_VERIFY") != null,
                "requires the disposable PostgreSQL wrapper");
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String schema = "grant_v69_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(url, user, password);
             java.sql.Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            Path migrationRootPath = Files.createTempDirectory("grant-v69-flyway-");
            Files.copy(Path.of("src/main/resources/db/flyway/V20261103_69__ai_purchase_task_grants.sql"),
                    migrationRootPath.resolve("V20261103_69__ai_purchase_task_grants.sql"));
            Flyway.configure().dataSource(url, user, password)
                    .schemas(schema).defaultSchema(schema)
                    .locations("filesystem:" + migrationRootPath.toAbsolutePath())
                    .placeholderReplacement(false)
                    .load().migrate();

            try (Connection connection = DriverManager.getConnection(url, user, password);
                 java.sql.PreparedStatement columns = connection.prepareStatement(
                         "SELECT data_type FROM information_schema.columns WHERE table_schema=? AND table_name='ai_purchase_task_grants' AND column_name='approved_input_snapshot'");
                 java.sql.PreparedStatement indexes = connection.prepareStatement(
                         "SELECT COUNT(*) FROM pg_indexes WHERE schemaname=? AND tablename='ai_purchase_task_grants' AND indexname IN ('uq_ai_purchase_grant_preview_token','uq_ai_purchase_grant_consumed_request')")) {
                columns.setString(1, schema);
                assertThat(columns.executeQuery().next()).isTrue();
                indexes.setString(1, schema);
                try (var result = indexes.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(2);
                }
            }

            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource(url, user, password));
            emf.setManagedTypes(org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of(
                    AiPurchaseTaskGrant.class.getName()));
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Map<String, Object> properties = new HashMap<>();
            properties.put("hibernate.hbm2ddl.auto", "validate");
            properties.put("hibernate.default_schema", schema);
            properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            emf.setJpaPropertyMap(properties);
            emf.afterPropertiesSet();
            try {
                assertThat(emf.getObject()).isNotNull();
            } finally {
                if (emf.getObject() != null) emf.getObject().close();
            }
            try (var files = Files.walk(migrationRootPath)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(url, user, password);
                 java.sql.Statement statement = admin.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static AiPurchaseTaskGrant grant(String id) {
        return AiPurchaseTaskGrant.builder()
                .id(id).factoryId("F-1").userId(42L)
                .toolName("purchase_order_create").descriptorVersion("1.0.0")
                .supplierId("SUP-1").materialTypeId("MAT-1")
                .quantityCap(new BigDecimal("5.0000")).quantityUnit("kg")
                .unitPrice(new BigDecimal("10.0000")).amountCap(new BigDecimal("50.00"))
                .currency("CNY").taxRate(BigDecimal.ZERO).inputDigest("digest-1")
                .approvedInputSnapshot("approved-input")
                .expiresAt(LocalDateTime.now().plusMinutes(15)).version(1L).build();
    }
}
