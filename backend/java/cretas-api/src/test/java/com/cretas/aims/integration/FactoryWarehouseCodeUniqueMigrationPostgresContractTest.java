package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真 PostgreSQL 跑 V20261103_173 原文 (验收 D-R2-01): 迁移之前软删的 WH-LOG 占着编码,
 * 迁移之后软删行不占编码、两条活行仍然撞。
 *
 * <p>前置表形状照 prod 只读实测 (2026-11-03): {@code uk_fw_factory_code} 是 UNIQUE <b>CONSTRAINT</b>。
 * 迁移 DDL 不抄 —— 从 {@code db/flyway} 里读整个文件原样执行, 守的就是那个文件的行为。
 * 另有一个自检用例: 活行里已有重复编码时, 迁移必须整体失败、表保持原样。
 *
 * <p>怎么跑: 与本包其余 opt-in PG 测试相同 ({@code CRETAS_WORKFLOW_PG_VERIFY=true} +
 * {@code CRETAS_WORKFLOW_PG_URL}), 目标库由 {@link DisposablePostgresTargetGuard} 限定为本机一次性库。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class FactoryWarehouseCodeUniqueMigrationPostgresContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/flyway/V20261103_173__factory_warehouses_code_unique_live_only.sql");
    private static final String UNIQUE_VIOLATION = "23505";

    @Test
    void afterTheMigrationASoftDeletedCodeCanBeReusedWhileLiveOnesStillCollide() throws Exception {
        withSchema((st) -> {
            createOriginalTable(st, true);
            st.execute(insert("w1", "WH-LOG"));
            st.execute("UPDATE factory_warehouses SET deleted_at = CURRENT_TIMESTAMP WHERE id = 'w1'");

            // 阳性对照: 迁移之前就是验收里撞到的那一下 —— 证明前置表确实是 prod 的旧形状
            assertEquals(UNIQUE_VIOLATION, assertThrows(SQLException.class,
                    () -> st.execute(insert("w2", "WH-LOG")),
                    "迁移之前: 软删的 WH-LOG 仍占着编码").getSQLState());

            st.execute(Files.readString(MIGRATION, StandardCharsets.UTF_8));

            assertFalse(isConstraint(st), "迁移之后 uk_fw_factory_code 不应再是约束");
            st.execute(insert("w3", "WH-LOG"));   // 目标行为: 软删之后按原编码重建
            assertEquals(UNIQUE_VIOLATION, assertThrows(SQLException.class,
                    () -> st.execute(insert("w4", "WH-LOG")),
                    "阴性对照: 已有活的 WH-LOG, 再建必须被拒 —— 少了这条, 上面那次成功也可能是索引整个没建").getSQLState());
            st.execute(insert("w5", "WH-WKS"));   // 别的编码不受影响
            assertCounts(st, "WH-LOG", 2, 1);
        });
    }

    @Test
    void theMigrationRefusesToRunWhenLiveRowsAlreadyDuplicateACode() throws Exception {
        withSchema((st) -> {
            createOriginalTable(st, false);    // 没有约束的库才可能存进重复活行
            st.execute(insert("d1", "WH-LOG"));
            st.execute(insert("d2", "WH-LOG"));

            SQLException e = assertThrows(SQLException.class,
                    () -> st.execute(Files.readString(MIGRATION, StandardCharsets.UTF_8)));
            assertTrue(e.getMessage().contains("重复"), e.getMessage());

            // 整体回滚: 一个 uk_fw_factory_code 都没建出来, 两行原样都在
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM pg_class WHERE relname = 'uk_fw_factory_code'"
                            + " AND relnamespace = current_schema()::regnamespace")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "自检失败时不应留下半截索引");
            }
            assertCounts(st, "WH-LOG", 2, 2);
        });
    }

    private interface SchemaWork {
        void run(Statement st) throws Exception;
    }

    private static void withSchema(SchemaWork work) throws Exception {
        String schema = "fw_code_unique_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
            st.execute("SET search_path TO " + schema);
            work.run(st);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement st = connection.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    /** prod 上 V20260424_08 建出来的形状 (只保留本迁移碰得到的列)。 */
    private static void createOriginalTable(Statement st, boolean withConstraint) throws SQLException {
        st.execute("""
                CREATE TABLE factory_warehouses (
                    id         VARCHAR(64)  PRIMARY KEY,
                    factory_id VARCHAR(64)  NOT NULL,
                    code       VARCHAR(32)  NOT NULL,
                    name       VARCHAR(100) NOT NULL,
                    deleted_at TIMESTAMP
                """ + (withConstraint ? ", CONSTRAINT uk_fw_factory_code UNIQUE (factory_id, code)" : "") + ")");
    }

    private static String insert(String id, String code) {
        return "INSERT INTO factory_warehouses (id, factory_id, code, name) VALUES ('"
                + id + "', 'QATEST_1021_001', '" + code + "', '物流仓')";
    }

    private static boolean isConstraint(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_constraint"
                + " WHERE conname = 'uk_fw_factory_code' AND conrelid = 'factory_warehouses'::regclass")) {
            assertTrue(rs.next());
            return rs.getInt(1) > 0;
        }
    }

    /** 必然读数: 总行 / 活行都对得上。 */
    private static void assertCounts(Statement st, String code, int total, int live) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT count(*) AS total, count(*) FILTER (WHERE deleted_at IS NULL) AS live"
                + " FROM factory_warehouses WHERE code = '" + code + "'")) {
            assertTrue(rs.next());
            assertEquals(total, rs.getInt("total"), code + " 总行数");
            assertEquals(live, rs.getInt("live"), code + " 活行数");
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
