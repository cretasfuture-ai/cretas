package com.cretas.aims.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回料(半成品)可以作为一行进标准配方 BOM —— migration 契约 + <b>约束真的会拒</b>。
 *
 * <h2>为什么不止是文本断言</h2>
 * 本仓已经栽过「文本闸在一份语法错的文件上照报 15/15 绿」。所以这条测试
 * <b>把迁移里那句 CHECK 表达式原样抠出来, 在真的数据库上建表跑一遍</b>:
 * 两条都非空要被拒, 两条都为 null 也要被拒, 恰有一个非空才放行。
 *
 * <p>⚠️ 抠出来跑而不是在测试里重打一遍那句表达式 —— 重打就是同一件事有了两份,
 * 它一定会漂 (形态 D), 而且漂了之后这条测试仍然是绿的, 因为它测的是自己那一份。
 *
 * <h2>为什么用 H2 而不是 PostgreSQL</h2>
 * CI 没有 PostgreSQL (见 application-test.properties)。这条 CHECK 只用了
 * {@code IS NOT NULL} 和布尔 {@code <>}, 两边方言一致, 所以 H2 能如实回答
 * 「这个表达式会不会拒掉这一行」。⛔ 它<b>不</b>回答「Flyway 能不能在 prod 跑通」——
 * 迁移里的 {@code DO $$ ... $$} 前置断言是 PL/pgSQL, H2 跑不了, 由下面的文本契约盯着。
 */
@DisplayName("BOM 半成品行 — migration 契约与 CHECK 约束行为")
class BomSemiFinishedRowMigrationContractTest {

    private static final Path SQL = Path.of("src", "main", "resources", "db", "flyway",
            "V20261101_10__bom_recipe_item_semi_finished_row.sql");

    /** 从迁移正文里抠出 chk_bri_material_ref_xor 的 CHECK 表达式(不含最外层 CHECK 括号)。 */
    private static final Pattern XOR_CHECK = Pattern.compile(
            "ADD\\s+CONSTRAINT\\s+chk_bri_material_ref_xor\\s+CHECK\\s*\\((.*?)\\);",
            Pattern.DOTALL);

    private static String migrationSql() throws Exception {
        assertThat(SQL).as("迁移文件不存在 —— 说明这条闸测错了地方, 不是「没问题」").exists();
        return Files.readString(SQL);
    }

    private static String xorExpression() throws Exception {
        Matcher m = XOR_CHECK.matcher(migrationSql());
        assertThat(m.find())
                .as("没有从迁移里抠到 chk_bri_material_ref_xor 的 CHECK 表达式 —— "
                        + "要么约束改名了、要么写法变了。这时下面所有断言都是**没量到**, "
                        + "不是「约束没问题」")
                .isTrue();
        String expr = m.group(1).trim();
        assertThat(expr).as("抠出来的表达式是空的").isNotBlank();
        return expr;
    }

    // ──────────────────────── 文本契约 ────────────────────────

    @Test
    @DisplayName("material_type_id 必须 DROP NOT NULL —— 否则半成品行根本写不进去")
    void migrationDropsNotNullOnMaterialTypeId() throws Exception {
        String sql = migrationSql().toUpperCase();
        assertThat(sql).contains("ALTER COLUMN MATERIAL_TYPE_ID DROP NOT NULL");
    }

    @Test
    @DisplayName("加约束前先断言存量满足 —— ⛔ 不许把约束改宽来放过存量")
    void migrationAssertsLegacyRowsBeforeAddingConstraint() throws Exception {
        String sql = migrationSql();
        assertThat(sql).as("缺少存量前置断言").contains("RAISE EXCEPTION");
        assertThat(sql)
                .as("前置断言必须出现在 ADD CONSTRAINT 之前, 否则它拦不住任何东西")
                .containsSubsequence("RAISE EXCEPTION", "ADD CONSTRAINT chk_bri_material_ref_xor");
    }

    @Test
    @DisplayName("分类白名单加 SEMI_FINISHED, 且旧的四个值一个都不能丢")
    void migrationWidensCategoryWhitelistWithoutDroppingLegacyValues() throws Exception {
        String sql = migrationSql();
        assertThat(sql).contains("chk_bri_category");
        for (String value : new String[]{"RAW", "AUXILIARY", "PACKAGING", "BYPRODUCT", "SEMI_FINISHED"}) {
            assertThat(sql).as("重建 chk_bri_category 时漏了 %s —— 存量行会一次性全部违约", value)
                    .contains("'" + value + "'");
        }
    }

    @Test
    @DisplayName("可重复执行: 先 DROP IF EXISTS 再 ADD")
    void migrationIsIdempotent() throws Exception {
        String sql = migrationSql().toUpperCase();
        assertThat(sql).contains("DROP CONSTRAINT IF EXISTS CHK_BRI_MATERIAL_REF_XOR");
        assertThat(sql).contains("DROP CONSTRAINT IF EXISTS CHK_BRI_CATEGORY");
    }

    @Test
    @DisplayName("阳性对照: 迁移落在活跃目录 db/flyway 里")
    void migrationLivesInTheActiveFlywayDirectory() {
        assertThat(SQL.getParent().toString().replace('\\', '/')).endsWith("db/flyway");
        assertThat(Path.of("src", "main", "resources", "db", "flyway",
                "V20261029_37__bom_recipe_item_byproduct_category.sql"))
                .as("阳性对照: 同目录下已知存在的另一个 migration —— 它不在就说明我在查错目录")
                .exists();
    }

    // ──────────────── CHECK 表达式的真实行为(在库上跑) ────────────────

    /**
     * 建一张只有那两列的小表, 把<b>迁移里那句</b> CHECK 挂上去。
     *
     * @param expression CHECK 表达式正文
     * @param dbName     每个用例一个独立库, 避免互相看见对方的表
     */
    private static Connection tableWithCheck(String expression, String dbName) throws SQLException {
        Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE bom_recipe_items ("
                    + "  material_type_id VARCHAR(191),"
                    + "  semi_finished_ref_code VARCHAR(100),"
                    + "  CONSTRAINT chk_bri_material_ref_xor CHECK (" + expression + ")"
                    + ")");
        }
        return c;
    }

    private static void insert(Connection c, String materialTypeId, String semiRef) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO bom_recipe_items (material_type_id, semi_finished_ref_code) VALUES ("
                    + literal(materialTypeId) + ", " + literal(semiRef) + ")");
        }
    }

    private static String literal(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    @Test
    @DisplayName("存量兼容: 普通原料行 (material_type_id 非空, semi_finished_ref_code 空) 仍然写得进")
    void plainMaterialRowIsStillAccepted() throws Exception {
        try (Connection c = tableWithCheck(xorExpression(), "xor_plain")) {
            assertThatCode(() -> insert(c, "MT-001", null)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("新路径: 半成品行 (material_type_id 空, semi_finished_ref_code 非空) 写得进")
    void semiFinishedRowIsAccepted() throws Exception {
        try (Connection c = tableWithCheck(xorExpression(), "xor_semi")) {
            assertThatCode(() -> insert(c, null, "WIP-20260829-001")).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("闸红方向一: 两列都非空 → 拒")
    void bothIdentitiesPresentIsRejected() throws Exception {
        try (Connection c = tableWithCheck(xorExpression(), "xor_both")) {
            assertThatThrownBy(() -> insert(c, "MT-001", "WIP-20260829-001"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_bri_material_ref_xor");
        }
    }

    @Test
    @DisplayName("闸红方向二: 两列都为 null → 拒")
    void neitherIdentityPresentIsRejected() throws Exception {
        try (Connection c = tableWithCheck(xorExpression(), "xor_neither")) {
            assertThatThrownBy(() -> insert(c, null, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_bri_material_ref_xor");
        }
    }

    /**
     * 变异对照: 把异或换成 OR, 那两个「该拒」的方向就不再被拒。
     *
     * <p>⛔ 本仓栽过「变异不红 = 断言没用」的误判 —— 症状和「变异根本没生效」一模一样。
     * 所以这里<b>先证明变异到达了被测对象</b>: 断言变异后的表达式与原表达式不同、
     * 且确实含 {@code OR}。到这一步之后, 「两条都非空不再被拒」才是结论。
     */
    @Test
    @DisplayName("变异对照: 异或改成 OR → 两个「该拒」的方向全部放行 (先证明变异到达)")
    void mutatingXorToOrLetsBothViolationsThrough() throws Exception {
        String original = xorExpression();
        String mutated = original.replace("<>", "OR");

        // ① 先证明变异真的打进去了 —— 不做这一步, 下面不红就分不清是哪种原因
        assertThat(mutated)
                .as("变异没改变表达式 —— 说明它打空了, 后面红不红都没有意义")
                .isNotEqualTo(original);
        assertThat(mutated).as("变异后的表达式里没有 OR —— 变异没生效").contains("OR");

        try (Connection c = tableWithCheck(mutated, "xor_mutated")) {
            // ② 变异之后这两条不再被拒 —— 正是原表达式在守的行为
            assertThatCode(() -> insert(c, "MT-001", "WIP-1"))
                    .as("异或换成 OR 之后「两列都非空」竟然还被拒 —— "
                            + "那说明拒它的不是这个表达式, 本条断言在守别的东西")
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> insert(c, null, null))
                    .as("OR 版本对「两列都为 null」仍然该拒 (false OR false = false) —— "
                            + "这一条是阴性对照, 证明变异后的约束本身还活着, 不是被整体关掉了")
                    .isInstanceOf(SQLException.class);
        }
    }
}
