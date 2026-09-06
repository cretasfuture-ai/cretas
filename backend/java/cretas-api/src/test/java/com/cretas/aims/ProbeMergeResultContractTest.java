package com.cretas.aims;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 临时探针（票 CI-JAVA-GATE-CHECKS-PR-HEAD-NOT-MERGE-RESULT-20260907）。
 *
 * <p>本仓有一批【全仓扫描型】契约闸（软删棘轮、{@code @SQLDelete}+{@code @Version} 源码闸、
 * 模块清单闸、NUL 字节闸……）。它们的作用域是整棵树，所以「CI 给的是哪棵树」直接决定它们
 * 有没有在守东西。本类模仿那种形状：扫 {@code src/main/java}，发现探针 base 分支上的
 * {@code ProbeMergeResultOffender.java} 就失败。
 *
 * <p>判据（这条 PR 的全部意义）：
 * <ul>
 *   <li>闸检出 <b>PR head</b> ⇒ 树里没有那个文件 ⇒ <b>绿</b>（= 闸是瞎的）</li>
 *   <li>闸检出 <b>合并结果</b> ⇒ 树里有那个文件 ⇒ <b>红</b>（= 闸修好了）</li>
 * </ul>
 *
 * <p>⛔ 不要合进 main；两个探针分支用完即删。类名以 {@code ContractTest} 结尾是为了落进
 * java-gate-pr.yml 的 {@code -Dtest='*ContractTest,…'} 选择器。
 */
class ProbeMergeResultContractTest {

    private static final String OFFENDER = "ProbeMergeResultOffender.java";

    @Test
    void offenderFromBaseBranchMustNotBeVisible() throws IOException {
        Path root = Paths.get("src/main/java");
        // 阳性对照：扫描面必须真的有东西，否则「0 命中」只说明我扫了个空目录。
        long scanned;
        List<String> hits;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> javaFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .collect(Collectors.toList());
            scanned = javaFiles.size();
            hits = javaFiles.stream()
                    .filter(p -> OFFENDER.equals(p.getFileName().toString()))
                    .map(Path::toString)
                    .collect(Collectors.toList());
        }
        System.out.println("READING: PROBE_SCANNED_JAVA_FILES=" + scanned + " OFFENDER_HITS=" + hits.size());
        if (scanned < 100) {
            throw new IllegalStateException(
                    "阳性对照不成立：只扫到 " + scanned + " 个 .java，扫描面不对，本次读数作废");
        }
        assertEquals(
                List.of(),
                hits,
                "全仓扫描断言命中 base 分支上的探针文件 " + hits
                        + " ⇒ 说明闸看到的是【合并结果】(这正是本探针要证明的)。");
    }
}
