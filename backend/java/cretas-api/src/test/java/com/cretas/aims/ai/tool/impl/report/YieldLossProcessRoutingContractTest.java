package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.service.execution.PhraseShortcutEligibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BP 问题 28 (2026-11-18 复测): 「最近有哪些损耗异常」被判成质检, 答「质检不合格率 0%」。
 * 路由断言跑在真实短语表 (IntentKnowledgeBase.initDefaults), 守的是「损耗 / 出成率问法进出成率工具、不进质检」。
 */
class YieldLossProcessRoutingContractTest {

    private IntentKnowledgeBase kb;

    @BeforeEach
    void init() {
        kb = new IntentKnowledgeBase();
        kb.initDefaults();
    }

    private String route(String q) {
        return kb.matchPhrase(q, "FACTORY").orElse(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"最近有哪些损耗异常", "最近有哪些损耗异常？", "有哪些损耗异常", "损耗异常", "损耗异常的批次"})
    void lossAnomalyGoesToTheYieldTool_neverToQuality(String q) {
        String intent = route(q);
        assertEquals("REPORT_BATCH_YIELD_RANK", intent, q);
        assertFalse(intent.startsWith("QUALITY"), "损耗异常不得进质检意图: " + q);
    }

    /** BP 问题 28 复测: 「最近一个月有哪些损耗异常」admin 落到 REPORT_ANOMALY (答原料过期/设备维护), finance 落到澄清。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "最近一个月有哪些损耗异常", "近一个月有哪些损耗异常", "近30天有哪些损耗异常", "近 30 天有哪些损耗异常",
            "最近30天有哪些损耗异常", "这个月有哪些损耗异常", "本月有哪些损耗异常", "本月有哪些损耗异常？",
            "最近一个月 有哪些损耗异常", "近一个月，有哪些损耗异常?", "本月 有哪些 损耗异常 ？"})
    void periodQualifiedLossAnomalyGoesToTheYieldTool(String q) {
        assertEquals("REPORT_BATCH_YIELD_RANK", route(q), q);
        // 生产入口是短语快捷通道: 命中的短语拿掉后整句不能还剩「一个月 / 30天」这类字 (否则判带条件, 交给决策层 → 落到异常/澄清)
        java.util.Map.Entry<String, String> hit = kb.matchPhraseHit(q, "FACTORY").orElseThrow();
        assertTrue(PhraseShortcutEligibility.isUnconditionedSimple(q, hit.getKey(), hit.getValue()),
                q + " 命中短语「" + hit.getKey() + "」, 整句还剩条件字, 快捷通道会放弃");
    }

    @ParameterizedTest
    @ValueSource(strings = {"这个月出成率为什么掉了、掉在哪道工序", "这个月出成率为什么掉了", "各工序出成率"})
    void processYieldQuestionsGoToTheYieldTool(String q) {
        assertEquals("REPORT_BATCH_YIELD_RANK", route(q), q);
    }

    @Test
    void plainYieldHowIsItIsLeftToTheRecognitionLayer_notShortcutNorQuality() {
        // 「出成率怎么样」不做短语快捷 (DigitalCrewRecognitionFixesEntryContractTest 守: 这类问法要进识别层, 不被短语直达);
        // 工序名由工具从原话里认, 不靠短语
        for (String q : new String[]{"熟制杀菌最近出成率怎么样", "酱牛肉这个月的出成率怎么样？"}) {
            String intent = route(q);
            assertFalse(intent != null && intent.startsWith("QUALITY"), q + " -> " + intent);
            assertEquals(null, intent, q);
        }
    }

    @Test
    void negativeControls_qualityAndOldYieldRoutesUnchanged() {
        assertEquals("QUALITY_STATS", route("质检合格率"));
        assertEquals("REPORT_BATCH_YIELD_RANK", route("最近有哪些批次出成率异常"));
        assertEquals("REPORT_BATCH_YIELD_RANK", route("哪个批次出成率最低"));
    }
}
