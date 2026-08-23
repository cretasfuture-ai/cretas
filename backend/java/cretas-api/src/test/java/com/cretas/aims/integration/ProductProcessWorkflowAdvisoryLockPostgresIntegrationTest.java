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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Opt-in proof that the workflow owner lock is a real PostgreSQL transaction lock.
 * The key is deliberately the same factory + U+001F + product seed used by
 * V20261028_53's activation and production-batch triggers.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductProcessWorkflowAdvisoryLockPostgresIntegrationTest {

    private static final String LOCK_SQL =
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
        registry.add("spring.jpa.database-platform",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void sameFactoryProductSeedSerializesTwoTransactions() throws Exception {
        String seed = "WF-LOCK-" + UUID.randomUUID() + "\u001fPT-PIG";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            takeLock(first, seed);

            Future<?> blocked = executor.submit(() -> {
                try {
                    takeLock(second, seed);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            Thread.sleep(250);
            assertFalse(blocked.isDone(), "second transaction must wait for the owner lock");

            first.commit();
            blocked.get(5, TimeUnit.SECONDS);
            second.rollback();
        } finally {
            executor.shutdownNow();
        }
    }

    private void takeLock(Connection connection, String seed) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
            statement.setString(1, seed);
            assertNotNull(statement.executeQuery());
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
}
