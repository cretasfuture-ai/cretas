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
                "没说是第 2 道工序缺人工 —— 那是唯一真正拦住计算的那一项: " + reason);
        assertTrue(reason.contains("1"),
                "没说一共差几项: " + reason);

        // 🔴 阴性对照: 设备/其他成本【不阻断】(2026-07-30「不强制填写」裁定),
        //    把它们混进「为什么算不出」会让用户去补一个补了也没用的东西。
        //    它们要出现在另一句口径说明里, 见 describeUncollectedOptionalCostNote。
        assertFalse(reason.contains("设备成本"),
                "设备成本不阻断计算, 不该出现在「为什么算不出」里: " + reason);
        assertFalse(reason.contains("其他成本"),
                "其他成本不阻断计算, 不该出现在「为什么算不出」里: " + reason);
    }

    @Test
    @DisplayName("🔴 两句话合起来才是完整事实: 阻断项说在原因里, 未录的可选项说在口径说明里")
    void blockingAndOptionalItemsAreBothSurfacedButInTheRightPlace() {
        OrderCostBreakdownDTO dto = prodShapedBreakdown();

        String reason = ProductionPlanServiceImpl.describeCostUnavailableReason(dto);
        String note = ProductionPlanServiceImpl.describeUncollectedOptionalCostNote(dto);

        // 阻断项 → 原因
        assertTrue(reason.contains("process-2"), "阻断项没进原因: " + reason);
        // 可选项 → 口径说明 (一项都不许被静默吞掉)
        assertTrue(note != null && note.contains("设备成本"),
                "未录的设备成本被静默吞掉了: " + note);
        assertTrue(note.contains("其他成本"),
                "未录的其他成本被静默吞掉了: " + note);
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
    @DisplayName("🔴🔴 审计透明度补丁: 已归集明细逐条列出, 同一个批次出现两次+depth不同 = 双计的信号"
            + " (PLAN-1788305552728-D94F7E22 复现的形状)")
    void showsPerItemBreakdownSoAReviewerCanSpotADoubleCountedBatch() {
        OrderCostBreakdownDTO dto = OrderCostBreakdownDTO.builder()
                .hasData(true)
                .knownCostSubtotal(new BigDecimal("1.9538"))
                .totalCost(null)
                .costComplete(false)
                .missingCostItems(List.of("LABOR_RATE_OR_TIME:process-1"))
                .sources(List.of(
                        // 同一个物料批次(冰水/4cce21c0)出现两次 —— traceCost 沿谱系回溯一次(depth=3),
                        // 结单并入又算一次(depth=1); prod 实测的真实形状。
                        OrderCostBreakdownDTO.SourceCost.builder()
                                .batchId("4cce21c0").batchName("冰水")
                                .cost(new BigDecimal("0.3800")).depth(3).build(),
                        OrderCostBreakdownDTO.SourceCost.builder()
                                .batchId("4cce21c0").batchName("冰水")
                                .cost(new BigDecimal("0.3738")).depth(1).build(),
                        OrderCostBreakdownDTO.SourceCost.builder()
                                .batchId("3755").batchName("L1纸箱")
                                .cost(new BigDecimal("1.2000")).depth(1).build()))
                .build();

        String reason = ProductionPlanServiceImpl.describeCostUnavailableReason(dto);

        assertTrue(reason.contains("已归集明细"), "没有列出已归集明细: " + reason);
        long timesIceWaterAppears = reason.split("冰水", -1).length - 1;
        assertEquals(2, timesIceWaterAppears,
                "冰水应该出现两次(两条不同 depth 的贡献), 审阅者才看得出双计: " + reason);
        assertTrue(reason.contains("深度3") && reason.contains("深度1"),
                "两条冰水的 depth 不一样, 这正是双计的信号, 但没有把 depth 一起报出来: " + reason);
        assertTrue(reason.contains("L1纸箱"), "最大的一项(纸箱 ¥1.20)没有列出来: " + reason);
    }

    @Test
    @DisplayName("按金额降序, 超过上限截断但报出总数")
    void costContributionsAreSortedDescendingAndTruncated() {
        List<OrderCostBreakdownDTO.SourceCost> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            many.add(OrderCostBreakdownDTO.SourceCost.builder()
                    .batchId("b" + i).batchName("料" + i)
                    .cost(new BigDecimal(i)).depth(1).build());
        }
        String joined = ProductionPlanServiceImpl.joinCostContributionLabels(many);

        assertTrue(joined.startsWith("料7"), "没有按金额降序: " + joined);
        assertTrue(joined.contains("等共 7 项"), "截断了却没说总数: " + joined);
        assertFalse(joined.contains("料1 "), "没有截断(第 1 项金额最小, 该被截掉): " + joined);
    }

    @Test
    @DisplayName("阴性对照: sources 为空时不出现「已归集明细」这一段")
    void noContributionsSectionWhenSourcesIsEmpty() {
        String reason = ProductionPlanServiceImpl
                .describeCostUnavailableReason(prodShapedBreakdown());   // 未设置 sources
        assertFalse(reason.contains("已归集明细"),
                "没有 sources 数据却凭空造了一段「已归集明细」: " + reason);
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

    // ── 客供主料成本口径 (a)（owner 裁定，2026-09-04）──────────────────────────────────

    @Test
    @DisplayName("阴性对照: 没有客供料按口径记 0 时, describeCustomerSuppliedNote 返回 null (不凭空造一句话)")
    void describeCustomerSuppliedNoteReturnsNullWhenNoneWereZeroed() {
        assertNull(ProductionPlanServiceImpl.describeCustomerSuppliedNote(prodShapedBreakdown()));
        assertNull(ProductionPlanServiceImpl.describeCustomerSuppliedNote(null));
        assertNull(ProductionPlanServiceImpl.describeCustomerSuppliedNote(
                OrderCostBreakdownDTO.builder().hasData(true).customerSuppliedItems(List.of()).build()));
    }

    @Test
    @DisplayName("🔴 客供料按口径记 0 时, 说出「N 项 + 批次号」, 不能只报一个总数让人猜")
    void describeCustomerSuppliedNoteNamesCountAndBatches() {
        OrderCostBreakdownDTO dto = OrderCostBreakdownDTO.builder()
                .hasData(true)
                .customerSuppliedItemCount(1)
                .customerSuppliedItems(List.of("CUSTOMER_SUPPLIED:1399上脑-CS-0001"))
                .build();

        String note = ProductionPlanServiceImpl.describeCustomerSuppliedNote(dto);

        assertTrue(note != null && note.contains("1 项"), "没说共几项: " + note);
        assertTrue(note.contains("1399上脑-CS-0001"), "没说是哪个批次: " + note);
        assertTrue(note.contains("¥0"), "没说明确记成 0: " + note);
        assertFalse(note.contains("缺") && note.contains("单价"),
                "客供口径记 0 不该被措辞成「缺单价」——那是另一件事: " + note);
    }

    @Test
    @DisplayName("🔴 拒绝原因里也要看得到客供料已排除, 不然会有人误以为「还差单价」包括客供料")
    void describeCostUnavailableReasonMentionsCustomerSuppliedEvenWhenStillBlockedByOtherItems() {
        // 一个真正阻断的项(公司自有批次缺价), 同时这个计划还有客供料按口径记 0 ——
        // 两件事同时成立时, 拒绝原因必须两件都说清楚, 不能只报阻断项让人漏看客供料已排除。
        OrderCostBreakdownDTO blocked = OrderCostBreakdownDTO.builder()
                .hasData(true)
                .knownCostSubtotal(new BigDecimal("10.00"))
                .totalCost(null)
                .costComplete(false)
                .missingCostItems(List.of("RAW_MATERIAL_PRICE:some-company-batch"))
                .customerSuppliedItemCount(1)
                .customerSuppliedItems(List.of("CUSTOMER_SUPPLIED:1399上脑-CS-0001"))
                .build();

        String reason = ProductionPlanServiceImpl.describeCostUnavailableReason(blocked);

        assertTrue(reason.contains("客供料"), "拒绝原因里看不到客供料已经排除的说明: " + reason);
        assertTrue(reason.contains("1399上脑-CS-0001"), "没具体说是哪个客供批次: " + reason);
        assertTrue(reason.contains("some-company-batch"), "真正阻断的那一项(公司自有缺价)没说出来: " + reason);
    }

    @Test
    @DisplayName("阴性对照: describeCostUnavailableReason 在没有客供料时不提「客供」二字")
    void describeCostUnavailableReasonStaysSilentAboutCustomerSuppliedWhenThereAreNone() {
        String reason = ProductionPlanServiceImpl
                .describeCostUnavailableReason(prodShapedBreakdown());
        assertFalse(reason.contains("客供"), "没有客供料却凭空提了一句: " + reason);
    }
}
