package com.cretas.aims.ai.grounding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闲聊 / 兜底回复的业务事实护栏: 判定规则本身 (接线在 {@code ChatNoFabricatedNumbersEntryContractTest})。
 * 阳性样本来自 2026-11-14 F006 实测 (「同比增长 8%」), 阴性样本是同两条闲聊链路上已有的合法回答。
 */
@DisplayName("ChatBusinessFactGuard — 闲聊不许有业务数字 / 业务事实")
class ChatBusinessFactGuardTest {

    private final ChatBusinessFactGuard guard = new ChatBusinessFactGuard();

    @ParameterizedTest(name = "业务问法: 「{0}」")
    @ValueSource(strings = {"这个月卖得怎么样", "最近生意咋样", "今天出了多少货", "这个月利润咋样",
            "本月产量多少", "库存够不够", "质检合格率怎么样", "上个月销售额同比涨了吗", "这周发了几单货"})
    void businessQuestionsAreNotChat(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isTrue();
    }

    @ParameterizedTest(name = "不是业务问法: 「{0}」")
    @ValueSource(strings = {"你好", "1", "冷藏温度多少合适", "怎么提高生产效率", "帮我通知质检员赵铁柱明天去复检",
            "今天天气怎么样", "订外卖怎么样", "谢谢"})
    void chatAndAdviceStayChat(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isFalse();
    }

    @ParameterizedTest(name = "拦: 「{0}」")
    @ValueSource(strings = {
            "这个月销售情况不错，同比增长 8%。",                  // 线上原样 (F006 2026-11-14 18:16)
            "本月营收约 ¥35,000，表现稳定。",
            "这个月大概卖了 1200 箱。",
            "本月销售额 12.5万元。",
            "环比上月多了 300。",
            "本月销售情况良好，较上月有所增长。"})            // 不带数字的业务事实
    void fabricatedBusinessFactsAreBlockedAndCounted(String reply) {
        ChatBusinessFactGuard.Result r = guard.check("t", reply);

        assertThat(r.decision()).isEqualTo(ChatBusinessFactGuard.Decision.BLOCKED);
        assertThat(r.text()).isEqualTo(ChatBusinessFactGuard.NEED_QUERY_REPLY);
        assertThat(r.rule()).isNotBlank();
        assertThat(guard.count("t", ChatBusinessFactGuard.Decision.BLOCKED)).isEqualTo(1);
    }

    @ParameterizedTest(name = "放行: 「{0}」")
    @ValueSource(strings = {
            "冷藏建议控制在 0-4℃。",                              // IntentExecutionOrchestratorNumberProvenanceTest 的常识回答
            "好的, 已记下 13912345678 。",                         // SseStreamingEgressRedactionTest 的电话 (标识符不是数)
            "库房湿度建议控制在 60% 以下。",
            "建议按 HACCP 的 7 个原则逐项检查。",
            "如果本月销量下滑，建议先排查渠道。",
            "您好！有什么可以帮您的吗？"})
    void commonSenseRepliesPass(String reply) {
        ChatBusinessFactGuard.Result r = guard.check("t", reply);

        assertThat(r.decision()).isEqualTo(ChatBusinessFactGuard.Decision.PASS);
        assertThat(r.text()).isEqualTo(reply);
        assertThat(guard.total("t")).as("判定确实跑了 (必然发生的读数)").isEqualTo(1);
    }

    @Test
    @DisplayName("固定回复自己过得了闸 (否则拦下后再过一次闸会变成别的东西)")
    void needQueryReplyPassesItself() {
        assertThat(guard.check("t", ChatBusinessFactGuard.NEED_QUERY_REPLY).decision())
                .isEqualTo(ChatBusinessFactGuard.Decision.PASS);
        assertThat(ChatBusinessFactGuard.NEED_QUERY_REPLY).doesNotContainPattern("\\d+(\\.\\d+)?\\s*[%％元]");
    }

    @Test
    @DisplayName("人设: 食品工厂助手, 明确不是古生物")
    void personaIsFoodFactoryNotPaleontology() {
        assertThat(ChatBusinessFactGuard.PERSONA).contains("食品工厂").contains("与古生物学")
                .doesNotContain("白垩纪AI Agent");
        assertThat(ChatBusinessFactGuard.NO_DATA_RULES).contains("不得给出任何业务数字或业务事实");
    }
}
