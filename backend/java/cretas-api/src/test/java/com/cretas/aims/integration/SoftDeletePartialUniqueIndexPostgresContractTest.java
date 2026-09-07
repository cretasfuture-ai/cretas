package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可选启用的真 PostgreSQL 证明：软删实体依赖的部分唯一索引，行为上确实是「活行互斥、软删后可复用」。
 *
 * <p><b>它接的是两条被删掉的 H2 断言</b>（2026-09-07，P0 FIX-ENTITY-FULL-UNIQUE-VS-PROD-PARTIAL）：
 * <ol>
 *   <li>{@code LogisticsPlanGraphRepositoryTest.deliveryOrderCanOnlyBelongToOneActiveTrip}
 *       —— 一张配送单只能挂一个活停靠点；</li>
 *   <li>{@code ProductionPlanRepositoryQueryValidationTest} 里那条
 *       「database unique constraint is the final concurrent-create guard」——同一幂等键不许并发建两条。</li>
 * </ol>
 *
 * <p>那两条断言原来靠的是 H2 从实体 {@code @UniqueConstraint} 生成的<b>全量</b>唯一键。而 prod 上这些键是
 * {@code … WHERE deleted_at IS NULL} 的<b>部分</b>索引 ⇒ 实体侧的声明让 H2 比 prod 严，
 * 「软删后按同键重建」在 prod 合法而在 H2 撞键（#701 之后 logistics 三个测试类 4 个 error 即此）⇒ 声明已摘掉。
 * ⚠️ H2 2.2.224 补不回来：{@code CREATE UNIQUE INDEX … WHERE …} 报 {@code JdbcSQLSyntaxErrorException 42000}。
 * ⇒ 规则本身没失效，只是<b>只有真 PostgreSQL 表达得了</b>，所以搬到这里。
 *
 * <p>⛔ <b>DDL 不抄一份</b>：索引语句是从 {@code src/main/resources/db/flyway} 里<b>按名字抽出原文</b>再执行的。
 * 抄一份就是两份会漂——本闸要证的恰恰是「迁移里那条语句的行为」，抄错了它照样绿。
 * 对应的「语句还在不在」由 {@code com.cretas.aims.contract.PartialUniqueIndexBackingSoftDeleteContractTest}
 * 在源码层守；这里守的是<b>它跑起来是什么行为</b>。
 *
 * <p><b>怎么跑</b>（与本包其余 14 个 opt-in PG 测试同一套机制）：
 * <pre>
 * CRETAS_WORKFLOW_PG_VERIFY=true
 * CRETAS_WORKFLOW_PG_URL=jdbc:postgresql://127.0.0.1:55434/cretas_workflow_verify_lineb
 * CRETAS_WORKFLOW_PG_USER=postgres
 * CRETAS_WORKFLOW_PG_PASSWORD=…
 * </pre>
 * 库名必须以 {@code cretas_workflow_verify_} 开头、host 必须是本机 —— 由
 * {@link DisposablePostgresTargetGuard} 强制，⛔ 不可能误指到共享库或 prod。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SoftDeletePartialUniqueIndexPostgresContractTest {

    private static final Path FLYWAY = Path.of("src/main/resources/db/flyway");
    private static final String UNIQUE_VIOLATION = "23505";

    @Test
    void liveRowsCollideWhileSoftDeletedOnesFreeTheKeyUp() throws Exception {
        String schema = "soft_delete_unique_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
            st.execute("SET search_path TO " + schema);

            // ── ① logistics_trips —— 就是 #701 之后炸掉 3 个用例的那个键 ────────────────────────
            st.execute("""
                    CREATE TABLE logistics_trips (
                        id VARCHAR(191) PRIMARY KEY,
                        plan_id VARCHAR(191) NOT NULL,
                        trip_no INTEGER NOT NULL,
                        deleted_at TIMESTAMP
                    )
                    """);
            st.execute(partialIndexDdl("uq_lt_plan_tripno"));
            st.execute("INSERT INTO logistics_trips VALUES ('t1','PLAN-1',1,NULL)");
            assertEquals(UNIQUE_VIOLATION, assertThrows(SQLException.class, () ->
                    st.execute("INSERT INTO logistics_trips VALUES ('t2','PLAN-1',1,NULL)"),
                    "阳性对照：两条活行同 (plan_id, trip_no) 必须撞").getSQLState(),
                    "阳性对照：两条活行同 (plan_id, trip_no) 必须撞");
            st.execute("UPDATE logistics_trips SET deleted_at = CURRENT_TIMESTAMP WHERE id='t1'");
            st.execute("INSERT INTO logistics_trips VALUES ('t3','PLAN-1',1,NULL)");   // 目标行为：regenerate
            assertEquals(UNIQUE_VIOLATION, assertThrows(SQLException.class, () ->
                    st.execute("INSERT INTO logistics_trips VALUES ('t4','PLAN-1',1,NULL)"),
                    "阴性对照：又有活行了, 再插必须又被拒 —— 少了这条, 上面那次成功也可能是索引整个失效").getSQLState(),
                    "阴性对照：又有活行了, 再插必须又被拒 —— 少了这条, 上面那次成功也可能是索引整个失效");
            assertCounts(st, "logistics_trips", "plan_id='PLAN-1'", 2, 1);

            // ── ② logistics_stops —— 接 deliveryOrderCanOnlyBelongToOneActiveTrip ──────────────
            st.execute("""
                    CREATE TABLE logistics_stops (
                        id VARCHAR(191) PRIMARY KEY,
                        trip_id VARCHAR(191) NOT NULL,
                        delivery_order_id VARCHAR(191) NOT NULL,
                        deleted_at TIMESTAMP
                    )
                    """);
            st.execute(partialIndexDdl("uq_ls_order_single_trip"));
            st.execute("INSERT INTO logistics_stops VALUES ('s1','TRIP-1','ORD-1',NULL)");
            assertEquals(UNIQUE_VIOLATION, assertThrows(SQLException.class, () ->
                    st.execute("INSERT INTO logistics_stops VALUES ('s2','TRIP-2','ORD-1',NULL)"),
                    "被删那条 H2 断言要的就是这个：同一张配送单挂到第二个活停靠点必须被拒").getSQLState(),
                    "被删那条 H2 断言要的就是这个：同一张配送单挂到第二个活停靠点必须被拒");
            st.execute("UPDATE logistics_stops SET deleted_at = CURRENT_TIMESTAMP WHERE id='s1'");
            st.execute("INSERT INTO logistics_stops VALUES ('s3','TRIP-2','ORD-1',NULL)");
            assertCounts(st, "logistics_stops", "delivery_order_id='ORD-1'", 2, 1);

            // ── ③ production_plans —— 接那条 concurrent-create guard 断言 ──────────────────────
            //    ⚠️ 这个键还多一层 client_request_id IS NOT NULL, 一并证明。
            st.execute("""
                    CREATE TABLE production_plans (
                        id VARCHAR(191) PRIMARY KEY,
                        factory_id VARCHAR(50) NOT NULL,
                        created_by INTEGER NOT NULL,
                        client_request_id VARCHAR(191),
                        deleted_at TIMESTAMP
                    )
                    """);
            st.execute(partialIndexDdl("uk_production_plan_create_request"));
            st.execute("INSERT INTO production_plans VALUES ('p1','F006',1,'req-001',NULL)");
            assertEquals(UNIQUE_VIOLATION, assertThrows(SQLException.class, () ->
                    st.execute("INSERT INTO production_plans VALUES ('p2','F006',1,'req-001',NULL)"),
                    "被删那条断言要的就是这个：同一幂等键并发建第二条必须被 DB 拒").getSQLState(),
                    "被删那条断言要的就是这个：同一幂等键并发建第二条必须被 DB 拒");
            st.execute("UPDATE production_plans SET deleted_at = CURRENT_TIMESTAMP WHERE id='p1'");
            st.execute("INSERT INTO production_plans VALUES ('p3','F006',1,'req-001',NULL)");
            // client_request_id 为 NULL 的计划不受该键约束(谓词里带 IS NOT NULL) —— 连插两条都必须放行
            st.execute("INSERT INTO production_plans VALUES ('p4','F006',1,NULL,NULL)");
            st.execute("INSERT INTO production_plans VALUES ('p5','F006',1,NULL,NULL)");
            assertCounts(st, "production_plans", "factory_id='F006'", 4, 3);

            // ── ④ logistics_order_batches —— 接软删批 3-B 里那条被删掉的 H2 断言 ────────────────
            //    原断言: LogisticsOrderBatchRepositoryTest.duplicateIdempotencyKeyRejected
            //    「同 (factory_id, business_date, source_fingerprint) 再插一条必须被 DB 拒」。
            //    批 3-B 摘掉实体级唯一声明之后 H2 建不出那个键 ⇒ 断言无法再成立, 规则本身没变。
            st.execute("""
                    CREATE TABLE logistics_order_batches (
                        id VARCHAR(191) PRIMARY KEY,
                        factory_id VARCHAR(50) NOT NULL,
                        business_date DATE NOT NULL,
                        source_fingerprint VARCHAR(191),
                        deleted_at TIMESTAMP
                    )
                    """);
            st.execute(partialIndexDdl("uq_lob_idempotent"));
            st.execute("INSERT INTO logistics_order_batches VALUES ('b1','F1',DATE '2026-07-11','fp-dup',NULL)");
            assertEquals(UNIQUE_VIOLATION, assertThrows(SQLException.class, () ->
                    st.execute("INSERT INTO logistics_order_batches VALUES ('b2','F1',DATE '2026-07-11','fp-dup',NULL)"),
                    "被删那条 H2 断言要的就是这个：同一幂等键再导一批必须被拒").getSQLState(),
                    "被删那条 H2 断言要的就是这个：同一幂等键再导一批必须被拒");
            st.execute("UPDATE logistics_order_batches SET deleted_at = CURRENT_TIMESTAMP WHERE id='b1'");
            st.execute("INSERT INTO logistics_order_batches VALUES ('b3','F1',DATE '2026-07-11','fp-dup',NULL)");
            assertCounts(st, "logistics_order_batches", "factory_id='F1'", 2, 1);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement st = connection.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    /** 必然读数：总行 / 活行都要对得上。⛔ 只看「插入没报错」的话，索引整个没建出来时读数一模一样。 */
    private static void assertCounts(Statement st, String table, String where, int total, int active)
            throws SQLException {
        try (ResultSet rows = st.executeQuery(
                "SELECT count(*) AS total, count(*) FILTER (WHERE deleted_at IS NULL) AS active FROM "
                        + table + " WHERE " + where)) {
            assertTrue(rows.next());
            assertEquals(total, rows.getInt("total"), table + " 总行数");
            assertEquals(active, rows.getInt("active"), table + " 活行数");
        }
    }

    /**
     * 从迁移原文里按名字抽出那条 {@code CREATE UNIQUE INDEX … WHERE … deleted_at IS NULL … ;}。
     *
     * <p>⚠️ 必须<b>恰好一条</b>：0 条说明索引被删/改名了（本测试就该红，而不是悄悄跳过），
     * 多于 1 条说明有重复定义、执行哪一条都不代表生产（{@code uk_production_plan_create_request}
     * 在仓库里就有两条 CREATE，只有一条带 {@code deleted_at}）。
     */
    private static String partialIndexDdl(String indexName) throws IOException {
        Pattern stmt = Pattern.compile("CREATE\\s+UNIQUE\\s+INDEX\\b[^;]*;", Pattern.CASE_INSENSITIVE);
        Pattern named = Pattern.compile("\\b" + Pattern.quote(indexName) + "\\b");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(FLYWAY)) {
            for (Path p : files.filter(f -> f.toString().toLowerCase().endsWith(".sql")).toList()) {
                Matcher m = stmt.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    String s = m.group();
                    if (named.matcher(s).find() && s.toLowerCase().contains("deleted_at is null")) {
                        hits.add(s);
                    }
                }
            }
        }
        assertEquals(1, hits.size(),
                "迁移里带 deleted_at 谓词的 " + indexName + " 定义应当恰好一条，实际 " + hits.size() + " 条");
        return hits.get(0);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
