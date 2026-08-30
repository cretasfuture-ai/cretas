package com.cretas.aims.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in PostgreSQL proof of tombstone retention and one active settlement per plan. */
@JdbcTest(properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductionSettlementPartialUniqueIndexPostgresIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }

    @BeforeEach
    void createIsolatedTableAndApplySettlementIndex() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS production_settlements CASCADE");
        jdbcTemplate.execute("""
                CREATE TABLE production_settlements (
                    id VARCHAR(191) PRIMARY KEY,
                    factory_id VARCHAR(50) NOT NULL,
                    production_plan_id VARCHAR(191) NOT NULL,
                    idempotency_key VARCHAR(128) NOT NULL,
                    deleted_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("ALTER TABLE production_settlements ADD CONSTRAINT uq_prod_settlement_plan UNIQUE (factory_id, production_plan_id)");
        try {
            String migration = Files.readString(Path.of(
                    "src/main/resources/db/flyway/V20261101_21__production_settlement_active_plan_unique.sql"))
                    .replaceAll("(?m)^\\s*--.*(?:\\R|$)", "");
            for (String statement : migration.split(";")) {
                String sql = statement.trim();
                if (!sql.isEmpty()) {
                    jdbcTemplate.execute(sql);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("target settlement migration is unavailable", e);
        }
        jdbcTemplate.execute("ALTER TABLE production_settlements ADD CONSTRAINT uq_prod_settlement_idem UNIQUE (factory_id, production_plan_id, idempotency_key)");
    }

    @AfterEach
    void dropIsolatedTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS production_settlements CASCADE");
    }

    @Test
    void deletedHistoryCanBeRetainedAndOnlyOneActiveRowIsAllowed() {
        jdbcTemplate.update("INSERT INTO production_settlements VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                "old", "F006", "PLAN-1", "old-key");
        jdbcTemplate.update("INSERT INTO production_settlements VALUES (?, ?, ?, ?, NULL)",
                "active", "F006", "PLAN-1", "new-key");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM production_settlements WHERE factory_id = 'F006' AND production_plan_id = 'PLAN-1'",
                Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO production_settlements VALUES (?, ?, ?, ?, NULL)",
                "active-2", "F006", "PLAN-1", "another-key"))
                .hasMessageContaining("uq_prod_settlement_plan_active");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO production_settlements VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                "old-replay", "F006", "PLAN-1", "old-key"))
                .hasMessageContaining("uq_prod_settlement_idem");

        jdbcTemplate.update("INSERT INTO production_settlements VALUES (?, ?, ?, ?, NULL)",
                "other-factory", "F007", "PLAN-1", "other-factory-key");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM production_settlements WHERE production_plan_id = 'PLAN-1' AND deleted_at IS NULL",
                Integer.class)).isEqualTo(2);
    }
}
