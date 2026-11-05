package com.cretas.aims.integration;

import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.platform.PlatformAiAnalysisGrant;
import com.cretas.aims.entity.platform.PlatformAiAnalysisGrantAudit;
import org.flywaydb.core.Flyway;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 在真实 PostgreSQL 上证明已应用 V20261104.50 后, V20261105.91 能以 outOfOrder=false 正常升级;
 * 新迁移的表与实体逐列一致, 授权角色的 CHECK 与枚举 {@link PlatformRole} 一致
 * (超级管理员不在内), 同一对象只有一行, 留痕只追加 (UPDATE / DELETE / TRUNCATE 都被触发器拒绝)。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class PlatformAiAnalysisGrantMigrationPostgresContractTest {

    private static final String MIGRATION = "db/flyway/V20261105_91__platform_ai_analysis_grants.sql";

    @Test
    void migrationMatchesEntitiesAndEnforcesItsRules() throws Exception {
        String schema = "platform_ai_grants_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        Path migrationDir = Files.createTempDirectory("platform-ai-grants-migrations-");
        Path location = migrationDir.resolve("db/flyway");
        Files.createDirectories(location);
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(location.resolve("V20261104_50__prior_fixture.sql"),
                    "CREATE TABLE platform_ai_grants_prior_fixture (id INTEGER PRIMARY KEY)");
            Flyway prior = flyway(url, user, password, schema, location);
            assertThat(prior.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(currentVersion(connection, schema)).isEqualTo("20261104.50");

            Files.writeString(location.resolve("V20261105_91__platform_ai_analysis_grants.sql"), sql);
            Flyway upgraded = flyway(url, user, password, schema, location);
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(currentVersion(connection, schema)).isEqualTo("20261105.91");
            statement.execute("SET search_path TO " + schema);

            assertColumnsMatchEntity(connection, schema, PlatformAiAnalysisGrant.class);
            assertColumnsMatchEntity(connection, schema, PlatformAiAnalysisGrantAudit.class);
            assertRoleCheckListsGrantableRoles(connection, schema);

            String grant = "INSERT INTO platform_ai_analysis_grants (grantee_type, grantee_ref, granted_by, reason) VALUES ";
            assertDoesNotThrow(() -> statement.execute(grant + "('ROLE', 'auditor', 7, '审计抽查')"));
            assertDoesNotThrow(() -> statement.execute(grant + "('ACCOUNT', '12', 7, '专人')"));
            assertThrows(SQLException.class, () -> statement.execute(grant + "('ROLE', 'auditor', 7, '再开一次')"), "同一对象只有一行");
            assertThrows(SQLException.class, () -> statement.execute(grant + "('ROLE', 'super_admin', 7, 'x')"), "超级管理员不需要开通");
            assertThrows(SQLException.class, () -> statement.execute(grant + "('TEAM', 'x', 7, 'x')"), "未知类型");
            assertThrows(SQLException.class, () -> statement.execute(grant + "('ACCOUNT', 'abc', 7, 'x')"), "账号是编号");
            assertThrows(SQLException.class, () -> statement.execute(grant + "('ROLE', 'operation_admin', 7, '  ')"), "必须有理由");
            assertDoesNotThrow(() -> statement.execute(
                    "DELETE FROM platform_ai_analysis_grants WHERE grantee_ref = 'auditor'"), "撤销就是删行");

            String audit = "INSERT INTO platform_ai_analysis_grant_audit (action, grantee_type, grantee_ref, actor_id,"
                    + " actor_role, reason) VALUES ";
            statement.execute(audit + "('GRANT', 'ROLE', 'auditor', 7, 'super_admin', '审计抽查')");
            assertThrows(SQLException.class, () -> statement.execute(
                    audit + "('CHANGE', 'ROLE', 'auditor', 7, 'super_admin', 'x')"), "未知动作");
            assertThrows(SQLException.class, () -> statement.execute(
                    audit + "('GRANT', 'ROLE', 'auditor', 7, 'super_admin', '')"), "必须有理由");
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE platform_ai_analysis_grant_audit SET reason = 'rewritten'"), "留痕只追加");
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM platform_ai_analysis_grant_audit"), "留痕不能删");
            assertThrows(SQLException.class, () -> statement.execute(
                    "TRUNCATE platform_ai_analysis_grant_audit"), "留痕不能清空");
            try (ResultSet rs = statement.executeQuery("SELECT reason FROM platform_ai_analysis_grant_audit")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("审计抽查");
                assertThat(rs.next()).isFalse();
            }
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(0);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            try (var paths = Files.walk(migrationDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (java.io.IOException error) { throw new IllegalStateException(error); }
                });
            }
        }
    }

    private static Flyway flyway(String url, String user, String password, String schema, Path location) {
        return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                .locations("filesystem:" + location.toAbsolutePath()).outOfOrder(false).load();
    }

    private static String currentVersion(Connection connection, String schema) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT version FROM " + schema
                + ".flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1");
             var rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
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

    private static void assertRoleCheckListsGrantableRoles(Connection connection, String schema) throws SQLException {
        String def;
        try (var ps = connection.prepareStatement("SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c"
                + " JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = ? AND c.conname = 'ck_paag_role'")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("ck_paag_role").isTrue();
                def = rs.getString(1);
            }
        }
        for (PlatformRole role : PlatformRole.values()) {
            if (role == PlatformRole.super_admin) {
                assertThat(def).doesNotContain("'super_admin'");
            } else {
                assertThat(def).as("CHECK 缺 " + role).contains("'" + role.name() + "'");
            }
        }
        assertThat(Arrays.stream(PlatformRole.values()).filter(r -> r != PlatformRole.super_admin).count()).isEqualTo(3);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
