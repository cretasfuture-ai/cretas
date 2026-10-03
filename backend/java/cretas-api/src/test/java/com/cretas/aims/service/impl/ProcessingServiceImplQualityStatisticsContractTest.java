package com.cretas.aims.service.impl;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@DisplayName("质量统计摘要：全周期、状态和缺失值必须保持真实口径")
class ProcessingServiceImplQualityStatisticsContractTest {

    private static final String FACTORY = "F006";
    private ProcessingServiceImpl service;
    private QualityInspectionRepository qualityInspectionRepository;

    @BeforeEach
    void setUp() {
        service = mock(ProcessingServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        qualityInspectionRepository = mock(QualityInspectionRepository.class);
        ReflectionTestUtils.setField(service, "qualityInspectionRepository", qualityInspectionRepository);
        ReflectionTestUtils.setField(service, "productionBatchRepository", mock(ProductionBatchRepository.class));
        ReflectionTestUtils.setField(service, "productTypeRepository", mock(ProductTypeRepository.class));
    }

    @Test
    @DisplayName("完整周期按 PASS/FAIL/CONDITIONAL/PENDING/未知/空状态分别计数")
    void qualitySummaryKeepsEveryResultStateSeparate() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 4);
        QualityInspection pass = inspection("PASS", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        QualityInspection fail = inspection("FAIL", BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ZERO, null);
        QualityInspection conditional = inspection("CONDITIONAL", BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.valueOf(50));
        QualityInspection pending = inspection("PENDING", null, null, null, null);
        QualityInspection unknown = inspection("REWORK", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.valueOf(100));
        QualityInspection empty = inspection("  ", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO);
        when(qualityInspectionRepository.findByFactoryIdAndDateRange(FACTORY, start, end))
                .thenReturn(List.of(pass, fail, conditional, pending, unknown, empty));

        Map<String, Object> response = service.getQualityStatistics(FACTORY, start, end);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) response.get("qualitySummary");
        assertThat(summary).containsEntry("startDate", start)
                .containsEntry("endDate", end)
                .containsEntry("totalInspections", 6L)
                .containsEntry("passedInspections", 1L)
                .containsEntry("failedInspections", 1L)
                .containsEntry("conditionalInspections", 1L)
                .containsEntry("pendingInspections", 1L)
                .containsEntry("unknownInspections", 1L)
                .containsEntry("emptyResultInspections", 1L)
                .containsEntry("reportedPassRateCount", 4L)
                .containsEntry("missingPassRateCount", 2L)
                .containsEntry("decisionPassRate", new BigDecimal("33.33"))
                .containsEntry("totalSampleSize", new BigDecimal("22"))
                .containsEntry("totalPassCount", new BigDecimal("13"))
                .containsEntry("totalFailCount", BigDecimal.ZERO)
                .containsEntry("missingSampleSizeCount", 1L);
        assertThat(summary.get("averagePassRate")).isEqualTo(new BigDecimal("37.50"));
        @SuppressWarnings("unchecked")
        Map<String, Long> statusCounts = (Map<String, Long>) summary.get("statusCounts");
        assertThat(statusCounts).containsEntry("PASS", 1L)
                .containsEntry("FAIL", 1L)
                .containsEntry("CONDITIONAL", 1L)
                .containsEntry("PENDING", 1L)
                .containsEntry("UNKNOWN", 1L)
                .containsEntry("EMPTY", 1L);

        // Legacy failedBatches remains non-PASS for existing consumers.
        assertThat(response).containsEntry("passedBatches", 1L).containsEntry("failedBatches", 5L);
    }

    @Test
    @DisplayName("空周期和全缺失值保持 null 率与零总量的区别")
    void emptyAndMissingValuesDoNotBecomeZeroRates() {
        LocalDate today = LocalDate.of(2026, 10, 4);
        QualityInspection missing = inspection(null, null, null, null, null);
        when(qualityInspectionRepository.findByFactoryIdAndDateRange(FACTORY, today, today))
                .thenReturn(List.of(missing));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) service
                .getQualityStatistics(FACTORY, today, today).get("qualitySummary");
        assertThat(summary).containsEntry("totalInspections", 1L)
                .containsEntry("averagePassRate", null)
                .containsEntry("decisionPassRate", null)
                .containsEntry("totalSampleSize", BigDecimal.ZERO)
                .containsEntry("missingSampleSizeCount", 1L)
                .containsEntry("missingPassCount", 1L)
                .containsEntry("missingFailCount", 1L);

        when(qualityInspectionRepository.findByFactoryIdAndDateRange(FACTORY, today, today))
                .thenReturn(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> emptySummary = (Map<String, Object>) service
                .getQualityStatistics(FACTORY, today, today).get("qualitySummary");
        assertThat(emptySummary).containsEntry("totalInspections", 0L)
                .containsEntry("averagePassRate", null)
                .containsEntry("decisionPassRate", null);
    }

    @Test
    @DisplayName("统计查询使用完整工厂周期，不依赖分页样本")
    void statisticsUsesRequestedPeriod() {
        LocalDate start = LocalDate.of(2026, 9, 28);
        LocalDate end = LocalDate.of(2026, 10, 4);
        when(qualityInspectionRepository.findByFactoryIdAndDateRange(FACTORY, start, end))
                .thenReturn(List.of());

        service.getQualityStatistics(FACTORY, start, end);

        ArgumentCaptor<LocalDate> starts = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> ends = ArgumentCaptor.forClass(LocalDate.class);
        verify(qualityInspectionRepository).findByFactoryIdAndDateRange(anyString(), starts.capture(), ends.capture());
        assertThat(starts.getValue()).isEqualTo(start);
        assertThat(ends.getValue()).isEqualTo(end);
    }

    @Test
    @DisplayName("入口二次过滤异工厂、空日期和周期外记录，并保留两个边界")
    void statisticsDefensivelyFiltersRepositoryRows() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 4);
        QualityInspection atStart = inspection(FACTORY, start, "PASS", BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
        QualityInspection atEnd = inspection(FACTORY, end, "FAIL", BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO);
        QualityInspection otherFactory = inspection("F007", end, "PASS", BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(100));
        QualityInspection before = inspection(FACTORY, start.minusDays(1), "PASS", BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(100));
        QualityInspection after = inspection(FACTORY, end.plusDays(1), "FAIL", BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO);
        QualityInspection missingDate = inspection(FACTORY, null, "PASS", BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(100));
        when(qualityInspectionRepository.findByFactoryIdAndDateRange(FACTORY, start, end))
                .thenReturn(Arrays.asList(atStart, atEnd, otherFactory, before, after, missingDate, null));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = service.getQualityStatistics(FACTORY, start, end);
        Map<String, Object> summary = (Map<String, Object>) response.get("qualitySummary");
        assertThat(response).containsEntry("totalInspections", 2)
                .containsEntry("passedBatches", 1L)
                .containsEntry("failedBatches", 1L);
        assertThat(summary).containsEntry("totalInspections", 2L)
                .containsEntry("passedInspections", 1L)
                .containsEntry("failedInspections", 1L)
                .containsEntry("decisionPassRate", new BigDecimal("50.00"));
    }

    private static QualityInspection inspection(String result, BigDecimal sampleSize, BigDecimal passCount,
                                                BigDecimal failCount, BigDecimal passRate) {
        return inspection(FACTORY, LocalDate.of(2026, 10, 4), result, sampleSize, passCount, failCount, passRate);
    }

    private static QualityInspection inspection(String factoryId, LocalDate date, String result,
                                                BigDecimal sampleSize, BigDecimal passCount,
                                                BigDecimal failCount, BigDecimal passRate) {
        return QualityInspection.builder()
                .factoryId(factoryId)
                .inspectionDate(date)
                .sampleSize(sampleSize)
                .passCount(passCount)
                .failCount(failCount)
                .passRate(passRate)
                .result(result)
                .build();
    }
}
