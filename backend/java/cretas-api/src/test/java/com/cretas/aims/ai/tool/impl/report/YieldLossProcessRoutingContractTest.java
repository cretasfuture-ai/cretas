package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.config.IntentKnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @ParameterizedTest
    @ValueSource(strings = {"熟制杀菌最近出成率怎么样", "这个月出成率为什么掉了、掉在哪道工序", "这个月出成率为什么掉了", "各工序出成率"})
    void processYieldQuestionsGoToTheYieldTool(String q) {
        assertEquals("REPORT_BATCH_YIELD_RANK", route(q), q);
    }

    @Test
    void negativeControls_qualityAndOldYieldRoutesUnchanged() {
        assertEquals("QUALITY_STATS", route("质检合格率"));
        assertEquals("REPORT_BATCH_YIELD_RANK", route("最近有哪些批次出成率异常"));
        assertEquals("REPORT_BATCH_YIELD_RANK", route("哪个批次出成率最低"));
    }
}
