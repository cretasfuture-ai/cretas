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
 * 🔴 {@code V20261101_33} 只许打在「有撤回日志认领 + 恰好一条已软删 IN 腿」的<b>负量</b> REVERSE 上，
 * 且判据里⛔ 不许出现任何硬编码的 txn / report id。
 * (任务 BUG-SFI-REVERSE-CLASSIFIER-LOCKOUT-20260906)
 *
 * <h2>为什么这道闸只能扫源码文本，而不是真跑一遍</h2>
 *
 * <p>与 {@link BomRecipeYieldWidenMigrationContractTest} 同一个理由，逐条仍然成立：
 * 本仓 {@code pom.xml} 里没有 testcontainers；CI 当前路由在<b>自建 Mac mini runner</b> 上，
 * 那台机器<b>没有 Docker</b>（{@code .claude/rules/ci-runner-routing.md} 第 6 关）。
 *
 * <p>🔴 本条另有一个更硬的理由：<b>H2 根本看不见这条迁移</b>。
 * <ul>
 *   <li>{@code src/test/resources/application-test.properties} 是
 *       {@code spring.flyway.enabled=false} + {@code ddl-auto=create} ——
 *       {@code db/flyway} 下的文件在任何 H2 测试里<b>一条都不执行</b>；</li>
 *   <li>就算强行执行，谓词里的 {@code reverted_txn_ids @> to_jsonb(...)} 是 PostgreSQL 的
 *       jsonb 包含运算符，H2 既没有 jsonb 类型也没有 {@code @>}。</li>
 * </ul>
 *
 * <h2>⚠️ 真正的四态验证在容器里做过，不在这个类里</h2>
 *
 * <p>2026-09-06 在一次性 {@code postgres:17-alpine} 上实跑，五态全 PASS：
 * <pre>
 *   STATE=1_before            PASS  4 条候选行 report_id 为 NULL(141/142/143 + 一条合成的歧义行 900)
 *   STATE=2_after             PASS  UPDATE 3; 141→23919 / 142→23921 / 143→23923
 *   STATE=3_idempotent        PASS  再跑一次 UPDATE 0
 *   STATE=4_negctl            PASS  12 条阴性对照(已有 report_id / 其它前缀 / 非 REVERSE)一行没动
 *   STATE=4b_ambiguous        PASS  合成的「两条等量已软删 IN 腿」那行仍是 NULL —— 迁移拒绝猜
 *   STATE=5_zero_match        PASS  空库上 UPDATE 0, 不报错
 *   STATE=6_softdeleted_claim PASS  合成行 910(唯一认领它的日志被软删)仍是 NULL —— 拒绝相信已撤销的日志
 * </pre>
 * 变异对照(同一容器)：
 * <pre>
 *   M1 去掉 r.deleted_at IS NOT NULL  -> UPDATE 0 (三条真行修不到; 因为活着的 IN 腿也成了候选)
 *   M2 去掉 HAVING COUNT(*) = 1       -> UPDATE 4, 歧义行 900 被瞎猜成 23990
 *   M3 去掉 l.deleted_at IS NULL      -> UPDATE 4, 软删日志行 910 被背书成功
 * </pre>
 *
 * <p>⚠️ 终审补 {@code l.deleted_at IS NULL} 那一维时, prod 上<b>零变化</b>:
 * 18 条 {@code report_reversal_logs} 全部 {@code soft_deleted=f}(2026-09-06 只读实测),
 * 三条目标行的认领日志 {@code id=18} 也是 f。补它是为了封死「唯一认领它的日志恰好被软删」那一支。
 *
 * <p>本类是那次验证的<b>防退化闸</b>：它保证「那份被验证过的 SQL 还在，且没人在后面把它改回去」。
 * ⛔ 它<b>不</b>执行任何一条 SQL —— 这是代理判据，标出来。
 */
class LegacyReverseInLegBackfillMigrationContractTest {

    private static final Path FLYWAY = Path.of("src/main/resources/db/flyway");

    private static final String MIGRATION_FILE =
            "V20261101_33__legacy_reverse_in_leg_report_id_backfill.sql";

    private static final Pattern MIGRATION_NAME = Pattern.compile("^V([0-9_]+)__.+\\.sql$");

    private String migrationSql() throws IOException {
        return Files.readString(FLYWAY.resolve(MIGRATION_FILE), StandardCharsets.UTF_8);
    }

    /** 去掉整行注释后的可执行部分 —— ⛔ 判据不许被注释里的字面量喂饱(形态 A⁗ 第 5 例)。 */
    private static String executablePart(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", " ");
    }

    @Test
    @DisplayName("阳性对照: V20261101_33 真的在 db/flyway 下(找不到最像「一切正常」)")
    void positiveControlMigrationExists() {
        assertThat(FLYWAY).as("迁移目录搬家了, 本闸路径要跟着改").exists();
        assertThat(FLYWAY.resolve(MIGRATION_FILE))
                .as("V20261101_33 不存在 ⇒ 仪器坏了, 不是「已经没有这个问题」")
                .exists();
    }

    @Test
    @DisplayName("🔴 只打在负量 + 无 report_id + reversal-log: 前缀的 REVERSE 上")
    void predicateIsScopedToNegativeUnstampedReversalLogRows() throws IOException {
        String code = executablePart(migrationSql());
        assertThat(code).containsPattern("c\\.txn_type\\s*=\\s*'REVERSE'");
        assertThat(code).containsPattern("c\\.source_type\\s*=\\s*'REVERSAL'");
        assertThat(code)
                .as("没有 report_id IS NULL ⇒ 会覆盖掉 #391 之后正常写入的 report_id")
                .containsPattern("c\\.report_id\\s+IS\\s+NULL");
        assertThat(code)
                .as("🔴 符号这一维没了 ⇒ 会把【外部领用还回】(正量)也当成 IN 腿补偿去挂 report_id, "
                        + "那是把两种语义搅成一种 —— 与本 PR 修的缺陷同源")
                .containsPattern("c\\.quantity\\s*<\\s*0");
        assertThat(code).contains("'reversal-log:%'");
    }

    @Test
    @DisplayName("🔴 血缘由撤回日志的 reverted_txn_ids 认领, 且必须【恰好一条】")
    void provenanceComesFromTheReversalLogAndMustBeUnique() throws IOException {
        String code = executablePart(migrationSql());
        assertThat(code)
                .as("没有读 reverted_txn_ids ⇒ 判据退化成「前缀对就改」, 那是代理判据(形态 E)")
                .contains("reverted_txn_ids @> to_jsonb(");
        assertThat(code)
                .as("没有「恰好一条日志」的约束 ⇒ 多条日志认领同一流水时会瞎挑一条")
                .containsPattern("n_logs\\s*=\\s*1");
        assertThat(code)
                .as("🔴 没有 l.deleted_at IS NULL ⇒ 会从一条【已撤销】的撤回日志推出 report_id。"
                        + "容器实测 M3: UPDATE 从 3 变 4, 合成行 910 被一条软删日志背书成功。"
                        + "⚠️ 它还必须与 ReportReversalServiceImpl#loadClaimedTxnIds 同口径(那边读的是 "
                        + "findByFactoryIdAndDeletedAtIsNull...) —— 两侧不一致就是形态 D")
                .containsPattern("l\\.deleted_at\\s+IS\\s+NULL");
        assertThat(code)
                .as("没有「恰好一条 IN 腿」的约束 ⇒ 容器实测 M2: 歧义行被猜成 23990")
                .containsPattern("HAVING\\s+COUNT\\(\\*\\)\\s*=\\s*1");
        assertThat(code)
                .as("没有 deleted_at IS NOT NULL ⇒ 撤回后【重做】的那条活报工也成了候选, "
                        + "容器实测 M1: 三条真行一条都修不到")
                .containsPattern("r\\.deleted_at\\s+IS\\s+NOT\\s+NULL");
        assertThat(code)
                .as("没按数量取反配对 ⇒ 同一 SFI 上多条 IN 时挑错腿")
                .containsPattern("i\\.quantity\\s*=\\s*-\\s*c\\.quantity");
    }

    @Test
    @DisplayName("幂等 + ⛔ 不硬编码任何 txn / report id")
    void idempotentAndEvidenceKeyedNotHardcoded() throws IOException {
        String sql = migrationSql();
        String code = executablePart(sql);
        assertThat(code)
                .as("UPDATE 没有 report_id IS NULL 收口 ⇒ 重跑会再写一遍(不幂等)")
                .containsPattern("t\\.report_id\\s+IS\\s+NULL");
        // 🔴 阴性对照: 生产命中的 141/142/143 与派生出的 23919/23921/23923
        //    只允许出现在**注释**里当证据, ⛔ 不允许出现在可执行 SQL 里当谓词。
        for (String literal : List.of("141", "142", "143", "23919", "23921", "23923", "18")) {
            assertThat(code)
                    .as("谓词里硬编码了 %s ⇒ 换个租户/换条数据就失效, 而且读不出它凭什么被改", literal)
                    .doesNotContain(literal);
        }
        assertThat(sql)
                .as("注释里没有登记这次会动哪几行 ⇒ 事后无法核对迁移实际命中的是不是它们")
                .contains("141");
    }

    @Test
    @DisplayName("登记「有意不动」: F006 343/344 与 LIUSHANMEN 378 不在本次范围(硬约束 8)")
    void deliberatelyUntouchedRowsAreRegistered() throws IOException {
        String sql = migrationSql();
        String code = executablePart(sql);
        assertThat(sql)
                .as("没登记 manual-cleanup: 那三行为什么不修 ⇒ 分不清是漏了还是有意跳过")
                .contains("manual-cleanup:");
        assertThat(sql).contains("interim-settle-reverse-in:");
        assertThat(code)
                .as("⛔ 不许顺手把这两个前缀加进谓词 —— 放宽前缀白名单正是形态 E")
                .doesNotContain("manual-cleanup");
        assertThat(code).doesNotContain("interim-settle-reverse-in");
    }

    @Test
    @DisplayName("🔴 没有任何版本号更高的迁移把这批 report_id 又清回 NULL")
    void noLaterMigrationUnstampsTheseRows() throws IOException {
        int[] baseVersion = parseVersion(MIGRATION_FILE);
        List<String> regressions = new ArrayList<>();
        Pattern unstamp = Pattern.compile(
                "UPDATE\\s+semi_finished_inventory_transactions[\\s\\S]{0,400}?report_id\\s*=\\s*NULL",
                Pattern.CASE_INSENSITIVE);
        for (Path p : laterMigrations(baseVersion)) {
            String code = executablePart(Files.readString(p, StandardCharsets.UTF_8));
            if (unstamp.matcher(code).find()) {
                regressions.add(p.getFileName().toString());
            }
        }
        assertThat(regressions)
                .as("这些迁移把 SFI 流水的 report_id 清回了 NULL —— 那正是把 370/371/372 锁死的那个形状")
                .isEmpty();
    }

    // ---- helpers (与 BomRecipeYieldWidenMigrationContractTest 同风格, 各自自足) ----

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
