package com.cretas.aims.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闭包维护函数的<b>最终定义</b>（按 Flyway 版本序最后一个 {@code CREATE OR REPLACE FUNCTION
 * fn_maintain_lineage_closure}）必须含第 ⑥ 步「anc(source) × desc(target)」，且补齐迁移只增不改。
 *
 * <p>行为证明在 {@code LineageClosureCrossJoinPostgresContractTest}（真 PG，CI 里要进
 * postgres job 的 -Dtest 列表才会跑）；本类是跑在快速门禁里的防退化闸：防止以后有人再
 * CREATE OR REPLACE 这个函数时照着 V20260821_02 的 5 步抄回去。⛔ 它不执行 SQL —— 代理判据，标出来。
 */
class LineageClosureCrossJoinMigrationContractTest {

    private static final Path FLYWAY = Path.of("src/main/resources/db/flyway");
    private static final Pattern MIGRATION_NAME = Pattern.compile("^V([0-9_]+)__.+\\.sql$");
    private static final Pattern DEFINES_FUNCTION = Pattern.compile(
            "CREATE\\s+(OR\\s+REPLACE\\s+)?FUNCTION\\s+fn_maintain_lineage_closure\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final String REPAIR = "V20261103_81__lineage_closure_cross_ancestor_descendant.sql";

    @Test
    @DisplayName("最后一个定义 fn_maintain_lineage_closure 的迁移含 anc(source) × desc(target) 交叉写入")
    void latestClosureFunctionHasCrossProductStep() throws IOException {
        List<Path> definers = migrationsInVersionOrder().stream()
                .filter(p -> DEFINES_FUNCTION.matcher(executable(read(p))).find())
                .toList();
        assertThat(definers).as("一个定义都没找到 —— 闸测错了地方").isNotEmpty();

        Path latest = definers.get(definers.size() - 1);
        String body = executable(read(latest)).replaceAll("\\s+", " ");
        System.out.println("[lineage-cross-contract] definers=" + definers.stream().map(Path::getFileName).toList()
                + " latest=" + latest.getFileName());

        assertThat(body)
                .as(latest.getFileName() + ": 第 ⑥ 步 —— 两张闭包表自连接, 祖先侧挂 NEW.source, 后代侧挂 NEW.target")
                .containsPattern("FROM batch_lineage_closure anc JOIN batch_lineage_closure desc_ ON "
                        + "desc_\\.factory_id = NEW\\.factory_id AND desc_\\.ancestor_id = NEW\\.target_id")
                .containsPattern("anc\\.descendant_id = NEW\\.source_id")
                .contains("anc.depth + 1 + desc_.depth");
    }

    @Test
    @DisplayName("补齐迁移对 batch_lineage_closure 只 INSERT … ON CONFLICT DO NOTHING, 不删不改")
    void repairMigrationIsInsertOnly() throws IOException {
        String sql = executable(read(FLYWAY.resolve(REPAIR)));
        assertThat(sql).doesNotContainPattern("(?i)\\bDELETE\\s+FROM\\s+batch_lineage_closure")
                .doesNotContainPattern("(?i)\\bUPDATE\\s+batch_lineage_closure")
                .doesNotContainPattern("(?i)\\bTRUNCATE\\b")
                .doesNotContainPattern("(?i)DO\\s+UPDATE");
        long inserts = Pattern.compile("(?i)INSERT\\s+INTO\\s+batch_lineage_closure").matcher(sql).results().count();
        long noops = Pattern.compile("(?i)ON\\s+CONFLICT\\s*\\([^)]*\\)\\s*DO\\s+NOTHING").matcher(sql).results().count();
        System.out.println("[lineage-cross-contract] inserts=" + inserts + " on-conflict-do-nothing=" + noops);
        assertThat(inserts).as("6 步触发器 + 2 段补齐").isEqualTo(8);
        assertThat(noops).as("每一条 INSERT 都必须 ON CONFLICT DO NOTHING (幂等)").isEqualTo(inserts);
    }

    private static List<Path> migrationsInVersionOrder() throws IOException {
        try (Stream<Path> files = Files.list(FLYWAY)) {
            return files.filter(p -> MIGRATION_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(LineageClosureCrossJoinMigrationContractTest::version,
                            LineageClosureCrossJoinMigrationContractTest::compareVersions))
                    .toList();
        }
    }

    private static List<BigDecimal> version(Path p) {
        Matcher m = MIGRATION_NAME.matcher(p.getFileName().toString());
        m.matches();
        return Arrays.stream(m.group(1).split("_")).map(BigDecimal::new).toList();
    }

    private static int compareVersions(List<BigDecimal> a, List<BigDecimal> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            BigDecimal x = i < a.size() ? a.get(i) : BigDecimal.ZERO;
            BigDecimal y = i < b.size() ? b.get(i) : BigDecimal.ZERO;
            int c = x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(p.toString(), e);
        }
    }

    /** 去掉整行与行尾 -- 注释: 判据不许被注释里的字面量喂饱。 */
    private static String executable(String sql) {
        return sql.replaceAll("--[^\\n]*", " ");
    }
}
