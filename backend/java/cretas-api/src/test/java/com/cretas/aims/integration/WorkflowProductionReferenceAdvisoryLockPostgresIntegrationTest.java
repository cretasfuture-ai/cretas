package com.cretas.aims.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Opt-in PostgreSQL proof using the actual V53/V65/V21 migration resources.
 * The disposable database is prepared with JDBC migrations, then production-start
 * triggers and the Java deactivation lock contend on the same literal "\\x1f" key.
 */
@JdbcTest(properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class WorkflowProductionReferenceAdvisoryLockPostgresIntegrationTest {

    private static final String JAVA_SEPARATOR = "\\x1f";
    private static final String JAVA_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String safeUrl = DisposablePostgresTargetGuard.requireSafeUrl(
                requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> safeUrl);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void applyProductionMigrations() throws Exception {
        try (Connection connection = dataSource.getConnection();
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
                      status VARCHAR(32) NOT NULL
                    );
                    CREATE TABLE public.production_batches (
                      id BIGINT PRIMARY KEY,
                      factory_id VARCHAR(64) NOT NULL,
                      product_type_id VARCHAR(64) NOT NULL,
                      production_plan_id BIGINT,
                      status VARCHAR(32) NOT NULL
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
            executeMigration(statement, "V20261028_53__pin_batch_workflow_selection.sql");
            executeMigration(statement, "V20261028_65__production_plan_workflow_snapshot.sql");
            executeMigration(statement,
                    "V20261030_21__workflow_production_reference_advisory_lock.sql");
            statement.execute("""
                    INSERT INTO public.product_process_workflows
                        (id, factory_id, product_type_id, status, definition_version)
                    VALUES (1, 'F006', 'OWNER-SKU', 'PUBLISHED', 1)
                    """);
            statement.execute("""
                    INSERT INTO public.product_process_workflow_activations
                        (id, factory_id, product_type_id, active_workflow_id,
                         active_definition_version, enabled)
                    VALUES (1, 'F006', 'OWNER-SKU', 1, 1, TRUE)
                    """);
            statement.execute("""
                    INSERT INTO public.product_process_workflow_activations
                        (id, factory_id, product_type_id, active_workflow_id,
                         active_definition_version, enabled)
                    VALUES (2, 'F006', 'OUTPUT-SKU', 1, 1, TRUE)
                    """);
        }
    }

    @AfterEach
    void removeProductionMigrations() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            dropObjects(statement);
        }
    }

    @Test
    void v53V65V21TriggersExecuteAndPinProductionReferences() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO public.production_batches
                        (id, factory_id, product_type_id, status)
                    VALUES (1, 'F006', 'OUTPUT-SKU', 'IN_PROGRESS')
                    """);
            assertEquals(1L, scalarLong(connection,
                    "SELECT selected_workflow_id FROM public.production_batches WHERE id = 1"));
            assertEquals(1, scalarInt(connection,
                    "SELECT selected_workflow_version FROM public.production_batches WHERE id = 1"));
            statement.execute("""
                    INSERT INTO public.production_plans
                        (id, factory_id, product_type_id, status,
                         workflow_selection_mode, selected_workflow_id, selected_workflow_version)
                    VALUES (1, 'F006', 'OWNER-SKU', 'IN_PROGRESS', 'WORKFLOW', 1, 1)
                    """);
        }
    }

    @Test
    void productionStartTriggerBlocksJavaDeactivationWithoutDeadlock() throws Exception {
        contend(
                first -> insertRunningBatch(first, 10L),
                second -> acquireJavaLock(second, "F006", "OWNER-SKU"));
    }

    @Test
    void javaDeactivationBlocksProductionStartWithoutDeadlock() throws Exception {
        contend(
                first -> acquireJavaLock(first, "F006", "OWNER-SKU"),
                second -> insertRunningBatch(second, 11L));
    }

    private void contend(SqlAction firstAction, SqlAction secondAction) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            firstAction.run(first);
            var waiting = executor.submit(() -> {
                secondAction.run(second);
                second.commit();
                return null;
            });
            assertThrows(TimeoutException.class,
                    () -> waiting.get(300, TimeUnit.MILLISECONDS),
                    "the opposite transaction must wait on the shared production key");
            first.commit();
            waiting.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void insertRunningBatch(Connection connection, long id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.production_batches
                    (id, factory_id, product_type_id, status)
                VALUES (?, 'F006', 'OUTPUT-SKU', 'IN_PROGRESS')
                """)) {
            statement.setLong(1, id);
            statement.execute();
        }
    }

    private static void acquireJavaLock(Connection connection, String factoryId,
                                        String productTypeId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(JAVA_LOCK_SQL)) {
            statement.setString(1, factoryId + JAVA_SEPARATOR + productTypeId);
            statement.execute();
        }
    }

    private static void executeMigration(Statement statement, String fileName)
            throws IOException, java.sql.SQLException {
        ClassPathResource resource = new ClassPathResource("db/flyway/" + fileName);
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        statement.execute(sql);
    }

    private static void dropObjects(Statement statement) throws java.sql.SQLException {
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

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static int scalarInt(Connection connection, String sql) throws Exception {
        return (int) scalarLong(connection, sql);
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
