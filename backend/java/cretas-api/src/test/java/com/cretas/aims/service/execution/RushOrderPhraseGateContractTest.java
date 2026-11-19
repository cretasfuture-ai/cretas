package com.cretas.aims.service.execution;

import com.cretas.aims.config.IntentKnowledgeBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BP 原话「周五要交2吨酱牛肉急单，原料够不够，产线排不排得开？」命中 SCHEDULING_RUSH_ORDER_ANALYSIS 短语,
 * 却被复合句闸 (residual 超 1 字) 拦下落到澄清 (2026-11-18 prod)。流式 SseStreamingService 与普通入口
 * IntentRecognitionPipelineServiceImpl 都调 isUnconditionedSimple, 同一处修两入口同时生效。
 */
class RushOrderPhraseGateContractTest {

    private static final String BP = "周五要交2吨酱牛肉急单，原料够不够，产线排不排得开？";

    @Test
    void bpSentenceIsAdmittedForRushOrderIntent() {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        var hit = kb.matchPhraseHit(BP, "FACTORY");
        assertThat(hit).isPresent();
        assertThat(hit.get().getValue()).isEqualTo("SCHEDULING_RUSH_ORDER_ANALYSIS");
        assertThat(PhraseShortcutEligibility.isUnconditionedSimple(BP, hit.get().getKey(), hit.get().getValue()))
                .isTrue();
    }

    @Test
    void allowanceIsOnlyForTheRushIntent_otherConditionedSentencesStillBlocked() {
        // 阴性对照: 同一句挂在别的意图上仍被拦; 既有被拦的带条件句 (销售额复合问 / 库存带物料) 仍被拦
        assertThat(PhraseShortcutEligibility.isUnconditionedSimple(BP, "原料够不够", "INVENTORY_QUERY")).isFalse();
        assertThat(PhraseShortcutEligibility.isUnconditionedSimple(
                "11月1日到18日销售额是多少，哪个客户买得最多", "销售额是多少", "REPORT_SALES_MARGIN_SUMMARY")).isFalse();
        assertThat(PhraseShortcutEligibility.isUnconditionedSimple(
                "现在牛前腱还有多少库存", "还有多少库存", "INVENTORY_QUERY")).isFalse();
    }
}
