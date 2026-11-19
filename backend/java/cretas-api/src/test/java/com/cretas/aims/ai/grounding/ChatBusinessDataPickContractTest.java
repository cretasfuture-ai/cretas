package com.cretas.aims.ai.grounding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-19 F006 财务数字专员 (BP4 问题 48): 「哪些客户有逾期应收？先催哪几张单，为什么？」句末的「为什么」含「什么」,
 * 护栏的咨询判定把整句当成概念咨询, 不算业务问法, 进了闲聊 / 本地 27B 自由回答, 没查数。
 * 点名要哪几个业务对象 (哪些客户 / 哪几张单 / 哪个产品) 的问法必须交给识别; 「哪些因素 / 怎么判断」这类咨询不能被一起放进来。
 */
@DisplayName("闲聊护栏: 点名要哪几个业务对象的问法是业务问法, 带「为什么」也一样; 哪些因素 / 怎么做仍是咨询")
class ChatBusinessDataPickContractTest {

    @ParameterizedTest(name = "业务问法「{0}」")
    @ValueSource(strings = {
            "哪些客户有逾期应收？先催哪几张单，为什么？",   // 线上原句 (带标点)
            "哪些客户有逾期应收，为什么",
            "应收先催哪几张单？为什么？",
            "哪个产品成本最高？为什么？",
            "哪家供应商的采购单还没入库，什么原因？",
            "哪批货质检不合格？为什么？"})
    void namedRecordQuestionIsABusinessQuestionEvenWithWhy(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isTrue();
    }

    @ParameterizedTest(name = "咨询「{0}」")
    @ValueSource(strings = {
            // 下面三句都带「哪些 / 哪个」+ 业务主语 + 概念词: 守的是排除词 (原因 / 方面 / 更), 去掉排除就会变成业务问法
            "回款慢一般是哪些原因，什么情况要先催？",
            "成本高通常是哪些方面的原因，什么最关键？",
            "哪个更好，大额应收还是逾期久的应收，有什么区别？",
            // 「怎么做 / 注意」类咨询: 点名对象也不放行
            "怎么判断应收先催哪几张单？",
            "哪些订单需要注意什么？",
            "为什么要做质检？",
            "质检要注意什么？"})
    void howToAndFactorQuestionsRemainConsultation(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isFalse();
    }
}
