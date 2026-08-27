package com.cretas.aims.ai.capability;

import static org.assertj.core.api.Assertions.assertThat;

import com.cretas.aims.ai.capability.UnsupportedCapabilityRegistry.UnsupportedCapability;
import com.cretas.aims.config.IntentKnowledgeBase;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「AI 不许承诺系统做不到的能力」这件事的闸。
 *
 * <p><b>为什么读运行时对象而不是读源码文本</b>：本仓记过多次「闸把自己的注释
 * 当成被测对象」。本次改动本身就留下了一条含 {@code CORRELATION_ANALYSIS} 的注释 ——
 * 任何 grep 式的闸都会在那条注释上误报或误判。所以这里
 * {@code new IntentKnowledgeBase()} + {@code initDefaults()}，
 * 量的是<b>真的那张表</b>，注释在这里根本不存在。</p>
 *
 * <p>{@link IntentKnowledgeBase} 是无依赖 POJO（{@code @ConfigurationProperties}），
 * 所以不需要 Spring 上下文、不需要连库，毫秒级。</p>
 */
class AiCapabilityHonestyGateTest {

    private static final String BASELINE_RESOURCE = "/ai/intent-capability-ratchet.txt";

    // ──────────────────────────────────────────────────────────────────
    // 取真表
    // ──────────────────────────────────────────────────────────────────

    /** phrase -> (mapName, intentCode)，三张表合并；同一 phrase 分表登记。 */
    private static List<PhraseMapping> allPhraseMappings() {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();

        List<PhraseMapping> out = new ArrayList<>();
        collect(out, "phraseToIntentMapping", kb.getPhraseToIntentMapping());
        collect(out, "restaurantPhraseMapping", kb.getRestaurantPhraseMapping());
        collect(out, "commonPhraseMapping", kb.getCommonPhraseMapping());
        return out;
    }

    private static void collect(List<PhraseMapping> out, String mapName, Map<String, String> map) {
        if (map == null) {
            return;
        }
        map.forEach((phrase, code) -> out.add(new PhraseMapping(mapName, phrase, code)));
    }

    private record PhraseMapping(String mapName, String phrase, String intentCode) {
    }

    // ──────────────────────────────────────────────────────────────────
    // 闸 A：问的是做不到的东西，就必须走明确拒答
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("闸A: 命名了已登记「做不到」能力的短语, 必须指向 UNSUPPORTED_CAPABILITY")
    void phrasesNamingAnUnsupportedCapabilityMustRouteToTheRefusalIntent() {
        List<String> violations = new ArrayList<>();

        for (PhraseMapping pm : allPhraseMappings()) {
            Optional<UnsupportedCapability> matched =
                    UnsupportedCapabilityRegistry.match(pm.phrase());
            if (matched.isEmpty()) {
                continue;
            }
            if (!UnsupportedCapabilityRegistry.INTENT_CODE.equals(pm.intentCode())) {
                violations.add(String.format(
                        "%s: \"%s\" -> %s (它问的是「%s」, 而这个意图不是明确拒答)",
                        pm.mapName(), pm.phrase(), pm.intentCode(), matched.get().askedFor()));
            }
        }

        assertThat(violations)
                .as("这些短语在向用户承诺一个系统做不到的能力。"
                        + "⛔ 不要靠删映射来消灭它们 —— 删了就掉进兜底话术, 用户更看不懂。"
                        + "改成指向 UnsupportedCapabilityRegistry.INTENT_CODE, "
                        + "并在 registry 里登记「为什么做不到 + 能给什么」。")
                .isEmpty();
    }

    @Test
    @DisplayName("闸A 阳性对照: 拿一个没登记的普通短语, 闸必须不响")
    void theGateDoesNotFireOnOrdinaryPhrases() {
        // 若这条也命中, 说明 match() 太宽 —— 那样闸 A 的「0 违例」毫无意义。
        assertThat(UnsupportedCapabilityRegistry.match("今天的质检合格率")).isEmpty();
        assertThat(UnsupportedCapabilityRegistry.match("相关单据")).isEmpty();
        assertThat(UnsupportedCapabilityRegistry.match("供应商延迟交货影响")).isEmpty();
        // 而这些必须命中, 否则闸 A 守的是空气。
        assertThat(UnsupportedCapabilityRegistry.match("SPC图表")).isPresent();
        assertThat(UnsupportedCapabilityRegistry.match("因果分析")).isPresent();
    }

    // ──────────────────────────────────────────────────────────────────
    // 闸 B / C：棘轮
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("闸B: 短语表里出现的每个意图码, 都必须在基线文件里被显式登记")
    void everyIntentCodeMustBeDeclaredInTheBaseline() {
        Baseline baseline = Baseline.load();
        Set<String> declared = new LinkedHashSet<>(baseline.noEvidence());
        declared.addAll(baseline.hasCapability());

        Set<String> undeclared = new TreeSet<>();
        for (PhraseMapping pm : allPhraseMappings()) {
            if (!declared.contains(pm.intentCode())) {
                undeclared.add(pm.intentCode());
            }
        }

        assertThat(undeclared)
                .as("新意图码必须在 %s 里表态: 放进 [HAS_CAPABILITY] 等于你声明"
                        + "「我查过 ai_intent_configs / Tool, 它有真实实现」; "
                        + "查不到就放 [NO_EVIDENCE] 并让棘轮计数上升 —— 而那会让闸C变红, "
                        + "逼你要么补实现要么改成明确拒答。", BASELINE_RESOURCE)
                .isEmpty();
    }

    @Test
    @DisplayName("闸C 棘轮: 指向「查不到能力证据」意图的短语数只许减少, 不许增加")
    void theCountOfPhrasesPointingAtUnbackedIntentsMustNotGrow() {
        Baseline baseline = Baseline.load();
        Set<String> unbacked = baseline.noEvidence();

        // 量的是**短语数**, 不是行数 —— 同一个问题被拆成几处 put 不该让读数虚高。
        Map<String, Set<String>> phrasesByCode = new TreeMap<>();
        for (PhraseMapping pm : allPhraseMappings()) {
            if (unbacked.contains(pm.intentCode())) {
                phrasesByCode.computeIfAbsent(pm.intentCode(), k -> new TreeSet<>())
                        .add(pm.phrase());
            }
        }
        int actual = phrasesByCode.values().stream().mapToInt(Set::size).sum();

        assertThat(actual)
                .as("指向无能力证据意图的短语数上升了。逐意图明细: %s%n"
                        + "⚠️ 这是棘轮, 不是硬闸: 存量 %d 条是历史包袱, 不要求你清零, "
                        + "但不许再长。", phrasesByCode, baseline.ratchetPhraseCount())
                .isLessThanOrEqualTo(baseline.ratchetPhraseCount());
    }

    @Test
    @DisplayName("闸C 自检: 基线文件真的被读到了, 且不是空的")
    void theBaselineFileIsActuallyLoaded() {
        // 「读不到就当成 0」会让闸C恒绿 —— 那正是本仓记过的「兜底把不知道翻译成 0」。
        Baseline baseline = Baseline.load();
        assertThat(baseline.noEvidence()).as("[NO_EVIDENCE] 段落为空 —— 基线没读到?").isNotEmpty();
        assertThat(baseline.hasCapability()).as("[HAS_CAPABILITY] 段落为空 —— 基线没读到?").isNotEmpty();
        assertThat(baseline.ratchetPhraseCount()).isGreaterThan(0);
    }

    // ──────────────────────────────────────────────────────────────────
    // 拒答措辞
    // ──────────────────────────────────────────────────────────────────

    /**
     * 面向厂长/店长的话里不许出现这些词。本仓 2026-08-13 记过:
     * 「决定系数」「未覆盖成本的部分」这类词等于没说。
     */
    private static final List<String> JARGON_BLACKLIST = List.of(
            "决定系数", "置信区间", "显著性", "标准差", "方差", "变异系数",
            "回归", "拟合", "残差", "分位数", "正态", "样本量", "P值", "p值",
            "未覆盖成本的部分", "口径", "维度", "resolver", "fallback");

    @Test
    @DisplayName("拒答措辞不许带黑话")
    void refusalTextContainsNoJargon() {
        for (UnsupportedCapability capability : UnsupportedCapabilityRegistry.all()) {
            String answer = UnsupportedCapabilityRegistry.answerFor(capability);
            for (String jargon : JARGON_BLACKLIST) {
                assertThat(answer)
                        .as("能力 %s 的拒答话术里出现黑话「%s」—— 用户看不懂等于没答。原文:%n%s",
                                capability.id(), jargon, answer)
                        .doesNotContain(jargon);
            }
        }
    }

    @Test
    @DisplayName("拒答必须是三段: 做不到 / 为什么 / 能给什么")
    void refusalAlwaysOffersAnAlternative() {
        assertThat(UnsupportedCapabilityRegistry.all()).isNotEmpty();
        for (UnsupportedCapability capability : UnsupportedCapabilityRegistry.all()) {
            String answer = UnsupportedCapabilityRegistry.answerFor(capability);
            assertThat(answer).as("%s 少了「做不了」", capability.id()).contains("做不了");
            assertThat(answer).as("%s 少了「为什么」", capability.id()).contains("为什么");
            assertThat(answer)
                    .as("%s 少了替代方案 —— 只说做不了而不说能给什么, 和兜底话术一样没用",
                            capability.id())
                    .contains("能给你的是");
            assertThat(capability.instead())
                    .as("%s 的替代方案列表是空的", capability.id())
                    .isNotEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 基线文件解析
    // ──────────────────────────────────────────────────────────────────

    private record Baseline(Set<String> noEvidence, Set<String> hasCapability, int ratchetPhraseCount) {

        static Baseline load() {
            String text;
            try (InputStream in = AiCapabilityHonestyGateTest.class
                    .getResourceAsStream(BASELINE_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "基线文件不存在: " + BASELINE_RESOURCE + " —— 闸不能在读不到基线时静默通过");
                }
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("基线文件读取失败: " + BASELINE_RESOURCE, e);
            }

            Map<String, Set<String>> sections = new LinkedHashMap<>();
            sections.put("NO_EVIDENCE", new LinkedHashSet<>());
            sections.put("HAS_CAPABILITY", new LinkedHashSet<>());
            String current = null;
            int count = -1;

            for (String raw : text.split("\\R")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[RATCHET_PHRASE_COUNT]")) {
                    count = Integer.parseInt(line.substring("[RATCHET_PHRASE_COUNT]".length()).trim());
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    current = line.substring(1, line.length() - 1);
                    continue;
                }
                if (current == null || !sections.containsKey(current)) {
                    continue;
                }
                int hash = line.indexOf('#');
                String code = (hash >= 0 ? line.substring(0, hash) : line).trim();
                if (!code.isEmpty()) {
                    sections.get(current).add(code);
                }
            }

            if (count < 0) {
                throw new IllegalStateException("基线文件缺少 [RATCHET_PHRASE_COUNT]");
            }
            return new Baseline(sections.get("NO_EVIDENCE"), sections.get("HAS_CAPABILITY"), count);
        }
    }
}
