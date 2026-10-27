package com.cretas.aims.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码级契约：<b>实体源码里出现的每一种注解，都必须有人判过「它声不声明唯一性」。</b>
 *
 * <h3>🔴 为什么是「枚举」而不是「匹配」——这是本闸唯一的设计理由</h3>
 *
 * <pre>
 * 匹配（此前所有闸的做法）：我列出我预期的形式 -> 去找它们
 *      ⇒ 没预期到的形式**静默不存在**，读数与「不存在这种形式」完全相同
 * 枚举（本闸）：列出那里**实际有什么** -> 断言每一种都被判过
 *      ⇒ 没见过的形式**报红**
 * </pre>
 *
 * <p>▎<b>匹配的失败方向是「看不见」，枚举的失败方向是「红」。</b>
 *
 * <p>2026-09-07 一晚有五个真实输入把既有的闸打瞎，复盘之后发现<b>其中三个 AST 也治不了</b>：
 * <ol>
 *   <li>{@code @Builder.Default} 打断跨行正则 —— 修法是把闸<b>写笨</b>（只锚一行，再往后找）</li>
 *   <li>{@code @jakarta.persistence.Version} 全限定写法 —— 一个 token 的正则改动</li>
 *   <li>{@code @org.hibernate.annotations.SQLDelete(} 同上</li>
 *   <li>🔴 {@code @Index(…, unique = true)} —— <b>不是解析问题</b>，是没人知道 @Index 也能声明唯一性</li>
 *   <li>🔴 {@code @Column(unique = true)} 不带 name —— 同上</li>
 * </ol>
 * <p>⇒ 解析器会告诉你「这里有个 {@code @Index} 注解」，
 * <b>⛔ 它不会告诉你「{@code @Index} 也能声明唯一性」——那是领域判断，不是语法。</b>
 * 4/5 两类正则和 AST <b>一样看不见</b>，因为两者都是「我去找我想找的东西」。
 *
 * <h3>⛔ 会让本闸悄悄退化成「匹配」的那个设计点</h3>
 *
 * <p>枚举的粒度必须是<b>实体文件里的每一个注解</b>，⛔ <b>不是「所有看起来和唯一性有关的注解」</b>。
 * 一旦用后者，就又在匹配了——只是把匹配挪到了「哪些名字值得看」这一层，
 * 而<b>那正是第 4/5 类栽的地方</b>（{@code @Index} 当时就不在「看起来相关」的名单里）。
 *
 * <p>⇒ 所以 {@link #EXPLICITLY_IRRELEVANT} 那张表<b>必然很长</b>（{@code @Data} {@code @Slf4j}
 * {@code @JsonIgnore}…）。⛔ <b>别因为长就想省掉它、改成「只列相关的」——那张长表就是本闸与旧做法的分界线。</b>
 *
 * <h3>本闸的价值在 UNCLASSIFIED 那一格</h3>
 *
 * <p>⛔ 不是「找到了几个唯一性声明」，是「<b>有几种形式还没人判过</b>」。
 * 一个新注解进入实体源码 ⇒ 它不在两张表里 ⇒ 当场红，逼一次显式判断。
 *
 * <h3>第一次跑出来的收获（2026-09-07，登记 pass）</h3>
 *
 * <p>73 种归一后的注解里，除了已知的 {@code @Table/@UniqueConstraint/@Index/@Column}，
 * 判出<b>两种此前没人考虑过</b>的唯一性声明形式：
 * <ul>
 *   <li>{@code @OneToOne} —— JPA 规范里一对一映射的 join column <b>默认带唯一约束</b>。
 *       树上 1 处（{@code entity/FactorySettings.java}）⇒ 典型的<b>潜伏盲区</b>：
 *       形式活着、只用一次、而没有任何闸看它。</li>
 *   <li>{@code @JoinColumn} —— 它有 {@code unique()} 属性。树上 158 处 {@code @JoinColumn}，
 *       其中带 {@code unique = true} 的 <b>0 处</b>（⇒ 今天不是缺陷，但形式是活的）。</li>
 * </ul>
 * ⚠️ 这两个不是我去找出来的，是<b>枚举逼我逐个判 73 种时撞出来的</b>——正是本闸存在的理由。
 */
@DisplayName("实体源码里的每一种注解都必须被判过「声不声明唯一性」")
class EntityAnnotationFormsAreAllClassifiedContractTest {

    private static final Path ENTITY_ROOT = Path.of("src/main/java/com/cretas/aims");

    /**
     * 注解名（含全限定 {@code @a.b.C}、含嵌套 {@code @X.Y}）。
     * ⚠️ 故意<b>不</b>要求后面跟 {@code (} —— 无参注解（{@code @Id} {@code @Override}）也要枚举到。
     */
    private static final Pattern ANNOTATION =
            Pattern.compile("@([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)");

    /**
     * <b>能够声明唯一性</b>的注解。名字 -&gt; 它靠什么声明。
     *
     * <p>⚠️ 「能够」≠「今天真的有人这么用了」：{@code @JoinColumn(unique=true)} 树上 0 处，
     * 但形式是活的 ⇒ 仍然归这一格。⛔ 不按「今天用没用」分类，那会让分类随数据漂。
     */
    private static final Map<String, String> DECLARES_UNIQUENESS = new TreeMap<>();

    /** <b>明确判定不声明唯一性</b>的注解。⛔ 这张表长是设计，不是负担（见类文档）。 */
    private static final Set<String> EXPLICITLY_IRRELEVANT = new TreeSet<>();

    static {
        DECLARES_UNIQUENESS.put("Table", "@Table(uniqueConstraints = …)");
        DECLARES_UNIQUENESS.put("UniqueConstraint", "直接就是一条唯一约束");
        DECLARES_UNIQUENESS.put("Index", "@Index(…, unique = true) —— 2026-09-07 咬过一次：它此前不在任何闸的视野里");
        DECLARES_UNIQUENESS.put("Column", "@Column(unique = true) —— 不带 name 时列名要从字段名推 snake_case");
        DECLARES_UNIQUENESS.put("Id", "主键本身就是一条唯一性声明");
        DECLARES_UNIQUENESS.put("OneToOne", "JPA 规范：一对一映射的 join column 默认带唯一约束（树上 1 处：FactorySettings）");
        DECLARES_UNIQUENESS.put("JoinColumn", "它有 unique() 属性（树上 158 处 @JoinColumn，带 unique=true 的 0 处）");

        // ── 明确判定「不声明唯一性」──────────────────────────────────────────────
        // 分组只是给人读的；⛔ 判据是「在不在这张表里」，与分组无关。
        // Lombok
        java.util.Collections.addAll(EXPLICITLY_IRRELEVANT,
                "AllArgsConstructor", "Builder", "Builder.Default", "Data", "EqualsAndHashCode",
                "EqualsAndHashCode.Exclude", "Getter", "NoArgsConstructor", "RequiredArgsConstructor",
                "Setter", "Slf4j", "SuperBuilder", "ToString", "ToString.Exclude");
        // JPA / Hibernate —— 映射、生成、生命周期，都不表达唯一性
        java.util.Collections.addAll(EXPLICITLY_IRRELEVANT,
                "BatchSize", "ColumnDefault", "Convert", "CreationTimestamp", "DynamicInsert", "Entity", "EntityGraph",
                "EntityListeners", "Enumerated", "Formula", "Generated", "GeneratedValue",
                "GenericGenerator", "JdbcTypeCode", "Lock", "ManyToOne", "MappedSuperclass", "Modifying",
                "NotFound", "OneToMany", "OrderBy", "Param", "PostLoad", "PrePersist", "PreRemove",
                "PreUpdate", "Query", "Repository", "SQLDelete", "Transactional", "Transient", "Type",
                "UpdateTimestamp", "Version", "Where");
        // Spring Data 审计
        java.util.Collections.addAll(EXPLICITLY_IRRELEVANT, "CreatedDate", "LastModifiedDate");
        // Jackson —— 只影响序列化
        java.util.Collections.addAll(EXPLICITLY_IRRELEVANT,
                "JsonIgnore", "JsonIgnoreProperties", "JsonProperty");
        // Bean Validation —— 约束的是**值域**，⛔ 不是行间的唯一性
        java.util.Collections.addAll(EXPLICITLY_IRRELEVANT,
                "DecimalMax", "DecimalMin", "Digits", "Max", "Min", "NotBlank", "NotNull",
                "Positive", "PositiveOrZero");
        // 语言 / 本仓自有
        java.util.Collections.addAll(EXPLICITLY_IRRELEVANT,
                "Deprecated", "Override", "SuppressWarnings", "PriceSensitive");
    }

    @Test
    void everyAnnotationFormInEntitySourcesHasBeenClassified() throws IOException {
        Map<String, Integer> census = new TreeMap<>();
        Map<String, Set<String>> writtenForms = new TreeMap<>();
        int entityFiles = 0;

        try (Stream<Path> files = Files.walk(ENTITY_ROOT)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (!src.contains("@Entity")) continue;
                entityFiles++;
                Matcher m = ANNOTATION.matcher(src);
                while (m.find()) {
                    String written = m.group(1);
                    String key = normalize(written);
                    census.merge(key, 1, Integer::sum);
                    writtenForms.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(written);
                }
            }
        }

        List<String> unclassified = new ArrayList<>();
        int inUnique = 0, inIrrelevant = 0;
        for (String name : census.keySet()) {
            if (DECLARES_UNIQUENESS.containsKey(name)) inUnique++;
            else if (EXPLICITLY_IRRELEVANT.contains(name)) inIrrelevant++;
            else unclassified.add(name + " (出现 " + census.get(name) + " 次, 写法 " + writtenForms.get(name) + ")");
        }

        System.out.println("ENTITY_FILES=" + entityFiles
                + " ANNOTATION_KINDS_TOTAL=" + census.size()
                + " IN_UNIQUE=" + inUnique
                + " IN_IRRELEVANT=" + inIrrelevant
                + " UNCLASSIFIED=" + unclassified.size());
        // 多写法的注解单独打出来：归一把它们合并了，而写法差异正是 2026-09-07 打瞎两道闸的那个东西
        writtenForms.forEach((k, v) -> {
            if (v.size() > 1) System.out.println("  MULTI_FORM " + k + " <- " + v);
        });

        // ── 把**全集连同它的分类**打进日志 ────────────────────────────────────────
        // 🔴 为什么这一段值得占 73 行日志：本闸首跑是**绿**的（登记表在同一个 PR 里就填满了），
        //    ⇒ 那份「这棵树上实际存在哪些注解形式」的清单，本来只活在 PR 正文里。
        //    ⚠️ 而 **PR body 没人回头读** —— 半年后想知道「当时判过哪些形式」，
        //    唯一还在的载体是 CI 日志。⛔ 所以不省这段。
        // ⚠️ 它同时是一条**可审计**的读数：分类改了、全集变了，diff 立刻看得见，
        //    ⛔ 不用去读 Java 常量表。
        System.out.println("--- ANNOTATION_CENSUS (全集 + 分类, 按名字排序) ---");
        for (Map.Entry<String, Integer> e : census.entrySet()) {
            String name = e.getKey();
            String verdict = DECLARES_UNIQUENESS.containsKey(name) ? "UNIQUE"
                    : EXPLICITLY_IRRELEVANT.contains(name) ? "IRRELEVANT" : "🔴UNCLASSIFIED";
            System.out.printf("ANNO %-12s %-24s %6d%s%n", verdict, name, e.getValue(),
                    DECLARES_UNIQUENESS.containsKey(name) ? "  " + DECLARES_UNIQUENESS.get(name) : "");
        }
        System.out.println("--- ANNOTATION_CENSUS END ---");

        // ── 阳性对照 ─────────────────────────────────────────────────────────────
        // ⛔ 少了这两条，「零未分类」在扫错目录 / 正则不命中时是恒真的。
        assertThat(entityFiles)
                .as("阳性对照：扫到的实体文件数——扫错目录时「零未分类」恒真")
                .isGreaterThan(400);
        assertThat(census.size())
                .as("阳性对照：枚举到的注解种数——正则一个都不命中时「零未分类」同样恒真")
                .isGreaterThan(50);
        // 分类表本身不许被清空（同上，空表 ⇒ 全部落进 unclassified ⇒ 会红；但反过来
        // 若有人把 unclassified 的断言删了，这两条还能守住表还在）。
        assertThat(DECLARES_UNIQUENESS).hasSizeGreaterThanOrEqualTo(7);
        assertThat(EXPLICITLY_IRRELEVANT).hasSizeGreaterThanOrEqualTo(60);

        assertThat(unclassified)
                .as("这些注解出现在实体源码里，但**没人判过它声不声明唯一性**。\n"
                        + "⇒ 请把它加进 DECLARES_UNIQUENESS（并说明它靠什么声明）"
                        + "或 EXPLICITLY_IRRELEVANT（判定它不声明）。\n"
                        + "⛔ 不要因为「看起来跟唯一性没关系」就跳过——@Index 当初就是这么被漏掉的，"
                        + "而它能用 unique = true 声明唯一。")
                .isEmpty();
    }

    /**
     * 归一：剥掉小写开头的<b>包名</b>段，保留类型路径。
     * <pre>
     * com.fasterxml.jackson.annotation.JsonProperty -> JsonProperty
     * org.hibernate.annotations.SQLDelete           -> SQLDelete
     * Builder.Default                               -> Builder.Default   （两段都大写 = 嵌套类型，保留）
     * </pre>
     * ⚠️ 归一的对象是<b>分类</b>：「@Version 声不声明唯一性」与它写成简单名还是全限定名无关。
     * 而写法差异本身没被丢掉——多写法的注解会单独打进读数（{@code MULTI_FORM}）。
     */
    private static String normalize(String written) {
        String[] segs = written.split("\\.");
        int i = 0;
        while (i < segs.length - 1 && Character.isLowerCase(segs[i].charAt(0))) i++;
        return String.join(".", java.util.Arrays.copyOfRange(segs, i, segs.length));
    }

    /** 去掉块注释与行注释：javadoc 里提到的 {@code @Xxx} 不是源码里的注解。 */
    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
