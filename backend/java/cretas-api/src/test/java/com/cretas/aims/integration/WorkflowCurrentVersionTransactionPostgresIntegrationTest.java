package com.cretas.aims.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL proof for the shared production-plan row lock used by Workflow rebind and
 * the first formal process-sheet submission. The test is opt-in and only accepts a disposable
 * database URL through {@link DisposablePostgresTargetGuard}.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class WorkflowCurrentVersionTransactionPostgresIntegrationTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "PLAN-WF-TX";

    @BeforeEach
    void createSchema() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            dropObjects(statement);
            statement.execute("""
                    CREATE TABLE product_process_workflows (
                      id BIGINT PRIMARY KEY,
                      factory_id VARCHAR(64) NOT NULL,
                      definition_version INTEGER NOT NULL,
                      status VARCHAR(32) NOT NULL
                    );
                    CREATE TABLE product_process_workflow_activations (
                      factory_id VARCHAR(64) PRIMARY KEY,
                      active_workflow_id BIGINT NOT NULL
                    );
                    CREATE TABLE production_plans (
                      id VARCHAR(64) PRIMARY KEY,
                      factory_id VARCHAR(64) NOT NULL,
                      status VARCHAR(32) NOT NULL,
                      selected_workflow_id BIGINT NOT NULL,
                      selected_workflow_version INTEGER NOT NULL
                    );
                    CREATE TABLE process_sheet_rows (
                      id BIGSERIAL PRIMARY KEY,
                      factory_id VARCHAR(64) NOT NULL,
                      plan_id VARCHAR(64) NOT NULL,
                      row_status VARCHAR(32) NOT NULL,
                      submission_status VARCHAR(32) NOT NULL,
                      interim_settled_at TIMESTAMP NULL,
                      deleted_at TIMESTAMP NULL,
                      workflow_id_at_submit BIGINT NULL
                    );
                    CREATE TABLE workflow_runtime_instances (
                      id BIGSERIAL PRIMARY KEY,
                      factory_id VARCHAR(64) NOT NULL,
                      plan_id VARCHAR(64) NOT NULL
                    );
                    INSERT INTO product_process_workflows VALUES
                      (1, 'F006', 1, 'PUBLISHED'),
                      (2, 'F006', 2, 'PUBLISHED');
                    INSERT INTO product_process_workflow_activations VALUES ('F006', 1);
                    INSERT INTO production_plans VALUES
                      ('PLAN-WF-TX', 'F006', 'IN_PROGRESS', 1, 1);
                    """);
        }
    }

    @AfterEach
    void removeSchema() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            dropObjects(statement);
        }
    }

    @Test
    void firstFormalReportCommitsFirstSoPlanKeepsOldVersionAndHistoryBinding() throws Exception {
        contend(
                first -> submitFormalReport(first),
                second -> updateCurrentWorkflow(second));

        try (Connection connection = openConnection()) {
            assertEquals(1L, scalarLong(connection,
                    "SELECT selected_workflow_id FROM production_plans WHERE id='" + PLAN + "'"));
            assertEquals(1L, scalarLong(connection,
                    "SELECT workflow_id_at_submit FROM process_sheet_rows WHERE plan_id='" + PLAN + "'"));
            assertTrue(started(connection));
            assertEquals(1, scalarInt(connection, """
                    SELECT count(*) FROM production_plans p
                    WHERE p.selected_workflow_id = 1
                      AND EXISTS (
                        SELECT 1 FROM process_sheet_rows r
                        WHERE r.factory_id=p.factory_id AND r.plan_id=p.id
                          AND r.deleted_at IS NULL
                          AND (r.row_status='SUBMITTED'
                            OR r.submission_status='SUBMITTED'
                            OR r.interim_settled_at IS NOT NULL))
                    """));
        }
    }

    @Test
    void workflowUpdateCommitsFirstSoFollowingFirstReportBindsNewVersion() throws Exception {
        contend(
                first -> updateCurrentWorkflow(first),
                second -> submitFormalReport(second));

        try (Connection connection = openConnection()) {
            assertEquals(2L, scalarLong(connection,
                    "SELECT selected_workflow_id FROM production_plans WHERE id='" + PLAN + "'"));
            assertEquals(2L, scalarLong(connection,
                    "SELECT workflow_id_at_submit FROM process_sheet_rows WHERE plan_id='" + PLAN + "'"));
            assertEquals(2L, scalarLong(connection,
                    "SELECT active_workflow_id FROM product_process_workflow_activations WHERE factory_id='F006'"));
            assertEquals(1, scalarInt(connection,
                    "SELECT count(DISTINCT selected_workflow_id) FROM production_plans WHERE id='" + PLAN + "'"));
        }
    }

    @Test
    void startedPredicateCoversAllSignalsAndIgnoresDraftRuntimeAndSoftDelete() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            assertFalse(started(connection), "IN_PROGRESS without report is not started");

            statement.execute("INSERT INTO workflow_runtime_instances(factory_id,plan_id) VALUES ('F006','" + PLAN + "')");
            assertFalse(started(connection), "materialized runtime without formal report is not started");

            insertRow(connection, "SAVED", "DRAFT", null, null);
            assertFalse(started(connection), "draft-only report is not started");
            statement.execute("TRUNCATE process_sheet_rows RESTART IDENTITY");

            insertRow(connection, "SUBMITTED", "DRAFT", null, null);
            assertTrue(started(connection), "rowStatus=SUBMITTED is started");
            statement.execute("TRUNCATE process_sheet_rows RESTART IDENTITY");

            insertRow(connection, "SAVED", "SUBMITTED", null, null);
            assertTrue(started(connection), "submissionStatus=SUBMITTED is started");
            statement.execute("TRUNCATE process_sheet_rows RESTART IDENTITY");

            insertRow(connection, "SAVED", "DRAFT", LocalDateTime.now(), null);
            assertTrue(started(connection), "interimSettledAt is started");
            statement.execute("TRUNCATE process_sheet_rows RESTART IDENTITY");

            insertRow(connection, "SUBMITTED", "SUBMITTED", null, LocalDateTime.now());
            assertFalse(started(connection), "soft-deleted formal report must not mark started");
        }
    }

    @Test
    void failedWorkflowUpdateRollsBackActivationAndPlanRebindTogether() throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            updateCurrentWorkflow(connection);
            assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO product_process_workflows VALUES (2,'F006',2,'PUBLISHED')");
                }
            });
            connection.rollback();
        }

        try (Connection connection = openConnection()) {
            assertEquals(1L, scalarLong(connection,
                    "SELECT selected_workflow_id FROM production_plans WHERE id='" + PLAN + "'"));
            assertEquals(1L, scalarLong(connection,
                    "SELECT active_workflow_id FROM product_process_workflow_activations WHERE factory_id='F006'"));
            assertEquals(0, scalarInt(connection, "SELECT count(*) FROM process_sheet_rows"));
        }
    }

    private void contend(SqlAction firstAction, SqlAction secondAction) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection first = openConnection(); Connection second = openConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            firstAction.run(first);
            var waiting = executor.submit(() -> {
                secondAction.run(second);
                second.commit();
                return null;
            });
            assertThrows(TimeoutException.class, () -> waiting.get(300, TimeUnit.MILLISECONDS),
                    "second transaction must wait on the same production plan row");
            first.commit();
            waiting.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void updateCurrentWorkflow(Connection connection) throws Exception {
        lockPlan(connection);
        if (!started(connection)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE production_plans
                       SET selected_workflow_id=2, selected_workflow_version=2
                     WHERE id=? AND factory_id=?
                    """)) {
                statement.setString(1, PLAN);
                statement.setString(2, FACTORY);
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE product_process_workflow_activations
                   SET active_workflow_id=2 WHERE factory_id=?
                """)) {
            statement.setString(1, FACTORY);
            statement.executeUpdate();
        }
    }

    private static void submitFormalReport(Connection connection) throws Exception {
        long workflowId = lockPlan(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO process_sheet_rows
                  (factory_id,plan_id,row_status,submission_status,workflow_id_at_submit)
                VALUES (?,?,'SUBMITTED','SUBMITTED',?)
                """)) {
            statement.setString(1, FACTORY);
            statement.setString(2, PLAN);
            statement.setLong(3, workflowId);
            statement.executeUpdate();
        }
    }

    private static long lockPlan(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT selected_workflow_id FROM production_plans
                 WHERE id=? AND factory_id=? FOR UPDATE
                """)) {
            statement.setString(1, PLAN);
            statement.setString(2, FACTORY);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static boolean started(Connection connection) throws Exception {
        return scalarInt(connection, """
                SELECT count(*) FROM process_sheet_rows
                 WHERE factory_id='F006' AND plan_id='PLAN-WF-TX'
                   AND deleted_at IS NULL
                   AND (row_status='SUBMITTED'
                     OR submission_status='SUBMITTED'
                     OR interim_settled_at IS NOT NULL)
                """) > 0;
    }

    private static void insertRow(Connection connection, String rowStatus, String submissionStatus,
                                  LocalDateTime settledAt, LocalDateTime deletedAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO process_sheet_rows
                  (factory_id,plan_id,row_status,submission_status,interim_settled_at,deleted_at)
                VALUES (?,?,?,?,?,?)
                """)) {
            statement.setString(1, FACTORY);
            statement.setString(2, PLAN);
            statement.setString(3, rowStatus);
            statement.setString(4, submissionStatus);
            statement.setTimestamp(5, settledAt == null ? null : Timestamp.valueOf(settledAt));
            statement.setTimestamp(6, deletedAt == null ? null : Timestamp.valueOf(deletedAt));
            statement.executeUpdate();
        }
    }

    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL")),
                requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }

    private static void dropObjects(Statement statement) throws SQLException {
        statement.execute("""
                DROP TABLE IF EXISTS workflow_runtime_instances CASCADE;
                DROP TABLE IF EXISTS process_sheet_rows CASCADE;
                DROP TABLE IF EXISTS production_plans CASCADE;
                DROP TABLE IF EXISTS product_process_workflow_activations CASCADE;
                DROP TABLE IF EXISTS product_process_workflows CASCADE;
                """);
    }

    private static int scalarInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run(Connection connection) throws Exception;
    }
}
