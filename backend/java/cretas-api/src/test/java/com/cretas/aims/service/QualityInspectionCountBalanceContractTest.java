package com.cretas.aims.service;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.impl.ProcessingServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QualityInspectionCountBalanceContractTest {
    private static final String FACTORY = "ORG-G5-COUNT-BALANCE";

    @Mock private QualityInspectionService inspectionService;
    @InjectMocks private ProcessingServiceImpl service;

    @BeforeAll
    static void verifiesActualServiceClassOriginAndReportsItsHash() throws Exception {
        Path origin = Path.of(ProcessingServiceImpl.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        Path expected = Path.of(System.getProperty("g5.processingClassRoot",
                Path.of("target", "classes").toAbsolutePath().toString())).toRealPath();
        assertEquals(expected, origin);
        try (var classBytes = ProcessingServiceImpl.class.getResourceAsStream("ProcessingServiceImpl.class")) {
            assertNotNull(classBytes);
            String classHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(classBytes.readAllBytes()));
            String expectedHash = System.getProperty("g5.processingClassSHA256");
            if (expectedHash != null) {
                assertEquals(expectedHash, classHash);
            }
            System.out.println("QUALITY_COUNT_BALANCE_CLASS origin=" + origin + " sha256=" + classHash);
        }
    }

    @BeforeEach
    void preservesTheActualEntityAtThePersistenceBoundary() {
        lenient().when(inspectionService.createInspection(eq(FACTORY), any(QualityInspection.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private Map<String, Object> payload(String sample, String passed, String failed, String result) {
        Map<String, Object> body = new HashMap<>();
        body.put("sampleSize", new BigDecimal(sample));
        body.put("passCount", new BigDecimal(passed));
        body.put("failCount", new BigDecimal(failed));
        body.put("result", result);
        body.put("inspectorId", 82L);
        return body;
    }

    @ParameterizedTest
    @CsvSource({
            "200,189,10,PENDING",
            "10,9,0,PENDING",
            "3.25,2.00,1.00,PENDING",
            "1,0,0,PENDING"
    })
    void rejectsUnclassifiedSampleRemainderBeforePersistence(
            String sample, String passed, String failed, String verdict) {
        assertThrows(BusinessException.class, () -> service.submitInspection(FACTORY, "10",
                payload(sample, passed, failed, verdict)));
        verifyNoInteractions(inspectionService);
    }

    @ParameterizedTest
    @CsvSource({
            "200,189,11,CONDITIONAL,94.50",
            "10,10,0,PASS,100.00",
            "2.50,2.25,0.25,CONDITIONAL,90.00",
            "3.25,2.00,1.25,PENDING,61.54",
            "0.03,0.01,0.02,PENDING,33.33",
            "10000,9500,500,PASS,95.00",
            "10000,7000,3000,CONDITIONAL,70.00",
            "200,0,200,FAIL,0.00",
            "2.50,2.5,0,PASS,100.00"
    })
    void acceptsCompleteIntegerAndFractionalSamplesWithoutChangingInputQuantities(
            String sample, String passed, String failed, String verdict, String expectedRate) {
        Map<String, Object> response = service.submitInspection(FACTORY, "10",
                payload(sample, passed, failed, verdict));
        ArgumentCaptor<QualityInspection> persisted = ArgumentCaptor.forClass(QualityInspection.class);
        verify(inspectionService).createInspection(eq(FACTORY), persisted.capture());
        QualityInspection inspection = persisted.getValue();
        assertEquals(new BigDecimal(sample), inspection.getSampleSize());
        assertEquals(new BigDecimal(passed), inspection.getPassCount());
        assertEquals(new BigDecimal(failed), inspection.getFailCount());
        assertEquals(new BigDecimal(expectedRate), inspection.getPassRate());
        assertEquals(new BigDecimal(expectedRate), response.get("passRate"));
        assertSame(inspection, response.get("inspection"));
        assertEquals(verdict, inspection.getResult());
        assertEquals(FACTORY, inspection.getFactoryId());
        assertEquals(Long.valueOf(10), inspection.getProductionBatchId());
        assertEquals(Long.valueOf(82), inspection.getInspectorId());
    }

    @ParameterizedTest
    @CsvSource({
            "0,0,0",
            "-1,0,0",
            "200,-1,201",
            "200,201,-1",
            "200,189,12"
    })
    void keepsZeroNegativeAndOverSampleGuards(String sample, String passed, String failed) {
        assertThrows(BusinessException.class, () -> service.submitInspection(FACTORY, "10",
                payload(sample, passed, failed, "PENDING")));
        verifyNoInteractions(inspectionService);
    }

    @ParameterizedTest
    @CsvSource({
            "200,189,11,PASS",
            "10000,9499,501,PASS",
            "10000,6999,3001,CONDITIONAL"
    })
    void preservesVerdictThresholdChecksAfterSampleBalance(
            String sample, String passed, String failed, String verdict) {
        assertThrows(BusinessException.class, () -> service.submitInspection(FACTORY, "10",
                payload(sample, passed, failed, verdict)));
        verifyNoInteractions(inspectionService);
    }
}
