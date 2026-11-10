package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WorkReportResponse;
import com.cretas.aims.dto.WorkReportSubmitRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ReportMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.unit.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 缺口21: App 报工产量没有单位, 数字直接加进批次 actualQuantity。 */
class WorkReportingUnitContractTest {

    private final ProductionReportRepository reportRepository = mock(ProductionReportRepository.class);
    private final ProductionBatchRepository batchRepository = mock(ProductionBatchRepository.class);
    private final UnitContractService units = mock(UnitContractService.class);
    private WorkReportingServiceImpl service;
    private ProductionBatch batch;

    @BeforeEach
    void setUp() {
        service = new WorkReportingServiceImpl(reportRepository, mock(BatchWorkSessionRepository.class),
                batchRepository, mock(UserRepository.class), mock(ApplicationEventPublisher.class), units,
                new com.cretas.aims.util.BackdateWindowValidator());
        batch = ProductionBatch.builder().id(1L).factoryId("F006").productTypeId("PT1")
                .quantity(new BigDecimal("100")).plannedQuantity(new BigDecimal("1000")).unit("kg")
                .actualQuantity(BigDecimal.ZERO).status(ProductionBatchStatus.IN_PROGRESS).build();
        when(batchRepository.findByIdAndFactoryId(1L, "F006")).thenReturn(Optional.of(batch));
        when(reportRepository.save(any(ProductionReport.class))).thenAnswer(i -> i.getArgument(0));
        when(units.normalize(any(), any())).thenAnswer(i -> {
            String raw = i.getArgument(1);
            boolean known = !raw.equals("乱写");
            return new UnitNormalizationResult(raw, raw, known
                    ? new CanonicalUnit(raw, UnitDimension.MASS, "g", BigDecimal.ONE, raw, 3) : null);
        });
        when(units.storageUnit(any(), any())).thenAnswer(i -> i.getArgument(1));
    }

    private WorkReportSubmitRequest req(String unit) {
        WorkReportSubmitRequest r = new WorkReportSubmitRequest();
        r.setBatchId(1L);
        r.setReportType("PROGRESS");
        r.setReportMode(ReportMode.MODE_1);
        r.setReportDate(LocalDate.now());
        r.setOutputQuantity(new BigDecimal("500"));
        r.setUnit(unit);
        return r;
    }

    @Test
    void gramsAreConvertedToTheBatchUnitBeforeAccumulating() {
        when(units.convert(any(BigDecimal.class), any(UnitConversionContext.class))).thenReturn(
                new UnitConversionResult(UnitConversionStatus.CONVERTED, new BigDecimal("0.5"), "g", "kg", List.of(), null, null, null));
        WorkReportResponse resp = service.submitReport("F006", 9L, req("g"));
        assertThat(batch.getActualQuantity()).isEqualByComparingTo("0.5");
        assertThat(resp.getUnit()).isEqualTo("kg");
        assertThat(resp.getUnitAssumed()).isFalse();
    }

    @Test
    void unconvertibleUnitIsRejectedAndNothingAccumulates() {
        when(units.convert(any(BigDecimal.class), any(UnitConversionContext.class))).thenReturn(
                new UnitConversionResult(UnitConversionStatus.PRODUCT_CONVERSION_MISSING, null, "只", "kg", List.of(), null, null, "缺单重"));
        assertThrows(BusinessException.class, () -> service.submitReport("F006", 9L, req("只")));
        assertThat(batch.getActualQuantity()).isEqualByComparingTo("0");
        verify(reportRepository, never()).save(any());
    }

    @Test
    void unrecognizedHandTypedUnitIsRejected() {
        assertThrows(BusinessException.class, () -> service.submitReport("F006", 9L, req("乱写")));
        verify(reportRepository, never()).save(any());
    }

    @Test
    void oldClientWithoutUnitIsTreatedAsBatchUnitAndFlagged() {
        WorkReportResponse resp = service.submitReport("F006", 9L, req(null));
        assertThat(batch.getActualQuantity()).isEqualByComparingTo("500");
        assertThat(resp.getUnitAssumed()).isTrue();
        assertThat(resp.getUnit()).isEqualTo("kg");
        verify(units, never()).convert(any(BigDecimal.class), any(UnitConversionContext.class));
    }
}
