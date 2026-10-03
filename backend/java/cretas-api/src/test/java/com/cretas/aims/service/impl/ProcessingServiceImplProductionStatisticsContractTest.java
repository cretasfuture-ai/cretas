package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

@DisplayName("生产统计摘要：窗口、状态和单位必须保持真实口径")
class ProcessingServiceImplProductionStatisticsContractTest {

    private static final String FACTORY = "F006";
    private ProcessingServiceImpl service;
    private ProductionBatchRepository batchRepository;
    private ProductTypeRepository productTypeRepository;

    @BeforeEach
    void setUp() {
        service = mock(ProcessingServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        batchRepository = mock(ProductionBatchRepository.class);
        productTypeRepository = mock(ProductTypeRepository.class);
        ReflectionTestUtils.setField(service, "productionBatchRepository", batchRepository);
        ReflectionTestUtils.setField(service, "productTypeRepository", productTypeRepository);
        when(batchRepository.findByFactoryIdAndCreatedAtAfter(anyString(), any())).thenReturn(List.of());
        when(productTypeRepository.findByIdIn(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("week 是含今日的 7 个自然日，并排除异厂、边界外和未来行")
    void weekWindowIsInclusiveForSevenCalendarDays() {
        LocalDate today = LocalDate.now();
        ProductionBatch valid = batch(FACTORY, today.minusDays(6).atStartOfDay(), ProductionBatchStatus.IN_PROGRESS,
                "kg", new BigDecimal("1"));
        ProductionBatch otherFactory = batch("F007", today.minusDays(1).atStartOfDay(), ProductionBatchStatus.COMPLETED,
                "kg", new BigDecimal("9"));
        ProductionBatch future = batch(FACTORY, today.plusDays(1).atStartOfDay(), ProductionBatchStatus.COMPLETED,
                "kg", new BigDecimal("9"));
        ProductionBatch beforeWindow = batch(FACTORY, today.minusDays(7).atStartOfDay(), ProductionBatchStatus.COMPLETED,
                "kg", new BigDecimal("9"));
        ProductionBatch clerkWip = batch(FACTORY, today.minusDays(1).atStartOfDay(), ProductionBatchStatus.COMPLETED,
                "kg", new BigDecimal("9"));
        clerkWip.setBatchType("CLERK_WIP");
        when(batchRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(valid, otherFactory, future, beforeWindow, clerkWip));

        Map<String, Object> summary = summary("week");

        assertThat(summary).containsEntry("period", "week").containsEntry("totalBatches", 1L);
        assertThat(summary.get("startDate")).isEqualTo(today.minusDays(6));
        assertThat(summary.get("endDate")).isEqualTo(today);
        ArgumentCaptor<LocalDateTime> starts = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> ends = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(batchRepository).findByFactoryIdAndCreatedAtBetween(anyString(), starts.capture(), ends.capture());
        assertThat(starts.getValue()).isEqualTo(today.minusDays(6).atStartOfDay());
        assertThat(ends.getValue()).isEqualTo(today.plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("状态分母包含所有状态，0 是已上报数量，NULL 是缺失，单位不换算")
    void summaryPreservesStatusAndRawUnitQuantities() {
        ProductionBatch completedZero = batch(FACTORY, LocalDateTime.now(), ProductionBatchStatus.COMPLETED,
                " kg ", BigDecimal.ZERO);
        ProductionBatch inProgressMissing = batch(FACTORY, LocalDateTime.now(), ProductionBatchStatus.IN_PROGRESS,
                "kg", null);
        ProductionBatch unknownUnit = batch(FACTORY, LocalDateTime.now(), ProductionBatchStatus.CANCELLED,
                "crate", new BigDecimal("2"));
        ProductionBatch missingUnit = batch(FACTORY, LocalDateTime.now(), ProductionBatchStatus.PLANNED,
                "  ", null);
        when(batchRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(completedZero, inProgressMissing, unknownUnit, missingUnit));

        Map<String, Object> summary = summary("today");

        assertThat(summary).containsEntry("totalBatches", 4L)
                .containsEntry("completedBatches", 1L)
                .containsEntry("missingUnitCount", 1L)
                .containsEntry("missingActualQuantityCount", 2L)
                .containsEntry("unknownUnitCount", 1L)
                .containsEntry("completionRate", new BigDecimal("25.00"));
        @SuppressWarnings("unchecked")
        Map<String, Long> statusCounts = (Map<String, Long>) summary.get("statusCounts");
        assertThat(statusCounts).containsEntry("COMPLETED", 1L)
                .containsEntry("IN_PROGRESS", 1L)
                .containsEntry("CANCELLED", 1L)
                .containsEntry("PLANNED", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> units = (List<Map<String, Object>>) summary.get("unitBreakdown");
        assertThat(units).extracting(unit -> unit.get("unit")).containsExactly("kg", "crate");
        assertThat(units.get(0)).containsEntry("reportedQuantity", BigDecimal.ZERO)
                .containsEntry("reportedBatches", 1L)
                .containsEntry("missingActualQuantityBatches", 1L);
        assertThat(units.get(1)).containsEntry("reportedQuantity", new BigDecimal("2"));
    }

    @Test
    @DisplayName("无批次时完成率保持未知")
    void emptySummaryDoesNotInventZeroRate() {
        when(batchRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any())).thenReturn(List.of());

        Map<String, Object> summary = summary("month");

        assertThat(summary).containsEntry("totalBatches", 0L)
                .containsEntry("completedBatches", 0L)
                .containsEntry("completionRate", null);
    }

    @Test
    @DisplayName("所有 actualQuantity 都是 NULL 时，不伪造已上报数量")
    void allMissingActualQuantitiesStayMissing() {
        ProductionBatch kg = batch(FACTORY, LocalDateTime.now(), ProductionBatchStatus.IN_PROGRESS,
                "kg", null);
        ProductionBatch crate = batch(FACTORY, LocalDateTime.now(), ProductionBatchStatus.IN_PROGRESS,
                "crate", null);
        when(batchRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(kg, crate));

        Map<String, Object> summary = summary("today");

        assertThat(summary).containsEntry("missingActualQuantityCount", 2L)
                .containsEntry("unknownUnitCount", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> units = (List<Map<String, Object>>) summary.get("unitBreakdown");
        assertThat(units).allSatisfy(unit -> {
            assertThat(unit.get("reportedBatches")).isEqualTo(0L);
            assertThat(unit.get("reportedQuantity")).isEqualTo(BigDecimal.ZERO);
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(String period) {
        return (Map<String, Object>) service.getProductionStatistics(FACTORY, period).get("productionSummary");
    }

    private static ProductionBatch batch(String factoryId, LocalDateTime createdAt, ProductionBatchStatus status,
                                         String unit, BigDecimal actualQuantity) {
        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(factoryId);
        batch.setCreatedAt(createdAt);
        batch.setStatus(status);
        batch.setUnit(unit);
        batch.setActualQuantity(actualQuantity);
        return batch;
    }
}
