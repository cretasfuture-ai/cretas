package com.cretas.aims.ai.grounding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-17 F006「酱牛肉这个月的出成率怎么样？」被当闲聊交给本地模型: 出成率 / 成品率 / 得率 / 出品率不在业务问法里。
 * #1813 终审 L3: 「得率」是子串, 「记得率先…」这类句子不能被它拉成业务问法。
 */
@DisplayName("业务问法: 出成率一类算业务, 「记得率先」不算")
class ChatBusinessYieldWordContractTest {

    @ParameterizedTest(name = "「{0}」")
    @ValueSource(strings = {"酱牛肉这个月的出成率怎么样？", "上周成品率是多少", "牛肉的得率怎么样", "这批出品率多少"})
    void yieldQuestionsAreBusinessQuestions(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isTrue();
    }

    @ParameterizedTest(name = "「{0}」")
    @ValueSource(strings = {"记得率先把手洗干净怎么样", "我觉得率先做这个怎么样"})
    @DisplayName("阴性对照: 「得率」只是相邻两个字时不算")
    void incidentalCharactersAreNotAYieldQuestion(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isFalse();
    }
}
