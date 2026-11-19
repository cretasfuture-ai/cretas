package com.cretas.aims.config;

import com.cretas.aims.ai.catalog.FactoryIntentCatalog;
import com.cretas.aims.service.execution.PhraseShortcutEligibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BP 问题 34 复测: 「9 月 20 号发给上海连锁餐饮的酱卤牛肉，是哪批原料做的？」(带空格、中文逗号和问号) 在 sales_manager 上
 * 被决策模型判成出货列表 (漏 9-20 的 DLV-001), 换 finance_manager / factory_super_admin 就对 —— 角色进了 LLM 提示词。
 * 说法没有歧义, 走确定性短语: 日期 + 发给某客户 + 问哪批。两个入口共用同一个 matchPhraseHit。
 */
class CustomerDateTracePhraseContractTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "9 月 20 号发给上海连锁餐饮的酱卤牛肉，是哪批原料做的？",    // BP 原句: 空格 + 中文逗号 + 问号
            "9月20号发给上海连锁餐饮的酱卤牛肉，是哪批原料做的",
            "9月20号发给上海连锁餐饮的酱卤牛肉是哪批原料做的",
            "9 月 20 号发给上海连锁餐饮的酱卤牛肉是哪批原料做的",
            "9月20日卖给上海连锁餐饮的酱卤牛肉是哪一批"})
    void originalSentenceAndVariantsGoToCustomerDateTrace(String q) {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        var hit = kb.matchPhraseHit(q.toLowerCase().trim(), "FACTORY");
        assertThat(hit).as(q).isPresent();
        assertThat(hit.get().getValue()).as(q).isEqualTo("BATCH_TRACE_BY_CUSTOMER");
        assertThat(kb.matchPhrase(q.toLowerCase().trim(), "FACTORY")).contains("BATCH_TRACE_BY_CUSTOMER");
        assertThat(PhraseShortcutEligibility.isUnconditionedSimple(q, hit.get().getKey(), hit.get().getValue()))
                .as("短语快捷必须放行: " + q).isTrue();
        // 命中的码要在现行意图目录里解析得到 (47 的教训: 命中字符串对不等于生产接得上)
        assertThat(FactoryIntentCatalog.canonicalCodes()).contains(FactoryIntentCatalog.canonicalCode(hit.get().getValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9 月 20 号发给上海连锁餐饮的酱卤牛肉",             // 没问哪批: 是出货查询
            "发给上海连锁餐饮的酱卤牛肉是哪批原料做的",         // 没有日期
            "9月20号的酱卤牛肉是哪批原料做的",                  // 没有发给哪个客户
            "9月20号上海连锁餐饮的订单有哪些"})
    void ordinaryQuestionsAreNotCaptured(String q) {
        assertThat(IntentKnowledgeBase.isCustomerDateTrace(q)).as(q).isFalse();
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        var hit = kb.matchPhraseHit(q.toLowerCase().trim(), "FACTORY");
        assertThat(hit.isEmpty() || !IntentKnowledgeBase.CUSTOMER_DATE_TRACE_PHRASE.equals(hit.get().getKey())).as(q).isTrue();
    }
}
