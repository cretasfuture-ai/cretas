package com.cretas.aims.service.yield;

import com.cretas.aims.entity.ProductionBatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 目录价理论毛利的成本 = 近 N 批按产量加权, 单批异常 (如 ¥54 的小批量) 不再把毛利拉到 -101%。 */
class CatalogMarginWeightedCostContractTest {

    private static ProductionBatch b(String unitCost, String qty) {
        ProductionBatch p = new ProductionBatch();
        p.setUnitCost(new BigDecimal(unitCost));
        p.setActualQuantity(qty == null ? null : new BigDecimal(qty));
        return p;
    }

    private static ProductionBatch full(String total, String actual, String good) {
        ProductionBatch p = new ProductionBatch();
        p.setTotalCost(new BigDecimal(total));
        p.setActualQuantity(new BigDecimal(actual));
        p.setGoodQuantity(good == null ? null : new BigDecimal(good));
        p.setUnitCost(new BigDecimal(total).divide(new BigDecimal(good != null ? good : actual), 4, java.math.RoundingMode.HALF_UP));
        return p;
    }

    @Test
    void goodQuantityBelowActual_weightedEqualsSumTotalOverSumActual_likeTheCostQueryTool() {
        // 批1: 总成本 100, 实际 100, 良品 50 (unitCost=2); 批2: 总成本 100, 实际 100, 无良品数 (unitCost=1)
        // 与成本查询工具同口径 Σtotal/Σ实际产量 = 200/200 = 1.0; 良品数口径 1.3333 和旧「单位成本×产量」口径 1.5 都不是
        BigDecimal w = ThreePriceComparisonService.weightedUnitCost(List.of(full("100", "100", "50"), full("100", "100", null)));
        assertEquals(1.0, w.doubleValue(), 0.0001);
        assertEquals(2, ThreePriceComparisonService.weightedCostBatchCount(List.of(full("100", "100", "50"), full("100", "100", null))));
        // 近 5 批里 4 批没有产量: 只有 1 批参与加权, 「近 N 批」如实报 1, 不报 5
        ProductionBatch noQty = new ProductionBatch();
        noQty.setUnitCost(new BigDecimal("9"));
        assertEquals(1, ThreePriceComparisonService.weightedCostBatchCount(
                List.of(full("100", "100", null), noQty, noQty, noQty, noQty)));
    }

    @Test
    void oneAbnormalSmallBatchBarelyMovesTheWeightedCost() {
        // 最近一批 ¥54.28 但只产 2 盒; 其余 4 批 ¥16 共 400 盒 → 加权 ≈ 16.19, 而不是 54.28
        BigDecimal w = ThreePriceComparisonService.weightedUnitCost(List.of(
                b("54.28", "2"), b("16", "100"), b("16", "100"), b("16", "100"), b("16", "100")));
        assertEquals(16.19, w.doubleValue(), 0.01);
        // 目录价 27: 加权毛利 ≈ 40%, 单取最近一批 = -101%
        double weightedMargin = (27 - w.doubleValue()) / 27 * 100;
        assertTrue(weightedMargin > 39 && weightedMargin < 41);
    }

    @Test
    void noQuantities_fallsBackToSimpleAverage_andNoBatchesIsNull() {
        assertEquals(15.0, ThreePriceComparisonService.weightedUnitCost(List.of(b("10", null), b("20", null))).doubleValue(), 0.0001);
        assertNull(ThreePriceComparisonService.weightedUnitCost(List.of()));
    }
}
