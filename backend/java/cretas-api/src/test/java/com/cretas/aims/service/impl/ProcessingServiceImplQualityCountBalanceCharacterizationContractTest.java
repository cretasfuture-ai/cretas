package com.cretas.aims.service.impl;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.service.QualityInspectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Documents an existing, separate under-balance gap; passing is not a QA green for that rule. */
@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplQualityCountBalanceCharacterizationContractTest {
    @Mock private QualityInspectionService inspectionService;
    @InjectMocks private ProcessingServiceImpl service;

    @Test
    void existingUnderBalanceRemainsAcceptedOutsideTheRatePrecisionFix() {
        when(inspectionService.createInspection(eq("SIM24QA"), any(QualityInspection.class)))
                .thenAnswer(call -> call.getArgument(1));
        service.submitInspection("SIM24QA", "10", Map.of(
                "sampleSize", new BigDecimal("200"),
                "passCount", new BigDecimal("189"),
                "failCount", new BigDecimal("10"),
                "result", "PENDING"));
        ArgumentCaptor<QualityInspection> saved = ArgumentCaptor.forClass(QualityInspection.class);
        verify(inspectionService).createInspection(eq("SIM24QA"), saved.capture());
        QualityInspection row = saved.getValue();
        assertEquals(new BigDecimal("200"), row.getSampleSize());
        assertEquals(new BigDecimal("189"), row.getPassCount());
        assertEquals(new BigDecimal("10"), row.getFailCount());
        assertTrue(row.getPassCount().add(row.getFailCount()).compareTo(row.getSampleSize()) < 0,
                "Known separate gap: source only rejects totals greater than the sample");
    }
}
