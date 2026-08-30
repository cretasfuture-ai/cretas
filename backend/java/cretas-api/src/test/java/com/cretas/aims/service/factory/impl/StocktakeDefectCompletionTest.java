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
        var b = new ProductionBatch();
        when(settlements.findPendingDefectCounts("F006")).thenReturn(List.of(s));
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate("F006", "P1")).thenReturn(Optional.of(s));
        when(batches.findByFactoryIdAndProductionPlanId("F006", "P1")).thenReturn(List.of(b));

        var result = service.completePendingDefectCount("F006", "P1", request(DefectCountOutcome.COUNTED, "2"), 7L);

        assertEquals(Boolean.TRUE, result.get("backfilled"));
        assertEquals(new BigDecimal("2"), b.getDefectQuantity());
        verify(batches).save(b); verify(settlements).save(s);
    }

    @Test void zeroConfirmationBackfillsZeroOnTheOnlyFormalBatch() {
        var s = pending("F006", "P1");
        when(settlements.findPendingDefectCounts("F006")).thenReturn(List.of(s));
        when(settlements.findByFactoryIdAndProductionPlanIdForUpdate("F006", "P1")).thenReturn(Optional.of(s));
        when(batches.findByFactoryIdAndProductionPlanId("F006", "P1")).thenReturn(List.of(new ProductionBatch()));

        var result = service.completePendingDefectCount("F006", "P1", request(DefectCountOutcome.NONE_CONFIRMED, null), 7L);

        assertEquals(Boolean.TRUE, result.get("backfilled"));
        assertEquals(BigDecimal.ZERO, s.getDefectQuantity());
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
        when(batches.findByFactoryIdAndProductionPlanId("F006", "P1")).thenReturn(List.of(new ProductionBatch(), new ProductionBatch()));

        var result = service.completePendingDefectCount("F006", "P1", request(DefectCountOutcome.COUNTED, "2"), 7L);

        assertEquals(Boolean.FALSE, result.get("backfilled"));
        assertEquals(DefectCountOutcome.COUNTED, s.getDefectCountOutcome());
        assertEquals(new BigDecimal("2"), s.getDefectQuantity());
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(result.get("message")).contains("多个正式批次"));
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
}
