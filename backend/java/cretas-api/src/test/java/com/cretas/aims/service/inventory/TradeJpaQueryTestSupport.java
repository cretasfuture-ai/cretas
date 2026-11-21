package com.cretas.aims.service.inventory;

import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.sql.SQLException;

/** Bind to the shard's disposable PostgreSQL instance while retaining the normal H2 startup gate. */
public abstract class TradeJpaQueryTestSupport {
    private static final String SCHEMA = "trade_query_validation";

    @DynamicPropertySource
    static void tradeDatabase(DynamicPropertyRegistry registry) throws SQLException {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        boolean hasUrl = url != null && !url.isBlank();
        boolean verify = "true".equals(System.getenv("CRETAS_WORKFLOW_PG_VERIFY"));
        if (hasUrl != verify) {
            throw new IllegalStateException("CRETAS_WORKFLOW_PG_URL and CRETAS_WORKFLOW_PG_VERIFY=true must be set together");
        }
        if (!hasUrl) return;

        String safeUrl = DisposablePostgresTargetGuard.requireSafeUrl(url);
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        // Keep Hibernate-created tables away from other suites sharing the shard database.
        try (var connection = DriverManager.getConnection(safeUrl, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            System.out.printf("TRADE_QUERY_DB=PostgreSQL %s schema=%s%n",
                    connection.getMetaData().getDatabaseProductVersion(), SCHEMA);
        }
        registry.add("spring.datasource.url", () -> safeUrl + "?stringtype=unspecified&currentSchema=" + SCHEMA);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
}
