package com.cretas.aims.contract;

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
 * 源码级契约：实体若同时有类级 {@code @SQLDelete} 和 {@code @Version} 字段，删除 SQL 必须绑 version 列
 * （{@code … WHERE id = ? AND <version_col> = ?}）。Hibernate 对 versioned 实体的自定义删除语句绑两个参数，
 * 少了第二个占位符 {@code repository.delete()} 必炸（2026-09-06 census：5/5 实体全部漏绑，其中 4 个有活的调用方）。
 *
 * <p>守的是源码形状，不是行为——行为由 {@code SoftDeleteVersionedEntitiesContractTest} 真删证明；本闸的价值是
 * 以后任何人给 versioned 实体补 {@code @SQLDelete} 时（棘轮批 2/3 里还有 10 个带 @Version 的实体）不再重犯。
 *
 * <p>阳性对照：扫到的「@SQLDelete + @Version」实体数必须 ≥ 5（扫错目录时零违规是恒真的）。
 */
@DisplayName("@SQLDelete 的 SQL 在 @Version 实体上必须绑 version 列")
class SqlDeleteBindsVersionColumnContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/cretas/aims");
    private static final Pattern SQL_DELETE = Pattern.compile("^\\s*@SQLDelete\\s*\\(\\s*sql\\s*=\\s*\"([^\"]*)\"", Pattern.MULTILINE);
    private static final Pattern VERSION_FIELD = Pattern.compile(
            "^\\s*@Version\\s*\\n(?:\\s*@\\w+(?:\\([^)]*\\))?\\s*\\n)*?\\s*(?:@Column\\s*\\(([^)]*)\\)\\s*\\n)?(?:\\s*@\\w+(?:\\([^)]*\\))?\\s*\\n)*\\s*private\\s+[\\w<>]+\\s+(\\w+)\\s*(?:=[^;]*)?;",
            Pattern.MULTILINE);
    private static final Pattern COLUMN_NAME = Pattern.compile("name\\s*=\\s*\"(\\w+)\"");

    record Finding(String file, String versionColumn, String sql) {}

    @Test
    void everyVersionedSoftDeleteBindsItsVersionColumn() throws IOException {
        List<Finding> violations = new ArrayList<>();
        int scanned = 0, versionedWithSqlDelete = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (!src.contains("@Entity")) continue;
                scanned++;
                Matcher sd = SQL_DELETE.matcher(src);
                Matcher ver = VERSION_FIELD.matcher(src);
                if (!sd.find() || !ver.find()) continue;
                versionedWithSqlDelete++;
                String col = null;
                if (ver.group(1) != null) {
                    Matcher cn = COLUMN_NAME.matcher(ver.group(1));
                    if (cn.find()) col = cn.group(1);
                }
                if (col == null) col = toSnake(ver.group(2));
                String sql = sd.group(1);
                if (!Pattern.compile("\\b" + Pattern.quote(col) + "\\s*=\\s*\\?").matcher(sql).find()) {
                    violations.add(new Finding(MAIN.relativize(p).toString().replace('\\', '/'), col, sql));
                }
            }
        }
        assertThat(scanned).as("阳性对照：扫到的实体源码数").isGreaterThan(400);
        assertThat(versionedWithSqlDelete).as("阳性对照：@SQLDelete + @Version 的实体数（2026-09-06 census = 5）").isGreaterThanOrEqualTo(5);
        assertThat(violations)
                .as("这些实体的 @SQLDelete SQL 没有绑 version 列 —— Hibernate 会绑第二个参数, delete() 必炸; 改成 \"… WHERE id = ? AND <version_col> = ?\"")
                .isEmpty();
    }

    private static String toSnake(String camel) {
        return camel.replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase();
    }

    /** 去掉块注释与行注释，免得 javadoc 里提到的 @SQLDelete / @Version 被数进来。 */
    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
