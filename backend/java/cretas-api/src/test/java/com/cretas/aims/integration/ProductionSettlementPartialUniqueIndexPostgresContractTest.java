package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in PostgreSQL proof of tombstone retention and one active settlement per plan. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductionSettlementPartialUniqueIndexPostgresContractTest {

    @Test
    void deletedHistoryCanBeRetainedAndOnlyOneActiveRowIsAllowed() throws Exception {
        String schema = "settlement_unique_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("""
                    CREATE TABLE production_settlements (
                        id VARCHAR(191) PRIMARY KEY,
                        factory_id VARCHAR(50) NOT NULL,
                        production_plan_id VARCHAR(191) NOT NULL,
                        idempotency_key VARCHAR(128) NOT NULL,
                        deleted_at TIMESTAMP,
                        CONSTRAINT uq_prod_settlement_plan UNIQUE (factory_id, production_plan_id),
                        CONSTRAINT uq_prod_settlement_idem UNIQUE (factory_id, production_plan_id, idempotency_key)
                    )
                    """);

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261101_21__production_settlement_active_plan_unique.sql"));

            statement.execute("""
                    INSERT INTO production_settlements
                      (id, factory_id, production_plan_id, idempotency_key, deleted_at)
                    VALUES ('old', 'F006', 'PLAN-1', 'old-key', CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO production_settlements
                      (id, factory_id, production_plan_id, idempotency_key, deleted_at)
                    VALUES ('active', 'F006', 'PLAN-1', 'new-key', NULL)
                    """);

            try (ResultSet rows = statement.executeQuery("""
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE deleted_at IS NULL) AS active
                    FROM production_settlements
                    WHERE factory_id='F006' AND production_plan_id='PLAN-1'
                    """)) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt("total"));
                assertEquals(1, rows.getInt("active"));
            }

            SQLException duplicateActive = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO production_settlements
                      (id, factory_id, production_plan_id, idempotency_key, deleted_at)
                    VALUES ('active-2', 'F006', 'PLAN-1', 'another-key', NULL)
                    """));
            assertEquals("23505", duplicateActive.getSQLState());

            SQLException duplicateReplay = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO production_settlements
                      (id, factory_id, production_plan_id, idempotency_key, deleted_at)
                    VALUES ('old-replay', 'F006', 'PLAN-1', 'old-key', CURRENT_TIMESTAMP)
                    """));
            assertEquals("23505", duplicateReplay.getSQLState());

            statement.execute("""
                    INSERT INTO production_settlements
                      (id, factory_id, production_plan_id, idempotency_key, deleted_at)
                    VALUES ('other-factory', 'F007', 'PLAN-1', 'other-factory-key', NULL)
                    """);

            try (ResultSet index = statement.executeQuery("""
                    SELECT i.indisunique,
                           pg_get_expr(i.indpred, i.indrelid) AS predicate
                    FROM pg_index i
                    JOIN pg_class c ON c.oid = i.indexrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema()
                      AND c.relname = 'uq_prod_settlement_plan_active'
                    """)) {
                assertTrue(index.next());
                assertTrue(index.getBoolean("indisunique"));
                assertTrue(index.getString("predicate").contains("deleted_at IS NULL"));
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }
}
