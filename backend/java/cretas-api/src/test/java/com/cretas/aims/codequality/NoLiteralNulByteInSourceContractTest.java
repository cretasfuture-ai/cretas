package com.cretas.aims.codequality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码里不许出现<b>字面 NUL 字节</b> —— 它会让整份文件对每一道 grep 闸隐形。
 *
 * <h2>2026-08-27 实测</h2>
 *
 * <p>三个文件里躺着 9 个真实的 {@code 0x00}（都是拼 key 用的分隔符
 * {@code a + "<NUL>" + b}）。后果不是编译失败 —— 它<b>编得过、跑得对</b>，
 * 但 {@code grep} 会把整份文件判成二进制并<b>整份跳过</b>：</p>
 *
 * <pre>
 * $ grep -c "settleProduction" ProductionPlanServiceImpl.java
 * Binary file ProductionPlanServiceImpl.java matches      ← 没有计数, 没有行号
 * </pre>
 *
 * <p>两个中招的是核心服务：{@code ProductionPlanServiceImpl}（479KB）与
 * {@code BomRecipeServiceImpl}（188KB）。<b>本仓大量依赖源码级扫描闸</b>
 * （黑话闸、单位契约闸、UTC 截断闸、变异可达性闸…），它们对这 667KB 代码
 * <b>全都是瞎的</b>，而且不会报错 —— 只是安静地少扫两个文件。</p>
 *
 * <h2>修法</h2>
 *
 * <p>换成 Java 八进制转义 {@code "\0"}：运行时是<b>同一个字符</b>，
 * 而源码变回纯 ASCII。已实测替换后 {@code mvn compile} 通过、相关契约测试不变。</p>
 *
 * <h2>为什么值得单独立一道闸</h2>
 *
 * <p>这一类缺陷<b>没有任何现有信号会提示它</b>：编译过、测试绿、IDE 正常显示、
 * git diff 也看不出（git 只探前 8000 字节判二进制，NUL 在更后面就侥幸不报）。
 * 唯一的症状是「某道闸的读数偏小」，而偏小的读数长得和「没有问题」一模一样。</p>
 */
@DisplayName("源码不许含字面 NUL 字节 (会让文件对 grep 闸隐形)")
class NoLiteralNulByteInSourceContractTest {

    private static final Path REPO = Paths.get("../../..").normalize();
    private static final List<String> SCAN_SUFFIXES =
            List.of(".java", ".ts", ".tsx", ".vue", ".py", ".sql");
    private static final List<String> SKIP_DIRS =
            List.of("node_modules", ".git", "target", "dist", ".codex-build", "build");

    private static List<Path> sourceFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(REPO)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return SKIP_DIRS.stream().noneMatch(d -> s.contains("/" + d + "/"));
                    })
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return SCAN_SUFFIXES.stream().anyMatch(n::endsWith);
                    })
                    .toList();
        }
    }

    @Test
    @DisplayName("阳性对照: 确实扫到了成千上万个源文件 (防扫 0 个后恒绿)")
    void scannerActuallyWalksTheRepo() throws IOException {
        assertThat(sourceFiles()).hasSizeGreaterThan(2000);
    }

    @Test
    @DisplayName("没有任何源文件含字面 0x00")
    void noSourceFileContainsLiteralNul() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : sourceFiles()) {
            byte[] bytes = Files.readAllBytes(p);
            int count = 0;
            for (byte b : bytes) {
                if (b == 0) count++;
            }
            if (count > 0) {
                offenders.add(REPO.relativize(p).toString().replace('\\', '/') + " (NUL x" + count + ")");
            }
        }
        assertThat(offenders)
                .as("这些文件对每一道 grep 闸都是隐形的 —— grep 会整份跳过它们, "
                        + "而且不报错。字符串字面量里请用 Java 八进制转义 \\0 代替裸 0x00。")
                .isEmpty();
    }
}
