package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionSettlementOutputLine;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductionWorkflowOutputCostAllocationTest {

    @Test
    void appliesRatioOncePerSkuThenSplitsThatSkuCostAcrossItsBatches() {
        ProductionSettlementOutputLine a1 = line("A", "60", "70");
        ProductionSettlementOutputLine a2 = line("A", "40", "70");
        ProductionSettlementOutputLine b1 = line("B", "50", "30");

        ProductionPlanServiceImpl.allocateWorkflowOutputCostsFromTotal(
                new BigDecimal("1000"), List.of(a1, a2, b1));

        assertDecimal("420", a1.getAllocatedCost());
        assertDecimal("280", a2.getAllocatedCost());
        assertDecimal("300", b1.getAllocatedCost());
        assertDecimal("7", a1.getUnitCost());
        assertDecimal("7", a2.getUnitCost());
        assertDecimal("6", b1.getUnitCost());
        assertDecimal("1000", List.of(a1, a2, b1).stream()
                .map(ProductionSettlementOutputLine::getAllocatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void partialReceiptUsesStableReportedQuantityAsCostDenominator() {
        ProductionSettlementOutputLine line = line("A", "0.01", "1");
        line.setReportedQuantity(new BigDecimal("0.02"));

        ProductionPlanServiceImpl.allocateWorkflowOutputCostsFromTotal(
                new BigDecimal("0.56"), List.of(line));

        assertDecimal("0.56", line.getAllocatedCost());
        assertDecimal("28", line.getUnitCost());
    }

    @Test
    void partialReceiptSplitsSameSkuByReportedQuantitiesNotCumulativeReceipts() {
        ProductionSettlementOutputLine first = line("A", "0.01", "1");
        first.setReportedQuantity(new BigDecimal("0.02"));
        ProductionSettlementOutputLine second = line("A", "0", "1");
        second.setReportedQuantity(new BigDecimal("0.02"));

        ProductionPlanServiceImpl.allocateWorkflowOutputCostsFromTotal(
                new BigDecimal("0.56"), List.of(first, second));

        assertDecimal("0.28", first.getAllocatedCost());
        assertDecimal("0.28", second.getAllocatedCost());
        assertDecimal("14", first.getUnitCost());
        assertDecimal("14", second.getUnitCost());
        assertDecimal("0.56", first.getAllocatedCost().add(second.getAllocatedCost()));
    }

    @Test
    void mainAndByproductNrvConserveAcrossSameSkuBatchesUsingReportedQuantities() {
        ProductionSettlementOutputLine mainFirst = line("MAIN", "0.02", "3");
        mainFirst.setReceivedQuantity(new BigDecimal("0.01"));
        ProductionSettlementOutputLine mainSecond = line("MAIN", "0.02", "3");
        mainSecond.setReceivedQuantity(BigDecimal.ZERO);
        ProductionSettlementOutputLine byproductFirst = line("BY", "0.01", "0");
        byproductFirst.setOutputRole(BomRecipe.OutputRole.BY_PRODUCT);
        byproductFirst.setByproductNrvUnitPrice(new BigDecimal("5"));
        byproductFirst.setReceivedQuantity(BigDecimal.ZERO);
        ProductionSettlementOutputLine byproductSecond = line("BY", "0.01", "0");
        byproductSecond.setOutputRole(BomRecipe.OutputRole.BY_PRODUCT);
        byproductSecond.setByproductNrvUnitPrice(new BigDecimal("5"));
        byproductSecond.setReceivedQuantity(new BigDecimal("0.01"));

        ProductionPlanServiceImpl.allocateWorkflowOutputCostsFromTotal(
                new BigDecimal("0.56"), List.of(mainFirst, mainSecond, byproductFirst, byproductSecond));

        assertDecimal("0.23", mainFirst.getAllocatedCost());
        assertDecimal("0.23", mainSecond.getAllocatedCost());
        assertDecimal("0.05", byproductFirst.getAllocatedCost());
        assertDecimal("0.05", byproductSecond.getAllocatedCost());
        assertDecimal("11.5", mainFirst.getUnitCost());
        assertDecimal("5", byproductSecond.getUnitCost());
        assertDecimal("0.56", List.of(mainFirst, mainSecond, byproductFirst, byproductSecond).stream()
                .map(ProductionSettlementOutputLine::getAllocatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * 存货多成品(F006【演示L4】8d77ec57): 三个成品小结时已按各自分支追溯出成本
     * 692.46 / 649.20 / 1998.58, 比例取占比, 分摊计划总额后守恒且按同一比例落到各成品。
     */
    @Test
    void byStockMultiOutputRatiosComeFromEachFinishedGoodsTracedCost() {
        List<FinishedGoodsBatch> fgs = List.of(
                fg("FG-BREAST", "238", "2.9095"), fg("FG-WING", "110", "5.9018"), fg("FG-LEG", "205", "9.7492"));
        java.util.Map<String, BigDecimal> ratios = ProductionPlanServiceImpl.byStockTracedCostRatios(fgs);

        ProductionSettlementOutputLine breast = line("A", "238", ratios.get("FG-BREAST").toPlainString());
        ProductionSettlementOutputLine wing = line("B", "110", ratios.get("FG-WING").toPlainString());
        ProductionSettlementOutputLine leg = line("C", "205", ratios.get("FG-LEG").toPlainString());
        ProductionPlanServiceImpl.allocateWorkflowOutputCostsFromTotal(
                new BigDecimal("3340.24"), List.of(breast, wing, leg));

        assertEquals(0, new BigDecimal("692.46").compareTo(breast.getAllocatedCost().setScale(2, java.math.RoundingMode.HALF_UP)));
        assertEquals(0, new BigDecimal("649.20").compareTo(wing.getAllocatedCost().setScale(2, java.math.RoundingMode.HALF_UP)));
        assertDecimal("3340.24", breast.getAllocatedCost().add(wing.getAllocatedCost()).add(leg.getAllocatedCost()));
    }

    @Test
    void byStockRatiosRefuseWhenAnyFinishedGoodHasNoTracedCost() {
        assertNull(ProductionPlanServiceImpl.byStockTracedCostRatios(List.of(
                fg("FG-BREAST", "238", "2.9095"), fg("FG-WING", "110", null))));
    }

    private FinishedGoodsBatch fg(String id, String quantity, String unitCost) {
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId(id);
        fg.setProducedQuantity(new BigDecimal(quantity));
        fg.setUnitCost(unitCost == null ? null : new BigDecimal(unitCost));
        return fg;
    }

    private ProductionSettlementOutputLine line(String sku, String quantity, String ratio) {
        ProductionSettlementOutputLine line = ProductionSettlementOutputLine.create();
        line.setProductTypeId(sku);
        line.setOutputRole("A".equals(sku)
                ? BomRecipe.OutputRole.MAIN : BomRecipe.OutputRole.CO_PRODUCT);
        BigDecimal quantityValue = new BigDecimal(quantity);
        line.setReportedQuantity(quantityValue);
        line.setReceivedQuantity(quantityValue);
        line.setCostAllocationRatio(new BigDecimal(ratio));
        return line;
    }

    private void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
