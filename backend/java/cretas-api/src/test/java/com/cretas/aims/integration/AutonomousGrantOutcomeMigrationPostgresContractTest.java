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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V20261103_85 on real PostgreSQL, applied after the grants migration: nullable outcome columns on
 * existing runs (left unresolved), and the database refuses an outcome outside the four known ones.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class AutonomousGrantOutcomeMigrationPostgresContractTest {

    @Test
    void outcomeColumnsAreAddedNullableAndOnlyKnownOutcomesAreAccepted() throws Exception {
        String schema = "grant_outcome_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261103_84__autonomous_tool_grants.sql"));
            statement.execute("INSERT INTO autonomous_tool_grants (id, factory_id, source_type, source_ref, tool_name,"
                    + " run_as_user_id, parameters_digest, config_revision, descriptor_version, max_runs, used_runs,"
                    + " expires_at, granted_by, granted_at) VALUES ('g1', 'F1', 'SCHEDULER', '78', 'user_disable', 7,"
                    + " 'd', 'r', '2.0.0', 3, 1, now() + interval '1 day', 7, now())");
            statement.execute("INSERT INTO autonomous_tool_grant_runs (id, grant_id, run_key, idempotency_key,"
                    + " descriptor_version, consumed_at) VALUES ('r1', 'g1', 'slot:1', 'k', '2.0.0', now())");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261103_85__autonomous_grant_run_outcome.sql"));

            for (String column : new String[]{"outcome", "audit_event_id", "resolved_at"}) {
                try (ResultSet row = statement.executeQuery("SELECT is_nullable FROM information_schema.columns"
                        + " WHERE table_schema = '" + schema + "' AND table_name = 'autonomous_tool_grant_runs'"
                        + " AND column_name = '" + column + "'")) {
                    assertTrue(row.next(), column + " added");
                    assertEquals("YES", row.getString(1));
                }
            }
            try (ResultSet row = statement.executeQuery("SELECT count(*) FROM autonomous_tool_grant_runs WHERE outcome IS NULL")) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1), "an existing run stays unresolved");
            }
            assertDoesNotThrow(() -> statement.execute(
                    "UPDATE autonomous_tool_grant_runs SET outcome = 'OUTCOME_UNKNOWN' WHERE id = 'r1'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE autonomous_tool_grant_runs SET outcome = 'SUCCESS' WHERE id = 'r1'"), "unknown outcome");
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
