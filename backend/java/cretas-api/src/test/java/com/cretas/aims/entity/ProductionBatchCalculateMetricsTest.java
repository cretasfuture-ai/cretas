package com.cretas.aims.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P0-2 review fix: {@link ProductionBatch#calculateMetrics()} 跨单位指标守卫。
 *
 * <p>末道报工产出单位 (份) ≠ 批次原计划单位 (kg) 时, completeProduction 把 unit 覆盖成产出单位,
 * 并把原单位记到 plannedUnit。calculateMetrics 判出 plannedUnit 与 unit 不是同一个单位时:</p>
 * <ul>
 *   <li>efficiency = actualQuantity(份)/plannedQuantity(kg) → 跨单位无意义 → 置 null (诚实留空)</li>
 *   <li>unitCost = totalCost(金额)/goodQuantity(份) → 元/份, 不涉及计划量 → 照算 (D-W-01)</li>
 *   <li>yieldRate = good/actual 同为产出单位 → 仍正常计算</li>
 * </ul>
 *
 * <p>同单位 (plannedUnit 与 unit 同一个单位; 或 plannedUnit 为 null 且没挂计划) 行为零回归。</p>
 */
class ProductionBatchCalculateMetricsTest {

    private static ProductionBatch baseBatch() {
        ProductionBatch b = new ProductionBatch();
        b.setPlannedQuantity(new BigDecimal("998"));
        b.setActualQuantity(new BigDecimal("382"));
        b.setGoodQuantity(new BigDecimal("382"));
        b.setDefectQuantity(BigDecimal.ZERO);
        b.setMaterialCost(new BigDecimal("5000"));
        b.setLaborCost(new BigDecimal("1000"));
        return b;
    }

    @Test
    @DisplayName("P0-2: 跨单位 (份≠kg) → efficiency 置 null; unitCost 按产出单位算 (元/份)")
    void crossUnit_suppressesEfficiencyButKeepsUnitCostPerOutputUnit() {
        ProductionBatch b = baseBatch();
        b.setPlannedUnit("kg");   // 原计划单位
        b.setUnit("份");          // 末道产出单位 (completeProduction 覆盖后)

        b.calculateMetrics();

        assertNull(b.getEfficiency(),
                "跨单位 efficiency=actualQuantity(份)/plannedQuantity(kg) 无意义, 应置 null");
        // D-W-01: unitCost = 金额 ÷ 产出单位数量, 两个操作数都不碰 plannedQuantity ⇒ 跨单位照样是「元/份」。
        // 性质: unitCost × 良品份数 ≈ 总成本 (只差四位小数的舍入); 6000 / 382 = 15.7068
        assertEquals(0, new BigDecimal("15.7068").compareTo(b.getUnitCost()),
                "跨单位 unitCost 是元/产出单位, 不再随 plannedUnit 置 null");
        assertEquals(0, b.getUnitCost().multiply(b.getGoodQuantity())
                        .setScale(0, java.math.RoundingMode.HALF_UP).compareTo(b.getTotalCost()),
                "unitCost 的分母是产出单位数量 (良品 382 份)");
        // 良品率 good/actual 同为产出单位 → 仍算 (382/382=100%)
        assertEquals(0, new BigDecimal("100.00").compareTo(b.getYieldRate()),
                "良品率同单位比值, 跨单位下仍正常计算");
        // 总成本不受单位影响, 仍归集
        assertEquals(0, new BigDecimal("6000").compareTo(b.getTotalCost()),
                "总成本与单位无关, 应正常归集");
    }

    @Test
    @DisplayName("P0-2 零回归: 同单位 (plannedUnit==unit) → efficiency/unitCost 正常计算")
    void sameUnit_explicitPlannedUnit_computesNormally() {
        ProductionBatch b = baseBatch();
        b.setActualQuantity(new BigDecimal("900"));
        b.setGoodQuantity(new BigDecimal("855"));
        b.setPlannedUnit("kg");
        b.setUnit("kg");          // 同单位

        b.calculateMetrics();

        assertNotNull(b.getEfficiency(), "同单位应正常计算 efficiency");
        // efficiency = 900/998*100 = 90.18
        assertEquals(0, new BigDecimal("90.18").compareTo(b.getEfficiency()));
        assertNotNull(b.getUnitCost(), "同单位应正常计算 unitCost");
        // unitCost = 6000/855 = 7.0175 (HALF_UP, scale 4)
        assertEquals(0, new BigDecimal("7.0175").compareTo(b.getUnitCost()));
    }

    @Test
    @DisplayName("P0-2 零回归: plannedUnit 为 null (常规批次, 从不跨单位) → 原行为不变")
    void nullPlannedUnit_legacyBehaviorUnchanged() {
        ProductionBatch b = baseBatch();
        b.setActualQuantity(new BigDecimal("900"));
        b.setGoodQuantity(new BigDecimal("855"));
        b.setUnit("kg");
        // plannedUnit 不设 (null) — 模拟历史/常规批次

        b.calculateMetrics();

        assertNotNull(b.getEfficiency(), "plannedUnit=null 时按原逻辑计算 efficiency");
        assertEquals(0, new BigDecimal("90.18").compareTo(b.getEfficiency()));
        assertNotNull(b.getUnitCost(), "plannedUnit=null 时按原逻辑计算 unitCost");
        assertEquals(0, new BigDecimal("7.0175").compareTo(b.getUnitCost()));
    }

    @Test
    @DisplayName("P0-2: 跨单位换算导致的高出成率应能持久化")
    void crossUnitYield_rateColumnSupportsLargePercentage() throws NoSuchFieldException {
        ProductionBatch b = baseBatch();
        b.setActualQuantity(new BigDecimal("0.13"));
        b.setGoodQuantity(new BigDecimal("20"));
        b.setPlannedUnit("kg");
        b.setUnit("盒");

        b.calculateMetrics();

        assertEquals(0, new BigDecimal("15384.62").compareTo(b.getYieldRate()),
                "kg→盒 的出成率可能超过 999.99, Java 计算不能被截断");
        Field yieldRate = ProductionBatch.class.getDeclaredField("yieldRate");
        Column column = yieldRate.getAnnotation(Column.class);
        assertEquals(12, column.precision(), "数据库列精度必须容纳跨单位出成率");
        assertEquals(2, column.scale());
    }
}
