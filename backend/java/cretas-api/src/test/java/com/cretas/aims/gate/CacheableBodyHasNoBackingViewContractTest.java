package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Cacheable} 方法体里不许出现<b>裸的视图型集合</b>。
 *
 * <h3>为什么在 {@link CacheableResultRoundTripTest} 之外还要这一道</h3>
 * 那一道守的是「这一类类型读不回来」这个<b>事实</b>；它并不知道生产代码里有没有人在用。
 * 把 {@code ProductionReportServiceImpl} 那一行改回 {@code subList} 原样，那道闸<b>照样绿</b>。
 *
 * <p>⇒ 一道闸守知识，一道闸守调用点。⛔ 只有前者时，「闸是绿的」不代表「代码是对的」。
 *
 * <h3>规则</h3>
 * 在带 {@code @Cacheable} / {@code @CachePut} 的方法体里，
 * {@code .subList(...)} / {@code .keySet()} / {@code .values()} 的结果必须被
 * {@code new ArrayList<>(...)} / {@code new HashSet<>(...)} / {@code new LinkedHashSet<>(...)}
 * 这类真实集合<b>复制一份</b>再用。
 *
 * <p>⚠️ 这是一条<b>代理判据</b>：它只看方法体里的字面写法，看不见「值是从别处传进来的」
 * 那一类。它拦得住已经发生过的那种写法，拦不住所有可能。
 * 真正的兜底是 {@link CacheableResultRoundTripTest} 里的往返，以及部署后打一次那个接口。
 */
class CacheableBodyHasNoBackingViewContractTest {

    private static final Path MAIN = Paths.get("src/main/java/com/cretas/aims");

    /** 视图型调用：返回的是「由另一个集合支撑的视图」，Jackson 写得出去读不回来。 */
    private static final Pattern VIEW_CALL =
            Pattern.compile("\\.(subList\\s*\\(|keySet\\s*\\(\\s*\\)|values\\s*\\(\\s*\\))");

    /** 复制成真实集合的构造：出现在视图调用之前就算已经安全。 */
    private static final Pattern COPY_CTOR =
            Pattern.compile("new\\s+(ArrayList|HashSet|LinkedHashSet|TreeSet|HashMap|LinkedHashMap)\\s*<");

    private static final Pattern CACHE_ANNOTATION =
            Pattern.compile("@(Cacheable|CachePut)\\b");

    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return Pattern.compile("//.*$", Pattern.MULTILINE).matcher(noBlock).replaceAll("");
    }

    /** 从注解位置往后取一个配平大括号的方法体。 */
    private static String methodBodyAfter(String code, int annotationIndex) {
        int open = code.indexOf('{', annotationIndex);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i + 1);
                }
            }
        }
        return code.substring(open);
    }

    private record Offence(String file, String snippet) {}

    private static List<Offence> scan() throws IOException {
        List<Offence> offences = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(MAIN).filter(p -> p.toString().endsWith(".java"))) {
            for (Path p : sources.toList()) {
                String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                Matcher ann = CACHE_ANNOTATION.matcher(code);
                while (ann.find()) {
                    String body = methodBodyAfter(code, ann.end());
                    Matcher view = VIEW_CALL.matcher(body);
                    while (view.find()) {
                        // 往前看一小段: 有没有把它复制成真实集合
                        int from = Math.max(0, view.start() - 120);
                        String before = body.substring(from, view.start());
                        if (COPY_CTOR.matcher(before).find()) {
                            continue;
                        }
                        int lineFrom = Math.max(0, view.start() - 90);
                        int lineTo = Math.min(body.length(), view.end() + 40);
                        offences.add(new Offence(
                                MAIN.relativize(p).toString().replace('\\', '/'),
                                body.substring(lineFrom, lineTo).replaceAll("\\s+", " ").trim()));
                    }
                }
            }
        }
        return offences;
    }

    @Test
    @DisplayName("@Cacheable 方法体里不许出现没复制过的 subList / keySet / values")
    void noBackingViewInsideCacheableMethods() throws IOException {
        List<Offence> offences = scan();

        assertThat(offences)
                .as("这些值会整个进 Redis, 而视图型集合**写得出去、读不回来** —— "
                        + "第一次调用把毒条目写进缓存, 此后每次命中都 500。"
                        + " 修法: 用 new ArrayList<>(...) 复制一份再放。"
                        + " 实测名单见 CacheableResultRoundTripTest 的类注释。")
                .isEmpty();
    }

    @Test
    @DisplayName("阳性对照: 扫描器确实能找到 @Cacheable 方法 —— 否则「0 处违例」只是它没看")
    void scannerActuallyFindsCacheableMethods() throws IOException {
        int annotated = 0;
        try (Stream<Path> sources = Files.walk(MAIN).filter(p -> p.toString().endsWith(".java"))) {
            for (Path p : sources.toList()) {
                String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                Matcher ann = CACHE_ANNOTATION.matcher(code);
                while (ann.find()) {
                    if (!methodBodyAfter(code, ann.end()).isEmpty()) {
                        annotated++;
                    }
                }
            }
        }
        assertThat(annotated)
                .as("一个 @Cacheable 方法都没扫到 —— 上一条的绿是无意义的")
                .isGreaterThan(0);
        System.out.println("扫到带缓存注解的方法体: " + annotated + " 个");
    }

    @Test
    @DisplayName("阴性对照: 扫描器剥注释 —— 不许把说明文字数成违例")
    void scannerStripsComments() {
        String code = """
                class X {
                  @Cacheable("x")
                  Map<String, Object> f() {
                    // 这里提到 .subList( 只是说明, 不是调用
                    /* .keySet() 也一样 */
                    Map<String, Object> m = new HashMap<>();
                    m.put("rows", new ArrayList<>(rows.subList(0, 5)));
                    return m;
                  }
                }
                """;
        String stripped = stripComments(code);
        assertThat(stripped).doesNotContain("只是说明");
        // 复制过的那一处不算违例
        Matcher view = VIEW_CALL.matcher(stripped);
        assertThat(view.find()).isTrue();
        String before = stripped.substring(Math.max(0, view.start() - 120), view.start());
        assertThat(COPY_CTOR.matcher(before).find())
                .as("包在 new ArrayList<>() 里的 subList 应当被判为安全")
                .isTrue();
    }
}
