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
            "本月产量多少", "库存够不够", "质检合格率怎么样", "上个月销售额同比涨了吗", "这周发了几单货",
            "本月销量如何", "库存如何？", "这个月卖了多少"})
    void businessQuestionsAreNotChat(String input) {
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).isTrue();
    }

    @ParameterizedTest(name = "不是业务问法: 「{0}」")
    @ValueSource(strings = {"你好", "1", "冷藏温度多少合适", "怎么提高生产效率", "帮我通知质检员赵铁柱明天去复检",
            "今天天气怎么样", "订外卖怎么样", "谢谢",
            // 修复项 4: 带问号 / 吗 / 呢的咨询句不是业务问法
            "怎么降低成本？", "如何提高合格率呢", "怎么创建订单？", "冷库库存温度多少合适", "怎样提高产量吗",
            "质检要注意什么？", "有什么办法减少损耗？", "你建议怎么管库存？",
            // 低危: 「卖」只认业务用法
            "你会卖萌吗"})
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
            "本月销售情况良好，较上月有所增长。",            // 不带数字的业务事实
            // 修复项 1: 数字 + 万/亿, 不带「元」
            "本月营收约35万。", "销售额120万左右。", "今年营业额超过2亿。",
            "营收达到35万以上。", "销售额在120万到150万之间。",   // 后面不是裸数字, 只有万/亿规则能拦
            // 修复项 2: 业务主语句里不带单位 / 带「个」「多」的数字
            "订单35个。", "本月产量达到1200。", "库存还有200多。",
            // 修复项 3: 无数字的趋势断言, 同比 / 比去年算时间锚
            "销售额比去年明显增长。", "销量同比上升。", "出货量较上月下降。"})
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
            "冷藏0-4℃。", "HACCP有7个原则。",                     // 修复项 2 的常识对照
            "想让销量同比增长，可以先做客户分层。",                 // 愿望句不是陈述事实
            "新员工培训一般需要3个月。", "订单管理系统支持导出报表。", "产量统计请在生产报表里看。",
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

    // ---- 修复项 5: 用户原话里的数字放行; 咨询被拦时不回销售菜单 ----

    @Test
    @DisplayName("用户原话里写过的数字, 回复复述时放行; 没写过的照拦")
    void numbersTheUserTypedThemselvesAreNotFabrication() {
        String reply = "订单量 35 个不算多，建议先按交期排产。";
        assertThat(guard.check("t", null, reply, true).decision()).isEqualTo(ChatBusinessFactGuard.Decision.BLOCKED);
        ChatBusinessFactGuard.Result echoed = guard.check("t", "我们订单35个，怎么排产", reply, true);
        assertThat(echoed.decision()).isEqualTo(ChatBusinessFactGuard.Decision.PASS);
        assertThat(echoed.text()).isEqualTo(reply);
        assertThat(guard.check("t", "我们订单35个，怎么排产", "订单量 36 个不算多，建议先按交期排产。", true).decision())
                .as("阴性对照: 用户没写过 36").isEqualTo(ChatBusinessFactGuard.Decision.BLOCKED);
    }

    @Test
    @DisplayName("咨询回复被拦: 去掉含数字的句子、保留通用建议、说明要先查, 不回销售菜单")
    void blockedAdviceKeepsGeneralAdviceAndHasNoSalesMenu() {
        String reply = "降低成本可以从三方面入手：优化采购批量，减少损耗，提高设备利用率。"
                + "您这个月成本同比增长 8%，需要重点关注。";
        ChatBusinessFactGuard.Result r = guard.check("t", "怎么降低成本？", reply, true);

        assertThat(r.decision()).isEqualTo(ChatBusinessFactGuard.Decision.BLOCKED);
        assertThat(r.text()).contains("优化采购批量").contains("涉及本厂").contains("先查询真实数据")
                .doesNotContain("8%").doesNotContain("同比")
                .doesNotContain("本月销售 / 经营概况").doesNotContain("出货记录");
        assertThat(guard.check("t", r.text()).decision()).as("改写后的文本自己过得了闸")
                .isEqualTo(ChatBusinessFactGuard.Decision.PASS);
    }

    @Test
    @DisplayName("咨询回复整段都是编的数: 只给「涉及本厂数据需先查」提示, 仍不带销售菜单")
    void blockedAdviceWithNothingLeftIsJustTheNotice() {
        ChatBusinessFactGuard.Result r = guard.check("t", "怎么提高销量？", "本月销量 1200 箱。", true);
        assertThat(r.decision()).isEqualTo(ChatBusinessFactGuard.Decision.BLOCKED);
        assertThat(r.text()).isEqualTo(ChatBusinessFactGuard.ADVICE_DATA_NOTICE);
        assertThat(r.text()).doesNotContain("出货记录");
        // 阴性对照: 闲聊口径仍是固定菜单
        assertThat(guard.check("t", "你好", "本月销量 1200 箱。", false).text())
                .isEqualTo(ChatBusinessFactGuard.NEED_QUERY_REPLY);
    }

    @Test
    @DisplayName("拦截日志不写回复原文, 只写规则类别和长度")
    void blockedLogDoesNotContainTheReplyText() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ChatBusinessFactGuard.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            guard.check("t", "客户张三的订单 35 个。");
            assertThat(appender.list).as("必然发生的读数: 确实打了拦截日志").hasSize(1);
            String line = appender.list.get(0).getFormattedMessage();
            assertThat(line).contains("rule=business-quantity").contains("replyLength=")
                    .doesNotContain("张三").doesNotContain("35");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
