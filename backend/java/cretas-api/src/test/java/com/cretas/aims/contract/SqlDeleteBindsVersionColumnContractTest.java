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
 * 少了第二个占位符 {@code repository.delete()} 必炸（2026-09-06 census：当时 5 个候选、5 个全部漏绑，其中 4 个有活的调用方；
 * 2026-09-07 合并 #682 之后候选变 7，见下面「为什么不写死候选数」）。
 *
 * <p>守的是源码形状，不是行为——行为由 {@code SoftDeleteVersionedEntitiesContractTest} 真删证明；本闸的价值是
 * 以后任何人给 versioned 实体补 {@code @SQLDelete} 时（棘轮批 2/3 里还有 10 个带 @Version 的实体）不再重犯。
 *
 * <p><b>阳性对照（两层）</b>：① 扫到的实体源码数 &gt; 400——扫错目录时「零违规」是恒真的；
 * ② <b>解析完备性 delta = 0</b>：带 {@code @SQLDelete} 且源码里提到 {@code @Version} 的实体，必须每一个都被解析到，
 * 解析不到就点名报红。这一条替代了「候选数 ≥ N」那种下限——下限抓得到「扫描面缩小」，
 * 抓不到<b>本闸正在修的那个失败模式</b>：新的 versioned 实体以解析不了的形状进来、候选数纹丝不动。
 *
 * <p><b>为什么不写死候选数</b>：候选数会随每一批软删补注解而变（2026-09-06 是 5，合 #682 后是 7，
 * 软删批 2 再补 10 个带 {@code @Version} 的实体之后是 17）。手工维护的常量在这里是错的仪器——
 * 它每批都要人改一次，而「忘了改」与「闸坏了」长得一样。实际候选数每次打进日志（{@code VERSIONED_SQLDELETE_CENSUS=}）。
 */
@DisplayName("@SQLDelete 的 SQL 在 @Version 实体上必须绑 version 列")
class SqlDeleteBindsVersionColumnContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/cretas/aims");
    private static final Pattern SQL_DELETE = Pattern.compile("^\\s*@SQLDelete\\s*\\(\\s*sql\\s*=\\s*\"([^\"]*)\"", Pattern.MULTILINE);
    /**
     * ⚠️ 只锚 {@code @Version} 那一行，字段名/列名在它之后的声明片段里找。
     * 早先写成「一条正则从 @Version 一路匹配到 private 字段」，被 {@code @Builder.Default} 这种
     * <b>带点的注解名</b> 打断（子式 {@code @\w+} 吃不掉 {@code .Default}）——2026-09-07 实测：
     * WagePolicy / WorkProcess 两个实体因此对闸隐身，而它们正是同一天合进来的 versioned + @SQLDelete 实体。
     * 「零违规」在那种写法下有一部分是恒真的。
     */
    private static final Pattern VERSION_ANCHOR =
            Pattern.compile("^[ \\t]*@(?:[\\w.]*\\.)?Version\\b", Pattern.MULTILINE);
    // 🔴 2026-09-07（软删批 3-B）：锚点补上**全限定**写法。
    //    此前只有下面那个 MENTIONS_VERSION（探测器）认 `@jakarta.persistence.Version`，
    //    而**锚点没跟着改** ⇒ 批 3-B 把第一个这么写的实体（EncodingRule:170）纳进软删集合时，
    //    本闸的「解析完备性 delta = 0」当场点名它 —— 闸按设计工作，⛔ 不是误报。
    // ⚠️ 教训（同一个念头散在三处，只修了一处）：
    //    探测器 ✅ 已修 / 锚点 ❌ 没修 / 我的取数脚本 ❌ 也没修 —— 三处长得都不一样，
    //    共同点只有「怎么认 @Version」这个**判据**。
    //    ⇒ 判据改了，要去找**用旧判据的所有地方**，⛔ 不能只修被指出来的那一处。
    //    找法：拿上次**发现缺陷的探测手法**（这里是「全限定注解名」）在全仓再跑一遍。
    /**
     * 只用来判「这个文件到底提没提 {@code @Version}」——与锚点的差集就是解析器的盲区。
     * ⚠️ 必须认全限定写法：2026-09-07 第一版只写 {@code @Version\\b}，用
     * {@code private @jakarta.persistence.Version Long v;} 做变异时**没红**——探测器自己漏了这一类，
     * 那样的话「delta = 0」在这一类上同样是恒真的。
     */
    private static final Pattern MENTIONS_VERSION = Pattern.compile("@(?:[\\w.]*\\.)?Version\\b");
    private static final Pattern FIELD_DECL = Pattern.compile("private\\s+[\\w<>.\\[\\]]+\\s+(\\w+)\\s*[;=]");
    private static final Pattern COLUMN_DECL = Pattern.compile("@Column\\s*\\(([^)]*)\\)");
    private static final Pattern COLUMN_NAME = Pattern.compile("name\\s*=\\s*\"(\\w+)\"");

    record Finding(String file, String versionColumn, String sql) {}

    @Test
    void everyVersionedSoftDeleteBindsItsVersionColumn() throws IOException {
        List<Finding> violations = new ArrayList<>();
        List<String> unparseable = new ArrayList<>();
        int scanned = 0, versionedWithSqlDelete = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (!src.contains("@Entity")) continue;
                scanned++;
                Matcher sd = SQL_DELETE.matcher(src);
                if (!sd.find()) continue;
                Matcher ver = VERSION_ANCHOR.matcher(src);
                if (!ver.find()) {
                    // 有 @SQLDelete、源码里也提到 @Version，却锚不到 ⇒ 解析器看不懂这个写法。
                    // ⛔ 不能 continue 掉：那正是 2026-09-07 之前 WagePolicy / WorkProcess 静默隐身的路径。
                    if (MENTIONS_VERSION.matcher(src).find()) {
                        unparseable.add(MAIN.relativize(p).toString().replace('\\', '/'));
                    }
                    continue;
                }
                versionedWithSqlDelete++;
                // 从 @Version 那一行往后取到字段声明结束（够放下若干注解行），在这段里读列名与字段名
                String decl = src.substring(ver.end(), Math.min(src.length(), ver.end() + 600));
                Matcher fd = FIELD_DECL.matcher(decl);
                assertThat(fd.find())
                        .as("%s 的 @Version 之后 600 字符内应能找到字段声明（扫描器读不懂就不能算它合规）",
                                MAIN.relativize(p).toString().replace('\\', '/'))
                        .isTrue();
                String decisive = decl.substring(0, fd.end());
                String col = null;
                Matcher cd = COLUMN_DECL.matcher(decisive);
                if (cd.find()) {
                    Matcher cn = COLUMN_NAME.matcher(cd.group(1));
                    if (cn.find()) col = cn.group(1);
                }
                if (col == null) col = toSnake(fd.group(1));
                String sql = sd.group(1);
                if (!Pattern.compile("\\b" + Pattern.quote(col) + "\\s*=\\s*\\?").matcher(sql).find()) {
                    violations.add(new Finding(MAIN.relativize(p).toString().replace('\\', '/'), col, sql));
                }
            }
        }
        System.out.println("VERSIONED_SQLDELETE_CENSUS=" + versionedWithSqlDelete + " SCANNED_ENTITY_FILES=" + scanned);
        assertThat(scanned).as("阳性对照：扫到的实体源码数").isGreaterThan(400);
        // 解析完备性：候选数是会变的（5 → 7 → 17…），所以判据不是「候选数 >= N」而是「该被解析的都被解析到了」。
        assertThat(unparseable)
                .as("这些实体带 @SQLDelete 且源码里提到 @Version, 但解析器锚不到它的 @Version 声明 —— "
                        + "闸对它们是瞎的, 「零违规」在这些文件上恒真。要么把写法改回可解析, 要么改解析器")
                .isEmpty();
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
