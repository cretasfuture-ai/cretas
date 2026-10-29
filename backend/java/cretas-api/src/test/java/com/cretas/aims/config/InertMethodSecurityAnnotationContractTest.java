package com.cretas.aims.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸: 没开方法级安全时, src/main 里不许出现方法安全注解。
 *
 * <p>{@code SecurityAutoConfiguration} 在所有 profile 被排除, 也没有 {@code @EnableMethodSecurity},
 * 所以 {@code @PreAuthorize} / {@code @Secured} / {@code @RolesAllowed} 等<b>一处都不检查</b>
 * (见 {@link RequireRole} javadoc)。它们写在那里只会让读的人以为守住了 —— 2026-10 复审时
 * {@code PUT /ai/quota} 就是这样: 注解写「仅限平台超级管理员」, 实际调度 / 财务主管都能改本厂配额。
 * 生效的写法是 {@link RequireRole} / {@code @RequirePermission} (拦截器执行)。
 *
 * <p>若将来真的开启方法安全 (出现 {@code @EnableMethodSecurity} / {@code @EnableGlobalMethodSecurity}),
 * 本闸自动放行 —— 那时这些注解才会生效, 需要另做全量回归。
 *
 * <p>注释与字符串里的提及不算 (先剥掉注释和字面量再匹配), 否则讲历史的注释会把闸误报到被关掉。
 */
@DisplayName("没开方法级安全时 src/main 不许出现不生效的方法安全注解")
class InertMethodSecurityAnnotationContractTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** 注解位置上的方法安全注解, 含全限定写法 {@code @org.springframework...PreAuthorize(}. */
    static final Pattern INERT_ANNOTATION = Pattern.compile(
            "@(?:[\\w.]+\\.)?(PreAuthorize|PostAuthorize|PreFilter|PostFilter|Secured|RolesAllowed)\\b");

    static final Pattern ENABLING = Pattern.compile(
            "@(?:[\\w.]+\\.)?(EnableMethodSecurity|EnableGlobalMethodSecurity)\\b");

    @Test
    @DisplayName("🔴 方法安全未开启 ⇒ 0 处 @PreAuthorize/@Secured/@RolesAllowed…")
    void noInertMethodSecurityAnnotationsInMain() throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        // 「一定会发生」的读数: 扫到的文件数. 路径错了会扫 0 个文件, 那时「0 违规」毫无意义.
        assertThat(sources).as("扫描的 src/main 源文件数").hasSizeGreaterThan(1000);

        List<String> enabling = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (Path p : sources) {
            String code = stripCommentsAndLiterals(Files.readString(p));
            if (ENABLING.matcher(code).find()) {
                enabling.add(p.toString());
            }
            for (String hit : findAll(INERT_ANNOTATION, code)) {
                violations.add(p + ": @" + hit);
            }
        }
        if (!enabling.isEmpty()) {
            return; // 方法安全已开启: 注解生效, 本闸不适用 (见类注释)
        }
        assertThat(violations)
                .as("方法安全未开启, 这些注解不做任何检查; 改用 @RequireRole / @RequirePermission")
                .isEmpty();
    }

    @Test
    @DisplayName("阳性对照: 闸认得注解写法, 也不把注释 / 字符串里的提及当违规")
    void detectorSeesAnnotationsButNotMentions() {
        String flagged = """
                class A {
                    @PreAuthorize("hasRole('X')")
                    void a() {}
                    @org.springframework.security.access.annotation.Secured("ROLE_X")
                    void b() {}
                    @RolesAllowed({"x"}) void c() {}
                }
                """;
        assertThat(findAll(INERT_ANNOTATION, stripCommentsAndLiterals(flagged)))
                .containsExactly("PreAuthorize", "org.springframework.security.access.annotation.Secured", "RolesAllowed");

        String mentionsOnly = """
                /** 用 {@code @PreAuthorize} 是 NO-OP, 见 @EnableMethodSecurity */
                class B {
                    // @PreAuthorize 在本项目不生效
                    String s = "@PreAuthorize(\\"x\\")";
                    char c = '@';
                    @RequireRole({"factory_super_admin"}) void ok() {}
                }
                """;
        String stripped = stripCommentsAndLiterals(mentionsOnly);
        assertThat(findAll(INERT_ANNOTATION, stripped)).isEmpty();
        assertThat(ENABLING.matcher(stripped).find()).isFalse();
        assertThat(stripped).contains("@RequireRole");
    }

    private static List<String> findAll(Pattern pattern, String code) {
        List<String> hits = new ArrayList<>();
        Matcher m = pattern.matcher(code);
        while (m.find()) {
            hits.add(m.group().substring(1));
        }
        return hits;
    }

    /** 去掉 // 与 /* *&#47; 注释、字符串 / 文本块 / 字符字面量, 保留其余代码。 */
    static String stripCommentsAndLiterals(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && src.charAt(i) != c) {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
                out.append(c).append(c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
