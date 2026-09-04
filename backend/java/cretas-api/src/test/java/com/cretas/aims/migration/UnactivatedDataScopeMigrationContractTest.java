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
 * 🔴 unactivated 的 role_definitions.data_scope 一旦收紧到 SELF, 不许被之后任何迁移静默改宽。
 *
 * <h2>为什么</h2>
 *
 * <p>PR #589 review 指出: {@code V20261101_31__unactivated_data_scope_self.sql} 本身把
 * {@code unactivated} 那一行从 {@code ALL} 收紧到了 {@code SELF} —— 但这件事此前<b>只有
 * 一份手工验证</b>(临时 Postgres 容器跑了一次 idempotency 检查), 自动化测试套件里没有任何
 * 断言守住"数据库里那一行的值"这件事本身。{@code FlywayVersionUniquenessTest} 只查版本号
 * 不撞车, 不执行任何一条迁移的 SQL; {@code DataScopeResolverTest} 里那一对新测试测的是
 * resolver 的分支逻辑(给定某个 DB 值, resolver 返回什么), 不是"DB 里实际存的是什么值"——
 * 就算有人把 {@code V20261101_31} 的种子改回 {@code ALL}, 或者以后哪个迁移重新
 * {@code INSERT/UPDATE} 了这一行, {@code DataScopeResolverTest} 一条都不会红。
 *
 * <h2>为什么不是 testcontainers</h2>
 *
 * <p>本仓目前<b>没有</b>可继承的 testcontainers 基类 —— 全仓只有 3 处提到
 * "Testcontainers", 全部是注释/{@code @Disabled} 占位, {@code pom.xml} 里也没有这个依赖
 * (2026-09-04 核实)。而且 CI 当下路由在<b>自建 Mac mini runner</b>
 * ({@code python scripts/ci/switch_ci_runner.py --check}), 那台机器<b>没有 Docker</b>——
 * 这正是 {@code .claude/rules/ci-runner-routing.md} 第 6 关记录过的坑
 * (services/容器类步骤在自建 runner 上一个 step 都跑不到)。真起一个 Postgres 容器跑全量
 * Flyway 会在当前 CI 路由下直接把这道闸变成"起不来", 不是"变严格"。⇒ 改用与本目录既有
 * {@code *MigrationContractTest} 同形的源码文本契约测试(参见
 * {@link com.cretas.aims.migration.PurgeMigrationKeptTenantCounterCheckContractTest}
 * 的形状), 不新增依赖、不需要 Docker。
 *
 * <h2>⚠️ 代理判据, 标出来</h2>
 *
 * <p>本测试<b>不执行</b>任何一条迁移 SQL, 只扫源码文本。判据是: 在版本号严格高于
 * {@link #BASE_MIGRATION_FILE} 的迁移文件里, 只要 {@code 'unactivated'} 附近
 * ({@value #PROXIMITY_WINDOW} 字符窗口, 覆盖 {@code V20261101_31} 自己那条跨 3 行的
 * {@code UPDATE} 语句的量级) 出现了 {@code 'ALL'} 而没有同时出现 {@code 'SELF'}, 就判定为
 * 疑似把 scope 又改宽了。
 *
 * <p>已知盲区: (a) 只抓"改回 {@code ALL}"这一种具体退化, 不抓改成
 * {@code DEPT_AND_BELOW}/{@code SELF_AND_BELOW} 之类的其它加宽; (b) 窗口是字符数, 不是
 * SQL 语法边界, 理论上可能被无关的巧合文本触发假阳性或漏判假阴性。它拦的是"完全没人管这件
 * 事就被静默改宽", 不保证抓住每一种改法。
 */
class UnactivatedDataScopeMigrationContractTest {

    private static final Path FLYWAY = Path.of("src/main/resources/db/flyway");

    /** 把 unactivated 的种子行从 ALL 收紧到 SELF 的那一条 —— 本测试的比较基准. */
    private static final String BASE_MIGRATION_FILE = "V20261101_31__unactivated_data_scope_self.sql";

    private static final Pattern MIGRATION_NAME = Pattern.compile("^V([0-9_]+)__.+\\.sql$");

    /** 覆盖 V20261101_31 自己那条跨 3 行的 UPDATE 语句量级(SET/updated_at/WHERE 各一行). */
    private static final int PROXIMITY_WINDOW = 300;

    @Test
    @DisplayName("阳性对照: 扫描器真的能在 db/flyway 下找到 V20261101_31(找不到最像「一切正常」)")
    void positiveControlFindsBaseMigration() {
        assertThat(FLYWAY).as("迁移目录搬家了, 本闸路径要跟着改").exists();
        assertThat(FLYWAY.resolve(BASE_MIGRATION_FILE))
                .as("V20261101_31 不存在 ⇒ 仪器坏了, 不是「已经没有这个问题」")
                .exists();
    }

    @Test
    @DisplayName("V20261101_31 自身确实把 unactivated 的 data_scope 设成 SELF")
    void baseMigrationSetsSelf() throws IOException {
        String sql = readFile(BASE_MIGRATION_FILE);
        assertThat(sql).as("基准迁移里找不到 role_code = 'unactivated' 这个条件").contains("role_code = 'unactivated'");
        assertThat(sql).as("基准迁移里找不到 data_scope = 'SELF' 这个赋值").contains("data_scope = 'SELF'");
    }

    @Test
    @DisplayName("🔴 没有任何版本号更高的迁移把 unactivated 的 data_scope 静默改回 ALL")
    void noLaterMigrationRegressesUnactivatedBackToAll() throws IOException {
        int[] baseVersion = parseVersion(BASE_MIGRATION_FILE);
        List<String> regressions = new ArrayList<>();
        for (Path p : laterMigrations(baseVersion)) {
            String sql = Files.readString(p, StandardCharsets.UTF_8);
            if (touchesUnactivatedWithAllButNoSelf(sql)) {
                regressions.add(p.getFileName().toString());
            }
        }
        assertThat(regressions)
                .as("这些迁移在 'unactivated' 附近写了 'ALL' 却没有 'SELF' —— 疑似把 "
                        + "V20261101_31 刚收紧的 scope 又静默改宽了。V20261101_30 已被另一条并行线"
                        + "占用且永久跳号(见 V20261101_31 头注释), 不会有人在 30/31 之间插队。")
                .isEmpty();
    }

    // ---- helpers, self-contained(与 FlywayVersionUniquenessTest / PurgeMigrationKeptTenantCounterCheckContractTest 同风格, 不额外抽公共类) ----

    private String readFile(String fileName) throws IOException {
        return Files.readString(FLYWAY.resolve(fileName), StandardCharsets.UTF_8);
    }

    private List<Path> laterMigrations(int[] baseVersion) throws IOException {
        try (Stream<Path> files = Files.list(FLYWAY)) {
            return files.filter(p -> {
                        String name = p.getFileName().toString();
                        if (name.equals(BASE_MIGRATION_FILE)) {
                            return false;
                        }
                        Matcher m = MIGRATION_NAME.matcher(name);
                        return m.matches() && compareVersion(parseVersion(name), baseVersion) > 0;
                    })
                    .toList();
        }
    }

    /** package-private(非 private): 供本类内的变异验证复用同一份逻辑, 避免测试与被测逻辑分叉. */
    boolean touchesUnactivatedWithAllButNoSelf(String sql) {
        for (int hit : findAllOccurrences(sql, "'unactivated'")) {
            String window = windowAround(sql, hit, PROXIMITY_WINDOW);
            if (window.contains("'ALL'") && !window.contains("'SELF'")) {
                return true;
            }
        }
        return false;
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

    /** Flyway 语义: 逐段数值比较, 缺的段当 0(例如 1.2 &lt; 1.2.1). */
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

    private List<Integer> findAllOccurrences(String haystack, String needle) {
        List<Integer> idx = new ArrayList<>();
        int from = 0;
        int i;
        while ((i = haystack.indexOf(needle, from)) >= 0) {
            idx.add(i);
            from = i + needle.length();
        }
        return idx;
    }

    private String windowAround(String s, int index, int radius) {
        int start = Math.max(0, index - radius);
        int end = Math.min(s.length(), index + radius);
        return s.substring(start, end);
    }
}
