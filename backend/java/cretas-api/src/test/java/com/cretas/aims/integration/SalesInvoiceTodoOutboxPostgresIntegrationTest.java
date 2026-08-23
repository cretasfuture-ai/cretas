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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Opt-in PostgreSQL proof for the V20 completion trigger.  The target guard
 * only permits a disposable local database; the default run is intentionally
 * skipped when no local opt-in is provided.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SalesInvoiceTodoOutboxPostgresIntegrationTest {

    @Test
    void oldJarCompletionSeedsTodoButHistoricalCompletionEditDoesNot() throws Exception {
        String schema = "invoice_todo_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(
                DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL")),
                requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("""
                    CREATE TABLE sales_orders (
                        id VARCHAR(191) PRIMARY KEY,
                        status VARCHAR(32) NOT NULL,
                        remark TEXT,
                        invoice_todo_status VARCHAR(20),
                        invoice_todo_attempts INTEGER,
                        invoice_todo_next_attempt_at TIMESTAMP,
                        invoice_todo_last_error TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE invoice_records (
                        factory_id VARCHAR(191) NOT NULL,
                        invoice_number VARCHAR(191) NOT NULL,
                        deleted_at TIMESTAMP
                    )
                    """);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261030_20__sales_invoice_todo_outbox.sql"));

            statement.execute("INSERT INTO sales_orders (id, status) VALUES ('old-jar', 'SHIPPED')");
            statement.execute("UPDATE sales_orders SET status = 'COMPLETED' WHERE id = 'old-jar'");
            assertTodo(statement, "old-jar", "PENDING", 0);

            statement.execute("INSERT INTO sales_orders (id, status) VALUES ('historical', 'COMPLETED')");
            statement.execute("UPDATE sales_orders SET remark = 'ordinary edit' WHERE id = 'historical'");
            assertTodo(statement, "historical", null, null);
        } finally {
            dropSchema(schema);
        }
    }

    private static void assertTodo(Statement statement, String id, String expectedStatus,
                                   Integer expectedAttempts) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT invoice_todo_status, invoice_todo_attempts FROM sales_orders WHERE id = '" + id + "'")) {
            if (expectedStatus == null) {
                assertEquals(true, resultSet.next());
                assertNull(resultSet.getString(1));
                assertNull(resultSet.getObject(2));
            } else {
                assertEquals(true, resultSet.next());
                assertEquals(expectedStatus, resultSet.getString(1));
                assertEquals(expectedAttempts, resultSet.getInt(2));
            }
        }
    }

    private static void dropSchema(String schema) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL")),
                requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
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
