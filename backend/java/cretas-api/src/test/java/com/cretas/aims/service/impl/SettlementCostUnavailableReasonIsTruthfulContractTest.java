package com.cretas.aims.service.impl;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 算不出成品单位成本时，说出来的原因必须是<b>真实的那个原因</b>。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-25 prod 实测)</h2>
 *
 * 计划 {@code ef5392c7-a575-42a3-8691-ebca0d8306e1} 入库后给用户的提示是：
 * <pre>
 * 本次未能算出成品单位成本: 没有归集到本计划的原料领用成本。
 * </pre>
 * 而<b>同一时刻</b>读真实接口
 * {@code GET /api/mobile/F006/production/batches/PB-PLAN-1787622818112-B3898275-15036/cost-breakdown}
 * 拿到的是：
 * <pre>
 * hasData           = true
 * rawMaterialCost   = 40.32      ← 7 行原料【全部 COLLECTED】, 单价均非空
 * laborCost         = 51.57
 * knownCostSubtotal = 93.12
 * totalCost         = null       ← 真正的原因是下面这三项
 * missingCostItems  = [LABOR_RATE_OR_TIME:process-2,
 *                      EQUIPMENT_COST:PB-PLAN-1787622818112-B3898275-15036,
 *                      OTHER_COST:PB-PLAN-1787622818112-B3898275-15036]
 * </pre>
 *
 * <p>也就是说<b>原料领用成本已经归集到了</b>（读侧桥接 {@code settlementConsumptionsFor} 是好的），
 * 缺的是人工/设备/其他。那句固定文案把用户指向了一个<b>不存在的问题</b>
 * （「联系管理员检查成本归集配置」），而真正要做的是补录第 2 道工序的工时/人工成本。
 *
 * <p>本仓形态 A：<b>我量的这个数，不是我真正想知道的那个数</b>；
 * 以及「报一个数的时候，把这个数是怎么来的一起报」。
 *
 * <h2>钉住的行为</h2>
 * <ol>
 *   <li>已经归集到原料成本时，<b>不许</b>再说「没有归集到原料领用成本」</li>
 *   <li>必须逐项说出真正缺的是哪几项</li>
 *   <li>必须把<b>已经归集到的金额</b>说出来（否则用户以为一行都没算到）</li>
 *   <li>{@code totalCost} 该 null 还是 null —— 已知合计<b>不得</b>顶替它去喂 COGS</li>
 * </ol>
 */
class SettlementCostUnavailableReasonIsTruthfulContractTest {

    private static final String BATCH = "PB-PLAN-1787622818112-B3898275-15036";

    /** prod 实测那一份读数，逐字段照抄。 */
    private static OrderCostBreakdownDTO prodShapedBreakdown() {
        return OrderCostBreakdownDTO.builder()
                .orderId("ef5392c7-a575-42a3-8691-ebca0d8306e1")
                .hasData(true)
                .boxCount(10)
                .rawMaterialCost(new BigDecimal("40.32"))
                .laborCost(new BigDecimal("51.57"))
                .packagingCost(new BigDecimal("1.23"))
                .knownCostSubtotal(new BigDecimal("93.12"))
                .knownPerBoxCost(new BigDecimal("9.31"))
                .totalCost(null)
                .perBoxCost(null)
                .costComplete(false)
                .calculationStatus("PARTIAL")
                .missingCostItemCount(3)
                .missingCostItems(List.of(
                        "LABOR_RATE_OR_TIME:process-2",
                        "EQUIPMENT_COST:" + BATCH,
                        "OTHER_COST:" + BATCH))
                .build();
    }

    @Test
    @DisplayName("🔴 原料成本已归集时, 不许再说「没有归集到本计划的原料领用成本」")
    void doesNotBlameRawMaterialCollectionWhenRawMaterialWasCollected() {
        String reason = ProductionPlanServiceImpl
                .describeCostUnavailableReason(prodShapedBreakdown());

        assertFalse(reason.contains("没有归集到本计划的原料领用成本"),
                "原料成本明明归集到了 ¥40.32(7 行全 COLLECTED), 却还在说没归集到: " + reason);
        assertFalse(reason.contains("检查成本归集配置"),
                "把用户指向了一个不存在的问题(去查归集配置): " + reason);
    }

    @Test
    @DisplayName("🔴 必须逐项说出真正缺的那几项")
    void namesEveryItemThatIsActuallyMissing() {
        String reason = ProductionPlanServiceImpl
                .describeCostUnavailableReason(prodShapedBreakdown());

        assertTrue(reason.contains("process-2"),
                "没说是第 2 道工序缺人工: " + reason);
        assertTrue(reason.contains("设备成本"),
                "没说缺设备成本: " + reason);
        assertTrue(reason.contains("其他成本"),
                "没说缺其他成本: " + reason);
        assertTrue(reason.contains("3"),
                "没说一共差几项: " + reason);
    }

    @Test
    @DisplayName("🔴 要说出【已经归集到】多少 —— 否则用户以为一行都没算到")
    void saysHowMuchWasAlreadyCollected() {
        String reason = ProductionPlanServiceImpl
                .describeCostUnavailableReason(prodShapedBreakdown());

        assertTrue(reason.contains("93.12"),
                "没说已经归集到 ¥93.12: " + reason);
    }

    @Test
    @DisplayName("阳性对照: 换一组缺项, 话必须跟着变 (否则它只是另一句固定文案)")
    void theReasonIsDerivedFromInputNotAConstant() {
        String a = ProductionPlanServiceImpl
                .describeCostUnavailableReason(prodShapedBreakdown());

        OrderCostBreakdownDTO other = OrderCostBreakdownDTO.builder()
                .hasData(true)
                .knownCostSubtotal(new BigDecimal("10.00"))
                .totalCost(null)
                .costComplete(false)
                .missingCostItems(List.of("RAW_MATERIAL_PRICE:batch-abc"))
                .build();
        String b = ProductionPlanServiceImpl.describeCostUnavailableReason(other);

        assertNotEquals(a, b, "两组完全不同的缺项给出了同一句话 —— 这句话不是从输入算出来的");
        assertTrue(b.contains("batch-abc"), "没说是哪个原料批次缺单价: " + b);
        assertFalse(b.contains("process-2"), "把别的样本的缺项串过来了: " + b);
    }

    @Test
    @DisplayName("真的一行都没归集到时, 仍然用原来那句话 (它对这种情形是对的)")
    void keepsTheOriginalWordingWhenNothingWasCollectedAtAll() {
        OrderCostBreakdownDTO noData = OrderCostBreakdownDTO.builder()
                .hasData(false)
                .build();

        assertEquals(ProductionPlanServiceImpl.COST_UNAVAILABLE_WARNING,
                ProductionPlanServiceImpl.describeCostUnavailableReason(noData),
                "hasData=false 是真的一行都没归集到, 这时那句话没说错");
        assertEquals(ProductionPlanServiceImpl.COST_UNAVAILABLE_WARNING,
                ProductionPlanServiceImpl.describeCostUnavailableReason(null),
                "读不到分解时也该给出可行动的原因");
    }

    @Test
    @DisplayName("🔴 已知合计不得顶替 totalCost 去喂 COGS (2026-07-30 口径, 不因本次改动松动)")
    void knownSubtotalMustNotBecomeTheAllocationBase() {
        assertNull(ProductionPlanServiceImpl.usableTotalCost(prodShapedBreakdown()),
                "拿不完整的已知合计当分摊基数会系统性低估成本、虚增毛利");
        assertNull(ProductionPlanServiceImpl.usableTotalCost(null));

        // 阳性对照: 完整且为正时必须能用, 否则上面那条 null 只是因为它永远返回 null
        OrderCostBreakdownDTO complete = OrderCostBreakdownDTO.builder()
                .hasData(true)
                .totalCost(new BigDecimal("123.45"))
                .costComplete(true)
                .missingCostItems(List.of())
                .build();
        assertEquals(0, new BigDecimal("123.45")
                        .compareTo(ProductionPlanServiceImpl.usableTotalCost(complete)),
                "成本完整时必须能当分摊基数");
    }

    @Test
    @DisplayName("认不出来的缺项码原样输出, 不静默丢掉")
    void unknownCodesAreShownVerbatimRatherThanDropped() {
        String label = ProductionPlanServiceImpl
                .missingCostItemLabel("SOME_FUTURE_CODE:xyz");
        assertEquals("SOME_FUTURE_CODE:xyz", label,
                "丢掉未翻译的码会变成「差 N 项但只列出 N-1 项」");
    }

    @Test
    @DisplayName("缺项很多时截断, 但要说出总数 (不许让人以为只差 5 项)")
    void truncatesLongListsButStillReportsTheTotal() {
        List<String> many = List.of(
                "EQUIPMENT_COST:b1", "EQUIPMENT_COST:b2", "EQUIPMENT_COST:b3",
                "EQUIPMENT_COST:b4", "EQUIPMENT_COST:b5", "EQUIPMENT_COST:b6",
                "EQUIPMENT_COST:b7");
        String joined = ProductionPlanServiceImpl.joinMissingCostLabels(many);

        assertTrue(joined.contains("等共 7 项"), "截断了却没说总数: " + joined);
        assertFalse(joined.contains("b7"), "没有截断: " + joined);
        assertTrue(joined.contains("b5"), "截得太狠, 前 5 项都没列全: " + joined);
    }

    @Test
    @DisplayName("这句话仍然守住原有三条: 说清留空、不说成 0、给下一步")
    void stillHonoursTheOlderContract() {
        String reason = ProductionPlanServiceImpl
                .describeCostUnavailableReason(prodShapedBreakdown());

        assertTrue(reason.contains("留空"), "没说清是留空而不是零: " + reason);
        assertTrue(reason.contains("下一步"), "没有下一步指引: " + reason);
        assertFalse(reason.contains("成本为 0") || reason.contains("成本为0")
                        || reason.contains("成本是 0"),
                "把「算不出」说成了「是 0」: " + reason);
        assertTrue(reason.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF),
                "给用户看的话不是中文: " + reason);
    }
}
