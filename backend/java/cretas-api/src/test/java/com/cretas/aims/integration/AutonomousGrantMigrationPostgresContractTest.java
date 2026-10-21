package com.cretas.aims.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V20261103_73 on real PostgreSQL: the database itself refuses a budget overrun, an unknown source
 * type, a run for an unknown grant and a second consumption of the same run key.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class AutonomousGrantMigrationPostgresContractTest {

    private static final String GRANT = "INSERT INTO autonomous_tool_grants (id, factory_id, source_type, source_ref,"
            + " tool_name, run_as_user_id, parameters_digest, max_runs, used_runs, expires_at, granted_by, granted_at)"
            + " VALUES ('%s', 'F1', '%s', '78', 'user_disable', 7, 'd', %d, %d, now() + interval '1 day', 7, now())";
    private static final String RUN = "INSERT INTO autonomous_tool_grant_runs (id, grant_id, run_key, idempotency_key,"
            + " consumed_at) VALUES ('%s', '%s', '%s', 'k', now())";

    @Test
    void constraintsHoldTheBudgetSourceAndOneConsumptionPerRunKey() throws Exception {
        String schema = "grant_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261103_73__autonomous_tool_grants.sql"));

            assertDoesNotThrow(() -> statement.execute(GRANT.formatted("g1", "SCHEDULER", 2, 0)));
            assertDoesNotThrow(() -> statement.execute(RUN.formatted("r1", "g1", "fire:1")));
            assertThrows(SQLException.class, () -> statement.execute(RUN.formatted("r2", "g1", "fire:1")),
                    "same run key twice");
            assertThrows(SQLException.class, () -> statement.execute(RUN.formatted("r3", "missing", "fire:1")),
                    "run for an unknown grant");
            assertThrows(SQLException.class, () -> statement.execute(GRANT.formatted("g2", "SCHEDULER", 2, 3)),
                    "used runs above budget");
            assertThrows(SQLException.class, () -> statement.execute(GRANT.formatted("g3", "SCHEDULER", 0, 0)),
                    "zero budget");
            assertThrows(SQLException.class, () -> statement.execute(GRANT.formatted("g4", "AI_CHAT", 1, 0)),
                    "source other than SCHEDULER / TRIGGER");
            assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE autonomous_tool_grants SET used_runs = 3 WHERE id = 'g1'"),
                    "consumption past the budget");
            assertDoesNotThrow(() -> statement.execute(GRANT.formatted("g5", "TRIGGER", 1, 1)));
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
