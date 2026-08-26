package com.cretas.aims.codequality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 闸: 源码里不许出现**字面 NUL 字节** (0x00)。
 *
 * <p><b>不是禁止 NUL 这个值</b> —— 它作为复合键分隔符是对的做法
 * ({@code a + NUL + b + NUL + c})，因为 NUL 是唯一保证不会出现在组件字符串里的字符，
 * 能避开 {@code "a|b"+"c"} 与 {@code "a"+"b|c"} 这类碰撞。
 * 禁的是**写法**: 要写 {@code "\u0000"} 转义，不要把 0x00 这个字节直接存进源文件。
 *
 * <p><b>为什么要紧</b>: 搜索工具遇到 NUL 会判定文件是 binary。2026-08-26 实测三份文件:
 * <ul>
 *   <li>{@code grep -n} 只输出一行 {@code Binary file X matches} —— <b>匹配行一条不给</b>，
 *       任何解析行的闸拿到零行</li>
 *   <li>ripgrep 返回 NUL <b>之前</b>的匹配就停下 —— 实测 {@code getWorkflowEdgeId}
 *       真实 3 处只返回 2 处，<b>静默少一处</b></li>
 *   <li>{@code grep -c} / {@code -l} 不受影响 —— 所以「有没有」问得出来，「在哪几行」问不出来</li>
 * </ul>
 *
 * <p>⚠️ <b>git 帮不上忙</b>: 它只探前 8000 字节判 binary。那三份文件里有两份的 NUL
 * 在 4000 行开外，git 完全没察觉，照常按文本 diff —— 于是这个缺陷不会在 review 里暴露。
 *
 * <p><b>覆盖面</b>: 本闸只扫 Java 源码树。RN / web-admin 树同样有这个风险但不在此闸内 ——
 * 收窄是有意的(宁可窄而可信)，2026-08-26 的三处命中全部在 Java 侧。
 */
class NoLiteralNulByteInSourceTest {

    private static final List<Path> ROOTS = List.of(
            Paths.get("src", "main", "java"),
            Paths.get("src", "test", "java"));

    private static List<Path> javaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .forEach(out::add);
            }
        }
        return out;
    }

    /** 一个文件里有几个 0x00。⛔ 必须按**字节**读 —— 按字符读会被解码器悄悄换掉。 */
    private static int nulCount(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        int n = 0;
        for (byte b : bytes) {
            if (b == 0) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("阳性对照: 确实扫到了成规模的源码(扫 0 个文件的闸永远是绿的)")
    void gateActuallyScansSomething() throws IOException {
        List<Path> files = javaFiles();
        assertThat(files).hasSizeGreaterThan(500);
        long totalBytes = 0;
        for (Path p : files) {
            totalBytes += Files.size(p);
        }
        assertThat(totalBytes).isGreaterThan(5_000_000L);
    }

    @Test
    @DisplayName("阳性对照: 探测器真的认得出 NUL(不是恒返回 0)")
    void detectorRecognisesNul() throws IOException {
        Path tmp = Files.createTempFile("nul-gate-probe", ".java");
        try {
            // 显式构造含 0x00 的字节串 —— 不经过任何转义写法, 免得被工具链吃掉
            Files.write(tmp, new byte[] {'a', 0, 'b', 0, 'c'});
            assertThat(nulCount(tmp)).isEqualTo(2);
            // 阴性: 同样内容去掉 NUL 必须数出 0, 否则探测器是恒真的
            Files.write(tmp, new byte[] {'a', 'b', 'c'});
            assertThat(nulCount(tmp)).isZero();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("🔴 一个字面 NUL 都不许有 —— 要 NUL 这个值就写 \\u0000 转义")
    void noLiteralNulBytesInJavaSources() throws IOException {
        // ⛔ 逐条收集再一次性断言, 不在第一个命中处停下 —— 否则只看得到最靠前的那一份
        List<String> offenders = new ArrayList<>();
        for (Path p : javaFiles()) {
            int n = nulCount(p);
            if (n > 0) {
                offenders.add(p.toString().replace('\\', '/') + " (" + n + " 个)");
            }
        }
        assertThat(offenders).isEmpty();
    }
}
