package com.cretas.aims.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V20261103_91_1 on real PostgreSQL: existing returns read tax_amount=0 (history untouched), the column
 * refuses NULL, the seed RETURN template becomes the three-line tax split while a customised template is
 * left alone, and re-running the script is harmless.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ReturnOrderTaxAmountMigrationPostgresContractTest {

    private static final String MIGRATION = "db/flyway/V20261103_91_1__return_order_tax_amount.sql";

    @Test
    void historyKeepsZeroTaxAndOnlySeedTemplateIsUpgraded() throws Exception {
        String schema = "return_tax_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE return_orders (id VARCHAR(191) PRIMARY KEY, total_amount NUMERIC(15,2))");
            statement.execute("INSERT INTO return_orders VALUES ('RO-OLD', 70.80)");
            statement.execute("CREATE TABLE voucher_templates (id VARCHAR(36) PRIMARY KEY, factory_id VARCHAR(50),"
                    + " voucher_type VARCHAR(32), name VARCHAR(100), description TEXT, entries_json JSONB,"
                    + " updated_at TIMESTAMP, deleted_at TIMESTAMP)");
            String twoLines = "'[{\"sortOrder\":1,\"subjectCode\":\"6001\",\"direction\":\"DEBIT\","
                    + "\"amountExpression\":\"#entity.totalAmount\"},{\"sortOrder\":2,\"subjectCode\":\"1122\","
                    + "\"direction\":\"CREDIT\",\"amountExpression\":\"#entity.totalAmount\"}]'::jsonb";
            statement.execute("INSERT INTO voucher_templates VALUES ('T-SEED','F006','RETURN','退货默认模板',NULL,"
                    + twoLines + ",NULL,NULL)");
            statement.execute("INSERT INTO voucher_templates VALUES ('T-CUSTOM','F006','RETURN','客户自定义退货',NULL,"
                    + twoLines + ",NULL,NULL)");

            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION)); // idempotent

            try (ResultSet rs = statement.executeQuery("SELECT tax_amount FROM return_orders WHERE id = 'RO-OLD'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getBigDecimal(1).signum(), "history is not backfilled");
            }
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO return_orders (id, total_amount, tax_amount) VALUES ('RO-NULL', 1, NULL)"));
            try (ResultSet rs = statement.executeQuery("SELECT id, jsonb_array_length(entries_json),"
                    + " entries_json->1->>'subjectCode', entries_json->1->>'omitWhenZero',"
                    + " entries_json->2->>'amountExpression' FROM voucher_templates ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals("T-CUSTOM", rs.getString(1));
                assertEquals(2, rs.getInt(2), "customised template is left alone");
                assertTrue(rs.next());
                assertEquals("T-SEED", rs.getString(1));
                assertEquals(3, rs.getInt(2));
                assertEquals("2221.01", rs.getString(3));
                assertEquals("true", rs.getString(4));
                assertEquals("(#entity.totalAmount ?: 0) + (#entity.taxAmount ?: 0)", rs.getString(5));
            }
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when CRETAS_WORKFLOW_PG_VERIFY=true");
        }
        return value;
    }
}
