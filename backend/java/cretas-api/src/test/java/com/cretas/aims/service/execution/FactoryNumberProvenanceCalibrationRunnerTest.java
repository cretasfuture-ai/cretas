package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.grounding.FactoryNumberProvenance;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 数字出处闸的<b>校准仪器</b> (不是回归测试): 读一份「真实工具产出 + 模型正文」的样本集,
 * 逐条报出每个数的判定和命中率分布 —— 近 0% 或近 100% 都先当仪器问题查。
 *
 * <p>样本集含工厂真实经营数据, 不进仓库; 只在显式给出路径时运行:
 * <pre>
 *   mvn -o test -Dtest=FactoryNumberProvenanceCalibrationRunnerTest \
 *       -Dprovenance.calibration.file=.../fixtures.json \
 *       -Dprovenance.calibration.out=.../calibration-report.txt
 * </pre>
 *
 * <p>每条样本同时跑两个对照, 让分布本身可判定:
 * <ul>
 *   <li><b>阳性对照 (必然有出处)</b>: 用同一份工具产出拼出的确定性摘要 —— 它只含工具数,
 *       命中率不是 100% 就是仪器 (遮挡 / 归一) 错了。</li>
 *   <li><b>阴性对照 (必然无出处)</b>: 在模型正文末尾注入两个编造的数 —— 闸若没报出这两个,
 *       就是在这条样本上恒绿。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "provenance.calibration.file", matches = ".+")
class FactoryNumberProvenanceCalibrationRunnerTest {

    private static final String INJECTED = "\n预计下月营收将增长 37.4%，可节省 12,860 元。";

    @Test
    void reportDistribution() throws Exception {
        Path in = Path.of(System.getProperty("provenance.calibration.file"));
        Path out = Path.of(System.getProperty("provenance.calibration.out",
                in.resolveSibling("calibration-report.txt").toString()));
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> cases = mapper.readValue(
                Files.readString(in, StandardCharsets.UTF_8), new TypeReference<>() { });

        FactoryNumberProvenance gate = new FactoryNumberProvenance();
        WorkdeskOutputSummarizer summarizer = new WorkdeskOutputSummarizer(mock(DashScopeClient.class), mapper);

        List<Double> rates = new ArrayList<>();
        int errors = 0;
        int positiveControls = 0;
        int positiveControlFailures = 0;
        int negativeControls = 0;
        int negativeControlMisses = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            for (Map<String, Object> c : cases) {
                String id = String.valueOf(c.get("id"));
                if (c.get("error") != null || c.get("answer") == null) {
                    errors++;
                    w.println("CASE " + id + " ERROR " + c.get("error"));
                    continue;
                }
                String answer = (String) c.get("answer");
                Object[] sources = ((List<?>) c.get("sources")).toArray();
                FactoryNumberProvenance.Audit audit = gate.audit(answer, sources);
                Double rate = audit.hitRate();
                if (rate != null) rates.add(rate);
                w.printf("CASE %s site=%s prompt=%s counted=%d unsourced=%d hitRate=%s pool=%d%n",
                        id, c.get("site"), c.get("prompt"), audit.counted(), audit.unsourced().size(),
                        rate == null ? "n/a" : String.format("%.3f", rate), audit.sourcePoolSize());
                for (FactoryNumberProvenance.Verdict v : audit.verdicts()) {
                    int s = Math.max(0, v.token().start() - 18);
                    int e = Math.min(answer.length(), v.token().end() + 12);
                    // 别把 emoji 的代理对切成两半: 半个代理对让 UTF-8 编码器抛错, 而 PrintWriter
                    // 吞掉异常后反复重刷缓冲区 —— 第一版报告就这样膨胀成 40MB 的重复内容
                    if (s > 0 && Character.isLowSurrogate(answer.charAt(s))) s--;
                    if (e < answer.length() && Character.isLowSurrogate(answer.charAt(e))) e++;
                    w.printf("   %s %-12s rule=%-14s ctx=[%s]%n", v.sourced() ? "ok " : "BAD",
                            v.token().raw(), v.rule(), answer.substring(s, e).replace('\n', ' '));
                }

                // 阴性对照
                negativeControls++;
                FactoryNumberProvenance.Audit injected = gate.audit(answer + INJECTED, sources);
                long caught = injected.unsourced().stream()
                        .filter(v -> v.token().start() >= answer.length()).count();
                if (caught < 2) {
                    negativeControlMisses++;
                    w.println("   NEGATIVE-CONTROL-MISS caught=" + caught);
                }

                // 阳性对照 (只有工作台摘要这一处有确定性模板)
                Object fallbackPayload = c.get("fallbackPayload");
                if (fallbackPayload instanceof Map<?, ?>) {
                    IntentExecuteResponse resp = new IntentExecuteResponse();
                    resp.setIntentName("工作台");
                    String det = summarizer.buildDeterministicFallback(fallbackPayload, resp,
                            WorkdeskOutputSummarizer.PROVENANCE_FALLBACK_HEADER);
                    FactoryNumberProvenance.Audit detAudit = gate.audit(det, sources);
                    positiveControls++;
                    if (!detAudit.unsourced().isEmpty()) {
                        positiveControlFailures++;
                        w.println("   POSITIVE-CONTROL-FAIL " + detAudit.unsourced().stream()
                                .map(v -> v.token().raw()).toList());
                    }
                }
            }
            Collections.sort(rates);
            w.println();
            w.printf("SUMMARY cases=%d errors=%d answersWithNumbers=%d%n", cases.size(), errors, rates.size());
            if (!rates.isEmpty()) {
                double median = rates.size() % 2 == 1 ? rates.get(rates.size() / 2)
                        : (rates.get(rates.size() / 2 - 1) + rates.get(rates.size() / 2)) / 2;
                long perfect = rates.stream().filter(r -> r >= 1.0).count();
                long zero = rates.stream().filter(r -> r <= 0.0).count();
                w.printf("SUMMARY hitRate min=%.3f median=%.3f max=%.3f all-sourced=%d/%d nothing-sourced=%d/%d%n",
                        rates.get(0), median, rates.get(rates.size() - 1), perfect, rates.size(), zero, rates.size());
            }
            w.printf("SUMMARY negativeControls=%d misses=%d positiveControls=%d failures=%d%n",
                    negativeControls, negativeControlMisses, positiveControls, positiveControlFailures);
            // PrintWriter 吞掉 IO 异常: 不查这一位, 写坏了的报告也是「测试通过」
            assertThat(w.checkError()).as("report write failed").isFalse();
        }
        assertThat(cases).isNotEmpty();
        assertThat(rates).as("没有任何一条正文里有可判定的数 —— 仪器没接上").isNotEmpty();
    }
}
