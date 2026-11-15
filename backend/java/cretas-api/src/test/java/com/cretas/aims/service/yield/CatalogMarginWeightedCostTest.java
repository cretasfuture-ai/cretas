package com.cretas.aims.service.yield;

import com.cretas.aims.entity.ProductionBatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 目录价理论毛利的成本 = 近 N 批按产量加权, 单批异常 (如 ¥54 的小批量) 不再把毛利拉到 -101%。 */
class CatalogMarginWeightedCostTest {

    private static ProductionBatch b(String unitCost, String qty) {
        ProductionBatch p = new ProductionBatch();
        p.setUnitCost(new BigDecimal(unitCost));
        p.setActualQuantity(qty == null ? null : new BigDecimal(qty));
        return p;
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
