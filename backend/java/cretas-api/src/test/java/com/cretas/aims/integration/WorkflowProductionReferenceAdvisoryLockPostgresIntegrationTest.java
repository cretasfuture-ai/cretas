package com.cretas.aims.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in PostgreSQL proof using the actual migration resources, on two real connections.
 *
 * <p>Workflow deactivation and "plan/batch enters running" share one transaction-scoped
 * advisory key (V20261030_21). The lock alone only serializes them; it does not decide the
 * loser. Running-first is decided by the deactivation recheck (P0001). Deactivation-first is
 * decided by V20261101_55: once the queued start gets the key it must see the committed
 * deactivation and refuse (23514) instead of starting on a Workflow that was just disabled.
 *
 * <p>The pin function is the body production runs (V20261029_83, verbatim copy of _77), not
 * the superseded V65 one. V20261029_30's plan/batch snapshot columns that it reads are replayed
 * inline, because that migration also needs the whole settlement schema.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class WorkflowProductionReferenceAdvisoryLockPostgresIntegrationTest {

    private static final String JAVA_SEPARATOR = "\\x1f";
    private static final String JAVA_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))";
    /** Long enough for an unblocked statement on the CI VM, short enough to keep the gate fast. */
    private static final long MUST_WAIT_MILLIS = 700;

    @BeforeEach
    void applyProductionMigrations() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            dropObjects(statement);
            statement.execute("SET search_path TO public");
            executeMigration(statement, "V20261028_50__product_process_workflow.sql");
            statement.execute("""
                    ALTER TABLE public.product_process_workflows
                      ADD CONSTRAINT uk_test_ppw_owner_version
                      UNIQUE (id, factory_id, product_type_id, definition_version)
                    """);
            statement.execute("""
                    CREATE TABLE public.production_plans (
                      id BIGINT PRIMARY KEY,
                      factory_id VARCHAR(64) NOT NULL,
                      product_type_id VARCHAR(64) NOT NULL,
                      status VARCHAR(32) NOT NULL,
                      deleted_at TIMESTAMP
                    );
                    CREATE TABLE public.production_batches (
                      id BIGINT PRIMARY KEY,
                      factory_id VARCHAR(64) NOT NULL,
                      product_type_id VARCHAR(64) NOT NULL,
                      production_plan_id BIGINT,
                      status VARCHAR(32) NOT NULL,
                      deleted_at TIMESTAMP
                    );
                    CREATE TABLE public.product_process_workflow_activations (
                      id BIGINT PRIMARY KEY,
                      factory_id VARCHAR(64) NOT NULL,
                      product_type_id VARCHAR(64) NOT NULL,
                      active_workflow_id BIGINT,
                      active_definition_version INTEGER,
                      enabled BOOLEAN NOT NULL DEFAULT TRUE,
                      deleted_at TIMESTAMP
                    )
                    """);
            // Flyway order. Each pin-function migration replaces the previous body.
            executeMigration(statement, "V20261028_53__pin_batch_workflow_selection.sql");
            executeMigration(statement, "V20261028_65__production_plan_workflow_snapshot.sql");
            executeMigration(statement, "V20261028_83__allow_joint_output_batch_workflow_pin.sql");
            // V20261028_59 + V20261029_30 snapshot columns read/written by the current pin body.
            statement.execute("""
                    ALTER TABLE public.production_plans
                      ADD COLUMN target_finished_good_ids JSONB,
                      ADD COLUMN selected_workflow_revision_id BIGINT,
                      ADD COLUMN selected_workflow_revision_hash VARCHAR(64),
                      ADD COLUMN workflow_output_units_by_product JSONB NOT NULL DEFAULT '{}'::jsonb,
                      ADD COLUMN selected_bom_family_id VARCHAR(64),
                      ADD COLUMN selected_bom_recipe_ids_by_product JSONB NOT NULL DEFAULT '{}'::jsonb,
                      ADD COLUMN selected_bom_versions_by_product JSONB NOT NULL DEFAULT '{}'::jsonb;
                    ALTER TABLE public.production_batches
                      ADD COLUMN selected_workflow_revision_id BIGINT,
                      ADD COLUMN selected_workflow_revision_hash VARCHAR(64),
                      ADD COLUMN selected_bom_family_id VARCHAR(64),
                      ADD COLUMN selected_bom_recipe_ids_by_product JSONB NOT NULL DEFAULT '{}'::jsonb,
                      ADD COLUMN selected_bom_versions_by_product JSONB NOT NULL DEFAULT '{}'::jsonb,
                      ADD COLUMN workflow_output_units_by_product JSONB NOT NULL DEFAULT '{}'::jsonb,
                      ADD COLUMN target_finished_good_ids JSONB NOT NULL DEFAULT '[]'::jsonb
                    """);
            executeMigration(statement, "V20261029_83__revert_clerk_wip_allowance.sql");
            executeMigration(statement,
                    "V20261030_21__workflow_production_reference_advisory_lock.sql");
            executeMigration(statement,
                    "V20261101_55__workflow_start_rejects_deactivated_version.sql");
            statement.execute("""
                    INSERT INTO public.product_process_workflows
                        (id, factory_id, product_type_id, status, definition_version)
                    VALUES (1, 'F006', 'OWNER-SKU', 'PUBLISHED', 1),
                           (2, 'F006', 'OWNER-SKU', 'PUBLISHED', 2)
                    """);
            statement.execute("""
                    INSERT INTO public.product_process_workflow_activations
                        (id, factory_id, product_type_id, active_workflow_id,
                         active_definition_version, enabled)
                    VALUES (1, 'F006', 'OWNER-SKU', 1, 1, TRUE)
                    """);
            statement.execute("""
                    INSERT INTO public.production_plans
                        (id, factory_id, product_type_id, status,
                         workflow_selection_mode, selected_workflow_id, selected_workflow_version)
                    VALUES (99, 'F006', 'OWNER-SKU', 'PLANNED', 'WORKFLOW', 1, 1)
                    """);
            printMutationProbe(connection);
        }
    }

    @AfterEach
    void removeProductionMigrations() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            dropObjects(statement);
        }
    }

    @Test
    void v53V65V21TriggersExecuteAndPinProductionReferences() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO public.production_plans
                        (id, factory_id, product_type_id, status,
                         workflow_selection_mode, selected_workflow_id, selected_workflow_version)
                    VALUES (1, 'F006', 'OWNER-SKU', 'IN_PROGRESS', 'WORKFLOW', 1, 1)
                    """);
            statement.execute("""
                    INSERT INTO public.production_batches
                        (id, factory_id, product_type_id, production_plan_id, status)
                    VALUES (1, 'F006', 'OUTPUT-SKU', 1, 'IN_PROGRESS')
                    """);
            assertEquals(1L, scalarLong(connection,
                    "SELECT selected_workflow_id FROM public.production_batches WHERE id = 1"));
            assertEquals(1, scalarInt(connection,
                    "SELECT selected_workflow_version FROM public.production_batches WHERE id = 1"));
            assertEquals(1, scalarInt(connection, """
                    SELECT count(*) FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND indexname = 'idx_production_plans_workflow_reference'
                    """));
            assertEquals(1, scalarInt(connection, """
                    SELECT count(*) FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND indexname = 'idx_production_batches_workflow_reference'
                    """));
        }
    }

    @Test
    void activationTriggerIgnoresSoftDeletedRunningHistory() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO public.production_plans
                        (id, factory_id, product_type_id, status,
                         workflow_selection_mode, selected_workflow_id, selected_workflow_version)
                    VALUES (12, 'F006', 'OWNER-SKU', 'IN_PROGRESS', 'WORKFLOW', 1, 1)
                    """);
            SQLException blocked = assertThrows(SQLException.class, () -> deactivate(connection));
            assertEquals("P0001", blocked.getSQLState());
            assertEquals(1, enabledCount(connection));

            statement.execute("""
                    UPDATE public.production_plans
                    SET deleted_at = CURRENT_TIMESTAMP
                    WHERE id = 12
                    """);
            deactivate(connection);
            assertEquals(0, enabledCount(connection));
        }
    }

    @Test
    void productionStartTriggerBlocksJavaDeactivationWithoutDeadlock() throws Exception {
        Outcome outcome = contend(
                first -> insertRunningBatch(first, 10L),
                second -> acquireJavaLock(second, "F006", "OWNER-SKU"));
        assertTrue(outcome.secondWaited(), "the Java lock must wait on the running batch's key");
        assertNull(outcome.secondFailure());
    }

    @Test
    void javaDeactivationBlocksProductionStartWithoutDeadlock() throws Exception {
        // The Java seed only takes the key; nothing was deactivated, so the start succeeds.
        Outcome outcome = contend(
                first -> acquireJavaLock(first, "F006", "OWNER-SKU"),
                second -> insertRunningBatch(second, 11L));
        assertTrue(outcome.secondWaited(), "Java seed and trigger seed must be the same key");
        assertNull(outcome.secondFailure());
        try (Connection connection = openConnection()) {
            assertEquals(1, runningBatchCount(connection));
        }
    }

    @Test
    void runningBatchFirstMakesConcurrentDeactivationWaitThenFail() throws Exception {
        Outcome outcome = contend(
                first -> insertRunningBatch(first, 13L),
                second -> deactivate(second));

        assertTrue(outcome.secondWaited(), "deactivation must wait on the running batch's key");
        assertInstanceOf(SQLException.class, outcome.secondFailure(),
                "after the batch commits, the deactivation recheck must see it");
        assertEquals("P0001", ((SQLException) outcome.secondFailure()).getSQLState());
        try (Connection connection = openConnection()) {
            assertEquals(1, enabledCount(connection));
            assertEquals(1, runningBatchCount(connection));
        }
    }

    @Test
    void deactivationFirstMakesConcurrentBatchStartWaitThenFail() throws Exception {
        Outcome outcome = contend(
                first -> deactivate(first),
                second -> insertRunningBatch(second, 14L));

        assertTrue(outcome.secondWaited(), "the batch start must wait on the deactivation's key");
        assertInstanceOf(SQLException.class, outcome.secondFailure(),
                "a batch must not start on a Workflow that was just deactivated");
        assertEquals("23514", ((SQLException) outcome.secondFailure()).getSQLState());
        try (Connection connection = openConnection()) {
            assertEquals(0, enabledCount(connection));
            assertEquals(0, runningBatchCount(connection));
        }
    }

    @Test
    void deactivationFirstMakesConcurrentPlanStartWaitThenFail() throws Exception {
        Outcome outcome = contend(
                first -> deactivate(first),
                second -> startPlan(second));

        assertTrue(outcome.secondWaited(), "the plan start must wait on the deactivation's key");
        assertInstanceOf(SQLException.class, outcome.secondFailure(),
                "a plan must not start on a Workflow that was just deactivated");
        assertEquals("23514", ((SQLException) outcome.secondFailure()).getSQLState());
        try (Connection connection = openConnection()) {
            assertEquals(0, enabledCount(connection));
            assertEquals("PLANNED", scalarString(connection,
                    "SELECT status FROM public.production_plans WHERE id = 99"));
        }
    }

    @Test
    void switchingTheActivationToAnotherVersionStillLetsOlderPinnedPlansStart() throws Exception {
        // Negative control for V20261101_55: only the exact deactivated version is refused.
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    UPDATE public.product_process_workflow_activations
                    SET active_workflow_id = 2, active_definition_version = 2
                    WHERE id = 1
                    """);
            deactivate(connection);
            assertEquals(0, enabledCount(connection));

            // Plan 99 is pinned to version 1, which is no longer the activation's version.
            startPlan(connection);
            insertRunningBatch(connection, 15L);
            assertEquals("IN_PROGRESS", scalarString(connection,
                    "SELECT status FROM public.production_plans WHERE id = 99"));
            assertEquals(1, runningBatchCount(connection));

            // Same activation, version 2 pinned: refused even without any concurrency.
            statement.execute("""
                    INSERT INTO public.production_plans
                        (id, factory_id, product_type_id, status,
                         workflow_selection_mode, selected_workflow_id, selected_workflow_version)
                    VALUES (100, 'F006', 'OWNER-SKU', 'PLANNED', 'WORKFLOW', 2, 2)
                    """);
            SQLException refused = assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE public.production_plans SET status = 'IN_PROGRESS' WHERE id = 100"));
            assertEquals("23514", refused.getSQLState(), refused.getMessage());
        }
    }

    private Outcome contend(SqlAction firstAction, SqlAction secondAction) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection first = openConnection();
             Connection second = openConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            firstAction.run(first);
            Future<Void> waiting = executor.submit(() -> {
                try {
                    secondAction.run(second);
                    second.commit();
                } catch (Exception failure) {
                    second.rollback();
                    throw failure;
                }
                return null;
            });
            boolean secondWaited;
            try {
                waiting.get(MUST_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                secondWaited = false;
            } catch (TimeoutException stillBlocked) {
                secondWaited = true;
            } catch (ExecutionException finishedEarly) {
                secondWaited = false;
            }
            first.commit();
            Throwable secondFailure = null;
            try {
                waiting.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException failed) {
                secondFailure = failed.getCause();
            }
            System.out.printf("[workflow-ref-lock] contend: secondWaited=%s secondFailure=%s%n",
                    secondWaited,
                    secondFailure == null ? "none"
                            : secondFailure instanceof SQLException sql
                                    ? sql.getSQLState() + " "
                                            + sql.getMessage().lines().findFirst().orElse("")
                                    : secondFailure.toString());
            return new Outcome(secondWaited, secondFailure);
        } finally {
            executor.shutdownNow();
        }
    }

    private record Outcome(boolean secondWaited, Throwable secondFailure) {
    }

    private static void insertRunningBatch(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.production_batches
                    (id, factory_id, product_type_id, production_plan_id, status)
                VALUES (?, 'F006', 'OUTPUT-SKU', 99, 'IN_PROGRESS')
                """)) {
            statement.setLong(1, id);
            statement.execute();
        }
    }

    private static void startPlan(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE public.production_plans SET status = 'IN_PROGRESS' WHERE id = 99"));
        }
    }

    private static void deactivate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("""
                    UPDATE public.product_process_workflow_activations
                    SET enabled = FALSE
                    WHERE id = 1
                    """));
        }
    }

    private static void acquireJavaLock(Connection connection, String factoryId,
                                        String productTypeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(JAVA_LOCK_SQL)) {
            statement.setString(1, factoryId + JAVA_SEPARATOR + productTypeId);
            statement.execute();
        }
    }

    private static int enabledCount(Connection connection) throws SQLException {
        return scalarInt(connection,
                "SELECT count(*) FROM public.product_process_workflow_activations WHERE id = 1 AND enabled");
    }

    private static int runningBatchCount(Connection connection) throws SQLException {
        return scalarInt(connection, """
                SELECT count(*) FROM public.production_batches
                WHERE factory_id = 'F006' AND status IN ('IN_PROGRESS', 'PRODUCING', 'PAUSED')
                """);
    }

    /** Observable for mutation runs: proves which function body this run executed. */
    private static void printMutationProbe(Connection connection) throws SQLException {
        System.out.printf(
                "[workflow-ref-lock] probe: referenceTriggers=%d lockCalls=%d recheckRaise=%d startGuard=%d%n",
                scalarInt(connection, """
                        SELECT count(*) FROM pg_trigger t
                          JOIN pg_class c ON c.oid = t.tgrelid
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public' AND t.tgname LIKE 'trg_z_%'
                        """),
                scalarInt(connection, functionSourceCount("pg_advisory_xact_lock")),
                scalarInt(connection, functionSourceCount("deactivate rejected after recheck")),
                scalarInt(connection, functionSourceCount("was deactivated; production cannot start")));
    }

    private static String functionSourceCount(String needle) {
        return """
                SELECT (length(prosrc) - length(replace(prosrc, '%1$s', ''))) / length('%1$s')
                  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'public' AND p.proname = 'lock_workflow_production_reference'
                """.formatted(needle);
    }

    private static void executeMigration(Statement statement, String fileName)
            throws IOException, SQLException {
        ClassPathResource resource = new ClassPathResource("db/flyway/" + fileName);
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        statement.execute(sql);
    }

    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL")),
                requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
    }

    private static void dropObjects(Statement statement) throws SQLException {
        statement.execute("""
                DROP TABLE IF EXISTS public.production_batches CASCADE;
                DROP TABLE IF EXISTS public.production_plans CASCADE;
                DROP TABLE IF EXISTS public.product_process_workflow_activations CASCADE;
                DROP TABLE IF EXISTS public.product_process_workflows CASCADE;
                DROP FUNCTION IF EXISTS public.pin_production_batch_workflow_selection() CASCADE;
                DROP FUNCTION IF EXISTS public.lock_product_process_workflow_activation() CASCADE;
                DROP FUNCTION IF EXISTS public.lock_workflow_production_reference() CASCADE
                """);
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "no row for: " + sql);
            return result.getLong(1);
        }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        return (int) scalarLong(connection, sql);
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "no row for: " + sql);
            return result.getString(1);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name
                    + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlAction {
        void run(Connection connection) throws Exception;
    }
}
