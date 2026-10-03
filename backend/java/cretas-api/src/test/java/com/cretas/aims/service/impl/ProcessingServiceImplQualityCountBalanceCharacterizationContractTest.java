package com.cretas.aims.service.impl;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.QualityInspectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplQualityCountBalanceCharacterizationContractTest {
    @Mock private QualityInspectionService inspectionService;
    @InjectMocks private ProcessingServiceImpl service;

    @Test
    void rejectsUnderBalanceBeforeSavingTheInspection() {
        assertThrows(BusinessException.class, () -> service.submitInspection("SIM24QA", "10", Map.of(
                "sampleSize", new BigDecimal("200"),
                "passCount", new BigDecimal("189"),
                "failCount", new BigDecimal("10"),
                "result", "PENDING")));
        verifyNoInteractions(inspectionService);
    }
}
