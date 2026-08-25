package com.cretas.aims.service.yield;

import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 人工「<b>明细不全</b>」和人工「<b>金额未知</b>」是两件事，后果也必须不同。
 *
 * <h2>🔴 为什么有这道闸（2026-08-25 prod 实测）</h2>
 *
 * 批次 {@code PB-PLAN-1787622818112-B3898275-15036} 的 {@code laborDetails} 实测只有一行：
 * <pre>
 * proc=2  workers=null  duration=null  rate=null  amount=26.00  status=UNCOLLECTED
 *         missingReason="缺少可验证工时段或持久化人工成本"
 * </pre>
 *
 * <p>人工<b>金额是有的</b>（{@code 26.00}），而且早就进了总账：
 * <pre>
 * laborCost         = 51.57
 * knownCostSubtotal = 93.12 = 原料 40.32 + 人工 51.57 + 包材 1.23   ← 逐项对得上
 * </pre>
 * 缺的只是「这笔钱对应多少工时」。但原来两种情形<b>共用一个码</b>
 * {@code LABOR_RATE_OR_TIME}，于是「金额齐了只是没工时段」也把整个
 * {@code totalCost} 抹掉 —— <b>把可审计性问题误判成了金额缺口</b>。
 * 本仓形态 A：我量的这个数，不是我真正想知道的那个数。
 *
 * <p>那句 {@code missingReason} 本身就是证据：「缺少可验证工时段<b>或</b>持久化人工成本」
 * —— 两件事被一个「或」糊在一起，而它们对总成本的后果完全相反。
 *
 * <h2>钉住的边界</h2>
 * <ul>
 *   <li>{@code LABOR_TIME_DETAIL_MISSING}（金额已知，缺工时段）→ <b>不阻断</b>总成本</li>
 *   <li>{@code LABOR_COST_UNKNOWN}（金额真的不知道）→ <b>照旧阻断</b></li>
 * </ul>
 */
class LaborDetailMissingVsUnknownContractTest {

    @Test
    @DisplayName("🔴 金额已知只是缺工时段 → 不阻断总成本")
    void timeDetailMissingDoesNotBlock() {
        assertTrue(OrderCostBreakdownService.isOptionalCostItem(
                        "LABOR_TIME_DETAIL_MISSING:process-2"),
                "人工金额已经计入 laborCost/总成本了, 再把 totalCost 抹掉等于扔掉一个算得出来的数");
    }

    @Test
    @DisplayName("🔴 阴性对照: 人工金额真的不知道时【必须】继续阻断")
    void unknownLaborCostMustStillBlock() {
        assertFalse(OrderCostBreakdownService.isOptionalCostItem(
                        "LABOR_COST_UNKNOWN:process-2"),
                "人工金额未知却按 0 计入总成本, 会系统性低估 COGS、虚增毛利");
        // 历史码含义不明(两种情形都可能), 保守起见照旧阻断
        assertFalse(OrderCostBreakdownService.isOptionalCostItem(
                        "LABOR_RATE_OR_TIME:process-2"),
                "历史码分不清是哪一种, 必须保守按阻断处理");
    }

    @Test
    @DisplayName("两个新码都有中文标签, 且说的不是同一件事")
    void bothCodesReadDifferentlyToTheUser() {
        String unknown = ProductionPlanServiceImpl
                .missingCostItemLabel("LABOR_COST_UNKNOWN:process-2");
        String detail = ProductionPlanServiceImpl
                .missingCostItemLabel("LABOR_TIME_DETAIL_MISSING:process-2");

        // 阳性对照: 真的被翻译了, 不是原样吐回机器码
        assertNotEquals("LABOR_COST_UNKNOWN:process-2", unknown, "没翻译成中文");
        assertNotEquals("LABOR_TIME_DETAIL_MISSING:process-2", detail, "没翻译成中文");

        assertNotEquals(unknown, detail, "两种完全不同的情形给了同一句话");
        assertTrue(unknown.contains("未知"), "没说清金额是未知的: " + unknown);
        assertTrue(detail.contains("已计入"),
                "没说清金额其实已经算进去了 —— 用户会以为成本少了一块: " + detail);
    }

    @Test
    @DisplayName("🔴 只缺工时段时, 「算不出」的原因里不该出现它 (它没拦住任何计算)")
    void timeDetailMissingIsNotBlamedAsTheReason() {
        com.cretas.aims.dto.yield.OrderCostBreakdownDTO onlyTimeDetail =
                com.cretas.aims.dto.yield.OrderCostBreakdownDTO.builder()
                        .hasData(true)
                        .knownCostSubtotal(new java.math.BigDecimal("93.12"))
                        .missingCostItems(List.of("LABOR_TIME_DETAIL_MISSING:process-2"))
                        .build();

        String note = ProductionPlanServiceImpl
                .describeUncollectedOptionalCostNote(onlyTimeDetail);
        assertTrue(note != null && note.contains("工时段"),
                "缺工时段被静默吞掉了, 用户不知道这个数不可审计到工时: " + note);
    }
}
