package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Opt-in real PostgreSQL proof that both transactions contend on the shared Workflow key. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class WorkflowProductionReferenceAdvisoryLockPostgresIntegrationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String safeUrl = DisposablePostgresTargetGuard.requireSafeUrl(
                requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> safeUrl);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired private DataSource dataSource;

    @Test
    void twoTransactionsBlockOnTheSameFactoryWorkflowKey() throws Exception {
        try (Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            acquire(first, "F006", "PT-PIG");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                var waiting = executor.submit(() -> {
                    acquire(second, "F006", "PT-PIG");
                    second.commit();
                    return null;
                });
                assertThrows(TimeoutException.class,
                        () -> waiting.get(300, TimeUnit.MILLISECONDS));
                first.commit();
                waiting.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                second.rollback();
            }
        }
    }

    private static void acquire(Connection connection, String factoryId, String productTypeId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_advisory_xact_lock(hashtextextended(
                    CAST(? AS text) || E'\\x1f' || CAST(? AS text), 0))
                """)) {
            statement.setString(1, factoryId);
            statement.setString(2, productTypeId);
            statement.execute();
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
