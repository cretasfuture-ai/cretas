package com.cretas.aims.service.impl;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.ArgumentMatchers.eq;

@DisplayName("质量统计摘要：全周期、状态和缺失值必须保持真实口径")
class ProcessingServiceImplQualityStatisticsContractTest {

    private static final String FACTORY = "F006";
    private ProcessingServiceImpl service;
    private QualityInspectionRepository qualityInspectionRepository;
    private ProductionBatchRepository productionBatchRepository;

    @BeforeEach
    void setUp() {
        service = mock(ProcessingServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        qualityInspectionRepository = mock(QualityInspectionRepository.class);
        ReflectionTestUtils.setField(service, "qualityInspectionRepository", qualityInspectionRepository);
        productionBatchRepository = mock(ProductionBatchRepository.class);
        ReflectionTestUtils.setField(service, "productionBatchRepository", productionBatchRepository);
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

        assertThat(response).containsEntry("passedBatches", 1L)
                .containsEntry("failedBatches", 1L)
                .containsEntry("conditionalBatches", 1L)
                .containsEntry("pendingBatches", 1L);
        @SuppressWarnings("unchecked")
        Map<String, Long> breakdown = (Map<String, Long>) response.get("breakdown");
        assertThat(breakdown).containsEntry("pass", 1L)
                .containsEntry("fail", 1L)
                .containsEntry("conditional", 1L)
                .containsEntry("pending", 1L)
                .containsEntry("unknown", 1L)
                .containsEntry("empty", 1L);
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
    @DisplayName("旧式终态别名归入真实桶，不把未知值伪造成失败")
    void legacyTerminalAliasesUseTheirRealBuckets() {
        LocalDate date = LocalDate.of(2026, 10, 4);
        when(qualityInspectionRepository.findByFactoryIdAndDateRange(FACTORY, date, date))
                .thenReturn(List.of(inspection("PASSED", BigDecimal.ONE, BigDecimal.ONE,
                                BigDecimal.ZERO, BigDecimal.valueOf(100)),
                        inspection("FAILED", BigDecimal.ONE, BigDecimal.ZERO,
                                BigDecimal.ONE, BigDecimal.ZERO),
                        inspection("PARTIAL_PASS", BigDecimal.ONE, BigDecimal.ONE,
                                BigDecimal.ZERO, BigDecimal.valueOf(80)),
                        inspection("UNKNOWN", BigDecimal.ONE, BigDecimal.ZERO,
                                BigDecimal.ZERO, BigDecimal.ZERO)));

        Map<String, Object> response = service.getQualityStatistics(FACTORY, date, date);

        assertThat(response).containsEntry("passedBatches", 1L)
                .containsEntry("failedBatches", 1L)
                .containsEntry("conditionalBatches", 1L)
                .containsEntry("pendingBatches", 0L);
        @SuppressWarnings("unchecked")
        Map<String, Long> breakdown = (Map<String, Long>) response.get("breakdown");
        assertThat(breakdown).containsEntry("pass", 1L)
                .containsEntry("fail", 1L)
                .containsEntry("conditional", 1L)
                .containsEntry("unknown", 1L);
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
    @DisplayName("待检任务只从 PENDING 观察分页读取，已有最终判定的旧 PENDING 不再回流")
    void qualityPendingQueueUsesPendingObservationAndEffectiveVerdict() {
        QualityInspection pending = inspection("PENDING", null, null, null, null);
        pending.setProductionBatchId(10806L);
        ProductionBatch batch = new ProductionBatch();
        batch.setId(10806L);
        batch.setFactoryId(FACTORY);
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        when(qualityInspectionRepository.findEffectivePendingByFactoryId(eq(FACTORY), any()))
                .thenReturn(new PageImpl<>(List.of(pending), PageRequest.of(0, 20), 1));
        when(productionBatchRepository.findByIdAndFactoryId(10806L, FACTORY)).thenReturn(java.util.Optional.of(batch));
        assertThat(service.getQualityPendingBatches(FACTORY, pageRequest(1, 20))
                .getContent()).hasSize(1);

        QualityInspection pass = inspection("PASS", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.valueOf(100));
        pass.setProductionBatchId(10806L);
        when(qualityInspectionRepository.findEffectivePendingByFactoryId(eq(FACTORY), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        assertThat(service.getQualityPendingBatches(FACTORY, pageRequest(1, 20)).getContent()).isEmpty();
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

    private static com.cretas.aims.dto.common.PageRequest pageRequest(int page, int size) {
        com.cretas.aims.dto.common.PageRequest request = new com.cretas.aims.dto.common.PageRequest();
        request.setPage(page);
        request.setSize(size);
        return request;
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
