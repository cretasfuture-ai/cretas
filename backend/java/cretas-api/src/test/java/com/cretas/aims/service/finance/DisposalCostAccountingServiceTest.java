package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.service.voucher.VoucherService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisposalCostAccountingServiceTest {
    @Test void readHistoryIncludesRealRedVoucherWithoutAnyAccountingWrites() {
        Voucher original=voucher("o","4.21");original.setStatus(VoucherStatus.REVERSED);original.setVoucherNumber("V-1");original.setReversalVoucherId("r");
        Voucher red=voucher("r","4.21");red.setVoucherNumber("V-2");red.setSourceBusinessType("VOUCHER_REVERSAL");red.setSourceBusinessId("o");red.setOriginalVoucherId("o");
        when(entityManager.createQuery(anyString(),eq(Voucher.class))).thenReturn(voucherQuery);
        when(voucherQuery.setParameter(anyString(),any())).thenReturn(voucherQuery);when(voucherQuery.getResultList()).thenReturn(List.of(original));
        when(entityManager.find(Voucher.class,"r")).thenReturn(red);
        assertThat(service.getAccountingHistory("F006",5L)).containsExactly(original,red);
        verify(voucherQuery).setParameter("factory","F006");verify(voucherQuery).setParameter("id","5");verify(voucherQuery).setParameter("prefix","5#%");
        verifyNoInteractions(voucherService,periods,finishedGoodsRepository);
    }

    @Test void readHistoryRejectsForeignOrMismatchedRedVoucher() {
        Voucher original=voucher("o","4.21");original.setStatus(VoucherStatus.REVERSED);original.setReversalVoucherId("r");
        Voucher red=voucher("r","4.21");red.setFactoryId("OTHER");
        when(entityManager.createQuery(anyString(),eq(Voucher.class))).thenReturn(voucherQuery);
        when(voucherQuery.setParameter(anyString(),any())).thenReturn(voucherQuery);when(voucherQuery.getResultList()).thenReturn(List.of(original));
        when(entityManager.find(Voucher.class,"r")).thenReturn(red);
        assertThatThrownBy(()->service.getAccountingHistory("F006",5L)).hasMessageContaining("红字凭证");
    }

    @Test void reversedHistoryCannotSilentlyOmitMissingRedLink() {
        Voucher original=voucher("o","4.21");original.setStatus(VoucherStatus.REVERSED);
        when(entityManager.createQuery(anyString(),eq(Voucher.class))).thenReturn(voucherQuery);
        when(voucherQuery.setParameter(anyString(),any())).thenReturn(voucherQuery);when(voucherQuery.getResultList()).thenReturn(List.of(original));
        assertThatThrownBy(()->service.getAccountingHistory("F006",5L)).hasMessageContaining("缺少红字关联");
    }

    @Test void draftRedVoucherCannotBePresentedAsCompletedReversal() {
        Voucher original=voucher("o","4.21");original.setStatus(VoucherStatus.REVERSED);original.setReversalVoucherId("r");
        Voucher red=voucher("r","4.21");red.setStatus(VoucherStatus.DRAFT);
        when(entityManager.createQuery(anyString(),eq(Voucher.class))).thenReturn(voucherQuery);
        when(voucherQuery.setParameter(anyString(),any())).thenReturn(voucherQuery);when(voucherQuery.getResultList()).thenReturn(List.of(original));
        when(entityManager.find(Voucher.class,"r")).thenReturn(red);
        assertThatThrownBy(()->service.getAccountingHistory("F006",5L)).hasMessageContaining("关联不完整");
    }
    @Mock private VoucherService voucherService;
    @Mock private EntityManager entityManager;
    @Mock private AccountingPeriodService periods;
    @Mock private FinishedGoodsBatchRepository finishedGoodsRepository;
    @Mock private FinishedGoodsAdjustmentLogRepository adjustmentLogRepository;
    @Mock private Query advisoryLockQuery;
    @Mock private TypedQuery<DisposalRecord> disposalQuery;
    @Mock private TypedQuery<Voucher> voucherQuery;

    private DisposalCostAccountingService service;

    @BeforeEach
    void setUp() {
        service = new DisposalCostAccountingService(voucherService, entityManager, periods, finishedGoodsRepository);
        ReflectionTestUtils.setField(service, "adjustmentLogRepository", adjustmentLogRepository);
    }

    @Test
    void postsBalancedExpenseAndInventoryVoucher() {
        DisposalRecord record = record("SCRAP", 7L);
        record.setFinishedGoodsBatchId("FG-7");
        when(voucherService.findBySourceBusiness("DISPOSAL_RECORD", "7")).thenReturn(Optional.empty());

        service.postApprovedDisposal(record, new BigDecimal("12.34"), 9L);

        ArgumentCaptor<List<VoucherEntrySpec>> entries = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createManual(eq("F006"), eq(VoucherType.EXPENSE), any(), entries.capture(),
                eq("DISPOSAL_RECORD"), eq("7"), anyString(), eq(9L));
        List<VoucherEntrySpec> value = entries.getValue();
        assertThat(value).extracting(VoucherEntrySpec::subjectCode).containsExactly("6711", "1405");
        assertThat(value.get(0).debit()).isEqualByComparingTo("12.34");
        assertThat(value.get(1).credit()).isEqualByComparingTo("12.34");
        verify(periods).assertOpen("F006", 2026, 10);
    }

    @Test
    void existingVoucherIsIdempotentAndDoesNotPostTwice() {
        DisposalRecord record = record("DESTROY", 8L);
        record.setFinishedGoodsBatchId("FG-8");
        Voucher existing = voucher("v-existing", "5.00");
        existing.setSourceBusinessType("DISPOSAL_RECORD");
        existing.setSourceBusinessId("8");
        existing.setVoucherDate(record.getDisposalDate().toLocalDate());
        when(voucherService.findBySourceBusiness("DISPOSAL_RECORD", "8"))
                .thenReturn(Optional.empty(), Optional.of(existing));

        service.postApprovedDisposal(record, new BigDecimal("5.00"), 9L);
        service.postApprovedDisposal(record, new BigDecimal("5.00"), 9L);

        verify(voucherService, times(1)).createManual(any(), any(), any(), anyList(), any(), any(), any(), any());
    }

    @Test
    void missingCostPeriodLockAndUnsupportedTypeFailBeforeVoucher() {
        DisposalRecord scrap = record("SCRAP", 10L);
        scrap.setFinishedGoodsBatchId("FG-10");
        assertThatThrownBy(() -> service.postApprovedDisposal(scrap, null, 9L))
                .isInstanceOf(BusinessException.class);
        doThrow(new BusinessException(409, "closed")).when(periods).assertOpen("F006", 2026, 10);
        assertThatThrownBy(() -> service.postApprovedDisposal(scrap, new BigDecimal("1"), 9L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.postApprovedDisposal(record("RECYCLE", 11L), BigDecimal.ONE, 9L))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(voucherService);
    }

    @Test
    void revalueKeepsPostedHistoryAndWritesRedReversalPlusRevision() {
        stubAdvisoryLock();
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("FG-1");
        batch.setFactoryId("F006");
        batch.setUnitCost(new BigDecimal("15.00"));
        when(finishedGoodsRepository.findByIdAndFactoryIdForUpdate("FG-1", "F006"))
                .thenReturn(Optional.of(batch));
        DisposalRecord record = record("SCRAP", 12L);
        record.setFinishedGoodsBatchId("FG-1");
        record.setDisposalQuantity(new BigDecimal("2"));
        record.setActualLoss(new BigDecimal("20.00"));
        when(entityManager.createQuery(anyString(), eq(DisposalRecord.class))).thenReturn(disposalQuery);
        when(entityManager.createQuery(anyString(), eq(Voucher.class))).thenReturn(voucherQuery);
        when(disposalQuery.setParameter(anyString(), any())).thenReturn(disposalQuery);
        when(disposalQuery.setLockMode(any())).thenReturn(disposalQuery);
        when(disposalQuery.getResultList()).thenReturn(List.of(record));
        when(voucherQuery.setParameter(anyString(), any())).thenReturn(voucherQuery);
        when(voucherQuery.getResultList()).thenReturn(List.of(voucher("v-original", "20.00")));

        service.revalueForBatch("F006", "FG-1", 9L);

        ArgumentCaptor<List<VoucherEntrySpec>> entries = ArgumentCaptor.forClass(List.class);
        verify(voucherService, times(1)).createManual(eq("F006"), eq(VoucherType.EXPENSE), any(), entries.capture(),
                anyString(), anyString(), anyString(), eq(9L));
        List<VoucherEntrySpec> revision = entries.getValue();
        assertThat(revision.get(0).debit()).isEqualByComparingTo("30.00");
        assertThat(revision.get(1).credit()).isEqualByComparingTo("30.00");
        verify(voucherService).reverseDisposalCostVoucher("F006",12L,"v-original",9L);
        verify(entityManager).merge(record);
    }

    @Test
    void crossFactoryBatchAndIncompleteCostDoNotWriteVoucher() {
        stubAdvisoryLock();
        when(finishedGoodsRepository.findByIdAndFactoryIdForUpdate("FG-1", "F006"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revalueForBatch("F006", "FG-1", 9L))
                .isInstanceOf(BusinessException.class);
        FinishedGoodsBatch noCost = new FinishedGoodsBatch();
        noCost.setId("FG-1");
        noCost.setFactoryId("F006");
        noCost.setUnitCost(null);
        when(finishedGoodsRepository.findByIdAndFactoryIdForUpdate("FG-1", "F006"))
                .thenReturn(Optional.of(noCost));
        assertThatThrownBy(() -> service.revalueForBatch("F006", "FG-1", 9L))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(voucherService);
    }

    @Test
    void consecutiveRevaluesUseActiveRevisionAndSameCostIsNoOp() {
        stubAdvisoryLock();
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("FG-2");
        batch.setFactoryId("F006");
        batch.setUnitCost(new BigDecimal("15.00"));
        when(finishedGoodsRepository.findByIdAndFactoryIdForUpdate("FG-2", "F006"))
                .thenReturn(Optional.of(batch));
        DisposalRecord record = record("SCRAP", 13L);
        record.setFinishedGoodsBatchId("FG-2");
        record.setDisposalQuantity(new BigDecimal("2"));
        record.setActualLoss(new BigDecimal("20.00"));
        when(entityManager.createQuery(anyString(), eq(DisposalRecord.class))).thenReturn(disposalQuery);
        when(disposalQuery.setParameter(anyString(), any())).thenReturn(disposalQuery);
        when(disposalQuery.setLockMode(any())).thenReturn(disposalQuery);
        when(disposalQuery.getResultList()).thenReturn(List.of(record));
        when(entityManager.createQuery(anyString(), eq(Voucher.class))).thenReturn(voucherQuery);
        when(voucherQuery.setParameter(anyString(), any())).thenReturn(voucherQuery);
        Voucher base = voucher("base", "20.00");
        Voucher revision = voucher("revision", "30.00");
        when(voucherQuery.getResultList()).thenReturn(List.of(base), List.of(revision), List.of(voucher("revision2", "40.00")));

        service.revalueForBatch("F006", "FG-2", 9L);
        batch.setUnitCost(new BigDecimal("20.00"));
        service.revalueForBatch("F006", "FG-2", 9L);
        service.revalueForBatch("F006", "FG-2", 9L);

        verify(voucherService, times(2)).reverseDisposalCostVoucher(eq("F006"),eq(13L),anyString(),eq(9L));
        verify(voucherService, times(2)).createManual(eq("F006"), eq(VoucherType.EXPENSE), any(), anyList(),
                anyString(), anyString(), anyString(), eq(9L));
    }

    @Test
    void legacyApprovedZeroLossWithoutVoucherCanBeBackfilledFromScrapEvidence() {
        stubAdvisoryLock();
        when(adjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc("FG-1"))
                .thenReturn(List.of(scrapLog("FG-1", 14L)));
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("FG-1");
        batch.setFactoryId("F006");
        batch.setUnitCost(new BigDecimal("15.00"));
        when(finishedGoodsRepository.findByIdAndFactoryIdForUpdate("FG-1", "F006"))
                .thenReturn(Optional.of(batch));
        DisposalRecord record = record("SCRAP", 14L);
        record.setFinishedGoodsBatchId("FG-1");
        record.setDisposalQuantity(new BigDecimal("2"));
        record.setActualLoss(BigDecimal.ZERO);
        when(entityManager.createQuery(anyString(), eq(DisposalRecord.class))).thenReturn(disposalQuery);
        when(disposalQuery.setParameter(anyString(), any())).thenReturn(disposalQuery);
        when(disposalQuery.setLockMode(any())).thenReturn(disposalQuery);
        when(disposalQuery.getResultList()).thenReturn(List.of(record));
        when(entityManager.createQuery(anyString(), eq(Voucher.class))).thenReturn(voucherQuery);
        when(voucherQuery.setParameter(anyString(), any())).thenReturn(voucherQuery);
        when(voucherQuery.getResultList()).thenReturn(List.of());

        service.revalueForBatch("F006", "FG-1", 9L);

        verify(voucherService).createManual(eq("F006"), eq(VoucherType.EXPENSE), any(), anyList(),
                eq("DISPOSAL_RECORD"), eq("14"), anyString(), eq(9L));
        verify(voucherService, never()).reverseDisposalCostVoucher(any(), any(), any(), any());
    }

    @Test
    void oneScrapLogCannotBeConsumedByTwoApprovedDisposals() {
        stubAdvisoryLock();
        when(adjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc("FG-1"))
                .thenReturn(List.of(scrapLog("FG-1", 14L)));
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("FG-1");
        batch.setFactoryId("F006");
        batch.setUnitCost(new BigDecimal("15.00"));
        when(finishedGoodsRepository.findByIdAndFactoryIdForUpdate("FG-1", "F006"))
                .thenReturn(Optional.of(batch));
        DisposalRecord first = record("SCRAP", 14L);
        first.setFinishedGoodsBatchId("FG-1");
        first.setDisposalQuantity(new BigDecimal("2"));
        first.setActualLoss(BigDecimal.ZERO);
        DisposalRecord second = record("SCRAP", 15L);
        second.setFinishedGoodsBatchId("FG-1");
        second.setDisposalQuantity(new BigDecimal("2"));
        second.setActualLoss(BigDecimal.ZERO);
        when(entityManager.createQuery(anyString(), eq(DisposalRecord.class))).thenReturn(disposalQuery);
        when(disposalQuery.setParameter(anyString(), any())).thenReturn(disposalQuery);
        when(disposalQuery.setLockMode(any())).thenReturn(disposalQuery);
        when(disposalQuery.getResultList()).thenReturn(List.of(first, second));
        when(entityManager.createQuery(anyString(), eq(Voucher.class))).thenReturn(voucherQuery);
        when(voucherQuery.setParameter(anyString(), any())).thenReturn(voucherQuery);
        when(voucherQuery.getResultList()).thenReturn(List.of(), List.of());

        assertThatThrownBy(() -> service.revalueForBatch("F006", "FG-1", 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SCRAP 数量日志");
        verify(voucherService, times(1)).createManual(any(), any(), any(), anyList(), any(), any(), any(), any());
    }

    private DisposalRecord record(String type, Long id) {
        DisposalRecord record = new DisposalRecord();
        record.setId(id);
        record.setFactoryId("F006");
        record.setDisposalType(type);
        record.setDisposalDate(LocalDateTime.of(2026, 10, 18, 10, 0));
        record.setDisposalQuantity(BigDecimal.ONE);
        return record;
    }

    private void stubAdvisoryLock() {
        when(entityManager.createNativeQuery(startsWith("SELECT pg_advisory_xact_lock"))).thenReturn(advisoryLockQuery);
        when(advisoryLockQuery.setParameter(anyString(), any())).thenReturn(advisoryLockQuery);
        when(advisoryLockQuery.getSingleResult()).thenReturn(1);
    }

    private FinishedGoodsAdjustmentLog scrapLog(String batchId, Long recordId) {
        return FinishedGoodsAdjustmentLog.builder().factoryId("F006").batchId(batchId)
                .adjustmentQuantity(new BigDecimal("-2")).referenceType("SCRAP")
                .reason("报损审批 [id=" + recordId + "]").build();
    }

    private Voucher voucher(String id, String amount) {
        BigDecimal value = new BigDecimal(amount);
        Voucher voucher = Voucher.builder().id(id).status(VoucherStatus.POSTED)
                .factoryId("F006").voucherType(VoucherType.EXPENSE)
                .voucherDate(java.time.LocalDate.of(2026, 10, 18))
                .sourceBusinessType("DISPOSAL_RECORD").sourceBusinessId(id)
                .totalDebit(value).totalCredit(value).build();
        voucher.setEntries(List.of(
                VoucherEntry.builder().subjectCode("6711").debit(value).credit(BigDecimal.ZERO).build(),
                VoucherEntry.builder().subjectCode("1405").debit(BigDecimal.ZERO).credit(value).build()));
        return voucher;
    }
}
