package com.cretas.aims.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V20261103_90_1 on real PostgreSQL: existing batches read cost_incomplete=false (behaviour unchanged),
 * new rows default to false, the column refuses NULL, and re-running the script is harmless.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductionBatchCostIncompleteMigrationPostgresContractTest {

    private static final String MIGRATION =
            "db/flyway/V20261103_90_1__production_batch_cost_incomplete.sql";

    @Test
    void existingAndNewBatchesAreCompleteByDefaultAndNullIsRefused() throws Exception {
        String schema = "cost_incomplete_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE production_batches (id BIGSERIAL PRIMARY KEY,"
                    + " material_cost NUMERIC(12,2), total_cost NUMERIC(12,2))");
            statement.execute("INSERT INTO production_batches (material_cost, total_cost) VALUES (10.80, 114.80)");

            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION)); // idempotent

            try (ResultSet rs = statement.executeQuery("SELECT is_nullable, column_default FROM"
                    + " information_schema.columns WHERE table_schema = '" + schema + "'"
                    + " AND table_name = 'production_batches' AND column_name = 'cost_incomplete'")) {
                assertTrue(rs.next(), "cost_incomplete column exists");
                assertEquals("NO", rs.getString(1));
                assertTrue(rs.getString(2).toLowerCase().contains("false"));
            }
            try (ResultSet rs = statement.executeQuery("SELECT cost_incomplete FROM production_batches")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "existing batch reads complete (behaviour unchanged)");
            }
            statement.execute("INSERT INTO production_batches (material_cost) VALUES (1)");
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO production_batches (material_cost, cost_incomplete) VALUES (1, NULL)"));
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when CRETAS_WORKFLOW_PG_VERIFY=true");
        }
        return value;
    }
}
