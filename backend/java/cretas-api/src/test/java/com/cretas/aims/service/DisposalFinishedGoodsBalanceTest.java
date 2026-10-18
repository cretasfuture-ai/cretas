package com.cretas.aims.service;

import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.finance.DisposalCostAccountingService;
import com.cretas.aims.repository.DisposalRecordRepository;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisposalFinishedGoodsBalanceTest {
    private DisposalRecordRepository records;
    private FinishedGoodsBatchRepository batches;
    private DisposalRecordService service;

    @BeforeEach
    void setUp() {
        records = mock(DisposalRecordRepository.class);
        batches = mock(FinishedGoodsBatchRepository.class);
        service = new DisposalRecordService(records, mock(MaterialBatchRepository.class),
                mock(MaterialBatchAdjustmentRepository.class), mock(ProductionBatchRepository.class),
                batches, mock(FinishedGoodsAdjustmentLogRepository.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "disposalCostAccountingService",
                mock(DisposalCostAccountingService.class));
    }

    private static FinishedGoodsBatch batch(String factoryId, String produced, String shipped, String reserved) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("FG-1");
        batch.setFactoryId(factoryId);
        batch.setBatchNumber("FG-TEST");
        batch.setProductName("测试成品");
        batch.setUnit("kg");
        batch.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        batch.setProducedQuantity(new BigDecimal(produced));
        batch.setShippedQuantity(new BigDecimal(shipped));
        batch.setReservedQuantity(new BigDecimal(reserved));
        return batch;
    }

    private static DisposalRecord request(String quantity) {
        DisposalRecord record = new DisposalRecord();
        record.setFactoryId("F006");
        record.setFinishedGoodsBatchId("FG-1");
        record.setDisposalQuantity(new BigDecimal(quantity));
        return record;
    }

    @Test
    void fullyShippedBatchCannotCreateDisposal() {
        when(batches.findById("FG-1")).thenReturn(Optional.of(batch("F006", "100", "100", "0")));

        assertThatThrownBy(() -> service.createDisposalRecord(request("1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409));
        verify(records, never()).save(any());
    }

    @Test
    void partlyShippedBatchAllowsOnlyUnshippedUnreservedRemainder() {
        FinishedGoodsBatch batch = batch("F006", "100", "60", "10");
        when(batches.findById("FG-1")).thenReturn(Optional.of(batch));
        when(batches.findDisposalEligibleByFactoryId(any(), any())).thenReturn(List.of(batch));
        when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(batches.findAllById(List.of("FG-1"))).thenReturn(List.of(batch));

        assertThat(service.getEligibleFinishedGoods("F006").get(0).availableQuantity())
                .isEqualByComparingTo("30");
        assertThat(service.createDisposalRecord(request("30")).getDisposalQuantity())
                .isEqualByComparingTo("30");
        assertThatThrownBy(() -> service.createDisposalRecord(request("30.01")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409));
    }

    @Test
    void otherFactoryBatchCannotCreateDisposal() {
        when(batches.findById("FG-1")).thenReturn(Optional.of(batch("F999", "100", "0", "0")));

        assertThatThrownBy(() -> service.createDisposalRecord(request("1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(403));
        verify(records, never()).save(any());
    }

    @Test
    void unshippedDefectiveStockCanBeDisposedButReversedStockCannot() {
        FinishedGoodsBatch batch = batch("F006", "5", "0", "0");
        when(batches.findById("FG-1")).thenReturn(Optional.of(batch));
        when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(batches.findAllById(List.of("FG-1"))).thenReturn(List.of(batch));

        batch.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(service.createDisposalRecord(request("5"))).isNotNull();

        batch.setStatus(FinishedGoodsBatch.Status.REVERSED);
        assertThatThrownBy(() -> service.createDisposalRecord(request("1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409));
    }

    @Test
    void mixedMaterialAndFinishedGoodsTargetsAreRejectedBeforeSave() {
        DisposalRecord record = request("1");
        record.setMaterialBatchId("MB-1");
        assertThatThrownBy(() -> service.createDisposalRecord(record))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(422));
        verify(records, never()).save(any());
    }

    @Test
    void customerOwnedFinishedGoodsCannotEnterGeneralDisposal() {
        FinishedGoodsBatch batch = batch("F006", "10", "0", "0");
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        when(batches.findById("FG-1")).thenReturn(Optional.of(batch));
        assertThatThrownBy(() -> service.createDisposalRecord(request("1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409));
        verify(records, never()).save(any());
    }
}
