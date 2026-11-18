package com.cretas.aims.ai.tool.impl.scheduling;

import com.cretas.aims.ai.tool.impl.scheduling.RushOrderImpactTool.SlotOutcome;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.entity.InsertSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1814 终审补测: 多候选时段怎么挑 (L1)、排不开 (L1)、计数与明细对不上 (M2)、链式口径 (L5)、
 * 口语中文数字 (L3)、「产线排不排得开」不再单独拉进急单 (L2)。
 */
class RushOrderImpactPickAndParseContractTest {

    private static final String RUSH = "SCHEDULING_RUSH_ORDER_ANALYSIS";

    private static InsertSlot slot(String id) {
        return InsertSlot.builder().id(id).factoryId("F006").productionLineId("QA-LINE-" + id)
                .productionLineName("QA-" + id).startTime(LocalDateTime.of(2026, 11, 19, 8, 0))
                .endTime(LocalDateTime.of(2026, 11, 19, 14, 0)).status("available").build();
    }

    private static Map<String, Object> resources(boolean capVerified, boolean capOk, boolean eqVerified, boolean eqOk) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("capacityVerified", capVerified);
        r.put("capacityAvailable", capOk);
        r.put("equipmentVerified", eqVerified);
        r.put("equipmentAvailable", eqOk);
        r.put("workerStatus", "READY");
        r.put("requiredWorkers", 6);
        r.put("availableWorkers", 6);
        return r;
    }

    private static SlotOutcome outcome(String id, boolean fits, Integer affected, Integer maxDelay, List<?> plans) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("impactLevel", affected == null || affected == 0 ? "none" : "medium");
        impact.put("resourceStatus", fits ? resources(true, true, true, true) : resources(true, false, true, true));
        impact.put("affectedPlanCount", affected);
        if (maxDelay != null) impact.put("chainImpactDetails", Map.of("maxDelayMinutes", maxDelay));
        impact.put("impactedPlans", plans);
        return new SlotOutcome(slot(id), impact, null);
    }

    // ------------------------------------------------------------------ L1 pickBest

    @Test
    @DisplayName("L1 多候选: 排得下优先, 其次挪单少, 再其次最大延误小; 没算出来的不选")
    void pickBestOrdersByFitThenAffectedThenDelay() {
        SlotOutcome notFitButFree = outcome("NOFIT", false, 0, 0, List.of());
        SlotOutcome fitsTwoMoves = outcome("TWO", true, 2, 60, List.of());
        SlotOutcome fitsOneLongDelay = outcome("ONE-LONG", true, 1, 300, List.of());
        SlotOutcome fitsOneShortDelay = outcome("ONE-SHORT", true, 1, 100, List.of());
        SlotOutcome failed = new SlotOutcome(slot("ERR"), null, "boom");

        SlotOutcome best = RushOrderImpactTool.pickBest(
                List.of(failed, notFitButFree, fitsTwoMoves, fitsOneLongDelay, fitsOneShortDelay));
        assertThat(best.slot().getId()).isEqualTo("ONE-SHORT");

        // 排得下的优先于「排不下但不挪单」
        assertThat(RushOrderImpactTool.pickBest(List.of(notFitButFree, fitsTwoMoves)).slot().getId()).isEqualTo("TWO");
        // 都排得下: 挪单少的优先于延误小的
        assertThat(RushOrderImpactTool.pickBest(List.of(fitsTwoMoves, fitsOneLongDelay)).slot().getId())
                .isEqualTo("ONE-LONG");
        // 全都没算出来 → null (调用方说算不出来)
        assertThat(RushOrderImpactTool.pickBest(List.of(failed))).isNull();
    }

    @Test
    @DisplayName("L1 只剩排不下的时段: 第 2 项说「排不开」, 状态 DOES_NOT_FIT; 产能未核对 → UNVERIFIED")
    void lineSectionReportsDoesNotFitAndUnverified() {
        SlotOutcome noFit = outcome("NOFIT", false, 0, 0, List.of());
        Map<String, Object> s = RushOrderImpactTool.lineSection(noFit, List.of(noFit), 1);
        assertThat(s.get("status")).isEqualTo("DOES_NOT_FIT");
        assertThat((String) s.get("text")).startsWith("排不开");

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("resourceStatus", resources(false, false, true, true));
        SlotOutcome unchecked = new SlotOutcome(slot("UNCHECKED"), impact, null);
        Map<String, Object> u = RushOrderImpactTool.lineSection(unchecked, List.of(unchecked), 1);
        assertThat(u.get("status")).isEqualTo("UNVERIFIED");
        assertThat((String) u.get("text")).startsWith("产线算不出来").doesNotContain("排得开。");
    }

    // ------------------------------------------------------------------ M2 / L5 挪单

    @Test
    @DisplayName("M2 计数 > 0 但明细为空 → UNVERIFIED「缺受影响计划明细」, ⛔ 不说「不用挪」; 计数 0 才 NONE")
    void movedSectionCountWithoutDetailsIsUnverified() {
        Map<String, Object> mismatch = RushOrderImpactTool.movedSection(outcome("X", true, 2, 120, List.of()));
        assertThat(mismatch.get("status")).isEqualTo("UNVERIFIED");
        assertThat((String) mismatch.get("text")).startsWith("算不出来，缺受影响计划明细").contains("2 张")
                .doesNotContain("不用挪");

        Map<String, Object> none = RushOrderImpactTool.movedSection(outcome("Y", true, 0, 0, List.of()));
        assertThat(none.get("status")).isEqualTo("NONE");
        assertThat((String) none.get("text")).startsWith("不用挪原单");
    }

    @Test
    @DisplayName("L5 挪单依据写明「全厂按时间重叠, 未按产线区分」")
    void movedSectionBasisSaysChainIsNotPerLine() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planNumber", "QA-PP-A");
        plan.put("delayMinutes", 60);
        Map<String, Object> moves = RushOrderImpactTool.movedSection(outcome("Z", true, 1, 60, List.of(plan)));
        assertThat(moves.get("status")).isEqualTo("MOVES");
        assertThat((String) moves.get("basis")).contains("未按产线区分");
        Map<String, Object> none = RushOrderImpactTool.movedSection(outcome("Y", true, 0, 0, List.of()));
        assertThat((String) none.get("basis")).contains("未按产线区分");
    }

    // ------------------------------------------------------------------ L3 中文数字

    @Test
    @DisplayName("L3 口语中文数字: 两百五 = 250, 一千二 = 1200, 三百零五 = 305, 一千 = 1000, 十二 = 12")
    void spokenChineseNumbers() {
        assertThat(RushOrderRequestParser.parseNumber("两百五")).isEqualByComparingTo("250");
        assertThat(RushOrderRequestParser.parseNumber("两百五十")).isEqualByComparingTo("250");
        assertThat(RushOrderRequestParser.parseNumber("三百零五")).isEqualByComparingTo("305");
        assertThat(RushOrderRequestParser.parseNumber("一千二")).isEqualByComparingTo("1200");
        assertThat(RushOrderRequestParser.parseNumber("一千二百")).isEqualByComparingTo("1200");
        assertThat(RushOrderRequestParser.parseNumber("一千")).isEqualByComparingTo("1000");
        assertThat(RushOrderRequestParser.parseNumber("十二")).isEqualByComparingTo("12");
        assertThat(RushOrderRequestParser.parseNumber("二十")).isEqualByComparingTo("20");
        assertThat(RushOrderRequestParser.quantityFromText("插一单两百五公斤卤鸭能不能排"))
                .hasValueSatisfying(q -> {
                    assertThat(q.value()).isEqualByComparingTo(new BigDecimal("250"));
                    assertThat(q.unit()).isEqualTo("公斤");
                });
    }

    // ------------------------------------------------------------------ L2 短语

    @Test
    @DisplayName("L2 普通排产问「明天产线排不排得开」不再被短语拉进急单; BP 原话连问仍命中急单")
    void barePhraseNoLongerPullsGenericSchedulingIntoRush() {
        IntentKnowledgeBase knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
        assertThat(knowledge.matchPhrase("明天产线排不排得开", "FACTORY")).isNotEqualTo(java.util.Optional.of(RUSH));
        assertThat(knowledge.matchPhrase("周五要交 2 吨酱牛肉急单，原料够不够，产线排不排得开？", "FACTORY")).contains(RUSH);
    }
}
