package com.cretas.aims.integration;

import com.cretas.aims.entity.integration.ExternalSourceSnapshot;
import com.cretas.aims.entity.integration.FactoryExternalIntegration;
import com.cretas.aims.service.integration.ExternalDataset;
import com.cretas.aims.service.integration.ExternalSystem;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V20261115_51 在真实 PostgreSQL 上: 两张表与实体逐列一致 (列名 + 可空), system / dataset 的 CHECK 与枚举逐项一致,
 * 授权状态只有 ACTIVE / SUSPENDED, 推送令牌只能是 64 位小写十六进制且全表唯一, 快照同一采集时点只一行、只追加
 * (UPDATE 被触发器拒绝, DELETE 允许 —— 平台删除工厂数据要能删掉)。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ExternalIntegrationMigrationPostgresContractTest {

    private static final String MIGRATION = "db/flyway/V20261115_51__factory_external_integrations.sql";
    private static final String COST_MIGRATION = "db/flyway/V20261121_01__external_snapshot_cost_datasets.sql";
    private static final String HASH_A = "a".repeat(64);

    @Test
    void migrationMatchesEntitiesAndEnforcesItsRules() throws Exception {
        String schema = "ext_integration_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE factories (id VARCHAR(50) PRIMARY KEY)");
            statement.execute("INSERT INTO factories (id) VALUES ('F1'), ('F2')");
            String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            statement.execute(sql);
            statement.execute(sql);   // IF NOT EXISTS / OR REPLACE: 重跑不报错

            assertColumnsMatchEntity(connection, schema, FactoryExternalIntegration.class);
            assertColumnsMatchEntity(connection, schema, ExternalSourceSnapshot.class);
            List<String> systems = Arrays.stream(ExternalSystem.values()).map(ExternalSystem::code).toList();
            List<String> datasets = Arrays.stream(ExternalDataset.values()).map(ExternalDataset::code).toList();
            assertCheckListsExactly(connection, schema, "ck_fei_system", systems);
            assertCheckListsExactly(connection, schema, "ck_ess_system", systems);

            String auth = "INSERT INTO factory_external_integrations (factory_id, external_system, status, source_org_id,"
                    + " authorized_at, authorized_by, push_token_sha256, updated_by) VALUES ";
            assertDoesNotThrow(() -> statement.execute(auth + "('F1', 'blacklake', 'ACTIVE', '1662664', NOW(), 1, '"
                    + HASH_A + "', 1)"));
            assertThrows(SQLException.class, () -> statement.execute(auth
                    + "('F1', 'blacklake', 'ACTIVE', 'x', NOW(), 1, NULL, 1)"), "同一工厂同一系统只有一行");
            assertThrows(SQLException.class, () -> statement.execute(auth
                    + "('F2', 'blacklake', 'ACTIVE', 'x', NOW(), 1, '" + HASH_A + "', 1)"), "推送令牌全表唯一");
            assertThrows(SQLException.class, () -> statement.execute(auth
                    + "('F2', 'blacklake', 'MAYBE', 'x', NOW(), 1, NULL, 1)"), "未知状态");
            assertThrows(SQLException.class, () -> statement.execute(auth
                    + "('F2', 'madeup', 'ACTIVE', 'x', NOW(), 1, NULL, 1)"), "未知系统");
            assertThrows(SQLException.class, () -> statement.execute(auth
                    + "('F2', 'blacklake', 'ACTIVE', 'x', NOW(), 1, 'NOT-HEX', 1)"), "令牌只存 SHA-256");
            assertThrows(SQLException.class, () -> statement.execute(auth
                    + "('NOPE', 'blacklake', 'ACTIVE', 'x', NOW(), 1, NULL, 1)"), "工厂外键");
            assertDoesNotThrow(() -> statement.execute(auth + "('F2', 'blacklake', 'SUSPENDED', NULL, NOW(), 1, NULL, 1)"));

            String snap = "INSERT INTO external_source_snapshots (factory_id, external_system, dataset, collected_at,"
                    + " row_count, payload, payload_sha256) VALUES ";
            statement.execute(snap + "('F1', 'blacklake', 'report_output', '2026-11-15 00:01', 1, '[]', '" + HASH_A + "')");
            assertCheckListsExactly(connection, schema, "ck_ess_dataset", List.of("report_output"));
            assertThrows(SQLException.class, () -> statement.execute(snap
                    + "('F1', 'blacklake', 'project_cost', '2026-11-15 00:01', 1, '[]', '" + HASH_A + "')"));
            statement.execute(new String(new ClassPathResource(COST_MIGRATION).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8));
            assertCheckListsExactly(connection, schema, "ck_ess_dataset", datasets);
            for (String dataset : List.of("project_cost", "production_issue")) {
                assertDoesNotThrow(() -> statement.execute(snap + "('F1', 'blacklake', '" + dataset
                        + "', '2026-11-15 00:01', 1, '[]', '" + HASH_A + "')"));
            }
            assertThrows(SQLException.class, () -> statement.execute(snap
                    + "('F1', 'blacklake', 'report_output', '2026-11-15 00:01', 1, '[]', '" + HASH_A + "')"),
                    "同一采集时点只一行");
            assertThrows(SQLException.class, () -> statement.execute(snap
                    + "('F1', 'blacklake', 'workers', '2026-11-15 00:02', 1, '[]', '" + HASH_A + "')"), "未知数据集");
            assertThrows(SQLException.class, () -> statement.execute(snap
                    + "('F1', 'blacklake', 'report_output', '2026-11-15 00:03', -1, '[]', '" + HASH_A + "')"), "行数非负");
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE external_source_snapshots SET payload = '[1]'"), "快照只追加");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT payload FROM external_source_snapshots WHERE dataset = 'report_output'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("[]");
            }
            assertDoesNotThrow(() -> statement.execute(
                    "DELETE FROM external_source_snapshots WHERE factory_id = 'F1'"), "删除工厂数据时能删");
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
            if (!nullable.equals(db.get(column))) mismatched.put(column, db.get(column));
        });
        assertThat(mismatched).as(table + " 可空性与实体不一致 (列 -> 库里是否可空)").isEmpty();
    }

    private static void assertCheckListsExactly(Connection connection, String schema, String constraint,
                                                List<String> values) throws SQLException {
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
        for (String v : values) assertThat(def).as(constraint + " 缺 " + v).contains("'" + v + "'");
        long quoted = Arrays.stream(def.split("'")).count() / 2;
        assertThat(quoted).as(constraint + " 里的取值个数 = 枚举项数").isEqualTo(values.size());
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
