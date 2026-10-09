package com.cretas.aims.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 放行入口 (#977/#979: 精确短语命中就跳过决策层) 只认够具体的短语。
 * 2026-10-09 意图重测: 质检员「登记质检不良」被 2 字泛词「质检」命中成 QUALITY_CHECK_QUERY 并跳过决策层,
 * 决策层本来选对 QUALITY_DEFECT_RECORD。
 */
class SpecificPhraseShortcutTest {

    private static IntentKnowledgeBase kb;

    @BeforeAll
    static void init() {
        kb = new IntentKnowledgeBase();
        kb.initDefaults();
    }

    @Test
    @DisplayName("泛词「质检」只配做候选: 普通入口照旧命中 (对照), 放行入口不命中")
    void genericTwoCharPhraseDoesNotShortcut() {
        assertThat(kb.matchPhrase("登记质检不良", "FACTORY")).contains("QUALITY_CHECK_QUERY");   // 对照: 普通入口行为不变
        assertThat(kb.matchSpecificPhrase("登记质检不良", "FACTORY")).isEmpty();
    }

    @Test
    @DisplayName("够具体的短语照常放行: 质检记录 (G1) 与产量比较 (G2)")
    void specificPhrasesStillShortcut() {
        assertThat(kb.matchSpecificPhrase("最近的质检记录", "FACTORY")).contains("QUALITY_CHECK_QUERY");
        assertThat(kb.matchSpecificPhrase("这周产量环比怎么样", "FACTORY")).contains("REPORT_TRENDS");
        assertThat(kb.matchSpecificPhrase("这个月产量同比", "FACTORY")).contains("REPORT_TRENDS");
    }
}
