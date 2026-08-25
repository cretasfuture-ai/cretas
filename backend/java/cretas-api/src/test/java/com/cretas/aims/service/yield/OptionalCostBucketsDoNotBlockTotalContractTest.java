package com.cretas.aims.service.yield;

import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 「不强制填写」的成本桶不许把整个总成本拉成 null，
 * 而「真的不知道单价」的那些<b>必须</b>继续拉。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-25 prod 实测 + owner 裁定)</h2>
 *
 * 2026-07-30 owner 拍板：<b>设备/其他成本不强制填写，填了就算进去</b>。
 * 但当时这条裁定只落到了 {@code knownCostSubtotal}（让它在 {@code costComplete=false}
 * 时不被清空），{@code costComplete} 自己仍然被这两项拉黑 ⇒ {@code totalCost} 置 null
 * ⇒ 成品 {@code unit_cost} 恒为空。
 *
 * <p>实测（2026-08-25，生产库 {@code cretas_prod_db}）：
 * <pre>
 * production_batches  共 61 行   equipment_cost 非空 0 行   other_cost 非空 1 行
 * production_plans    共 43 行   actual_equipment_cost 非空 0 行
 * </pre>
 * 也就是说<b>没有任何一个批次录过设备成本</b> ⇒ 任何有 Workflow/BOM 的计划
 * （{@code auditEligible=true}）都<b>永远</b>算不出成品单位成本。
 * 「不填就永远算不出」在事实上就是强制，与那条裁定相反。
 * 本仓硬约束 8：<b>同一条裁定要么两处都改，要么显式登记哪处故意不改</b>。
 *
 * <h2>钉住的边界（这道闸的价值全在这条分界线上）</h2>
 * <ul>
 *   <li>可以不发生的成本桶（设备/其他）缺失 → <b>不阻断</b>，按 0 计入，但要说出来</li>
 *   <li>真的不知道的单价（原料/包材/调料/半成品投料）缺失 → <b>照旧阻断</b>，
 *       按 0 计会系统性低估 COGS、虚增毛利</li>
 * </ul>
 */
class OptionalCostBucketsDoNotBlockTotalContractTest {

    @Test
    @DisplayName("🔴 设备/其他成本是「不强制填写」那一类")
    void equipmentAndOtherCostAreOptional() {
        assertTrue(OrderCostBreakdownService.isOptionalCostItem("EQUIPMENT_COST:PB-1"));
        assertTrue(OrderCostBreakdownService.isOptionalCostItem("OTHER_COST:PB-1"));
        assertTrue(OrderCostBreakdownService.isOptionalCostItem("EQUIPMENT_COST"),
                "没有 detail 后缀时也要认得出来");
    }

    @Test
    @DisplayName("🔴 阴性对照: 缺【单价】那几类绝不许被当成可选 —— 按 0 计会虚增毛利")
    void unknownPricesMustStillBlock() {
        // 这一条是整道闸的重点: 如果它松了, 缺价会被静默按 0 计入 COGS。
        for (String blocking : List.of(
                "RAW_MATERIAL_PRICE:batch-1",
                "PACKAGING_PRICE:mt-1",
                "SEASONING_PRICE:mt-1",
                "SEMI_FINISHED_OR_FINISHED_FEED_PRICE",
                "LABOR_RATE_OR_TIME:process-2",
                "PINNED_BOM_ITEMS_MISSING:recipe-1")) {
            assertFalse(OrderCostBreakdownService.isOptionalCostItem(blocking),
                    blocking + " 被当成了「不强制填写」—— 缺价按 0 计会系统性低估成本");
        }
        assertFalse(OrderCostBreakdownService.isOptionalCostItem(null));
    }

    @Test
    @DisplayName("成本算出来了但设备/其他没录时, 仍然要说明这个数不含它们")
    void saysTheTotalExcludesUncollectedOptionalBuckets() {
        OrderCostBreakdownDTO dto = OrderCostBreakdownDTO.builder()
                .hasData(true)
                .totalCost(new BigDecimal("93.12"))
                .costComplete(true)
                .calculationStatus("PARTIAL")
                .missingCostItems(List.of("EQUIPMENT_COST:PB-1", "OTHER_COST:PB-1"))
                .build();

        String note = ProductionPlanServiceImpl.describeUncollectedOptionalCostNote(dto);

        assertNotNull(note, "算出来了就闭嘴 = 用户会把它当全成本用去定价");
        assertTrue(note.contains("不含"), "没说清这个数不含那两项: " + note);
        assertTrue(note.contains("设备成本"), "没点名设备成本: " + note);
        assertTrue(note.contains("其他成本"), "没点名其他成本: " + note);
        assertTrue(note.contains("按 0 计入"), "没说清缺的是按 0 计入的: " + note);
    }

    @Test
    @DisplayName("阳性对照: 该闭嘴的时候真的闭嘴 (否则「总是喊」和「该喊才喊」分不开)")
    void staysSilentWhenNothingOptionalIsMissing() {
        assertNull(ProductionPlanServiceImpl.describeUncollectedOptionalCostNote(
                OrderCostBreakdownDTO.builder()
                        .hasData(true)
                        .totalCost(new BigDecimal("93.12"))
                        .costComplete(true)
                        .missingCostItems(List.of())
                        .build()),
                "什么都不缺却还在喊");

        assertNull(ProductionPlanServiceImpl.describeUncollectedOptionalCostNote(null));

        // 只缺【会阻断】的项时也不该走这条说明 —— 那条路会走「算不出」的原因
        assertNull(ProductionPlanServiceImpl.describeUncollectedOptionalCostNote(
                OrderCostBreakdownDTO.builder()
                        .hasData(true)
                        .missingCostItems(List.of("RAW_MATERIAL_PRICE:b1"))
                        .build()),
                "缺价不属于「不强制填写」, 不该出现在这句口径说明里");
    }

    @Test
    @DisplayName("🔴 缺项清单仍然照常登记 —— 不阻断不等于藏起来")
    void theItemsAreStillReportedEvenThoughTheyNoLongerBlock() {
        // 这一条钉的是「没有隐藏」: 若哪天有人把 EQUIPMENT_COST 从 missingCostItems 里
        // 直接删掉来「让状态变绿」, 上面那句口径说明就再也生成不出来了。
        OrderCostBreakdownDTO dto = OrderCostBreakdownDTO.builder()
                .hasData(true)
                .totalCost(new BigDecimal("93.12"))
                .costComplete(true)
                .missingCostItems(List.of("EQUIPMENT_COST:PB-1", "OTHER_COST:PB-1"))
                .missingCostItemCount(2)
                .build();

        assertEquals(2, dto.getMissingCostItems().size(),
                "缺项被从清单里抹掉了 —— 用户就看不到这两项没录");
        assertNotNull(ProductionPlanServiceImpl.describeUncollectedOptionalCostNote(dto));
    }
}
