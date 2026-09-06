package com.cretas.aims.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 实体上的 {@code columnDefinition} 必须是 PostgreSQL 认识的类型, 且 JSON 列只许写
 * {@code jsonb}。
 *
 * <p>2026-09-06 实测(任务 OPS-PROD-DDL-AUTO-NONE-AND-SCHEMA-CANNOT-VALIDATE):
 * 把真实 Spring 上下文按 {@code profile=pg-prod + ddl-auto=validate} 打到一份 prod
 * schema 拷贝上, 163 处 validate 失败里 <b>127 处</b>是同一个形状 ——
 * {@code columnDefinition = "JSON"} 而库里一直是 {@code jsonb}。同一个仓里另外
 * 151 处写的就是 {@code jsonb}, 两种写法并存了很久(形态 D: 同一个东西有两份, 它一定会漂)。
 *
 * <p>还捞出两个 <b>MySQL 专有类型</b>: {@code MEDIUMBLOB} 和 {@code LONGTEXT}。
 * 它们在 PostgreSQL 上<b>根本不存在</b>, 之所以没炸, 只是因为 prod 跑的是
 * {@code ddl-auto=none} —— 谁都没让 Hibernate 拿它们跟真库对过。
 *
 * <p>这条闸是<b>源码级</b>的: 不起上下文、不连库, 所以它在 H2 profile 下也照跑。
 * 它守的行为是"实体声明的列类型 == prod 上真实的列类型", 变异方式就是把任意一个
 * {@code jsonb} 改回 {@code JSON}, 或者把 {@code MEDIUMBLOB} 放回去。
 *
 * <p>⚠️ 扫描前会先剥掉注释 —— 本仓 {@code StringListConverter} 的 javadoc 里就有一行
 * {@code columnDefinition = "json"} 的示例; 不剥的话闸会去测自己人写的注释
 * (2026-08-20 一晚四次的老坑)。
 */
class EntityColumnDefinitionIsPostgresTypeContractTest {

    private static final Path SRC = Path.of("src/main/java");

    private static final Pattern COLUMN_DEFINITION =
            Pattern.compile("columnDefinition\\s*=\\s*\"([^\"]*)\"");

    /** MySQL 专有, PostgreSQL 上不存在这些类型名。 */
    private static final Set<String> MYSQL_ONLY_TYPES = Set.of(
            "tinyint", "mediumint", "datetime", "tinytext", "mediumtext", "longtext",
            "tinyblob", "mediumblob", "longblob", "unsigned", "enum", "set", "year",
            "double", "nvarchar", "ntext", "bit");

    /** PostgreSQL 上 json 和 jsonb 是两个类型; 本仓的 JSON 列全部是 jsonb。 */
    private static final Set<String> FORBIDDEN_JSON_SPELLINGS = Set.of("json");

    private record Hit(Path file, int line, String value) {
        @Override
        public String toString() {
            return file + ":" + line + "  columnDefinition = \"" + value + "\"";
        }
    }

    @Test
    @DisplayName("实体的 columnDefinition 里没有 MySQL 专有类型, 也没有裸 json(应为 jsonb)")
    void columnDefinitionsAreRealPostgresTypes() throws IOException {
        assertThat(SRC)
                .as("源码目录不存在 —— 说明这条闸测错了地方, 不是「没问题」")
                .exists();

        List<Hit> all = new ArrayList<>();
        int filesScanned = 0;
        try (Stream<Path> files = Files.walk(SRC)) {
            List<Path> javaFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path p : javaFiles) {
                filesScanned++;
                String text = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                Matcher m = COLUMN_DEFINITION.matcher(text);
                while (m.find()) {
                    all.add(new Hit(p, lineOf(text, m.start()), m.group(1)));
                }
            }
        }

        // 阳性对照: 仪器必须真的看见东西。扫出 0 个 columnDefinition 只可能是扫错了
        // 目录 / 正则被改坏, 而那种状态下"没有违规"是恒真的。
        assertThat(filesScanned)
                .as("扫到的 .java 文件数 —— 太少说明目录不对")
                .isGreaterThan(500);
        assertThat(all.size())
                .as("扫到的 columnDefinition 总数 —— 太少说明正则失效, 这时「零违规」没有信息量")
                .isGreaterThan(400);

        List<Hit> mysqlOnly = all.stream()
                .filter(h -> MYSQL_ONLY_TYPES.contains(baseType(h.value())))
                .toList();
        List<Hit> bareJson = all.stream()
                .filter(h -> FORBIDDEN_JSON_SPELLINGS.contains(baseType(h.value())))
                .toList();

        assertThat(mysqlOnly)
                .as("这些 columnDefinition 写的是 MySQL 专有类型, PostgreSQL 上不存在。"
                        + " prod 之所以没炸只是因为 ddl-auto=none —— 一开 validate 立刻红。"
                        + " 共扫描 %d 个文件 / %d 处 columnDefinition", filesScanned, all.size())
                .isEmpty();

        assertThat(bareJson)
                .as("PostgreSQL 上 json 与 jsonb 是两个类型, 本仓所有 JSON 列在库里都是 jsonb;"
                        + " 写成 json 会让 Hibernate schema validation 报"
                        + " found [jsonb (Types#OTHER)], but expecting [json (Types#VARCHAR)]。"
                        + " 共扫描 %d 个文件 / %d 处 columnDefinition", filesScanned, all.size())
                .isEmpty();
    }

    /** {@code "DECIMAL(3,2)"} -> {@code "decimal"}; {@code "jsonb"} -> {@code "jsonb"} */
    private static String baseType(String raw) {
        String t = raw.trim().toLowerCase(Locale.ROOT);
        int i = t.indexOf('(');
        if (i >= 0) {
            t = t.substring(0, i);
        }
        return t.trim();
    }

    /**
     * 去掉块注释与行注释, 但保留换行以免行号错位。
     * ⚠️ 不做完整的 Java 词法分析: 字符串字面量里出现 {@code //} 的情况本仓没有,
     * 一旦有, 这里会少扫而不是多扫 —— 而少扫会被上面的阳性对照数量断言接住。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        boolean inString = false;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    out.append(src.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                int stop = end < 0 ? src.length() : end + 2;
                for (int k = i; k < stop; k++) {
                    if (src.charAt(k) == '\n') {
                        out.append('\n');
                    }
                }
                i = stop;
                continue;
            }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                i = end < 0 ? src.length() : end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
