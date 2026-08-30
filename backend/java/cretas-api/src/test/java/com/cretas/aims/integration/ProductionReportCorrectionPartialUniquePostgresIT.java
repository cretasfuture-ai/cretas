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

@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductionReportCorrectionPartialUniquePostgresIT {

    @Test
    void migrationKeepsHistoryAndAllowsOnlyOnePendingCorrectionPerReport() throws Exception {
        String schema = "report_correction_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE production_reports (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO production_reports(id) VALUES (101)");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261101_22__production_report_corrections.sql"));

            insertCorrection(statement, "C-1", "PENDING_APPROVAL");
            SQLException duplicatePending = assertThrows(SQLException.class,
                    () -> insertCorrection(statement, "C-2", "PENDING_APPROVAL"));
            assertEquals("23505", duplicatePending.getSQLState());

            statement.execute("UPDATE production_report_corrections SET status='APPROVED_APPLIED' WHERE id='C-1'");
            insertCorrection(statement, "C-3", "PENDING_APPROVAL");

            try (ResultSet rs = statement.executeQuery("""
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE status='PENDING_APPROVAL') AS pending,
                           count(*) FILTER (WHERE status='APPROVED_APPLIED') AS approved
                    FROM production_report_corrections
                    WHERE factory_id='F006' AND production_report_id=101
                    """)) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("total"));
                assertEquals(1, rs.getInt("pending"));
                assertEquals(1, rs.getInt("approved"));
            }
            try (ResultSet rs = statement.executeQuery("""
                    SELECT indexdef FROM pg_indexes
                    WHERE schemaname=current_schema() AND indexname='uq_prod_report_correction_pending'
                    """)) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).contains("WHERE (status = 'PENDING_APPROVAL'::text)"));
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT data_corrected FROM production_reports WHERE id=101")) {
                assertTrue(rs.next());
                assertEquals(false, rs.getBoolean(1));
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static void insertCorrection(Statement statement, String id, String status) throws SQLException {
        statement.execute("""
                INSERT INTO production_report_corrections
                  (id, factory_id, production_report_id, status, correction_reason,
                   submitted_by, submitted_at, created_at, updated_at)
                VALUES ('%s', 'F006', 101, '%s', '现场核对', 42, now(), now(), now())
                """.formatted(id, status));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }
}
