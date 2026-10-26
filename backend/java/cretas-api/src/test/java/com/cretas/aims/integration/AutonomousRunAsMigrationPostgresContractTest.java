package com.cretas.aims.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V20261103_84 on real PostgreSQL: adds nullable run-as columns and leaves existing configs unset. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class AutonomousRunAsMigrationPostgresContractTest {

    @Test
    void migrationAddsNullableRunAsColumnsWithoutTouchingExistingRows() throws Exception {
        String schema = "run_as_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE factory_scheduler_configs (id bigserial primary key, factory_id varchar(50), task_code varchar(64) not null, enabled boolean not null)");
            statement.execute("CREATE TABLE factory_trigger_chains (id bigserial primary key, factory_id varchar(50), chain_code varchar(64) not null, enabled boolean not null)");
            statement.execute("INSERT INTO factory_scheduler_configs (factory_id, task_code, enabled) VALUES (NULL, 'BATCH_EXPIRY_CHECK', false)");
            statement.execute("INSERT INTO factory_trigger_chains (factory_id, chain_code, enabled) VALUES (NULL, 'BATCH_COMPLETED', false)");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261103_84__autonomous_run_as_user.sql"));

            for (String table : new String[]{"factory_scheduler_configs", "factory_trigger_chains"}) {
                try (ResultSet column = statement.executeQuery("""
                        SELECT data_type, is_nullable FROM information_schema.columns
                        WHERE table_schema = '%s' AND table_name = '%s' AND column_name = 'run_as_user_id'
                        """.formatted(schema, table))) {
                    assertTrue(column.next(), table + " has run_as_user_id");
                    assertEquals("bigint", column.getString(1));
                    assertEquals("YES", column.getString(2));
                }
                try (ResultSet rows = statement.executeQuery(
                        "SELECT count(*), count(run_as_user_id) FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1), "existing row kept");
                    assertEquals(0, rows.getInt(2), "existing row has no run-as user, so it stays refused");
                }
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
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
