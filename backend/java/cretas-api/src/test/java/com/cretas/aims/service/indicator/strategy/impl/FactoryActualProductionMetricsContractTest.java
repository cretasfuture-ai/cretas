package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.service.unit.*;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.cretas.aims.service.yield.YieldReportService;
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
        assertThat(new FactoryLuYieldRateStrategy(metrics).getSourceVersion()).isEqualTo("actual-flow-v2");
        assertThat(new FactoryLuUnitCostStrategy(metrics).getSourceVersion()).isEqualTo("actual-flow-v2");
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
        assertThat(result.getSourceVersion()).isEqualTo("actual-flow-v2");
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
}
