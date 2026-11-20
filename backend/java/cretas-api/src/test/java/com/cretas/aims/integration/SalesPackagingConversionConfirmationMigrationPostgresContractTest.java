package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V20261109_10 在真 PG 上 (Q10): 换算确认留痕只增 —— UPDATE / TRUNCATE 被触发器拒绝; 数量必须为正, 规格系数为正;
 * 整厂数据删除 (按 factory_id DELETE) 不被拦。迁移可重复执行 (IF NOT EXISTS / DROP TRIGGER IF EXISTS)。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SalesPackagingConversionConfirmationMigrationPostgresContractTest {

    private static final String MIGRATION = "db/flyway/V20261109_10__sales_packaging_conversion_confirmations.sql";
    private static final String INSERT = "INSERT INTO sales_packaging_conversion_confirmations (factory_id, delivery_record_id,"
            + " delivery_item_id, finished_goods_batch_id, batch_number, allocated_qty, unit, from_packaging_unit,"
            + " from_packaging_base_unit, from_packaging_factor, to_packaging_unit, to_packaging_base_unit,"
            + " to_packaging_factor, confirmed_by, confirmed_at) VALUES ";

    private static String row(String factory, String qty, String fromFactor) {
        return INSERT + "('" + factory + "', 'DLV-1', '271', 'FG02', 'FG02', " + qty + ", '箱', '托盘', '盒', "
                + fromFactor + ", '箱', '盒', 8, 7001, now())";
    }

    @Test
    void appendOnlyTrailWithErasureStillPossible() throws Exception {
        String schema = "spcc_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            // 整个脚本一次执行 (函数体里有分号, 不能按分号拆); 第二次执行证明可重复
            String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            statement.execute(sql);
            statement.execute(sql);

            statement.execute(row("F1", "1", "96"));
            statement.execute(row("F2", "2", "96"));
            assertEquals(2L, count(statement, "SELECT count(*) FROM sales_packaging_conversion_confirmations"));

            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE sales_packaging_conversion_confirmations SET confirmed_by = 1 WHERE factory_id = 'F1'"),
                    "trail rows cannot be rewritten");
            assertThrows(SQLException.class, () -> statement.execute(
                    "TRUNCATE sales_packaging_conversion_confirmations"), "trail cannot be truncated");
            assertThrows(SQLException.class, () -> statement.execute(row("F1", "0", "96")), "zero quantity");
            assertThrows(SQLException.class, () -> statement.execute(row("F1", "1", "0")), "zero factor");
            assertThrows(SQLException.class, () -> statement.execute(INSERT
                    + "('F1', NULL, '271', 'FG02', NULL, 1, NULL, '托盘', '盒', 96, '箱', '盒', 8, NULL, now())"),
                    "confirmer is mandatory");
            assertEquals(1L, count(statement,
                    "SELECT count(*) FROM sales_packaging_conversion_confirmations WHERE factory_id = 'F1' AND confirmed_by = 7001"));

            // 整厂删除按 factory_id 逐表 DELETE: 不在库里拦, 而且只删那一厂 (阴性对照: 别厂一行不少)
            assertDoesNotThrow(() -> statement.execute(
                    "DELETE FROM sales_packaging_conversion_confirmations WHERE factory_id = 'F1'"));
            assertEquals(0L, count(statement, "SELECT count(*) FROM sales_packaging_conversion_confirmations WHERE factory_id = 'F1'"));
            assertEquals(1L, count(statement, "SELECT count(*) FROM sales_packaging_conversion_confirmations WHERE factory_id = 'F2'"));
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static long count(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
