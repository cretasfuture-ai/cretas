package com.cretas.aims.service.mobile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

/** Same real service/migration contract against disposable PostgreSQL, not H2. */
@EnabledIfEnvironmentVariable(named="CRETAS_WORKFLOW_PG_VERIFY", matches="true")
class WebLoginSessionPostgresContractTest extends WebLoginSessionContractTest {
    private Connection connection;
    private String schema;
    @Override protected JdbcTemplate createJdbc() {
        try {
            String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
            if (url == null || !(url.startsWith("jdbc:postgresql://127.0.0.1:") || url.startsWith("jdbc:postgresql://localhost:")))
                throw new IllegalStateException("Only disposable loopback PostgreSQL accepted");
            connection = DriverManager.getConnection(url, System.getenv("CRETAS_WORKFLOW_PG_USER"), System.getenv("CRETAS_WORKFLOW_PG_PASSWORD"));
            schema = "web_session_verify_" + UUID.randomUUID().toString().replace("-", "");
            try (var statement = connection.createStatement()) { statement.execute("CREATE SCHEMA " + schema); }
            connection.setSchema(schema);
            return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
    }
    @AfterEach void cleanup() throws Exception {
        if (connection != null) {
            try { connection.setSchema("public"); try (var s = connection.createStatement()) { s.execute("DROP SCHEMA " + schema + " CASCADE"); } }
            finally { connection.close(); }
        }
    }
}
