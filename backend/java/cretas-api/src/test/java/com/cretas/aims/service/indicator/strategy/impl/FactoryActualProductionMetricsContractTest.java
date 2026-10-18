package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.service.unit.*;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.cretas.aims.service.yield.YieldReportService;
import com.cretas.aims.service.yield.ProductionSummaryService;
import com.cretas.aims.service.indicator.MultiOutputActualMetricsService;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static com.cretas.aims.service.indicator.strategy.impl.FactoryActualProductionMetrics.Metric.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FactoryActualProductionMetricsContractTest {
    private final YieldReportService reporting = mock(YieldReportService.class);
    private final OrderCostBreakdownService costing = mock(OrderCostBreakdownService.class);
    private final UnitContractService units = mock(UnitContractService.class);
    private final FactoryActualProductionMetrics metrics = new FactoryActualProductionMetrics(reporting, costing, units);
    private final List<Object[]> rows = List.of(row(1L, "A", "9", "8", "kg", "1"),
            row(2L, "B", "22", "20", "盒", "2"));
    private BatchYieldDTO a, b;
    private OrderCostBreakdownDTO ca, cb;
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static Object[] row(long id, String code, String actual, String good, String unit, String defect) {
        return new Object[]{id, code, n(actual), good == null ? null : n(good), unit,
                defect == null ? null : n(defect), null, null, null, null, 1L, 0L};
    }

    @BeforeEach void fixture() {
        a = BatchYieldDTO.builder().firstStepInput(n("10")).firstStepInputUnit("kg").lastStepOutput(n("8"))
                .lastStepOutputUnit("kg").lastStepOutputInFirstUnit(n("8")).complete(true).inProgress(false).build();
        b = BatchYieldDTO.builder().firstStepInput(n("10")).firstStepInputUnit("kg").lastStepOutput(n("20"))
                .lastStepOutputUnit("盒").lastStepOutputInFirstUnit(n("10")).complete(true).inProgress(false)
                .pinnedNetWeightGrams(n("500")).build();
        ca = OrderCostBreakdownDTO.builder().hasData(true).costComplete(true).calculationStatus("COMPLETE")
                .totalCost(n("100")).convertedOutputKg(n("8")).estimatedAuxItemCount(0).build();
        cb = OrderCostBreakdownDTO.builder().hasData(true).costComplete(true).calculationStatus("COMPLETE")
                .totalCost(n("100")).convertedOutputKg(n("10")).estimatedAuxItemCount(0).build();
        when(reporting.getYield("FA", 1L)).thenReturn(a);
        when(reporting.getYield("FA", 2L)).thenReturn(b);
        when(costing.computeByBatch("FA", "A", false)).thenReturn(ca);
        when(costing.computeByBatch("FA", "B", false)).thenReturn(cb);
        when(units.describe(eq("FA"), anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(1);
            return Set.of("kg", "千克", "g").contains(value)
                    ? Optional.of(new CanonicalUnit(value, UnitDimension.MASS, "kg", value.equals("g") ? n("0.001") : BigDecimal.ONE, value, 4))
                    : Optional.empty();
        });
        when(units.convert(any(BigDecimal.class), any(UnitConversionContext.class))).thenAnswer(invocation -> {
            BigDecimal quantity = invocation.getArgument(0);
            UnitConversionContext context = invocation.getArgument(1);
            assertThat(context.factoryId()).isEqualTo("FA");
            assertThat(context.productTypeId()).isNull();
            if (context.fromUnit().equals(context.toUnit()) || (context.fromUnit().equals("千克") && context.toUnit().equals("kg"))) {
                return new UnitConversionResult(UnitConversionStatus.IDENTITY, quantity, context.fromUnit(), context.toUnit(), List.of(), null, null, null);
            }
            if (context.fromUnit().equals("g") && context.toUnit().equals("kg")) {
                return new UnitConversionResult(UnitConversionStatus.CONVERTED, quantity.movePointLeft(3), "g", "kg", List.of(), null, null, null);
            }
            return null;
        });
    }

    @Test void actualFlowSeparatesYieldQualityAndCostForKgAndBoxSkus() {
        assertThat(metrics.aggregate("FA", rows, YIELD_RATE)).isEqualByComparingTo("90");
        assertThat(metrics.aggregate("FA", rows, GOOD_OUTPUT_RATE)).isEqualByComparingTo("90");
        assertThat(metrics.aggregate("FA", rows, UNIT_COST)).isEqualByComparingTo("11.1111");
        assertThat(metrics.aggregate("FA", rows, TOTAL_COST)).isEqualByComparingTo("200");
        verify(costing, atLeastOnce()).computeByBatch("FA", "B", false);
    }
    @Test void aggregationWeightsActualInputInsteadOfAveragingBatchPercentages() {
        b.setFirstStepInput(n("30"));
        assertThat(metrics.aggregate("FA", rows, YIELD_RATE)).isEqualByComparingTo("45");
    }
    @Test void missingConversionDoesNotSilentlyDropOneSku() {
        b.setLastStepOutputInFirstUnit(null);
        assertThat(metrics.aggregate("FA", rows, YIELD_RATE)).isNull();
        assertThat(metrics.aggregate("FA", rows, UNIT_COST)).isNull();
    }
    @Test void incompleteOrInProgressBatchesCannotProduceFinalMetrics() {
        b.setComplete(false);
        assertThat(metrics.aggregate("FA", rows, YIELD_RATE)).isNull();
        b.setComplete(true); b.setInProgress(true);
        assertThat(metrics.aggregate("FA", rows, YIELD_RATE)).isNull();
    }
    @Test void massAliasesAndGramsUseTheExistingUnitContract() {
        a.setFirstStepInput(n("10000")); a.setFirstStepInputUnit("g"); a.setLastStepOutputInFirstUnit(n("8000"));
        b.setFirstStepInputUnit("千克");
        assertThat(metrics.aggregate("FA", rows, YIELD_RATE)).isEqualByComparingTo("90");
    }
    @Test void unknownQualityDoesNotBecomePerfectQualityOrZeroCost() {
        List<Object[]> unknown = java.util.Collections.singletonList(row(1L, "A", "8", null, "kg", null));
        assertThat(metrics.aggregate("FA", unknown, YIELD_RATE)).isEqualByComparingTo("80");
        assertThat(metrics.aggregate("FA", unknown, GOOD_OUTPUT_RATE)).isNull();
        assertThat(metrics.aggregate("FA", unknown, UNIT_COST)).isNull();
    }
    @Test void qualityCountsMustMatchActualReportedOutput() {
        List<Object[]> mismatch = java.util.Collections.singletonList(row(1L, "A", "10", "9", "kg", "1"));
        assertThat(metrics.aggregate("FA", mismatch, GOOD_OUTPUT_RATE)).isNull();
    }
    @Test void settlementQualityUsesQualifiedOutputPlusAdditionalDefectsWithoutDoubleDeduction() {
        Object[] settled = row(1L, "A", "8", "8", "kg", "0");
        settled[6] = "SETTLEMENT-1"; settled[7] = "COUNTED";
        settled[8] = n("1"); settled[9] = "kg"; settled[11] = 1L;
        assertThat(metrics.aggregate("FA", java.util.Collections.singletonList(settled), GOOD_OUTPUT_RATE)).isEqualByComparingTo("88.8889");
        settled[7] = "NOT_COUNTED"; settled[8] = null;
        assertThat(metrics.aggregate("FA", java.util.Collections.singletonList(settled), GOOD_OUTPUT_RATE)).isNull();
        settled[7] = "NONE_CONFIRMED"; settled[8] = BigDecimal.ZERO;
        assertThat(metrics.aggregate("FA", java.util.Collections.singletonList(settled), GOOD_OUTPUT_RATE)).isEqualByComparingTo("100");
    }
    @Test void sharedInputOrAmbiguousQualityAllocationCannotBeCountedOncePerCoProduct() {
        Object[] shared = row(1L, "A", "9", "8", "kg", "1"); shared[10] = 2L;
        assertThat(metrics.aggregate("FA", java.util.Collections.singletonList(shared), YIELD_RATE)).isNull();
        shared[10] = 1L; shared[6] = "SETTLEMENT-1"; shared[7] = "COUNTED";
        shared[8] = n("1"); shared[9] = "kg"; shared[11] = 2L;
        assertThat(metrics.aggregate("FA", java.util.Collections.singletonList(shared), GOOD_OUTPUT_RATE)).isNull();
    }
    @Test void incompleteOrEstimatedCostNeverBecomesACompleteCostKpi() {
        cb.setCostComplete(false);
        assertThat(metrics.aggregate("FA", rows, UNIT_COST)).isNull();
        cb.setCostComplete(true); cb.setCalculationStatus("PARTIAL");
        assertThat(metrics.aggregate("FA", rows, UNIT_COST)).isNull();
        cb.setCalculationStatus("COMPLETE"); cb.setEstimatedAuxItemCount(1);
        assertThat(metrics.aggregate("FA", rows, TOTAL_COST)).isNull();
    }
    @Test void confirmedZeroCostsAreRealZeroAndEmptyDataIsNull() {
        ca.setTotalCost(BigDecimal.ZERO); cb.setTotalCost(BigDecimal.ZERO);
        assertThat(metrics.aggregate("FA", rows, UNIT_COST)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(metrics.aggregate("FA", List.of(), TOTAL_COST)).isNull();
    }
    @Test void partiallyRecordedAuxiliaryMaterialsCannotPublishActualCost() {
        cb.setPartiallyRecordedAuxItemCount(1);
        assertThat(metrics.aggregate("FA", rows, TOTAL_COST)).isNull();
        assertThat(metrics.aggregate("FA", rows, UNIT_COST)).isNull();
    }
    @Test void recipeAndPinnedBomPackagingCostsAreReferencesRatherThanActualIssues() {
        for (String source : List.of("RECIPE_FALLBACK_ITEM_COST:v1", "PINNED_BOM_ITEM_COST:v3", "UNKNOWN")) {
            cb.setPackagingDetails(List.of(OrderCostBreakdownDTO.PackagingDetail.builder()
                    .amount(n("5")).priceSource(source).build()));
            assertThat(metrics.aggregate("FA", rows, TOTAL_COST)).as(source).isNull();
        }
        cb.setPackagingDetails(List.of(OrderCostBreakdownDTO.PackagingDetail.builder()
                .amount(n("5")).priceSource("ACTUAL_MATERIAL_BATCH").build()));
        assertThat(metrics.aggregate("FA", rows, TOTAL_COST)).isEqualByComparingTo("200");
    }
    @Test void sourceAdaptersKeepDistinctCodesAndVersionedFormulaIdentity() {
        assertThat(new FactoryLuYieldRateStrategy(metrics).getSourceVersion()).isEqualTo("actual-flow-v3");
        assertThat(new FactoryLuUnitCostStrategy(metrics).getSourceVersion()).isEqualTo("actual-flow-v3");
        assertThat(new FactoryGoodOutputRateStrategy(metrics).getIndicatorCode()).isEqualTo("FACTORY_GOOD_OUTPUT_RATE");
    }
    @Test void completedBusinessPeriodBindsFactoryAndInclusiveEndDate() {
        EntityManager em = mock(EntityManager.class); Query query = mock(Query.class);
        ReflectionTestUtils.setField(metrics, "entityManager", em);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        LocalDate day = LocalDate.of(2026, 10, 16);
        assertThat(metrics.compute("FA", day, day, YIELD_RATE)).isEqualByComparingTo("90");
        verify(query).setParameter(1, "FA"); verify(query).setParameter(2, day.atStartOfDay());
        verify(query).setParameter(3, day.plusDays(1).atStartOfDay());
        verify(em).createNativeQuery(argThat(sql -> sql.contains("end_time >= ?2") && sql.contains("status = 'COMPLETED'") && sql.contains("CLERK_WIP")));
    }

    private Query productRows(String idA, String idB) {
        EntityManager em = mock(EntityManager.class); Query query = mock(Query.class);
        ReflectionTestUtils.setField(metrics, "entityManager", em);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        Object[] ra = java.util.Arrays.copyOf(rows.get(0), 14);
        Object[] rb = java.util.Arrays.copyOf(rows.get(1), 14);
        ra[12] = idA; rb[12] = idB; ra[13] = rb[13] = "同名成品";
        when(query.getResultList()).thenReturn(List.of(ra, rb));
        return query;
    }

    @Test void productsUseStableSkuIdentityAndReuseTheAuthoritativeFormulas() {
        Query query = productRows("sku-a", "sku-b");
        LocalDate day = LocalDate.of(2026, 10, 16);
        var result = metrics.byProduct("FA", day, day, false);
        assertThat(result.getPeriodStart()).isEqualTo(day);
        assertThat(result.getSourceVersion()).isEqualTo("actual-flow-v3");
        assertThat(result.getProducts()).hasSize(2);
        var first = result.getProducts().get(0); var second = result.getProducts().get(1);
        assertThat(first.getBatchNumbers()).containsExactly("A");
        assertThat(second.getBatchNumbers()).containsExactly("B");
        assertThat(first.getYieldRate()).isEqualByComparingTo("80");
        assertThat(second.getYieldRate()).isEqualByComparingTo("100");
        assertThat(first.getGoodOutputRate()).isEqualByComparingTo("88.8889");
        assertThat(second.getGoodOutputRate()).isEqualByComparingTo("90.9091");
        assertThat(first.getUnitCost()).isEqualByComparingTo("12.5");
        assertThat(second.getUnitCost()).isEqualByComparingTo("10");
        assertThat(first.getTotalCost()).isEqualByComparingTo("100");
        verify(query).setParameter(1, "FA");
        verify(query).setParameter(3, day.plusDays(1).atStartOfDay());
    }

    @Test void sameSkuRatiosUseWeightsAndIncompleteCostDoesNotInvalidateOtherProducts() {
        productRows("sku-a", "sku-a"); b.setFirstStepInput(n("30"));
        var result = metrics.byProduct("FA", LocalDate.now(), LocalDate.now(), false);
        assertThat(result.getProducts()).hasSize(1);
        assertThat(result.getProducts().getFirst().getYieldRate()).isEqualByComparingTo("45");
        assertThat(result.getProducts().getFirst().getBatchCount()).isEqualTo(2);
        productRows("sku-a", "sku-b"); cb.setCostComplete(false);
        result = metrics.byProduct("FA", LocalDate.now(), LocalDate.now(), false);
        assertThat(result.getProducts().getFirst().getTotalCost()).isEqualByComparingTo("100");
        assertThat(result.getProducts().getLast().getTotalCost()).isNull();
        assertThat(result.getProducts().getLast().getGoodOutputRate()).isNotNull();
    }

    @Test void maskedCostsAreNotComputedAndNoDataIsAnEmptyPeriod() {
        Query query = productRows("sku-a", "sku-b");
        var result = metrics.byProduct("FA", LocalDate.now(), LocalDate.now(), true);
        assertThat(result.getProducts()).allSatisfy(p -> {
            assertThat(p.isCostMasked()).isTrue();
            assertThat(p.getTotalCost()).isNull(); assertThat(p.getUnitCost()).isNull();
        });
        verifyNoInteractions(costing);
        when(query.getResultList()).thenReturn(List.of());
        assertThat(metrics.byProduct("FA", LocalDate.now(), LocalDate.now(), false).getProducts()).isEmpty();
    }

    private record JointFixture(FactoryActualProductionMetrics metrics, Query completed, Query lines,
                                ProcessSheetService process, OrderCostBreakdownDTO cost,
                                List<Object[]> batches, List<Object[]> outputLines) {}

    private JointFixture jointFixture() {
        var summaries = mock(ProductionSummaryService.class);
        var process = mock(ProcessSheetService.class);
        when(summaries.computeSummary("FA", "P", true)).thenReturn(
                com.cretas.aims.dto.yield.ProductionSummaryDTO.builder()
                        .yieldRawDenominator(n("3")).totalFinishedWeight(n("3")).totalCost(n("31")).build());
        var cost = OrderCostBreakdownDTO.builder()
                .hasData(true).costComplete(true).calculationStatus("COMPLETE").totalCost(n("31"))
                .estimatedAuxItemCount(0).partiallyRecordedAuxItemCount(0).build();
        when(costing.computeByPlan("FA", "P", false)).thenReturn(cost);
        when(process.getInventoryYieldCard("FA", "P")).thenReturn(List.of(
                ProcessSheetInventoryItem.builder().batchNumber("A").productTypeId("sku-a").productWeight(n("1")).produced(n("2")).unit("盒").build(),
                ProcessSheetInventoryItem.builder().batchNumber("B").productTypeId("sku-b").productWeight(n("2")).produced(n("2")).unit("盒").build()));
        Object[] a = java.util.Arrays.copyOf(rows.get(0), 15), b = java.util.Arrays.copyOf(rows.get(1), 15);
        for (Object[] row : List.of(a,b)) { row[6]="S"; row[7]="NONE_CONFIRMED"; row[8]=BigDecimal.ZERO; row[9]="kg"; row[10]=2L; row[14]="P"; }
        a[12]="sku-a"; b[12]="sku-b"; a[13]="成品A"; b[13]="成品B";
        List<Object[]> outputLines = List.of(
                new Object[]{"A","sku-a",n("2"),"盒",n("11.7"),"NONE_CONFIRMED",BigDecimal.ZERO,"kg"},
                new Object[]{"B","sku-b",n("2"),"盒",n("19.3"),"NONE_CONFIRMED",BigDecimal.ZERO,"kg"});
        EntityManager em = mock(EntityManager.class); Query completed = mock(Query.class), lines = mock(Query.class);
        when(em.createNativeQuery(argThat(sql -> sql != null && sql.contains("SELECT b.id")))).thenReturn(completed);
        when(em.createNativeQuery(argThat(sql -> sql != null && sql.contains("SELECT o.reported_batch_number")))).thenReturn(lines);
        when(completed.setParameter(anyInt(), any())).thenReturn(completed);
        when(lines.setParameter(anyInt(), any())).thenReturn(lines);
        when(completed.getResultList()).thenReturn(List.of(a,b));
        when(lines.getResultList()).thenReturn(outputLines);
        var joint = new FactoryActualProductionMetrics(reporting, costing, units, summaries,
                new MultiOutputActualMetricsService(), process);
        ReflectionTestUtils.setField(joint, "entityManager", em);
        return new JointFixture(joint, completed, lines, process, cost, List.of(a,b), outputLines);
    }

    @Test void jointAdapterReadsPersistedProductWeightsAndAllocatedCostPerOutput() {
        var f = jointFixture();
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), YIELD_RATE)).isEqualByComparingTo("100");
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), TOTAL_COST)).isEqualByComparingTo("31");
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), UNIT_COST)).isEqualByComparingTo("10.3333");
        var products = f.metrics.byProduct("FA", LocalDate.now(), LocalDate.now(), false).getProducts();
        assertThat(products).hasSize(2);
        assertThat(products.getFirst().getYieldRate()).isEqualByComparingTo("33.3333");
        assertThat(products.getLast().getYieldRate()).isEqualByComparingTo("66.6667");
        assertThat(products.getFirst().getTotalCost()).isEqualByComparingTo("11.7");
        assertThat(products.getLast().getTotalCost()).isEqualByComparingTo("19.3");
        assertThat(products.getFirst().getUnitCost()).isEqualByComparingTo("11.7");
        assertThat(products.getLast().getUnitCost()).isEqualByComparingTo("9.65");
    }

    @Test void jointAdapterRejectsPartialWindowMissingSnapshotCostAndAmbiguousQuality() {
        var f = jointFixture();
        when(f.completed.getResultList()).thenReturn(java.util.Collections.singletonList(f.batches.getFirst()));
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), YIELD_RATE)).isNull();
        when(f.completed.getResultList()).thenReturn(f.batches);
        f.cost.setCostComplete(false);
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), TOTAL_COST)).isNull();
        f.cost.setCostComplete(true);
        when(f.process.getInventoryYieldCard("FA", "P")).thenReturn(List.of());
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), YIELD_RATE)).isNull();
    }

    @Test void jointQualityDoesNotSubtractRejectedQuantityFromQualifiedOutput() {
        var f = jointFixture();
        var summaries = (ProductionSummaryService) ReflectionTestUtils.getField(f.metrics, "productionSummaryService");
        when(summaries.computeSummary("FA", "P", true)).thenReturn(
                com.cretas.aims.dto.yield.ProductionSummaryDTO.builder().yieldRawDenominator(n("3")).totalFinishedWeight(n("1")).build());
        when(f.completed.getResultList()).thenReturn(java.util.Collections.singletonList(f.batches.getFirst()));
        var line = new Object[]{"A","sku-a",n("2"),"盒",n("31"),"COUNTED",n("0.1"),"kg"};
        when(f.lines.getResultList()).thenReturn(java.util.Collections.singletonList(line));
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), GOOD_OUTPUT_RATE)).isEqualByComparingTo("90.9091");
        line[5]="NONE_CONFIRMED";
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), GOOD_OUTPUT_RATE)).isNull();
        line[5]="COUNTED";
        when(f.lines.getResultList()).thenReturn(f.outputLines);
        f.outputLines.getFirst()[5]="COUNTED"; f.outputLines.getFirst()[6]=n("0.1");
        when(f.completed.getResultList()).thenReturn(f.batches);
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), GOOD_OUTPUT_RATE)).isNull();
    }

    @Test void jointQuantityQualityAndMaskedReadsDoNotRequireOrReadCost() {
        var f = jointFixture();
        f.cost.setCostComplete(false);
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), YIELD_RATE)).isEqualByComparingTo("100");
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), GOOD_OUTPUT_RATE)).isEqualByComparingTo("100");
        var products = f.metrics.byProduct("FA", LocalDate.now(), LocalDate.now(), true).getProducts();
        assertThat(products).allSatisfy(p -> {assertThat(p.getYieldRate()).isNotNull(); assertThat(p.getGoodOutputRate()).isNotNull(); assertThat(p.getTotalCost()).isNull();});
        verify(costing, never()).computeByPlan(any(), any(), anyBoolean());
    }

    @Test void jointMissingReusedRawLineageDoesNotPublishPartialYield() {
        var f = jointFixture();
        var summaries = (ProductionSummaryService) ReflectionTestUtils.getField(f.metrics, "productionSummaryService");
        when(summaries.computeSummary("FA", "P", true)).thenReturn(
                com.cretas.aims.dto.yield.ProductionSummaryDTO.builder().yieldRawDenominator(n("3"))
                        .totalFinishedWeight(n("3")).yieldNote("上游原料来源缺失").build());
        assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), YIELD_RATE)).isNull();
    }

    @Test void jointFormalOutputsMustCoverTheFullAuthoritativeFinishedWeight() {
        var f = jointFixture();
        var summaries = (ProductionSummaryService) ReflectionTestUtils.getField(f.metrics, "productionSummaryService");
        for (String weight : List.of("2", "4")) {
            when(summaries.computeSummary("FA", "P", true)).thenReturn(
                    com.cretas.aims.dto.yield.ProductionSummaryDTO.builder().yieldRawDenominator(n("3"))
                            .totalFinishedWeight(n(weight)).build());
            assertThat(f.metrics.compute("FA", LocalDate.now(), LocalDate.now(), YIELD_RATE)).isNull();
        }
    }
}
