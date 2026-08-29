package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.material.MaterialBatchCostCorrection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.material.MaterialBatchCostCorrectionRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaterialBatchCostCorrectionContractTest {
    @Mock MaterialBatchRepository batchRepository;
    @Mock MaterialBatchAdjustmentRepository adjustmentRepository;
    @Mock RawMaterialTypeRepository materialTypeRepository;
    @Mock MaterialBatchMapper mapper;
    @Mock MaterialConsumptionRepository consumptionRepository;
    @Mock ProductionPlanBatchUsageRepository usageRepository;
    @Mock ExcelUtil excelUtil;
    @Mock FuturePlanMatchingService matchingService;
    @Mock MaterialBatchCostCorrectionRepository correctionRepository;

    MaterialBatchServiceImpl service;
    MaterialBatch batch;

    @BeforeEach
    void setUp() {
        service = new MaterialBatchServiceImpl(batchRepository, adjustmentRepository, materialTypeRepository,
                mapper, consumptionRepository, usageRepository, excelUtil, matchingService);
        ReflectionTestUtils.setField(service, "materialBatchCostCorrectionRepository", correctionRepository);
        batch = new MaterialBatch();
        batch.setId("B-1");
        batch.setFactoryId("F006");
        batch.setMaterialTypeId("M-1");
        batch.setReceiptQuantity(new BigDecimal("10"));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        when(batchRepository.findById("B-1")).thenReturn(Optional.of(batch));
    }

    @Test
    void unknownPriceCanBeValuedWithOldNewReasonAndActorAudit() {
        when(batchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.correctUnitPrice("F006", "B-1", new BigDecimal("32.5000"),
                "客户提供的本批结算参考价", 42L, "finance_manager");

        ArgumentCaptor<MaterialBatchCostCorrection> captor =
                ArgumentCaptor.forClass(MaterialBatchCostCorrection.class);
        verify(correctionRepository).save(captor.capture());
        assertThat(captor.getValue().getOldUnitPrice()).isNull();
        assertThat(captor.getValue().getNewUnitPrice()).isEqualByComparingTo("32.5000");
        assertThat(captor.getValue().getReason()).contains("结算参考价");
        assertThat(captor.getValue().getCorrectedBy()).isEqualTo(42L);
        assertThat(captor.getValue().getCorrectedByRole()).isEqualTo("finance_manager");
        assertThat(batch.getUnitPrice()).isEqualByComparingTo("32.5000");
        verify(batchRepository).save(batch);
    }

    @Test
    void invalidRoleCrossFactoryAndSamePriceProduceNoWrites() {
        batch.setUnitPrice(new BigDecimal("20"));
        assertThatThrownBy(() -> service.correctUnitPrice("F006", "B-1", new BigDecimal("21"),
                "reason", 42L, "warehouse_manager")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.correctUnitPrice("OTHER", "B-1", new BigDecimal("21"),
                "reason", 42L, "finance_manager")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.correctUnitPrice("F006", "B-1", new BigDecimal("20.00"),
                "reason", 42L, "finance_manager")).isInstanceOf(BusinessException.class);
        verify(correctionRepository, never()).save(any());
        verify(batchRepository, never()).save(any());
    }
}
