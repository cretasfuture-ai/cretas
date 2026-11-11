package com.cretas.aims.codequality;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-08 / 验收 D-S410-01: 「把生产计划设成 IN_PROGRESS」的每一处都要登记, 并说明是否过缺料开工闸。
 *
 * <p>开工曾有三条路 (startProduction / create-batch / 逐道录入), 只接了一条时另外两条就是绕路。这道闸让
 * 「新增一处翻状态而没想过缺料闸」在 PR 检查里变红: 发现的位置必须与下表<b>一一对应</b>, 多一处少一处都红。
 *
 * <ul>
 *   <li>GATED: 待处理 → 生产中 (= 开工), 所在文件必须调用 {@code shortageStartGate.check(}。</li>
 *   <li>NOT_GATED: 不是开工 (恢复暂停 / 冲销后重开), 写明理由。</li>
 * </ul>
 */
class ProductionPlanStartPathSweepContractTest {

    /** 「文件#所在方法」→ 处置与理由。 */
    private static final Map<String, String> SITES = Map.of(
            "ProductionPlanServiceImpl#materializeBatchForPlan",
            "GATED: startProduction 与 createBatchFromPlan 共用的批次物化, 两个调用方都先过闸",
            "ClerkProcessEntryServiceImpl#createProductionBatch",
            "GATED: 逐道录入把待处理计划翻成生产中",
            "ProductionPlanServiceImpl#resumeProduction",
            "NOT_GATED: 已暂停 → 生产中, 计划早已开过工, 恢复不是开工",
            "ReportReversalServiceImpl#reopenPlanAndBatchAfterSettlementReversal",
            "NOT_GATED: 已完成 → 生产中, 结算冲销后重开, 不是开工");

    /** 每个「开工」入口方法都要自己过闸 (不只是文件里出现过)。 */
    private static final Map<String, List<String>> GATED_ENTRY_METHODS = Map.of(
            "ProductionPlanServiceImpl", List.of("startProduction", "createBatchFromPlan"),
            "ClerkProcessEntryServiceImpl", List.of("createProductionBatch"));

    private static final Pattern SET_IN_PROGRESS = Pattern.compile(
            "\\.setStatus\\(\\s*(?:com\\.cretas\\.aims\\.entity\\.enums\\.)?ProductionPlanStatus\\.IN_PROGRESS\\s*\\)");
    private static final Pattern METHOD = Pattern.compile(
            "^    (?:public|private|protected)[^=;]*?\\s(\\w+)\\s*\\(");

    @Test
    void everyPlaceThatSetsAPlanInProgressIsClassifiedAndTheStartsAreGated() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                String cls = p.getFileName().toString().replace(".java", "");
                for (int i = 0; i < lines.size(); i++) {
                    if (SET_IN_PROGRESS.matcher(lines.get(i)).find()) {
                        found.merge(cls + "#" + enclosingMethod(lines, i), 1, Integer::sum);
                    }
                }
            }
        }
        assertThat(found.keySet())
                .as("新增或挪动了把计划设成 IN_PROGRESS 的地方: 判断它是不是开工, 是就接上 ShortageStartGate, 然后登记进 SITES")
                .containsExactlyInAnyOrderElementsOf(SITES.keySet());
        assertThat(found.values()).as("每个方法只应有一处").containsOnly(1);

        for (Map.Entry<String, List<String>> e : GATED_ENTRY_METHODS.entrySet()) {
            Path p = find(e.getKey());
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String method : e.getValue()) {
                assertThat(bodiesOf(lines, method))
                        .as(e.getKey() + "#" + method + " 是开工入口, 必须调用 shortageStartGate.check(")
                        .anyMatch(body -> body.contains("shortageStartGate.check("));
                // 1551-11: 待审批的强制插单 (批准那一刻才排入) 不能经任何开工入口先开工
                assertThat(bodiesOf(lines, method))
                        .as(e.getKey() + "#" + method + " 是开工入口, 必须调用 PendingApprovalStartGuard.check(")
                        .anyMatch(body -> body.contains("PendingApprovalStartGuard.check("));
            }
        }
    }

    private static String enclosingMethod(List<String> lines, int at) {
        for (int i = at; i >= 0; i--) {
            Matcher m = METHOD.matcher(lines.get(i));
            if (m.find()) {
                return m.group(1);
            }
        }
        return "?";
    }

    /** 该类里所有同名方法 (含重载) 的方法体文本, 按 4 空格缩进的方法边界切。 */
    private static List<String> bodiesOf(List<String> lines, String method) {
        List<String> bodies = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = METHOD.matcher(lines.get(i));
            if (m.find() && m.group(1).equals(method)) {
                StringBuilder b = new StringBuilder();
                for (int j = i; j < lines.size(); j++) {
                    b.append(lines.get(j)).append('\n');
                    if (j > i && lines.get(j).equals("    }")) {
                        break;
                    }
                }
                bodies.add(b.toString());
            }
        }
        return bodies;
    }

    private static Path find(String cls) throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(f -> f.getFileName().toString().equals(cls + ".java")).findFirst().orElseThrow();
        }
    }
}
