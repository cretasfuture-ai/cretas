package com.cretas.aims.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 PostgreSQL：批次追溯闭包触发器的「祖先 × 后代」交叉组合 + V20261103_81 的只增补齐。
 *
 * <p>缺陷形状：在两段已有链中间补一条边 B→C 时，旧触发器只写 anc(B)→C 与 B→desc(C)，
 * 不写 anc(B)×desc(C) ⇒ A 不是 D 的祖先。prod 实测（2026-10-25 只读）缺 614 对，
 * LIUSHANMEN 生产批 10898 的祖先 3 个 / 最深 2，按边集应为 21 个 / 最深 19。
 *
 * <p>为什么一定要真库：被测的是 plpgsql 触发器与递归 CTE，H2 两样都没有。
 * 每个测试一个独立 schema，迁移原文执行（不改写），跑完 DROP SCHEMA。
 *
 * <p>开关：{@code CRETAS_WORKFLOW_PG_VERIFY=true} + {@code CRETAS_WORKFLOW_PG_URL}
 * （库名前缀由 {@link DisposablePostgresTargetGuard} 把关）；本机/CI 用
 * {@code scripts/ci/run-with-test-postgres.sh} 起一次性实例。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class LineageClosureCrossJoinPostgresContractTest {

    private static final String SCHEMA_MIGRATION = "db/flyway/V20260821_02__batch_lineage_schema.sql";
    private static final String FIX_MIGRATION =
            "db/flyway/V20261103_81__lineage_closure_cross_ancestor_descendant.sql";
    private static final String F = "LCX-F";

    /**
     * 独立仪器：从边集算出的「应有的 祖先≠后代 对」里，closure 缺几对。
     * ⚠️ 故意不复用迁移里的 CTE 文本 —— 这里用 path 数组防环（与迁移的「depth 上限 + UNION 去重」不同法），
     * 两份各算各的才能互相校验（形态 D）。
     */
    private static final String MISSING_PAIRS_SQL = """
            WITH RECURSIVE e AS (
                SELECT DISTINCT factory_id, source_type || ':' || source_id AS s, target_type || ':' || target_id AS t
                FROM batch_lineage_edges
            ),
            r (factory_id, a, d, path) AS (
                SELECT factory_id, s, t, ARRAY[s, t] FROM e WHERE s <> t
                UNION ALL
                SELECT r.factory_id, r.a, e.t, r.path || e.t
                FROM r JOIN e ON e.factory_id = r.factory_id AND e.s = r.d
                WHERE e.t <> ALL (r.path)
            ),
            want AS (SELECT DISTINCT factory_id, a, d FROM r)
            SELECT count(*) FROM want w
            WHERE NOT EXISTS (
                SELECT 1 FROM batch_lineage_closure c
                WHERE c.factory_id = w.factory_id
                  AND c.ancestor_type || ':' || c.ancestor_id = w.a
                  AND c.descendant_type || ':' || c.descendant_id = w.d)
            """;

    private String url;
    private String user;
    private String password;
    private String schema;
    private Connection connection;
    private Statement sql;

    @BeforeEach
    void openIsolatedSchema() throws Exception {
        url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        schema = "lineage_cross_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(url, user, password);
        sql = connection.createStatement();
        sql.execute("CREATE SCHEMA " + schema);
        sql.execute("SET search_path TO " + schema);
        sql.execute(migration(SCHEMA_MIGRATION));
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        try {
            if (sql != null) {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    @Test
    void edgeBridgingTwoExistingSegmentsConnectsUpstreamAncestorsToDownstreamDescendants() throws Exception {
        applyFixMigration();

        edge("A", "B");
        edge("C", "D");
        long before = count();
        edge("B", "C");

        Integer depthAD = depth("A", "D");
        System.out.println("[lineage-cross] closure rows before bridge=" + before + " after=" + count()
                + " depth(A,D)=" + depthAD + " missing=" + missingPairs());
        assertThat(depthAD).as("A 必须是 D 的祖先 (anc(B) × desc(C))").isEqualTo(3);
        assertThat(depth("A", "C")).isEqualTo(2);
        assertThat(depth("B", "D")).isEqualTo(2);
        assertThat(missingPairs()).isZero();
    }

    @Test
    void threeSegmentsWrittenDownstreamFirstThenBridgedEndUpConnectedEndToEnd() throws Exception {
        applyFixMigration();

        // 先写下游段, 再写上游段, 最后补两座桥 —— 回填就是这个顺序
        edge("M5", "M6");
        edge("M3", "M4");
        edge("M1", "M2");
        edge("M4", "M5");
        edge("M2", "M3");

        Integer depth = depth("M1", "M6");
        System.out.println("[lineage-cross] 3-segment depth(M1,M6)=" + depth + " missing=" + missingPairs());
        assertThat(depth).as("首尾相连").isEqualTo(5);
        assertThat(missingPairs()).isZero();
    }

    @Test
    void repairMigrationOnlyAddsTheMissingPairsAndIsIdempotent() throws Exception {
        // 旧触发器 (只有 V20260821_02) 下构造缺口 —— 与 prod 回填同一个形状
        edge("A", "B");
        edge("C", "D");
        edge("B", "C");
        edge("D", "E");
        edge("C", "C");                     // WIP_CONSUME 式自环: 递归里必须被排除
        edgeIn("LCX-OTHER", "A", "Z");      // 另一个工厂里同名节点: 不许跨厂连起来

        long missingBefore = missingPairs();
        Map<String, Object> rowsBefore = snapshot();
        assertThat(missingBefore).as("阳性对照: 旧触发器下必须真的缺对, 否则下面的「补齐后 0」不说明任何事")
                .isGreaterThan(0);

        applyFixMigration();
        long missingAfter = missingPairs();
        Map<String, Object> rowsAfter = snapshot();
        System.out.println("[lineage-cross] repair: missing before=" + missingBefore + " after=" + missingAfter
                + " rows before=" + rowsBefore.size() + " after=" + rowsAfter.size());

        assertThat(missingAfter).isZero();
        assertThat(rowsAfter.size() - rowsBefore.size()).as("只新增缺的那几对").isEqualTo(missingBefore);
        rowsBefore.forEach((id, depth) -> assertThat(rowsAfter)
                .as("已有行一行不删、depth 一个不改: " + id).containsEntry(id, depth));
        assertThat(depth("A", "D")).as("补齐行取最短跳数").isEqualTo(3);
        assertThat(depth("A", "E")).isEqualTo(4);
        assertThat(scalar("SELECT count(*) FROM batch_lineage_closure WHERE depth > 0 "
                + "AND ancestor_id = descendant_id AND ancestor_type = descendant_type"))
                .as("自环不产生 depth>0 的自引用行").isZero();
        assertThat(scalar("SELECT count(*) FROM batch_lineage_closure WHERE factory_id = 'LCX-OTHER' "
                + "AND ancestor_id = 'A' AND descendant_id IN ('B','C','D','E')"))
                .as("阴性对照: 别的工厂的 A 不许被连到本厂的后代").isZero();

        applyFixMigration();
        assertThat(snapshot()).as("重跑一次结果不变 (幂等)").isEqualTo(rowsAfter);
    }

    @Test
    void repairTerminatesOnATrueCycle() throws Exception {
        // 边集理论上无环; 但真有环时迁移必须停得下来, 且不写出 depth>0 的自引用
        edge("X", "Y");
        edge("Y", "W");
        edge("W", "X");

        applyFixMigration();
        System.out.println("[lineage-cross] cycle: rows=" + count() + " missing=" + missingPairs());
        assertThat(missingPairs()).isZero();
        assertThat(scalar("SELECT count(*) FROM batch_lineage_closure WHERE depth > 0 "
                + "AND ancestor_id = descendant_id")).isZero();
    }

    // ------------------------------------------------------------------

    /** 与 Flyway 一样在一个事务里执行 (迁移首句 LOCK TABLE 只能在事务块里跑)。 */
    private void applyFixMigration() throws Exception {
        connection.setAutoCommit(false);
        try {
            sql.execute(migration(FIX_MIGRATION));
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void edge(String source, String target) throws SQLException {
        edgeIn(F, source, target);
    }

    private void edgeIn(String factory, String source, String target) throws SQLException {
        sql.execute(("INSERT INTO batch_lineage_edges (id, factory_id, edge_type, source_type, source_id, "
                + "target_type, target_id) VALUES ('%s', '%s', 'WIP_CONSUME', 'MATERIAL_BATCH', '%s', "
                + "'MATERIAL_BATCH', '%s')").formatted(UUID.randomUUID(), factory, source, target));
    }

    private Integer depth(String ancestor, String descendant) throws SQLException {
        try (ResultSet rs = sql.executeQuery(("SELECT depth FROM batch_lineage_closure WHERE factory_id = '%s' "
                + "AND ancestor_id = '%s' AND descendant_id = '%s'").formatted(F, ancestor, descendant))) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }

    private long count() throws SQLException {
        return scalar("SELECT count(*) FROM batch_lineage_closure");
    }

    private long missingPairs() throws SQLException {
        return scalar(MISSING_PAIRS_SQL);
    }

    private long scalar(String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Map<String, Object> snapshot() throws SQLException {
        Map<String, Object> rows = new HashMap<>();
        try (ResultSet rs = sql.executeQuery("SELECT id, factory_id, ancestor_id, descendant_id, depth "
                + "FROM batch_lineage_closure")) {
            while (rs.next()) {
                rows.put(rs.getString("id"), rs.getString("factory_id") + "|" + rs.getString("ancestor_id")
                        + "|" + rs.getString("descendant_id") + "|" + rs.getInt("depth"));
            }
        }
        return rows;
    }

    private static String migration(String path) throws Exception {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }
}
