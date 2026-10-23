package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityCheckItemBindingRepository;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Calls the actual service entry; mocked persistence keeps this suite isolated. */
@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplQualityRatePrecisionContractTest {
    private static final String FACTORY = "SIM24QA";

    @Mock private QualityInspectionService inspectionService;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private QualityCheckItemBindingRepository qualityCheckItemBindingRepository;
    @InjectMocks private ProcessingServiceImpl service;

    @BeforeEach
    void preserveTheActualServiceEntityAtThePersistenceBoundary() {
        ReflectionTestUtils.setField(service, "qualityCheckItemBindingRepository", qualityCheckItemBindingRepository);
        ProductionBatch batch = ProductionBatch.builder().id(10L).factoryId(FACTORY)
                .productTypeId("QA-RATE-PRECISION-LEGACY").build();
        lenient().when(productionBatchRepository.findById(10L)).thenReturn(Optional.of(batch));
        lenient().when(inspectionService.createInspection(eq(FACTORY), any(QualityInspection.class)))
                .thenAnswer(call -> call.getArgument(1));
    }

    private Map<String, Object> payload(String sample, String passed, String failed, String result) {
        Map<String, Object> body = new HashMap<>();
        body.put("sampleSize", new BigDecimal(sample));
        body.put("passCount", new BigDecimal(passed));
        body.put("failCount", new BigDecimal(failed));
        body.put("result", result);
        body.put("inspectorId", 82L);
        body.put("notes", "QC-RATE isolated direct-service regression");
        return body;
    }

    @ParameterizedTest
    @CsvSource({
            "200,189,11,PENDING,94.50",
            "200,0,200,FAIL,0.00",
            "200,200,0,PASS,100.00",
            "3,1,2,PENDING,33.33",
            "3.00,1.00,2.00,PENDING,33.33",
            "10000,9499,501,CONDITIONAL,94.99",
            "10000,9500,500,PASS,95.00",
            "10000,6999,3001,FAIL,69.99",
            "10000,7000,3000,CONDITIONAL,70.00"
    })
    void savesPercentagePrecisionAndPreservesAllInputCounts(
            String sample, String passed, String failed, String verdict, String rate) {
        Map<String, Object> result = service.submitInspection(FACTORY, "10",
                payload(sample, passed, failed, verdict));
        ArgumentCaptor<QualityInspection> saved = ArgumentCaptor.forClass(QualityInspection.class);
        verify(inspectionService).createInspection(eq(FACTORY), saved.capture());
        QualityInspection row = saved.getValue();
        assertEquals(new BigDecimal(rate), row.getPassRate());
        assertEquals(new BigDecimal(rate), result.get("passRate"));
        assertSame(row, result.get("inspection"));
        assertEquals(new BigDecimal(sample), row.getSampleSize());
        assertEquals(new BigDecimal(passed), row.getPassCount());
        assertEquals(new BigDecimal(failed), row.getFailCount());
        assertEquals(verdict, row.getResult());
        assertEquals(Long.valueOf(82), row.getInspectorId());
        assertEquals(Long.valueOf(10), row.getProductionBatchId());
        assertEquals(FACTORY, row.getFactoryId());
        // The existing entity already computes defect percentage at scale 2.
        assertEquals(2, row.getDefectRate().scale());
    }

    @ParameterizedTest
    @CsvSource({
            "200,189,11,PASS",
            "10000,9499,501,PASS",
            "10000,9500,500,CONDITIONAL",
            "10000,6999,3001,CONDITIONAL",
            "10000,7000,3000,FAIL"
    })
    void appliesExisting95And70VerdictBoundariesToTheCorrectPercentage(
            String sample, String passed, String failed, String verdict) {
        assertThrows(BusinessException.class, () -> service.submitInspection(FACTORY, "10",
                payload(sample, passed, failed, verdict)));
        verifyNoInteractions(inspectionService);
    }

    @Test
    void retainsFactoryThresholdOverridesWithoutChangingPolicy() {
        ThresholdResolverService resolver = mock(ThresholdResolverService.class);
        when(resolver.getBigDecimal(eq(FACTORY), eq(ThresholdKeys.PROCESSING_PASS_THRESHOLD), any()))
                .thenReturn(new BigDecimal("90"));
        when(resolver.getBigDecimal(eq(FACTORY), eq(ThresholdKeys.PROCESSING_FAIL_THRESHOLD), any()))
                .thenReturn(new BigDecimal("60"));
        ReflectionTestUtils.setField(service, "thresholdResolver", resolver);
        assertEquals(new BigDecimal("90.00"), service.submitInspection(FACTORY, "10",
                payload("100", "90", "10", "PASS")).get("passRate"));
        clearInvocations(inspectionService);
        assertThrows(BusinessException.class, () -> service.submitInspection(FACTORY, "10",
                payload("10000", "8999", "1001", "PASS")));
        verifyNoInteractions(inspectionService);
    }

    @ParameterizedTest
    @CsvSource({
            "0,0,0,PENDING",
            "200,-1,201,PENDING",
            "200,201,-1,PENDING",
            "200,189,12,PENDING"
    })
    void keepsExistingZeroNegativeAndOverTotalGuardsSeparateFromRatePrecision(
            String sample, String passed, String failed, String verdict) {
        assertThrows(BusinessException.class, () -> service.submitInspection(FACTORY, "10",
                payload(sample, passed, failed, verdict)));
        verifyNoInteractions(inspectionService);
    }
}
