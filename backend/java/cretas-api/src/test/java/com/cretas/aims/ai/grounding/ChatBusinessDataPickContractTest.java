package com.cretas.aims.ai.grounding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-19 F006 财务数字专员 (BP4 问题 48): 「哪些客户有逾期应收？先催哪几张单，为什么？」句末的「为什么」含「什么」,
 * 护栏的咨询判定把整句当成概念咨询, 不算业务问法, 进了闲聊 / 本地 27B 自由回答, 没查数。
 * 只有「点名对象 + 业务主语 + 问原因」时剥掉问原因的词; 其余概念词 / 怎么做 / 建议照常否决, 没有业务主语不放行。
 */
@DisplayName("闲聊护栏: 点名要哪几个业务对象 + 问原因是业务问法; 概念 / 规范 / 怎么做类咨询不放行")
class ChatBusinessDataPickContractTest {

    @ParameterizedTest(name = "业务问法「{0}」")
    @ValueSource(strings = {
            "哪些客户有逾期应收？先催哪几张单，为什么？",   // 线上原句 (带标点)
            "哪些客户有逾期应收，为什么",
            "应收先催哪几张单？为什么？",
            "哪个产品成本最高？为什么？",
            "哪家供应商的采购单还没入库，什么原因？",
            "哪批货质检不合格？为什么？",
            // 终审补的量词: 条 / 款 / 一批 / 天
            "哪条产线不良率最高，为什么？",
            "哪款产品毛利最低，什么原因？",
            "哪一批原料质检不合格，为什么？",
            "哪天出货最多，什么原因？"})
    void namedRecordQuestionWithWhyIsABusinessQuestion(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isTrue();
    }

    @ParameterizedTest(name = "咨询「{0}」")
    @ValueSource(strings = {
            // 排除词 (方面 / 方法 / 情况): 去掉排除就会被当成点名对象, 剥掉「为什么」后放行
            "成本高是哪些方面的问题，为什么？",
            "哪种方法降低成本最有效，为什么？",
            "哪些情况下需要做出库复核，为什么？",
            // 剥掉问原因词后仍有概念 / 规范词: 照常否决
            "冷库存储哪些产品温度要求最高，什么原因？",
            "哪些食品添加剂在生产中是禁止的，为什么？",
            // 没有业务主语: 点名对象 + 为什么也不放行
            "哪个供应商比较靠谱，为什么",
            "哪种包装更好",
            // 终审 20 句里修复前判对、第一版判错的概念问法 (不带问原因词, 判定与改前相同)
            "质检员的职责包括哪些？",
            "毛利和净利是什么区别，哪些费用算成本？",
            "成本核算分哪几类，有什么区别？",
            "订单管理在哪里，哪个菜单能找到？",
            "生产车间温度一般控制在哪个范围？",
            "怎么判断应收先催哪几张单？",
            "哪些订单需要注意什么？",
            "为什么要做质检？",
            "质检要注意什么？"})
    void conceptAndHowToQuestionsRemainConsultation(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isFalse();
    }
}
