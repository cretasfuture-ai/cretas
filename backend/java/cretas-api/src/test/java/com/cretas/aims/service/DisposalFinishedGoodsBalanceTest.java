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
        record.setDisposalType("SCRAP");
        return record;
    }

    @Test
    void reasonCategoryAsDisposalTypeIsRejectedBeforeSave() {
        // S3-10: web 表单曾把「质量问题」当处置方式提交, 建得出来却永远批不了 (approve 只认 SCRAP/DESTROY)
        when(batches.findById("FG-1")).thenReturn(Optional.of(batch("F006", "5", "0", "0")));
        for (String type : new String[]{"QUALITY_ISSUE", "EXPIRED", "DAMAGED", "OTHER", "RECYCLE", null}) {
            DisposalRecord record = request("1");
            record.setDisposalType(type);
            assertThatThrownBy(() -> service.createDisposalRecord(record))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(400));
        }
        verify(records, never()).save(any());
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

    // ===== 客户自有成品 (Steve 2026-10-28: 退还客户 或 经客户同意报废, 都要客户确认) =====

    private static FinishedGoodsBatch returnedCustomerBatch() {
        FinishedGoodsBatch batch = batch("F006", "10", "0", "0");
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        batch.setOwnerCustomerId("CUST-1");
        batch.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        return batch;
    }

    private static DisposalRecord confirmed(DisposalRecord record) {
        record.setCustomerConfirmedBy("客户王经理");
        record.setCustomerConfirmedAt(java.time.LocalDate.of(2026, 10, 28));
        record.setCustomerConfirmationEvidence("https://example.test/signed.jpg");
        return record;
    }

    @Test
    void customerOwnedDisposalWithoutCustomerConfirmationIsRejected() {
        when(batches.findById("FG-1")).thenReturn(Optional.of(returnedCustomerBatch()));
        for (String type : new String[]{"SCRAP", "DESTROY", "RETURN_TO_CUSTOMER"}) {
            DisposalRecord record = request("1");
            record.setDisposalType(type);
            assertThatThrownBy(() -> service.createDisposalRecord(record))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(400));
            DisposalRecord partial = confirmed(request("1"));
            partial.setDisposalType(type);
            partial.setCustomerConfirmationEvidence(" ");
            assertThatThrownBy(() -> service.createDisposalRecord(partial))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(400));
        }
        verify(records, never()).save(any());
    }

    @Test
    void returnedCustomerGoodsCanBeReturnedOrScrappedOnceCustomerConfirms() {
        FinishedGoodsBatch batch = returnedCustomerBatch();
        when(batches.findById("FG-1")).thenReturn(Optional.of(batch));
        when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(batches.findAllById(List.of("FG-1"))).thenReturn(List.of(batch));

        DisposalRecord giveBack = confirmed(request("4"));
        giveBack.setDisposalType("RETURN_TO_CUSTOMER");
        assertThat(service.createDisposalRecord(giveBack).getDisposalType()).isEqualTo("RETURN_TO_CUSTOMER");
        DisposalRecord scrap = confirmed(request("6"));
        assertThat(service.createDisposalRecord(scrap).getDisposalType()).isEqualTo("SCRAP");
    }

    @Test
    void sellableCustomerGoodsMustLeaveThroughTollDelivery() {
        FinishedGoodsBatch batch = returnedCustomerBatch();
        batch.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        when(batches.findById("FG-1")).thenReturn(Optional.of(batch));
        DisposalRecord record = confirmed(request("1"));
        record.setDisposalType("RETURN_TO_CUSTOMER");
        assertThatThrownBy(() -> service.createDisposalRecord(record))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409));
        verify(records, never()).save(any());
    }

    @Test
    void companyGoodsCannotBeReturnedToCustomer() {
        when(batches.findById("FG-1")).thenReturn(Optional.of(batch("F006", "10", "0", "0")));
        DisposalRecord record = confirmed(request("1"));
        record.setDisposalType("RETURN_TO_CUSTOMER");
        assertThatThrownBy(() -> service.createDisposalRecord(record))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(400));
        DisposalRecord material = confirmed(request("1"));
        material.setFinishedGoodsBatchId(null);
        material.setMaterialBatchId("MB-1");
        material.setDisposalType("RETURN_TO_CUSTOMER");
        assertThatThrownBy(() -> service.createDisposalRecord(material))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(400));
        verify(records, never()).save(any());
    }
}
