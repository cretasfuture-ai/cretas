package com.cretas.aims.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住：每个 {@code @Async("x")} 的 x 都必须真有一个 bean 定义。
 *
 * <h2>它守的是什么</h2>
 * Spring 解析 {@code @Async} 的限定符时走
 * {@code AsyncExecutionAspectSupport.findQualifiedExecutor} →
 * {@code BeanFactoryAnnotationUtils.qualifiedBeanOfType}，
 * 找不到就<b>在第一次调用那个方法时抛</b>。
 * ⚠️ 不是启动时抛 —— 所以一个引用了不存在 executor 的方法可以<b>长期潜伏</b>，
 * 编译过、启动过、测试过，直到某天有人第一次调它。
 *
 * <h2>prod 实证（2026-08-19）</h2>
 * {@code DashboardStatisticsServiceImpl} 里 8 个方法写着
 * {@code @Async("dashboardExecutor")}，而 <b>{@code dashboardExecutor} 全树零个定义</b>
 * （阳性对照：{@code startupWarmupExecutor} / {@code smartbiExecutor} 都能同时找到
 * {@code @Bean} 和引用）。它们当时之所以没炸，只是因为<b>零个调用者</b>，
 * 而且它们没有声明在 {@code DashboardStatisticsService} 接口上，
 * 消费方注入的是接口 ⇒ 经正常接线根本到不了。
 * 已改指 {@code reportExecutor}（core 5 / max 10 / queue 100，语义上就是报表类工作）。
 *
 * <h2>⚠️ 这道闸必须同时认两种 bean 声明写法</h2>
 * <pre>
 *   @Bean(name = "reportExecutor")  public Executor reportExecutor() { ... }   // 显式命名
 *   @Bean                           public Executor reportExecutor() { ... }   // 方法名即 bean 名
 * </pre>
 * ⛔ 只认第一种会让闸自己产生误报 —— 我第一版就是只 grep {@code @Bean(name = "...")}，
 * 差点据此得出「某个 executor 没定义」的错误结论。
 *
 * <p>⚠️ 它<b>看不见</b>的：XML 配置、{@code @Component} 命名的 Executor、
 * 运行时注册的 bean、以及 {@code @Async} 之外的限定符（{@code @Qualifier} 没有被扫）。
 * 这是**源码层的代理判据**（形态 E：宁可窄而可信）。
 */
class AsyncQualifierResolvesToRealBeanContractTest {

    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");

    private static final Pattern ASYNC_QUALIFIER = Pattern.compile("@Async\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern BEAN_NAMED = Pattern.compile("@Bean\\([^)]*name\\s*=\\s*\"([^\"]+)\"");
    /** `public Executor foo()` / `public ThreadPoolTaskExecutor foo()` … 方法名即 bean 名。 */
    private static final Pattern METHOD_NAME =
            Pattern.compile("^\\s*(?:public|protected)\\s+[\\w.<>,\\[\\]\\s]+?\\s+(\\w+)\\s*\\(");

    @Test
    @DisplayName("每个 @Async(\"x\") 的 x 都有 bean 定义 —— 否则第一次调用才炸, 可以潜伏很久")
    void everyAsyncQualifierHasABeanDefinition() throws IOException {
        Map<String, List<String>> qualifierToSites = collectAsyncQualifiers();
        Set<String> beanNames = collectBeanNames();

        // 阳性对照 ①: 扫描不能是空的, 否则下面那条断言恒真
        assertFalse(qualifierToSites.isEmpty(),
                "一个 @Async(\"...\") 都没扫到 —— 大概率是路径或正则坏了, "
                        + "这时候「全部通过」没有意义。cwd=" + Paths.get("").toAbsolutePath());
        assertFalse(beanNames.isEmpty(), "一个 @Bean 名字都没扫到 —— 扫描器坏了");

        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : qualifierToSites.entrySet()) {
            if (!beanNames.contains(e.getKey())) {
                broken.add("@Async(\"" + e.getKey() + "\") 引用了一个不存在的 bean, 出现在: " + e.getValue());
            }
        }

        assertTrue(broken.isEmpty(),
                "以下 @Async 限定符指向不存在的 bean。\n"
                        + "⚠️ 它们**不会在启动时报错** —— Spring 在第一次调用那个方法时才解析限定符, "
                        + "所以可以长期潜伏。\n  - "
                        + String.join("\n  - ", broken)
                        + "\n\n修法二选一: 在 AsyncConfig 里定义那个 executor, "
                        + "或把限定符改指一个已存在的池(改之前想清楚池大小合不合适)。\n"
                        + "当前已定义的 bean: " + beanNames);
    }

    /**
     * 阳性对照 ② —— 证明这个检测器**能**判定「某个名字不存在」。
     *
     * <p>没有这一条，上面那条断言可能因为 {@code beanNames} 意外包含了一切而恒真。
     */
    @Test
    @DisplayName("阳性对照: 一个编造的名字必须【不】在已定义 bean 集合里")
    void detectorCanTellAMissingBean() throws IOException {
        Set<String> beanNames = collectBeanNames();
        assertFalse(beanNames.contains("zzzNotARealExecutorBean"),
                "编造的名字竟然出现在已定义 bean 集合里 ⇒ 收集器把不该收的东西收进来了, "
                        + "上面那条断言是恒真式");
        // 同时证明它确实收到了真实的那些
        assertTrue(beanNames.contains("reportExecutor"),
                "reportExecutor 是 @Bean(name = \"...\") 显式命名的, 必须被收到");
        assertTrue(beanNames.contains("startupWarmupExecutor"),
                "startupWarmupExecutor 同上");
    }

    /**
     * 阳性对照 ③ —— 证明收集器认得**不写 name 的**那种写法。
     *
     * <p>⛔ 我第一版只 grep {@code @Bean(name = "...")}，那会让闸对
     * {@code @Bean public Executor foo()} 产生误报。这条钉住它别退化回去。
     */
    @Test
    @DisplayName("阳性对照: 收集器认得 @Bean 不写 name 时「方法名即 bean 名」")
    void detectorUnderstandsUnnamedBeanMethods() {
        String snippet = String.join("\n",
                "    @Bean",
                "    public Executor someUnnamedExecutor() {",
                "        return null;",
                "    }");
        Set<String> names = beanNamesIn(snippet);
        assertTrue(names.contains("someUnnamedExecutor"),
                "没认出「@Bean + 方法名」这种写法 —— 闸会对这类 bean 误报不存在");
    }

    /**
     * 阳性对照 ④ —— 剥注释这一步本身必须是对的。
     *
     * <p>⚠️ 这条是**被咬出来的**：闸第一版不剥注释，我在产品代码里写了一段解释
     * 「原来写的是 {@code @Async("dashboardExecutor")}」，闸当场红，报的正是那段注释。
     * ⛔ 而如果 {@code stripComments} 反过来把**代码也剥掉**，主断言会变成恒真式 ——
     * 所以两个方向都要钉。
     */
    @Test
    @DisplayName("阳性对照: 剥注释既要剥干净, 也不许把代码剥掉")
    void stripCommentsRemovesCommentsButKeepsCode() {
        String src = String.join("\n",
                "// @Async(\"commentOnlyExecutor\") 这行是注释, 不该被扫到",
                "/* @Async(\"blockCommentExecutor\") 块注释同理 */",
                "@Async(\"realExecutor\")",
                "String s = \"@Async(\\\"stringLiteralExecutor\\\")\";");
        String stripped = stripComments(src);

        assertFalse(stripped.contains("commentOnlyExecutor"), "行注释没剥掉");
        assertFalse(stripped.contains("blockCommentExecutor"), "块注释没剥掉");
        assertTrue(stripped.contains("realExecutor"),
                "把真代码也剥掉了 —— 那会让主断言变成恒真式");
        assertTrue(stripped.contains("stringLiteralExecutor"),
                "字符串字面量里的内容不该被当注释剥掉");
        // 行数不变, 否则报出来的行号会错位
        assertTrue(stripped.split("\n", -1).length == src.split("\n", -1).length,
                "剥注释改变了行数 ⇒ 闸报的行号会指错地方");
    }

    // ==================== 扫描实现 ====================

    private static Map<String, List<String>> collectAsyncQualifiers() throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        forEachJavaFile((path, text) -> {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = ASYNC_QUALIFIER.matcher(lines[i]);
                while (m.find()) {
                    out.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                            .add(path.getFileName() + ":" + (i + 1));
                }
            }
        });
        return out;
    }

    private static Set<String> collectBeanNames() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        forEachJavaFile((path, text) -> out.addAll(beanNamesIn(text)));
        return out;
    }

    /** 同时认 {@code @Bean(name = "x")} 与 {@code @Bean} + 方法名两种写法。 */
    private static Set<String> beanNamesIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains("@Bean")) {
                continue;
            }
            Matcher named = BEAN_NAMED.matcher(lines[i]);
            if (named.find()) {
                out.add(named.group(1));
                continue;
            }
            // 不写 name: 往下找最近的方法签名, 方法名即 bean 名
            for (int j = i + 1; j < Math.min(i + 6, lines.length); j++) {
                String line = lines[j];
                if (line.trim().startsWith("@")) {
                    continue;   // 中间可能还有别的注解
                }
                Matcher method = METHOD_NAME.matcher(line);
                if (method.find()) {
                    out.add(method.group(1));
                }
                break;
            }
        }
        return out;
    }

    private interface FileVisitor {
        void accept(Path path, String text) throws IOException;
    }

    private static void forEachJavaFile(FileVisitor visitor) throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            for (Path p : (Iterable<Path>) paths.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                String raw = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                visitor.accept(p, stripComments(raw));
            }
        }
    }

    /**
     * 扫描前把注释剥掉 —— <b>不剥的话这道闸会命中它自己的说明文字</b>。
     *
     * <p>⚠️ 实测: 我在 {@code DashboardStatisticsServiceImpl} 里写了一段注释解释
     * 「原来写的是 {@code @Async("dashboardExecutor")}」，闸立刻红了，报的正是那段注释。
     * 本仓这个形状记过三次（数注解不剥注释 / grep 数进了 docstring /
     * {@code count("/ all_gross")} 打中了覆盖率那一行），
     * 判据是 <b>闸量的应该是结构不是文本</b>；这里退而求其次至少要先剥注释。
     *
     * <p>⛔ 行号会因此错位（用换行占位保持行数不变，所以行号仍然对）。
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        boolean inLine = false;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = (i + 1 < src.length()) ? src.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') { inLine = false; out.append(c); }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') { inBlock = false; i++; }
                else if (c == '\n') { out.append(c); }   // 保持行数不变
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\') { if (next != '\0') { out.append(next); i++; } }
                else if (c == '"') { inString = false; }
                continue;
            }
            if (inChar) {
                out.append(c);
                if (c == '\\') { if (next != '\0') { out.append(next); i++; } }
                else if (c == '\'') { inChar = false; }
                continue;
            }
            if (c == '/' && next == '/') { inLine = true; i++; continue; }
            if (c == '/' && next == '*') { inBlock = true; i++; continue; }
            if (c == '"') { inString = true; out.append(c); continue; }
            if (c == '\'') { inChar = true; out.append(c); continue; }
            out.append(c);
        }
        return out.toString();
    }
}
