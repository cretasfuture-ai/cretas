package com.cretas.aims.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicInsert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用门禁: 「NOT NULL DEFAULT 列, Java 侧没有对应默认值」的缺陷类
 * (per {@code product_types.accepts_rework}, #508, 2026-09-02 07:47 事故).
 *
 * <h3>缺陷形状</h3>
 *
 * <pre>ALTER TABLE product_types
 *     ADD COLUMN IF NOT EXISTS accepts_rework boolean NOT NULL DEFAULT TRUE;</pre>
 *
 * 对应实体字段 {@code private Boolean acceptsRework;} 没有初始化。DB 层的
 * {@code DEFAULT TRUE} 只在 <b>INSERT 语句压根没提这一列</b>时才生效
 * ——而 Hibernate 只要字段在内存里是 {@code null}, 就会显式把这一列写成 {@code NULL},
 * DB 收到的是「客户端说要 NULL」而不是「客户端没提」, {@code DEFAULT} 因此从不触发,
 * 直接撞 {@code 23502 not-null violation}。#508 给这一列补了
 * {@code = Boolean.TRUE} 才修好——本测试把这个缺陷类**机械化**成一道闸,
 * 防止同型缺陷在其它 684 个 Flyway 迁移 / 445 个实体里重演。
 *
 * <h3>怎么判定</h3>
 *
 * <ol>
 *   <li>解析 Flyway 语料: 每一处 {@code ADD COLUMN ... NOT NULL ... DEFAULT ...}
 *       和 {@code CREATE TABLE (...)} 里 {@code NOT NULL} + {@code DEFAULT} 同时出现的列定义。</li>
 *   <li>反射扫描 {@code @Entity}: 把 {@code table.column} 映射到具体字段
 *       ({@code @Column(name=...)}, 缺省时按 Hibernate 默认命名策略
 *       camelCase→snake_case)。</li>
 *   <li>只看 <b>wrapper/reference 类型</b> 字段 (Boolean/Integer/Long/BigDecimal/
 *       String/枚举) —— 基本类型 (boolean/int/…) 不可能是 null, 天然安全, 不在本闸范围。</li>
 *   <li>该字段若满足以下任一项, 判定为「已防御」(verdict a, 结构性证据,
 *       不需要逐字段人工核对构造路径):
 *     <ul>
 *       <li>{@code @jakarta.persistence.Version} —— JPA/Hibernate 规范保证
 *           persist 时自动赋初始值 (数值型 0), 不经过应用代码</li>
 *       <li>{@code @Column(insertable = false)} —— 该列根本不出现在 INSERT 语句里
 *           (常见于 DB 触发器/GENERATED 列, 例: {@code ProductionBatch.workflowSelectionMode}
 *           由 {@code @Generated(event = EventType.INSERT)} 触发器回填)</li>
 *       <li>{@code @ColumnDefault} 或类级 {@code @DynamicInsert} —— Hibernate
 *           自己知道要在 INSERT 里省略这一列, 让 DB 的 {@code DEFAULT} 生效</li>
 *       <li>源码里该字段声明<b>本身带初始化表达式</b> (e.g. {@code = Boolean.TRUE;})</li>
 *     </ul>
 *   </li>
 *   <li>都不满足 ⇒ offender: 只要有任何构造路径省略这个字段 (e.g. builder 没显式
 *       set), Hibernate 就会显式插入 NULL, 撞同一类 23502。</li>
 * </ol>
 *
 * <h3>扫描结果 (2026-09-02 首次跑通)</h3>
 *
 * <p>初次扫描命中 <b>79</b> 处候选 (857 条 Flyway NOT NULL+DEFAULT 列中, 439 处映射到
 * checked-type 实体字段)。逐条人工核对 (grep 枚举常量定义 / 检查
 * {@code insertable}/{@code @Version}/{@code @DynamicInsert}) 后:</p>
 *
 * <ul>
 *   <li><b>45 处</b> — {@code @Version} 乐观锁字段 (structural verdict a, 见上)</li>
 *   <li><b>1 处</b> — {@code ProductionBatch.workflowSelectionMode},
 *       {@code insertable = false} + DB 触发器回填 (structural verdict a)</li>
 *   <li><b>78 处</b> — verdict b (无任何防御, 真实同型缺陷), 全部按 DB 侧
 *       {@code DEFAULT} 字面量补了匹配的字段初始化值并已在同一 PR 修复
 *       (枚举常量逐个核对过实际枚举定义, 例如 {@code DistanceEdgeSource.DEMO_FIXTURE}
 *       与 {@code PlanDistanceSource.MAINTAINED_MATRIX} 是两个相似但不同值集的
 *       独立枚举, 分别对号入座, 没有混用)</li>
 * </ul>
 *
 * <p>⇒ 修复后 offender 数应为 <b>0</b>, 本测试是<b>硬门禁</b> (不是棘轮): 任何新迁移
 * 引入同型缺陷都会让它当场变红, 不需要另外维护基线文件。</p>
 *
 * <h3>阳性对照</h3>
 *
 * <p>{@link #positiveControlScannerCatchesMissingInitializer()} 用
 * {@link com.cretas.aims.contract.fixture.NotNullDefaultScannerFixture} 独立验证
 * 「有初始化的字段不报, 没初始化的字段报」这条核心判定逻辑本身没坏 ——
 * 不依赖生产实体, 不需要每次手工改 {@code ProductType.java} 来确认。</p>
 *
 * <p>此外, 本测试编写时額外做过一次<b>手工变异验证</b> (未入库, 过程见 PR 描述):
 * 临时删掉 {@code ProductType.acceptsRework} 的 {@code = Boolean.TRUE}, 重跑本测试,
 * 确认 offender 列表里出现且仅出现 {@code product_types.accepts_rework} 一条;
 * 恢复后重跑, 确认列表清零。证明变异真的打进了扫描器覆盖的行为, 不是断言在守空气。</p>
 */
class NotNullColumnsHaveEntityDefaultsContractTest {

    /**
     * 解析器/扫描器坏掉时的兜底: 若正则或反射静默失效, 命中数会掉到 0,
     * 断言就会"没有 offender"而假绿。857 条 Flyway 语料样本、439 处 checked-type
     * 映射(2026-09-02 实测), 下限各取约一半, 只要求「明显还在工作」。
     */
    private static final int MIN_SQL_COLUMNS = 400;
    private static final int MIN_MATCHED_CHECKED_TYPE_PAIRS = 200;

    private static final Set<String> TABLE_CONSTRAINT_KEYWORDS = Set.of(
            "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "CONSTRAINT", "EXCLUDE", "LIKE");

    private static final Pattern ALTER_TABLE = Pattern.compile(
            "\\bALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?(?:public\\.)?\"?(\\w+)\"?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_COLUMN = Pattern.compile("\\bADD\\s+COLUMN\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:public\\.)?\"?(\\w+)\"?\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_NULL = Pattern.compile("\\bNOT\\s+NULL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULT_KW = Pattern.compile("\\bDEFAULT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_IDENT = Pattern.compile("^\\s*(?:IF\\s+NOT\\s+EXISTS\\s+)?\"?(\\w+)\"?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_TOKEN = Pattern.compile("^\\s*\"?(\\w+)\"?");

    private record SqlColumn(String table, String column, String source, String rawDefinition) {
    }

    private record EntityField(String table, String column, Class<?> declaringClass, Class<?> beanClass, Field field) {
    }

    // ── 1) SQL 侧: 从 Flyway 语料里取每个 NOT NULL + DEFAULT 列定义 ──────────

    @Test
    @DisplayName("NOT NULL DEFAULT 列必须有匹配的 JPA 侧默认值 (accepts_rework 同型扫描)")
    void notNullDefaultColumnsHaveEntityDefaults() throws Exception {
        Map<String, SqlColumn> sqlColumns = parseFlywayNotNullDefaultColumns();
        Map<String, EntityField> entityFields = scanEntityFields();

        assertTrue(sqlColumns.size() >= MIN_SQL_COLUMNS, () -> String.format(
                "只解析到 %d 条 NOT NULL+DEFAULT 列, 少于下限 %d —— 大概率是 SQL 正则/语句"
                        + "边界扫描坏了, 而不是真的没有这类列, 空跑会假绿所以直接失败。",
                sqlColumns.size(), MIN_SQL_COLUMNS));

        List<String> offenders = new ArrayList<>();
        int matched = 0;
        for (SqlColumn sc : sqlColumns.values()) {
            EntityField ef = entityFields.get(key(sc.table(), sc.column()));
            if (ef == null) {
                continue; // 这一列不映射到任何已知实体字段 —— 不在本闸范围
            }
            Class<?> type = ef.field().getType();
            if (!isCheckedType(type)) {
                continue; // 基本类型不可能是 null, 天然安全
            }
            matched++;
            if (isStructurallyDefended(ef.field(), ef.beanClass())) {
                continue;
            }
            if (hasSourceInitializer(Path.of("src/main/java"), ef.declaringClass(), ef.field().getName())) {
                continue;
            }
            offenders.add(String.format(
                    "%n  %s.%s (定义于 %s)%n"
                            + "    实体字段 %s#%s (%s), 无字段初始化 / 无 @Version / "
                            + "无 insertable=false / 无 @ColumnDefault / 无 @DynamicInsert%n"
                            + "    DB: %s%n"
                            + "    → 给字段加一个匹配 DEFAULT 的初始化表达式 (例: "
                            + "`= Boolean.TRUE;` / 对应枚举常量), 否则任何省略该字段的构造路径"
                            + "都会让 Hibernate 显式插入 NULL, 撞 23502 (accepts_rework 同型缺陷)。",
                    sc.table(), sc.column(), sc.source(),
                    ef.declaringClass().getSimpleName(), ef.field().getName(), type.getSimpleName(),
                    sc.rawDefinition()));
        }

        final int matchedPairs = matched;
        assertTrue(matchedPairs >= MIN_MATCHED_CHECKED_TYPE_PAIRS, () -> String.format(
                "只配上 %d 组 (NOT NULL+DEFAULT 列 ↔ checked-type 实体字段), 少于下限 %d —— "
                        + "大概率是本测试的实体反射扫描或命名映射坏了, 而不是真的没有这类字段, "
                        + "空跑会假绿所以直接失败。(SQL 侧 %d 条, 实体侧 %d 个字段)",
                matchedPairs, MIN_MATCHED_CHECKED_TYPE_PAIRS, sqlColumns.size(), entityFields.size()));

        assertTrue(offenders.isEmpty(), () -> "NOT NULL DEFAULT 列缺 Java 侧默认值 "
                + offenders.size() + " 处 (accepts_rework 同型缺陷) —— 省略该字段就撞 23502:"
                + String.join("", offenders));
    }

    @Test
    @DisplayName("阳性对照: SQL 侧真的解析到东西 —— 否则「零 offender」只是因为没扫到")
    void positiveControlSqlColumnsAreParsed() throws Exception {
        Map<String, SqlColumn> sqlColumns = parseFlywayNotNullDefaultColumns();
        assertTrue(sqlColumns.containsKey("product_types.accepts_rework"),
                "连 #508 那条本体 (product_types.accepts_rework) 都没解析到 —— SQL 侧解析器坏了。");
    }

    @Test
    @DisplayName("阳性对照: 实体侧真的反射扫描到东西 —— 否则「零 offender」只是因为没扫到")
    void positiveControlEntityFieldsAreScanned() throws Exception {
        Map<String, EntityField> entityFields = scanEntityFields();
        EntityField ef = entityFields.get("product_types.accepts_rework");
        assertTrue(ef != null, "连 ProductType.acceptsRework 都没反射扫描到 —— 实体侧扫描器坏了。");
        assertEquals(Boolean.class, ef.field().getType());
        assertTrue(hasSourceInitializer(Path.of("src/main/java"), ef.declaringClass(), ef.field().getName()),
                "ProductType.acceptsRework 当前应已有 `= Boolean.TRUE` 初始化 (#508 修复) —— "
                        + "若这条断言红了, 说明 #508 的修复被回退了。");
    }

    /**
     * 阳性对照(fixture, 不碰生产实体): 用一个独立的固定装置类直接验证「有初始化的字段
     * 不报, 没初始化的字段报」这条核心判定逻辑。不依赖临时改写 ProductType.java 才能验证
     * ——那种手工验证做过一次(见类注释), 但持久留在库里的是这个 fixture 化的版本。
     */
    @Test
    @DisplayName("阳性对照(fixture): 缺初始化的字段必被判定为 offender, 有初始化的不能")
    void positiveControlScannerCatchesMissingInitializer() throws Exception {
        Class<?> fixture = com.cretas.aims.contract.fixture.NotNullDefaultScannerFixture.class;
        Path testRoot = Path.of("src/test/java");

        assertTrue(hasSourceInitializer(testRoot, fixture, "flagWithInitializer"),
                "fixture 的 flagWithInitializer 字段本身写了 `= Boolean.TRUE`, "
                        + "hasSourceInitializer 必须判定为 true —— 否则扫描器会把 accepts_rework "
                        + "这种已修复字段继续误报。");
        assertFalse(hasSourceInitializer(testRoot, fixture, "flagWithoutInitializer"),
                "fixture 的 flagWithoutInitializer 字段没有初始化表达式, hasSourceInitializer "
                        + "必须判定为 false —— 否则扫描器会漏掉 accepts_rework 这种真实缺陷"
                        + "(该字段就是刻意模拟修复前的 accepts_rework)。");

        Field versionField = fixture.getDeclaredField("versionFlag");
        assertTrue(versionField.isAnnotationPresent(Version.class));
        assertTrue(isStructurallyDefended(versionField, fixture),
                "@Version 字段必须被结构性豁免判定命中 —— 否则每个乐观锁字段都会被误报成 offender。");

        Field insertableFalseField = fixture.getDeclaredField("triggerPopulatedFlag");
        assertTrue(isStructurallyDefended(insertableFalseField, fixture),
                "insertable=false 字段必须被结构性豁免判定命中 —— 否则 DB 触发器回填的列"
                        + "(如 ProductionBatch.workflowSelectionMode) 会被误报成 offender。");

        Field plainField = fixture.getDeclaredField("flagWithoutInitializer");
        assertFalse(isStructurallyDefended(plainField, fixture),
                "没有任何防御的字段不应该被结构性豁免误判成「已防御」。");
    }

    // ── helpers: SQL 侧解析 ──────────────────────────────────────────────

    private static Map<String, SqlColumn> parseFlywayNotNullDefaultColumns() throws IOException {
        Path dir = new ClassPathResource("db/flyway").getFile().toPath();
        Map<String, SqlColumn> byKey = new LinkedHashMap<>();

        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(NotNullColumnsHaveEntityDefaultsContractTest::versionKey))
                    .toList();
        }

        for (Path file : files) {
            String sql = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            String fileName = file.getFileName().toString();

            // ADD COLUMN 系列
            Matcher alterMatcher = ALTER_TABLE.matcher(sql);
            while (alterMatcher.find()) {
                String table = alterMatcher.group(1).toLowerCase(Locale.ROOT);
                int stmtEnd = findStatementEnd(sql, alterMatcher.end());
                String stmt = sql.substring(alterMatcher.end(), stmtEnd);
                if (!ADD_COLUMN.matcher(stmt).find()) {
                    continue;
                }
                List<Integer> positions = new ArrayList<>();
                Matcher addColMatcher = ADD_COLUMN.matcher(stmt);
                while (addColMatcher.find()) {
                    positions.add(addColMatcher.start());
                }
                for (int i = 0; i < positions.size(); i++) {
                    int chunkStart = positions.get(i);
                    int chunkEnd = (i + 1 < positions.size()) ? positions.get(i + 1) : stmt.length();
                    String chunk = stmt.substring(chunkStart, chunkEnd);
                    String afterKeyword = ADD_COLUMN.matcher(chunk).replaceFirst("");
                    Matcher identMatcher = LEADING_IDENT.matcher(afterKeyword);
                    if (!identMatcher.find()) {
                        continue;
                    }
                    String col = identMatcher.group(1).toLowerCase(Locale.ROOT);
                    if (NOT_NULL.matcher(chunk).find() && DEFAULT_KW.matcher(chunk).find()) {
                        byKey.put(key(table, col), new SqlColumn(table, col, fileName, chunk.strip()));
                    }
                }
            }

            // CREATE TABLE ( ... ) 系列
            Matcher createMatcher = CREATE_TABLE.matcher(sql);
            while (createMatcher.find()) {
                String table = createMatcher.group(1).toLowerCase(Locale.ROOT);
                int openIdx = createMatcher.end() - 1;
                String body = balanced(sql, openIdx, '(', ')');
                if (body == null) {
                    continue;
                }
                for (String colDef : splitTopLevel(body)) {
                    String trimmed = colDef.strip();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    Matcher tokenMatcher = FIRST_TOKEN.matcher(trimmed);
                    if (!tokenMatcher.find()) {
                        continue;
                    }
                    String firstWord = tokenMatcher.group(1).toUpperCase(Locale.ROOT);
                    if (TABLE_CONSTRAINT_KEYWORDS.contains(firstWord)) {
                        continue; // 表级约束, 不是列定义
                    }
                    String col = tokenMatcher.group(1).toLowerCase(Locale.ROOT);
                    if (NOT_NULL.matcher(trimmed).find() && DEFAULT_KW.matcher(trimmed).find()) {
                        byKey.put(key(table, col), new SqlColumn(table, col, fileName, trimmed));
                    }
                }
            }
        }
        return byKey;
    }

    /** 从 start 起找语句结尾的 ';', 跳过括号嵌套和单引号字符串 (含 '' 转义)。 */
    private static int findStatementEnd(String sql, int start) {
        int depth = 0;
        boolean inQuote = false;
        int n = sql.length();
        int i = start;
        while (i < n) {
            char ch = sql.charAt(i);
            if (inQuote) {
                if (ch == '\'') {
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    inQuote = false;
                }
                i++;
                continue;
            }
            if (ch == '\'') {
                inQuote = true;
                i++;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ';' && depth <= 0) {
                return i;
            }
            i++;
        }
        return n;
    }

    /** 按顶层逗号切分 (跳过括号嵌套和单引号字符串), 用于 CREATE TABLE 的列清单。 */
    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inQuote = false;
        int n = body.length();
        int i = 0;
        while (i < n) {
            char ch = body.charAt(i);
            if (inQuote) {
                cur.append(ch);
                if (ch == '\'') {
                    if (i + 1 < n && body.charAt(i + 1) == '\'') {
                        cur.append(body.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    inQuote = false;
                }
                i++;
                continue;
            }
            if (ch == '\'') {
                inQuote = true;
                cur.append(ch);
                i++;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
                i++;
                continue;
            }
            cur.append(ch);
            i++;
        }
        if (!cur.isEmpty()) {
            parts.add(cur.toString());
        }
        return parts;
    }

    /** 从 s[open] 处的开括号起, 返回配平后的内部内容 (不含首尾括号)。 */
    private static String balanced(String s, int open, char opener, char closer) {
        if (open < 0 || open >= s.length() || s.charAt(open) != opener) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == opener) {
                depth++;
            } else if (ch == closer) {
                depth--;
                if (depth == 0) {
                    return s.substring(open + 1, i);
                }
            }
        }
        return null;
    }

    /** 去掉 {@code --} 行注释和 {@code /* *}{@code /} 块注释。 */
    private static String stripComments(String sql) {
        sql = sql.replaceAll("(?m)--[^\\n]*", "");
        sql = sql.replaceAll("(?s)/\\*.*?\\*/", "");
        return sql;
    }

    /** {@code V20261029_33__x.sql} → {@code 0000020261029.0000000033}, 保证数值序而非字典序。 */
    private static String versionKey(Path p) {
        String name = p.getFileName().toString();
        int sep = name.indexOf("__");
        String v = (sep > 0 ? name.substring(0, sep) : name).replaceFirst("^[Vv]", "");
        StringBuilder sb = new StringBuilder();
        for (String part : v.split("[._]")) {
            sb.append(String.format("%010d.", part.matches("\\d+") ? Long.parseLong(part) : 0L));
        }
        return sb.toString();
    }

    // ── helpers: 实体侧反射扫描 ──────────────────────────────────────────

    private static Map<String, EntityField> scanEntityFields() throws ClassNotFoundException {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        Map<String, EntityField> out = new LinkedHashMap<>();
        for (BeanDefinition bd : provider.findCandidateComponents("com.cretas.aims")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            Table table = type.getAnnotation(Table.class);
            if (table == null || table.name().isBlank()) {
                continue;
            }
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                        continue;
                    }
                    Column col = f.getAnnotation(Column.class);
                    String column = (col != null && !col.name().isBlank())
                            ? col.name() : camelToSnake(f.getName());
                    String k = key(table.name(), column);
                    // 同一列若被多个字段声称 (罕见), 以先扫描到的 (子类优先, 见外层循环顺序) 为准
                    out.putIfAbsent(k, new EntityField(
                            table.name().toLowerCase(Locale.ROOT), column.toLowerCase(Locale.ROOT), c, type, f));
                }
            }
        }
        return out;
    }

    /** 判定「已防御」: 结构性证据, 不需要逐字段人工核对构造路径。 */
    private static boolean isStructurallyDefended(Field field, Class<?> beanClass) {
        if (field.isAnnotationPresent(Version.class)) {
            return true; // JPA/Hibernate 规范保证 persist 时自动赋值
        }
        Column col = field.getAnnotation(Column.class);
        if (col != null && !col.insertable()) {
            return true; // 这一列根本不出现在 INSERT 语句里 (DB 触发器/GENERATED 回填)
        }
        if (field.isAnnotationPresent(ColumnDefault.class)) {
            return true; // Hibernate 自己知道 INSERT 里省略这一列, 让 DB DEFAULT 生效
        }
        return beanClass.isAnnotationPresent(DynamicInsert.class);
    }

    private static boolean isCheckedType(Class<?> type) {
        return type == Boolean.class || type == Integer.class || type == Long.class
                || type == BigDecimal.class || type == String.class || type.isEnum();
    }

    /**
     * 字段声明本身是否带初始化表达式 (e.g. {@code = Boolean.TRUE;})。
     * 从 private/protected/public 关键字起匹配 (不要求关键字在行首 —— 部分字段把
     * 注解写在同一行, 例: {@code @Column(...) @Builder.Default private Boolean x = true;}),
     * 关键字与字段名之间不允许出现括号/分号/换行, 避免跨行或跨语句串味/误吞方法签名。
     * 找不到声明行时抛异常而不是静默返回 false —— 判「没有初始化」的前提是
     * 「确实看到了这行声明」, 不能用「没找到」冒充「找到了但没初始化」。
     */
    private static boolean hasSourceInitializer(Path sourceRoot, Class<?> declaringClass, String fieldName)
            throws IOException {
        Path file = sourceRoot.resolve(declaringClass.getName().replace('.', '/') + ".java");
        if (!Files.exists(file)) {
            throw new IllegalStateException("找不到源文件: " + file + " (declaringClass=" + declaringClass + ")");
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // 不锚定行首: 部分字段把注解写在同一行 (e.g. `@Column(...) @Builder.Default
        // private Boolean enabled = true;`), 那些注解自带括号, 一旦锚定行首 + 排除
        // 括号就会整行落空。改为从 private/protected/public 关键字起匹配, 关键字与
        // 字段名之间仍不允许出现括号/分号/换行, 避免跨行或跨语句串味。
        //
        // 只匹配到字段名为止, 不在同一个正则里去吃「= 初始化表达式 ;」——
        // 有些初始化表达式本身跨行 (e.g. 枚举常量单独另起一行), 逼着整段落在
        // 一行内会把这类字段的声明整行漏掉。「有没有初始化」改成看字段名后
        // 紧跟的第一个非空白字符是 '=' 还是 ';'。
        Pattern fieldDecl = Pattern.compile(
                "\\b(?:private|protected|public)\\s+(?:static\\s+)?(?:final\\s+)?"
                        + "[^\\n;()]*?\\b" + Pattern.quote(fieldName) + "\\b");
        Matcher m = fieldDecl.matcher(content);
        List<Integer> ends = new ArrayList<>();
        while (m.find()) {
            ends.add(m.end());
        }
        if (ends.isEmpty()) {
            throw new IllegalStateException(
                    "在 " + file + " 里找不到字段声明 " + fieldName + " —— 扫描器的字段声明正则可能失配了,"
                            + " 不能把「没找到」当成「没有初始化」。");
        }
        if (ends.size() > 1) {
            throw new IllegalStateException(
                    "在 " + file + " 里字段声明 " + fieldName + " 匹配到 " + ends.size() + " 处, 期望恰好 1 处 "
                            + "—— 正则可能误命中了非字段声明的行, 结果不可信。");
        }
        int idx = ends.get(0);
        int n = content.length();
        while (idx < n && Character.isWhitespace(content.charAt(idx))) {
            idx++;
        }
        if (idx >= n) {
            throw new IllegalStateException("在 " + file + " 里字段 " + fieldName + " 声明后没有更多内容。");
        }
        char next = content.charAt(idx);
        if (next == ';') {
            return false;
        }
        if (next == '=') {
            return true;
        }
        throw new IllegalStateException(
                "在 " + file + " 里字段 " + fieldName + " 声明后紧跟意外字符 '" + next + "' (期望 ';' 或 '=') "
                        + "—— 大概率是正则误命中了同名方法而不是字段声明, 结果不可信。");
    }

    private static String key(String table, String column) {
        return table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
    }

    private static String camelToSnake(String s) {
        return s.replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase(Locale.ROOT);
    }
}
