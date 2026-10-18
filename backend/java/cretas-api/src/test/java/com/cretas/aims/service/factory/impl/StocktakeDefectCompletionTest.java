package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.StocktakeDefectCountRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.enums.DefectCountOutcome;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StocktakeDefectCompletionTest {
    @Mock ProductionSettlementRepository settlements;
    @Mock ProductionBatchRepository batches;
    @InjectMocks FactoryStocktakeServiceImpl service;

    private ProductionSettlement pending(String factory, String plan) {
        ProductionSettlement s = new ProductionSettlement();
        s.setFactoryId(factory); s.setProductionPlanId(plan);
        s.setDefectCountOutcome(DefectCountOutcome.NOT_COUNTED);
        return s;
    }

    private StocktakeDefectCountRequest request(DefectCountOutcome outcome, String quantity) {
        StocktakeDefectCountRequest r = new StocktakeDefectCountRequest();
        r.setOutcome(outcome);
        r.setDefectQuantity(quantity == null ? null : new BigDecimal(quantity));
        return r;
    }

    @Test void countedBackfillsTheOnlyFormalBatch() {
        var s = pending("F006", "P1");
        s.setActualFinishedQuantity(new BigDecimal("8"));
        s.setQuantityUnit("kg");
        var b = legacyGoodBatch("F006", "P1", "8", "kg");
        when(settlements.findPendingDefectCounts("F006")).thenReturn(List.of(s));
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate("F006", "P1")).thenReturn(Optional.of(s));
        when(batches.findByFactoryIdAndProductionPlanId("F006", "P1")).thenReturn(List.of(b));

        var result = service.completePendingDefectCount("F006", "P1", request(DefectCountOutcome.COUNTED, "2"), 7L);

        assertEquals(Boolean.TRUE, result.get("backfilled"));
        assertEquals(new BigDecimal("2"), result.get("defectQuantity"));
        assertEquals(1, result.get("formalBatchCount"));
        assertEquals(7L, result.get("completedBy"));
        assertEquals(new BigDecimal("2"), b.getDefectQuantity());
        assertEquals(new BigDecimal("8"), b.getGoodQuantity());
        assertEquals(new BigDecimal("10"), b.getActualQuantity());
        assertEquals(new BigDecimal("80.00"), b.getYieldRate());
        verify(batches).save(b); verify(settlements).save(s);
    }

    @Test void zeroConfirmationBackfillsZeroOnTheOnlyFormalBatch() {
        var s = pending("F006", "P1");
        s.setActualFinishedQuantity(new BigDecimal("8"));
        s.setQuantityUnit("kg");
        var b = legacyGoodBatch("F006", "P1", "8", "kg");
        when(settlements.findPendingDefectCounts("F006")).thenReturn(List.of(s));
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate("F006", "P1")).thenReturn(Optional.of(s));
        when(batches.findByFactoryIdAndProductionPlanId("F006", "P1"))
                .thenReturn(List.of(b));

        var result = service.completePendingDefectCount("F006", "P1", request(DefectCountOutcome.NONE_CONFIRMED, null), 7L);

        assertEquals(Boolean.TRUE, result.get("backfilled"));
        assertEquals(BigDecimal.ZERO, result.get("defectQuantity"));
        org.junit.jupiter.api.Assertions.assertTrue(
                String.valueOf(result.get("message")).contains("已清点确认零"));
        assertEquals(BigDecimal.ZERO, s.getDefectQuantity());
        assertEquals(new BigDecimal("8"), b.getGoodQuantity());
        assertEquals(new BigDecimal("8"), b.getActualQuantity());
        assertEquals(new BigDecimal("100.00"), b.getYieldRate());
        verify(batches).save(any());
    }

    @Test void contradictoryQuantityIsRejectedBeforeWrites() {
        var s = pending("F006", "P1");
        when(settlements.findPendingDefectCounts("F006")).thenReturn(List.of(s));
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate("F006", "P1")).thenReturn(Optional.of(s));

        assertThrows(RuntimeException.class, () -> service.completePendingDefectCount(
                "F006", "P1", request(DefectCountOutcome.NONE_CONFIRMED, "1"), 7L));
        verify(settlements, never()).save(any());
    }

    @Test void multipleBatchesAreExplicitlyNotGuessed() {
        var s = pending("F006", "P1");
        when(settlements.findPendingDefectCounts("F006")).thenReturn(List.of(s));
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate("F006", "P1")).thenReturn(Optional.of(s));
        when(batches.findByFactoryIdAndProductionPlanId("F006", "P1")).thenReturn(List.of(
                legacyGoodBatch("F006", "P1", "5", "kg"),
                legacyGoodBatch("F006", "P1", "5", "kg")));

        var result = service.completePendingDefectCount("F006", "P1", request(DefectCountOutcome.COUNTED, "2"), 7L);

        assertEquals(Boolean.FALSE, result.get("backfilled"));
        assertEquals(2, result.get("formalBatchCount"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "AMBIGUOUS_BATCH_COUNT:2", result.get("backfillSkipReason"));
        assertEquals(DefectCountOutcome.COUNTED, s.getDefectCountOutcome());
        assertEquals(new BigDecimal("2"), s.getDefectQuantity());
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(result.get("message")).contains("多个正式批次"));
        verify(batches, never()).save(any());
        verify(settlements).save(s);
    }

    @Test void missingGoodOutputDoesNotGuessBatchQuality() {
        var s = pending("F006", "P1");
        s.setActualFinishedQuantity(new BigDecimal("8"));
        s.setQuantityUnit("kg");
        var b = new ProductionBatch();
        b.setFactoryId("F006");
        b.setProductionPlanId("P1");
        b.setActualQuantity(new BigDecimal("8"));
        b.setUnit("kg");
        when(settlements.findPendingDefectCounts("F006")).thenReturn(List.of(s));
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate("F006", "P1"))
                .thenReturn(Optional.of(s));
        when(batches.findByFactoryIdAndProductionPlanId("F006", "P1")).thenReturn(List.of(b));

        var result = service.completePendingDefectCount(
                "F006", "P1", request(DefectCountOutcome.COUNTED, "2"), 7L);

        assertEquals(Boolean.FALSE, result.get("backfilled"));
        assertEquals("LEGACY_GOOD_OUTPUT_NOT_PROVABLE", result.get("backfillSkipReason"));
        verify(batches, never()).save(any());
        verify(settlements).save(s);
    }

    @Test void unitMismatchDoesNotGuessBatchQuality() {
        var s = pending("F006", "P1");
        s.setActualFinishedQuantity(new BigDecimal("8"));
        s.setQuantityUnit("box");
        var b = legacyGoodBatch("F006", "P1", "8", "kg");
        when(settlements.findPendingDefectCounts("F006")).thenReturn(List.of(s));
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate("F006", "P1"))
                .thenReturn(Optional.of(s));
        when(batches.findByFactoryIdAndProductionPlanId("F006", "P1")).thenReturn(List.of(b));

        var result = service.completePendingDefectCount(
                "F006", "P1", request(DefectCountOutcome.COUNTED, "2"), 7L);

        assertEquals(Boolean.FALSE, result.get("backfilled"));
        verify(batches, never()).save(any());
        verify(settlements).save(s);
    }

    @Test void clerkWipIsExcludedFromFormalBatchBackfill() {
        var s = pending("F006", "P1");
        s.setActualFinishedQuantity(new BigDecimal("8"));
        s.setQuantityUnit("kg");
        var clerk = legacyGoodBatch("F006", "P1", "8", "kg");
        clerk.setBatchType("CLERK_WIP");
        when(settlements.findPendingDefectCounts("F006")).thenReturn(List.of(s));
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate("F006", "P1"))
                .thenReturn(Optional.of(s));
        when(batches.findByFactoryIdAndProductionPlanId("F006", "P1")).thenReturn(List.of(clerk));

        var result = service.completePendingDefectCount(
                "F006", "P1", request(DefectCountOutcome.COUNTED, "2"), 7L);

        assertEquals(Boolean.FALSE, result.get("backfilled"));
        assertEquals(0, result.get("formalBatchCount"));
        verify(batches, never()).save(any());
        verify(settlements).save(s);
    }

    @Test void crossFactoryPendingIsRejected() {
        when(settlements.findPendingDefectCounts("F007")).thenReturn(List.of(pending("F007", "P1")));
        assertThrows(RuntimeException.class, () -> service.completePendingDefectCount(
                "F006", "P1", request(DefectCountOutcome.NONE_CONFIRMED, null), 7L));
        verify(settlements, never()).findByFactoryIdAndProductionPlanIdForUpdate(any(), any());
    }

    @Test void completedSettlementCannotBeRepeatedAfterLock() {
        var s = pending("F006", "P1"); s.setDefectCountOutcome(DefectCountOutcome.NONE_CONFIRMED);
        when(settlements.findPendingDefectCounts("F006")).thenReturn(List.of(s));
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate("F006", "P1")).thenReturn(Optional.of(s));
        assertThrows(RuntimeException.class, () -> service.completePendingDefectCount(
                "F006", "P1", request(DefectCountOutcome.NONE_CONFIRMED, null), 7L));
        verify(settlements, never()).save(any());
    }

    private ProductionBatch legacyGoodBatch(String factory, String plan, String quantity, String unit) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(factory);
        b.setProductionPlanId(plan);
        b.setQuantity(new BigDecimal(quantity));
        b.setActualQuantity(new BigDecimal(quantity));
        b.setGoodQuantity(new BigDecimal(quantity));
        b.setUnit(unit);
        return b;
    }
}
