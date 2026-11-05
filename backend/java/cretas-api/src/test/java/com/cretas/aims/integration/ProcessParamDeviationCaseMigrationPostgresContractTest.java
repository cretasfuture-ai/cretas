package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Opt-in PG gate for the new source enum, detail table and source-key uniqueness. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProcessParamDeviationCaseMigrationPostgresContractTest {

    @Test
    void migrationUsesTransactionalLockTimeoutAndPreservesAllExistingSources() throws Exception {
        String schema = "param_case_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (Connection setup = connect(url, user, password)) {
                createBaseline(setup, schema);
                assertCurrentShortageSourceConstraint(setup);
            }

            // A real transaction holding the table lock must fail fast with 55P03,
            // rather than leaving a Flyway worker waiting indefinitely.
            try (Connection holder = connect(url, user, password)) {
                holder.setAutoCommit(false);
                setSearchPath(holder, schema);
                try (Statement statement = holder.createStatement()) {
                    statement.execute("LOCK TABLE shortage_cases IN ACCESS EXCLUSIVE MODE");
                }
                Future<Throwable> blockedMigration = executor.submit(() -> {
                    try (Connection blocked = connect(url, user, password)) {
                        blocked.setAutoCommit(false);
                        runMigration(blocked, schema);
                        blocked.commit();
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                Throwable failure = blockedMigration.get(20, TimeUnit.SECONDS);
                assertThat(sqlState(failure)).isEqualTo("55P03");
                holder.rollback();
            }

            try (Connection connection = connect(url, user, password)) {
                connection.setAutoCommit(false);
                runMigration(connection, schema);
                connection.commit();
            }

            try (Connection connection = connect(url, user, password);
                 Statement statement = connection.createStatement()) {
                setSearchPath(connection, schema);
                for (String source : List.of("SALES_ORDER", "COST_VARIANCE", "MATERIAL_BALANCE", "DELIVERY_RISK",
                        "PROCESS_PARAM_DEVIATION")) {
                    statement.execute("INSERT INTO shortage_cases(id, factory_id, source_type, source_id, stage, "
                            + "owner_role, gap_line_count, input_fingerprint, last_refreshed_at, last_refresh_reason, "
                            + "created_at, updated_at) VALUES ('" + source + "', 'F1', '" + source + "', 'source-" + source
                            + "', 'DETECTED', 'quality_manager', 1, 'fp', now(), 'TEST', now(), now())");
                }
                statement.execute("INSERT INTO process_param_deviation_case_details "
                        + "(case_id, factory_id, source_key, source_kind, param_key, status, fact_fingerprint, "
                        + "observed_at, created_at, updated_at) VALUES "
                        + "('PROCESS_PARAM_DEVIATION', 'F1', 'yield-report:1:temp', 'YIELD_REPORT', 'temp', 'HIGH', 'fp', now(), now(), now())");

                SQLException duplicate = assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO process_param_deviation_case_details "
                                + "(case_id, factory_id, source_key, source_kind, param_key, status, fact_fingerprint, "
                                + "observed_at, created_at, updated_at) VALUES "
                                + "('COST_VARIANCE', 'F1', 'yield-report:1:temp', 'YIELD_REPORT', 'temp', 'HIGH', 'fp2', now(), now(), now())"));
                assertThat(duplicate.getSQLState()).isEqualTo("23505");

                SQLException unknown = assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO shortage_cases(id, factory_id, source_type, source_id, stage, owner_role, "
                                + "gap_line_count, input_fingerprint, last_refreshed_at, last_refresh_reason, created_at, updated_at) "
                                + "VALUES ('UNKNOWN', 'F1', 'UNKNOWN', 'source-unknown', 'DETECTED', 'quality_manager', "
                                + "1, 'fp', now(), 'TEST', now(), now())"));
                assertThat(unknown.getSQLState()).isEqualTo("23514");

                try (var columns = statement.executeQuery("SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'process_param_deviation_case_details' "
                        + "AND column_name = 'product_type_id'")) {
                    assertThat(columns.next()).isTrue();
                    assertThat(columns.getInt(1)).isEqualTo(100);
                }
                assertDoesNotThrow(() -> statement.execute(
                        "INSERT INTO shortage_cases(id, factory_id, source_type, source_id, stage, owner_role, "
                                + "gap_line_count, input_fingerprint, last_refreshed_at, last_refresh_reason, created_at, updated_at) "
                                + "VALUES ('C6', 'F1', 'PROCESS_PARAM_DEVIATION', 'source-c6', 'DETECTED', 'quality_manager', "
                                + "1, 'fp', now(), 'TEST', now(), now())"));
            }
        } finally {
            executor.shutdownNow();
            try (Connection cleanup = connect(url, user, password);
                 Statement statement = cleanup.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
            }
        }
    }

    private static Connection connect(String url, String user, String password) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static void createBaseline(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        setSearchPath(connection, schema);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE shortage_cases (id VARCHAR(36) PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                    + "source_type VARCHAR(32) NOT NULL, "
                    + "source_id VARCHAR(191) NOT NULL, stage VARCHAR(32) NOT NULL, owner_role VARCHAR(64) NOT NULL, "
                    + "owner_user_id BIGINT, gap_line_count INTEGER NOT NULL DEFAULT 0, input_fingerprint VARCHAR(64) NOT NULL, "
                    + "last_refreshed_at TIMESTAMP NOT NULL, last_refresh_reason VARCHAR(64) NOT NULL, gap_resolved_at TIMESTAMP, "
                    + "closed_at TIMESTAMP, version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP, "
                    + "CHECK (source_type IN ('SALES_ORDER', 'COST_VARIANCE', 'MATERIAL_BALANCE', 'DELIVERY_RISK')))" );
        }
    }

    private static void assertCurrentShortageSourceConstraint(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var constraints = statement.executeQuery(
                     "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint "
                             + "WHERE conrelid = 'shortage_cases'::regclass AND contype = 'c' "
                             + "AND pg_get_constraintdef(oid) LIKE '%SALES_ORDER%'")) {
            assertThat(constraints.next()).isTrue();
            assertThat(constraints.getString(1)).isEqualTo("shortage_cases_source_type_check");
            String definition = constraints.getString(2);
            assertThat(definition).contains("COST_VARIANCE", "MATERIAL_BALANCE", "DELIVERY_RISK");
            assertThat(constraints.next()).isFalse();
        }
    }

    private static void runMigration(Connection connection, String schema) throws Exception {
        setSearchPath(connection, schema);
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/flyway/V20261106_1__process_param_deviation_case.sql"));
    }

    private static void setSearchPath(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + quoteIdentifier(schema));
        }
    }

    private static String quoteIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("invalid test identifier");
        return "\"" + identifier + "\"";
    }

    private static String sqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql) return sql.getSQLState();
            current = current.getCause();
        }
        return null;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
