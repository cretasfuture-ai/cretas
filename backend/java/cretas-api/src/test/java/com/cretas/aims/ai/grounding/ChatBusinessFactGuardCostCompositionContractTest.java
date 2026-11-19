package com.cretas.aims.ai.grounding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-19 F006「酱卤牛肉成本怎么构成」答「需要先查一下真实数据，请您说明想查哪一项」: 护栏的咨询判定把
 * 「怎么 + 构成」当成「怎么做」, 句子不算业务问法, 进了闲聊兜底。成本构成 / 成本怎么算是在问业务情况, 必须交给识别;
 * 「怎么降低成本」这类真咨询不能被一起放进来。
 */
@DisplayName("闲聊护栏: 成本构成 / 成本怎么算是业务问法, 怎么降低成本仍是咨询")
class ChatBusinessFactGuardCostCompositionContractTest {

    @ParameterizedTest(name = "业务问法「{0}」")
    @ValueSource(strings = {
            "酱卤牛肉成本怎么构成",
            "酱卤牛肉的成本是怎么构成的？",
            "演示·酱卤牛肉250g 的成本是怎么构成的？",
            "酱卤牛肉成本如何构成",
            "酱卤牛肉成本是怎么算的",
            "酱卤牛肉成本怎么计算",
            "酱卤牛肉的成本构成",
            "成本由哪几块组成"})
    void costCompositionIsABusinessQuestion(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isTrue();
    }

    @ParameterizedTest(name = "咨询「{0}」")
    @ValueSource(strings = {
            "酱卤牛肉成本怎么降低",
            "怎么控制成本",
            "如何优化成本构成",
            "怎么提高合格率"})
    void howToQuestionsRemainConsultation(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isFalse();
    }
}
