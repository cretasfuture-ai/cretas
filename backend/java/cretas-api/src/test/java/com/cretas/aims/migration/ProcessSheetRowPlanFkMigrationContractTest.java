package com.cretas.aims.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码级钉住 {@code V20261101_34} 的四条形状 —— <b>它们在 H2 上一条都测不到</b>。
 *
 * <h2>为什么只能源码级测</h2>
 *
 * <p>三个测试 profile ({@code application-test} / {@code application-pg-test} /
 * {@code application-r10-it}) 都写着 {@code spring.flyway.enabled=false}, H2 的 schema
 * 由 {@code hibernate ddl-auto=create} 从<b>实体注解</b>生成 —— 而
 * {@code ProcessSheetRow.planId} 是普通 {@code @Column}(没有 {@code @JoinColumn}),
 * 所以 H2 上根本不会有这条外键。⇒ 行为四态(存量孤儿容忍 / 新孤儿被拒 / 删被引用的计划被拒 /
 * 幂等重跑)只能在真 Postgres 上验, 本 PR 用一次性 docker postgres:17 跑过, 读数在 PR 正文。
 *
 * <p>这道闸守的是<b>剩下那一半</b>: 迁移文件的形状不许被后来的人改坏。
 * 与 {@code FlywayVersionUniquenessTest} 同一思路(读文件, 不起上下文, 毫秒级)。
 *
 * <h2>四条形状分别在守什么</h2>
 *
 * <ol>
 *   <li><b>NOT VALID</b> —— prod 上 {@code process_sheet_rows} 有 56 行孤儿
 *       (2026-08-29 带外 SQL 清理漏删), {@code report_reversal_logs} 有 13 行。
 *       这些行是那天生产的唯一存档, ⛔ 不得删除。去掉 NOT VALID ⇒ 迁移在 prod 上当场失败。</li>
 *   <li><b>ON DELETE RESTRICT</b> —— 整个改动的目的就是让「物理删掉一条还被引用的计划」
 *       报错而不是静默造孤儿。换成 CASCADE 会把 56 行历史一起删掉(比现状更糟),
 *       换成 SET NULL 会把 {@code NOT NULL} 的 {@code plan_id} 撞穿。</li>
 *   <li><b>没有 VALIDATE CONSTRAINT</b> —— 实测: 在孤儿还在的情况下 VALIDATE 直接
 *       {@code ERROR 23503}。谁想补 VALIDATE, 得先解决那 56 行的归属。</li>
 *   <li><b>没有删除语句</b> —— 这个迁移一行数据都不许动。</li>
 * </ol>
 *
 * <p>另钉一条<b>登记</b>: 两张日志表 ({@code scheduling_alerts} /
 * {@code process_sheet_row_change_log}) 的 {@code plan_id} 是<b>故意</b>不加 FK 的
 * (append-only 日志, 分别有 163 / 56 行孤儿, 加 RESTRICT 会把日志变成删除阻塞点)。
 * 硬约束 8 的「故意不改要显式登记」—— 登记是留痕, 不是豁免。
 */
@DisplayName("V20261101_34 plan_id 外键迁移 — 源码级契约 (H2 看不见这个迁移)")
class ProcessSheetRowPlanFkMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/flyway/V20261101_34__process_sheet_rows_plan_fk_not_valid.sql");

    private static final Path SEED_SCRIPT = Path.of(
            "../../../tests/v1-e2e/scripts/seed-and-reset.sh");

    private static String migrationSql() throws IOException {
        assertThat(MIGRATION)
                .as("迁移文件不在 —— 这说明闸测错了地方(或文件被改名), 不是「没问题」")
                .exists();
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("两条 FK 都是 NOT VALID + ON DELETE RESTRICT")
    void constraintsAreNotValidAndRestrict() throws IOException {
        String sql = migrationSql();

        for (String constraint : List.of(
                "fk_process_sheet_rows_plan", "fk_report_reversal_logs_plan")) {
            assertThat(sql)
                    .as("迁移里应该有约束 " + constraint)
                    .contains(constraint);
        }

        // 阳性对照的一半: 先证明我数的是「ADD CONSTRAINT 块」而不是整份文件里随便哪个词。
        List<String> addBlocks = addConstraintBlocks(sql);
        System.out.println("MIGRATION_CONTRACT add_constraint_blocks=" + addBlocks.size());
        assertThat(addBlocks)
                .as("迁移里应该恰好有 2 个 ADD CONSTRAINT 块(process_sheet_rows / report_reversal_logs)。"
                        + " 数不到就说明解析坏了, 下面的断言全部无意义")
                .hasSize(2);

        for (String block : addBlocks) {
            assertThat(block)
                    .as("每个 ADD CONSTRAINT 块都必须是 NOT VALID —— 否则 prod 上 56+13 行存量孤儿"
                            + "会让迁移当场失败。块内容:\n" + block)
                    .containsIgnoringCase("NOT VALID");
            assertThat(block)
                    .as("每个 ADD CONSTRAINT 块都必须是 ON DELETE RESTRICT —— CASCADE 会把"
                            + "那 56 行历史一起删掉, SET NULL 会撞穿 NOT NULL 的 plan_id。块内容:\n" + block)
                    .containsIgnoringCase("ON DELETE RESTRICT");
        }
    }

    @Test
    @DisplayName("迁移里不许有 VALIDATE CONSTRAINT —— 存量孤儿会让它 23503")
    void migrationMustNotValidate() throws IOException {
        // ⚠️ 必须先去注释: 这个迁移的头部**明确写着**「⛔ 不要有人事后跑 ALTER TABLE …
        //    VALIDATE CONSTRAINT」。第一版没去注释, 闸把自己被守护对象的说明文字当成了违例
        //    (本仓同形第 N 次: 闸匹配到自己的注释)。
        String sql = stripSqlComments(migrationSql().toUpperCase(Locale.ROOT));
        System.out.println("MIGRATION_CONTRACT validate_check_body_chars=" + sql.length());
        assertThat(sql.length())
                .as("去掉注释后正文为空 —— 解析坏了, 下面的阴性断言会恒真")
                .isGreaterThan(200);
        assertThat(sql)
                .as("加了 VALIDATE CONSTRAINT 就会在 prod 上撞 56 行存量孤儿直接失败"
                        + "(docker 实测: ERROR 23503 Key (plan_id)=(...) is not present)")
                .doesNotContain("VALIDATE CONSTRAINT");
    }

    @Test
    @DisplayName("迁移里不许有任何删数据的语句 —— 那 56 行是历史存档")
    void migrationMustNotDeleteData() throws IOException {
        String sql = migrationSql().toUpperCase(Locale.ROOT);
        // 注释里出现「DELETE」是允许的(本文件的说明就提到 ON DELETE RESTRICT),
        // 所以只看**去掉注释行之后**的语句体。
        String body = stripSqlComments(sql);
        System.out.println("MIGRATION_CONTRACT body_chars=" + body.length());
        assertThat(body.length())
                .as("去掉注释后正文为空 —— 解析坏了, 下面的阴性断言会恒真")
                .isGreaterThan(200);
        assertThat(body).doesNotContain("DELETE FROM");
        assertThat(body).doesNotContain("TRUNCATE");
        assertThat(body).doesNotContain("DROP TABLE");
    }

    @Test
    @DisplayName("uk_production_plan_create_request 必须收紧到只管活行(否则软删会锁死幂等键)")
    void idempotencyIndexMustBeScopedToLiveRows() throws IOException {
        String body = stripSqlComments(migrationSql());
        System.out.println("MIGRATION_CONTRACT idem_body_has_index="
                + body.contains("uk_production_plan_create_request"));

        assertThat(body)
                .as("迁移正文里应该重建 uk_production_plan_create_request —— 把 delete() 换成软删之后, "
                        + "被删的那行会继续占着 (factory_id, created_by, client_request_id), "
                        + "而查幂等键那步是派生查询(被 @Where 过滤)看不见它 ⇒ 查不到就 INSERT ⇒ 23505。"
                        + "prod 实测: 8 行软删计划里 7 行带 client_request_id, 正占着这个索引。")
                .contains("uk_production_plan_create_request");
        assertThat(body)
                .as("重建出来的索引谓词必须含 deleted_at IS NULL —— 少了它就等于没修")
                .contains("client_request_id IS NOT NULL AND deleted_at IS NULL");
        assertThat(body)
                .as("必须保留 client_request_id IS NOT NULL —— 只留 deleted_at IS NULL 会把"
                        + "所有 client_request_id 为 NULL 的活计划拖进唯一性约束")
                .contains("WHERE client_request_id IS NOT NULL");
    }

    @Test
    @DisplayName("登记: 两张日志表是故意不加 FK 的, 文件里必须写明理由")
    void deliberatelyExcludedLogTablesAreRegistered() throws IOException {
        String sql = migrationSql();
        for (String logTable : List.of("scheduling_alerts", "process_sheet_row_change_log")) {
            assertThat(sql)
                    .as(logTable + " 是故意排除的, 必须在迁移头部登记(硬约束 8: "
                            + "「故意不改」要显式登记并写理由, 否则分不清「漏了」和「有意跳过」)")
                    .contains(logTable);
        }
        // 但它们**不能**真的被加上 FK。
        String body = stripSqlComments(sql.toUpperCase(Locale.ROOT));
        assertThat(body).doesNotContain("SCHEDULING_ALERTS");
        assertThat(body).doesNotContain("PROCESS_SHEET_ROW_CHANGE_LOG");
    }

    @Test
    @DisplayName("E2E seed 脚本必须先删 process_sheet_rows 再删 production_plans")
    void seedScriptDeletesChildRowsFirst() throws IOException {
        // 三态, 不是两态 (形态 E′: 「没能做决定」不许折叠进「决定不喊」):
        //   目录不在 = 这次 checkout 里根本没有 tests/ 树(单模块构建) -> 明确跳过并打标记
        //   目录在但文件不在 = 脚本被改名/删了 -> 红
        Path scriptsDir = SEED_SCRIPT.getParent();
        if (!Files.isDirectory(scriptsDir)) {
            System.out.println("MIGRATION_CONTRACT seed_script_check=SKIPPED_NO_TESTS_TREE dir=" + scriptsDir);
            org.junit.jupiter.api.Assumptions.abort(
                    "本次 checkout 里没有 " + scriptsDir + " —— 跳过 seed 脚本形状检查(不是通过)");
        }
        assertThat(SEED_SCRIPT)
                .as("tests/v1-e2e/scripts 存在但 seed-and-reset.sh 不在 —— 脚本被改名或删了, "
                        + "而 fk_process_sheet_rows_plan(RESTRICT) 要求它先删子表")
                .exists();
        // 同样先去注释 —— 我给这个脚本加的说明里就写着 `DELETE FROM production_plans`,
        // 不去掉的话 indexOf 会指到注释那一行, 得出「子表删在父表后面」的反向结论(实测踩过)。
        String script = stripShellAndSqlComments(
                Files.readString(SEED_SCRIPT, StandardCharsets.UTF_8));

        int childIdx = script.indexOf("DELETE FROM process_sheet_rows");
        int parentIdx = script.indexOf("DELETE FROM production_plans");
        System.out.println("MIGRATION_CONTRACT seed_child_idx=" + childIdx + " seed_parent_idx=" + parentIdx);

        assertThat(childIdx)
                .as("seed-and-reset.sh 里找不到 `DELETE FROM process_sheet_rows` —— "
                        + "有了 fk_process_sheet_rows_plan(RESTRICT) 之后, 直接删 production_plans 会 23503")
                .isGreaterThanOrEqualTo(0);
        assertThat(parentIdx)
                .as("seed-and-reset.sh 里找不到 `DELETE FROM production_plans` —— 阳性对照失效, "
                        + "说明这个脚本变了形, 上面那条断言不再说明任何事")
                .isGreaterThanOrEqualTo(0);
        assertThat(childIdx)
                .as("子表必须删在父表之前")
                .isLessThan(parentIdx);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** 切出每一个 {@code ALTER TABLE … ADD CONSTRAINT …;} 语句块。 */
    private static List<String> addConstraintBlocks(String sql) {
        String body = stripSqlComments(sql);
        return java.util.Arrays.stream(body.split(";"))
                .map(String::trim)
                .filter(s -> s.toUpperCase(Locale.ROOT).contains("ADD CONSTRAINT"))
                .toList();
    }

    /** 去掉 {@code --} 与 {@code #} 行注释(seed 脚本两种都有)。 */
    private static String stripShellAndSqlComments(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--") || trimmed.startsWith("#")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 去掉 {@code --} 行注释 —— 免得闸把自己的说明文字当成被测对象(形态 A⁴/C⁸)。 */
    private static String stripSqlComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\r?\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
