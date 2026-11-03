package com.cretas.aims.integration;

import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.entity.secrecy.FactorySecrecySettingAudit;
import com.cretas.aims.service.secrecy.SecrecyItem;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V20261104_21 在真实 PostgreSQL 上: 表与实体逐列一致 (列名 + 可空), item_code 的 CHECK 与枚举 {@link SecrecyItem}
 * 逐项一致, 「跟随」只允许出现在 AI_SECRECY_AUDIT 上, 留痕表只追加 (UPDATE 被触发器拒绝, DELETE 允许 ——
 * 平台删除工厂数据要能删掉)。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class FactorySecrecyMigrationPostgresContractTest {

    private static final String MIGRATION = "db/flyway/V20261104_21__factory_secrecy_settings.sql";

    @Test
    void migrationMatchesEntitiesAndEnforcesItsRules() throws Exception {
        String schema = "factory_secrecy_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE factories (id VARCHAR(50) PRIMARY KEY)");
            statement.execute("INSERT INTO factories (id) VALUES ('F1'), ('F2')");
            // 整份脚本一次执行 (含 $$ 函数体; pgjdbc 简单查询协议支持多语句)
            String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            statement.execute(sql);
            statement.execute(sql);   // IF NOT EXISTS / OR REPLACE: 重跑不报错

            assertColumnsMatchEntity(connection, schema, FactorySecrecySetting.class);
            assertColumnsMatchEntity(connection, schema, FactorySecrecySettingAudit.class);
            assertCheckListsEveryItem(connection, schema, "ck_fss_item_code");
            assertCheckListsEveryItem(connection, schema, "ck_fssa_item_code");

            String set = "INSERT INTO factory_secrecy_settings (factory_id, item_code, enabled, updated_by) VALUES ";
            assertDoesNotThrow(() -> statement.execute(set + "('F1', 'PROCESS_SECRET', true, 7)"));
            assertDoesNotThrow(() -> statement.execute(set + "('F1', 'AI_SECRECY_AUDIT', NULL, 7)"), "f 可以是「跟随」");
            assertThrows(SQLException.class, () -> statement.execute(set + "('F1', 'EGRESS_REDACTION', NULL, 7)"),
                    "只有 f 可以是「跟随」");
            assertThrows(SQLException.class, () -> statement.execute(set + "('F1', 'PROCESS_SECRET', false, 7)"),
                    "同一工厂同一项只有一行");
            assertThrows(SQLException.class, () -> statement.execute(set + "('F1', 'MADE_UP', true, 7)"), "未知项");
            assertThrows(SQLException.class, () -> statement.execute(set + "('NOPE', 'PROCESS_SECRET', true, 7)"),
                    "工厂外键");
            assertDoesNotThrow(() -> statement.execute(set + "('F2', 'PROCESS_SECRET', false, 7)"), "别的工厂");

            String audit = "INSERT INTO factory_secrecy_setting_audit (factory_id, item_code, old_value, new_value,"
                    + " actor_id, actor_role, reason, confirmed, bulk) VALUES ";
            statement.execute(audit + "('F1', 'PROCESS_SECRET', 'UNSET', 'ON', 7, 'super_admin', '客户要求', false, false)");
            assertThrows(SQLException.class, () -> statement.execute(
                    audit + "('F1', 'PROCESS_SECRET', 'ON', 'ON', 7, 'super_admin', 'x', false, false)"), "没变不记");
            assertThrows(SQLException.class, () -> statement.execute(
                    audit + "('F1', 'PROCESS_SECRET', 'ON', 'UNSET', 7, 'super_admin', 'x', false, false)"), "新值不能是 UNSET");
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE factory_secrecy_setting_audit SET reason = 'rewritten'"), "留痕只追加");
            try (ResultSet rs = statement.executeQuery("SELECT reason FROM factory_secrecy_setting_audit")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("客户要求");
            }
            assertDoesNotThrow(() -> statement.execute(
                    "DELETE FROM factory_secrecy_setting_audit WHERE factory_id = 'F1'"), "删除工厂数据时能删");
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static void assertColumnsMatchEntity(Connection connection, String schema, Class<?> entity) throws SQLException {
        String table = entity.getAnnotation(Table.class).name();
        Map<String, Boolean> db = new TreeMap<>();
        try (var ps = connection.prepareStatement("SELECT column_name, is_nullable FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) db.put(rs.getString(1), "YES".equals(rs.getString(2)));
            }
        }
        Map<String, Boolean> mapped = new TreeMap<>();
        for (Field field : entity.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column == null) continue;
            mapped.put(column.name(), column.nullable() && !field.getType().isPrimitive());
        }
        assertThat(mapped.keySet()).as(table + " 列").isEqualTo(db.keySet());
        Map<String, Boolean> mismatched = new HashMap<>();
        mapped.forEach((column, nullable) -> {
            // 主键 / NOT NULL 列: 实体必须标 nullable=false; 可空列: 实体不能标 nullable=false
            if (!nullable.equals(db.get(column))) mismatched.put(column, db.get(column));
        });
        assertThat(mismatched).as(table + " 可空性与实体不一致 (列 -> 库里是否可空)").isEmpty();
    }

    private static void assertCheckListsEveryItem(Connection connection, String schema, String constraint) throws SQLException {
        String def;
        try (var ps = connection.prepareStatement("SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c"
                + " JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = ? AND c.conname = ?")) {
            ps.setString(1, schema);
            ps.setString(2, constraint);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(constraint).isTrue();
                def = rs.getString(1);
            }
        }
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(def).as(constraint + " 缺 " + item).contains("'" + item.name() + "'");
        }
        long quoted = Arrays.stream(def.split("'")).count() / 2;
        assertThat(quoted).as(constraint + " 里的取值个数 = 枚举项数").isEqualTo(SecrecyItem.values().length);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
