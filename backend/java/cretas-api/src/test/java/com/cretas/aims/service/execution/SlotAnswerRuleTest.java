package com.cretas.aims.service.execution;

import com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 「这句话是不是在回答缺参问题」的规则表 —— 正反两面都钉住。 */
class SlotAnswerRuleTest {

    private static final List<RequiredParameter> TASK_ID = List.of(
            RequiredParameter.builder().name("taskId").label("工序任务ID (Long)").type("INTEGER").build());
    private static final List<RequiredParameter> PRODUCT = List.of(
            RequiredParameter.builder().name("productId").label("产品").type("TEXT").build());

    @ParameterizedTest
    @ValueSource(strings = {"1827", "1827号", "就是1827", "第1827号任务", " 1827 。", "是1827吧", "#1827"})
    void aBareNumberAnswersANumericSlot(String reply) {
        assertThat(SlotAnswerRule.looksLikeAnswer(reply, TASK_ID, t -> false)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"查一下1830号任务", "做完了，产量0", "上班打卡", "今天我的工序任务", "开始1830号工序任务",
            "1827和1828", "好了", ""})
    void aNewSentenceIsNotTakenAsTheAnswer(String reply) {
        assertThat(SlotAnswerRule.looksLikeAnswer(reply, TASK_ID, t -> false)).isFalse();
    }

    @Test
    void textSlotTakesAShortPhraseUnlessItIsAKnownCommand() {
        assertThat(SlotAnswerRule.looksLikeAnswer("牛腩排", PRODUCT, t -> false)).isTrue();
        assertThat(SlotAnswerRule.looksLikeAnswer("上班打卡", PRODUCT, "上班打卡"::equals)).isFalse();
        assertThat(SlotAnswerRule.looksLikeAnswer("这个产品的名字特别特别特别长超过十六个字了吧", PRODUCT, t -> false))
                .isFalse();
    }

    @Test
    void numbersOnlyKeepsDecimalsWhole() {
        assertThat(SlotAnswerRule.numbersOnly("做了95.5公斤")).contains(List.of("95.5"));
        assertThat(SlotAnswerRule.numbersOnly("查一下95.5")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MT-20260829-9687", "MT-20260829-9687-02", " mt-20260829-9687 ", "RB20261007001", "1827"})
    void aWholeCodeIsAnAnswerToATextSlotWhateverItsLength(String reply) {
        assertThat(SlotAnswerRule.codeToken(reply)).contains(reply.strip());
        // 即使「已知命令」判定恒为真也算回答: 编号不可能是一条命令
        assertThat(SlotAnswerRule.looksLikeAnswer(reply, PRODUCT, t -> true)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MT-20260829-9687 帮我领用", "查MT-20260829-9687", "yes", "ok", "上班打卡", "MT 20260829", "", "-1-"})
    void aSentenceOrABareWordIsNotACode(String reply) {
        assertThat(SlotAnswerRule.codeToken(reply)).isEmpty();
    }

    @Test
    void aCodeDoesNotAnswerANumericOnlyOrDateSlot() {
        // 数字型槽位只认「报数」; 日期槽位不能把任何带数字的东西当值
        assertThat(SlotAnswerRule.looksLikeAnswer("MT-20260829-9687", TASK_ID, t -> false)).isFalse();
        assertThat(SlotAnswerRule.isFreeTextType("DATE")).isFalse();
        assertThat(SlotAnswerRule.isFreeTextType("TEXT")).isTrue();
        assertThat(SlotAnswerRule.isFreeTextType(null)).isTrue();
    }

    @Test
    void noPendingSlotMeansNothingIsAnAnswer() {
        assertThat(SlotAnswerRule.looksLikeAnswer("1827", List.of(), t -> false)).isFalse();
    }
}
