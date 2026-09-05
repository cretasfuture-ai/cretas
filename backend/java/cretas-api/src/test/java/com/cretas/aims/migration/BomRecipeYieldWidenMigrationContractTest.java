package com.cretas.aims.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 {@code bom_recipes.chk_br_yield} 一旦放宽到 300, 不许被之后任何迁移悄悄收回 100;
 * 而 {@code V20261101_32} 的数据清洗必须只打在 <b>SYSTEM 自动 apply</b> 出来的那一行上。
 * (任务 RECON-LEGAL-VALUE-SENTINEL-20260906)
 *
 * <h2>为什么这道闸只能扫源码文本, 而不是真跑一遍</h2>
 *
 * <p>与 {@link UnactivatedDataScopeMigrationContractTest} 同一个理由, 逐条仍然成立:
 * 本仓 {@code pom.xml} 里没有 testcontainers; 而 CI 当前路由在<b>自建 Mac mini runner</b>
 * 上, 那台机器<b>没有 Docker</b>(见 {@code .claude/rules/ci-runner-routing.md} 第 6 关) ——
 * 真起一个 Postgres 容器会把这道闸变成「起不来」, 不是「变严格」。
 *
 * <p>🔴 更要紧的是: <b>H2 根本看不见这条约束</b>。
 * {@code src/test/resources/application-test.properties} 是
 * {@code spring.flyway.enabled=false} + {@code spring.jpa.hibernate.ddl-auto=create},
 * 表由 Hibernate 按 entity 建, entity 上没有 CHECK ⇒ 「填 110% 会不会被 PG 拒」这件事
 * <b>在任何 H2 测试里都测不出来</b>(与 #581 / {@code V20261101_29} 同形)。
 *
 * <h2>⚠️ 所以真正的四态验证是在容器里做的, 不在这个类里</h2>
 *
 * <p>2026-09-06 在一次性 {@code postgres:17-alpine} 上实跑, 五态全 PASS:
 * <pre>
 *   STATE=1_old_check_rejects_110              PASS  (旧 CHECK 确实拦得住 110 —— 阳性对照)
 *   STATE=2_migration_applies                  PASS  def=CHECK ((overall_yield_rate IS NULL)
 *                                                    OR ((overall_yield_rate > 0) AND (<= 300)))
 *   STATE=2b_only_system_row_nulled            PASS  UPDATE 1; 无 SYSTEM 证据的 100.00 与
 *                                                    有人工改动留痕的 100.00 都原样保留
 *   STATE=3_accepts_100_110_126_300            PASS
 *   STATE=4_rejects_0_neg_301_accepts_null     PASS
 *   STATE=5_idempotent_rerun                   PASS  再跑一次 UPDATE 0
 * </pre>
 *
 * <p>本类是那次验证的<b>防退化闸</b>: 它保证「那份被验证过的 SQL 还在, 且没人在后面把它改回去」。
 * ⛔ 它<b>不</b>执行任何一条 SQL —— 这是代理判据, 标出来。
 */
class BomRecipeYieldWidenMigrationContractTest {

    private static final Path FLYWAY = Path.of("src/main/resources/db/flyway");

    private static final String MIGRATION_FILE = "V20261101_32__bom_recipe_yield_legal_value_widen.sql";

    private static final Pattern MIGRATION_NAME = Pattern.compile("^V([0-9_]+)__.+\\.sql$");

    private String readFile(String fileName) throws IOException {
        return Files.readString(FLYWAY.resolve(fileName), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("阳性对照: V20261101_32 真的在 db/flyway 下(找不到最像「一切正常」)")
    void positiveControlMigrationExists() {
        assertThat(FLYWAY).as("迁移目录搬家了, 本闸路径要跟着改").exists();
        assertThat(FLYWAY.resolve(MIGRATION_FILE))
                .as("V20261101_32 不存在 ⇒ 仪器坏了, 不是「已经没有这个问题」")
                .exists();
    }

    @Test
    @DisplayName("(a) 约束确实被 DROP + ADD 重建, 上限 300, 且显式接受 NULL")
    void migrationWidensTheCheckTo300() throws IOException {
        String sql = readFile(MIGRATION_FILE);
        assertThat(sql)
                .as("PostgreSQL 改 CHECK 取值范围只有 DROP+ADD 一条路; 少了 DROP 就是 ADD 一条同名约束报错")
                .contains("ALTER TABLE bom_recipes DROP CONSTRAINT IF EXISTS chk_br_yield");
        assertThat(sql).contains("ADD CONSTRAINT chk_br_yield");
        assertThat(sql)
                .as("上限不是 300 ⇒ 与 BomYieldApplyRequest 的 @DecimalMax / 前端 "
                        + "YIELD_RATE_MAX_PERCENT 对不齐, 三层规则漂了(形态 D)")
                .containsPattern("overall_yield_rate\\s*<=\\s*300");
        assertThat(sql)
                .as("NULL 没有被显式接受 ⇒ 「没配」这个合法状态在约束里看不见")
                .containsPattern("overall_yield_rate\\s+IS\\s+NULL");
        assertThat(sql)
                .as("下界丢了 ⇒ 0 和负数能进库")
                .containsPattern("overall_yield_rate\\s*>\\s*0");
    }

    @Test
    @DisplayName("(b) 清洗谓词按**证据**写: SYSTEM + 100.00 + 无人工留痕, ⛔ 不硬编码 id")
    void cleanupPredicateIsEvidenceKeyedNotHardcoded() throws IOException {
        String sql = readFile(MIGRATION_FILE);
        assertThat(sql).contains("UPDATE bom_recipes");
        assertThat(sql).containsPattern("overall_yield_rate\\s*=\\s*NULL");
        assertThat(sql)
                .as("没有按 applied_by='SYSTEM' 取证 ⇒ 会把人工填的 100.00 也一起清掉")
                .contains("s.applied_by = 'SYSTEM'");
        assertThat(sql).contains("s.status = 'APPLIED'");
        assertThat(sql)
                .as("没有排除有人工改动留痕的配方 ⇒ 部署前有人手填 100.00 会被覆盖")
                .contains("bom_change_logs");
        // 🔴 阴性对照: prod 上命中的那一行 id 是 259cb14f-… / product_type_id 是 eb0aa47b-… ,
        //    它们只允许出现在**注释**里当证据, ⛔ 不允许出现在 WHERE 里当谓词。
        String code = sql.replaceAll("(?m)^\\s*--.*$", " ");
        assertThat(code)
                .as("谓词里硬编码了那一行的 id ⇒ 换个租户/换条数据就失效, 而且读不出它凭什么被清")
                .doesNotContain("259cb14f");
        assertThat(code).doesNotContain("eb0aa47b");
    }

    @Test
    @DisplayName("(c) 行级 chk_bri_yield 故意不动 —— 登记「有意跳过」, 不是漏了(硬约束 8)")
    void itemLevelCheckIsDeliberatelyUntouched() throws IOException {
        String sql = readFile(MIGRATION_FILE);
        String code = sql.replaceAll("(?m)^\\s*--.*$", " ");
        assertThat(code)
                .as("这条迁移动了 bom_recipe_items 的约束 —— 行级出成率已废弃, 放宽它没有收益, "
                        + "只会多一份可漂的规则。要改请单开一条迁移并说明理由")
                .doesNotContain("chk_bri_yield");
        assertThat(sql)
                .as("注释里没有登记「为什么不动行级约束」⇒ 分不清是漏了还是有意跳过")
                .contains("chk_bri_yield");
    }

    @Test
    @DisplayName("🔴 没有任何版本号更高的迁移把 chk_br_yield 又收回 100")
    void noLaterMigrationNarrowsTheCheckBack() throws IOException {
        int[] baseVersion = parseVersion(MIGRATION_FILE);
        List<String> regressions = new ArrayList<>();
        for (Path p : laterMigrations(baseVersion)) {
            String sql = Files.readString(p, StandardCharsets.UTF_8);
            String code = sql.replaceAll("(?m)^\\s*--.*$", " ");
            if (code.contains("chk_br_yield")
                    && Pattern.compile("overall_yield_rate\\s*<=\\s*100").matcher(code).find()) {
                regressions.add(p.getFileName().toString());
            }
        }
        assertThat(regressions)
                .as("这些迁移把 chk_br_yield 的上限写回了 100 —— 保水/腌制工序(六膳门猪舌 105–126%) "
                        + "会在 PG 上 500, 而 H2 单测全绿看不出来")
                .isEmpty();
    }

    // ---- helpers (与 UnactivatedDataScopeMigrationContractTest 同风格, 各自自足) ----

    private List<Path> laterMigrations(int[] baseVersion) throws IOException {
        try (Stream<Path> files = Files.list(FLYWAY)) {
            return files.filter(p -> {
                        String name = p.getFileName().toString();
                        if (name.equals(MIGRATION_FILE)) {
                            return false;
                        }
                        Matcher m = MIGRATION_NAME.matcher(name);
                        return m.matches() && compareVersion(parseVersion(name), baseVersion) > 0;
                    })
                    .toList();
        }
    }

    private int[] parseVersion(String fileName) {
        Matcher m = MIGRATION_NAME.matcher(fileName);
        if (!m.matches()) {
            throw new IllegalArgumentException("不是合法 Flyway 文件名: " + fileName);
        }
        String[] parts = m.group(1).split("_");
        int[] version = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            version[i] = Integer.parseInt(parts[i]);
        }
        return version;
    }

    /** Flyway 语义: 逐段数值比较, 缺的段当 0。 */
    private int compareVersion(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }
}
