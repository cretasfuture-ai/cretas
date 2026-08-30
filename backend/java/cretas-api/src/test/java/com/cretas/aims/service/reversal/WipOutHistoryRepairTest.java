package com.cretas.aims.service.reversal;

import com.cretas.aims.dto.production.WipOutHistoryRepairRequestDTO;
import com.cretas.aims.dto.production.WipOutHistoryRepairResultDTO;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WipOutHistoryRepairTest {
    private final ReportReversalLogRepository logs = mock(ReportReversalLogRepository.class);
    private final ProductionReportRepository reports = mock(ProductionReportRepository.class);
    private final SemiFinishedInventoryRepository wips = mock(SemiFinishedInventoryRepository.class);
    private final SemiFinishedInventoryTransactionRepository txns = mock(SemiFinishedInventoryTransactionRepository.class);
    private final WorkProcessTaskRepository tasks = mock(WorkProcessTaskRepository.class);
    private ReportReversalServiceImpl service;
    private final List<SemiFinishedInventoryTransaction> ledger = new ArrayList<>();
    private final SemiFinishedInventory sfi = SemiFinishedInventory.builder().id(370L).factoryId("LIUSHANMEN")
            .batchId(10888L).intermediateBatchNo("WIP-370").producedQuantity(new BigDecimal("1.25"))
            .consumedQuantity(new BigDecimal("1.25")).availableQuantity(BigDecimal.ZERO)
            .status(SemiFinishedInventory.Status.AVAILABLE).build();
    private ReportReversalLog reversal;
    private ProductionReport report;
    private final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
    private final AtomicLong savedTxnId = new AtomicLong(9000L);

    @BeforeEach
    void setUp() {
        service = new ReportReversalServiceImpl(logs, mock(ProductionBatchRepository.class), reports, wips, txns,
                mock(FinishedGoodsBatchRepository.class), mock(UserRepository.class), tasks, mock(NotificationService.class));
        LocalDateTime t = LocalDateTime.of(2026, 8, 30, 10, 0);
        reversal = ReportReversalLog.builder().id(18L).factoryId("LIUSHANMEN").batchId(10888L)
                .status(ReportReversalLog.ReversalStatus.DONE).approvedAt(t)
                .revertedTxnIds(new ArrayList<>(List.of(141L, 142L, 143L))).build();
        report = new ProductionReport();
        report.setId(900L); report.setFactoryId("LIUSHANMEN"); report.setBatchId(10888L);
        report.setWorkProcessTaskId(77L); report.setDeletedAt(t.plusSeconds(2));
        WorkProcessTask task = WorkProcessTask.builder().id(77L).factoryId("LIUSHANMEN")
                .productionBatchId(10888L).build();
        ledger.add(SemiFinishedInventoryTransaction.builder().id(10L).factoryId("LIUSHANMEN").semiFinishedId(370L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN).sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .quantity(new BigDecimal("2.50")).unitCostAtTxn(new BigDecimal("1.25")).createdAt(t.minusSeconds(2)).build());
        ledger.add(SemiFinishedInventoryTransaction.builder().id(141L).factoryId("LIUSHANMEN").semiFinishedId(370L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE).sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("reversal-log:18").quantity(new BigDecimal("-1.25")).createdAt(t.plusSeconds(1)).build());
        ledger.add(SemiFinishedInventoryTransaction.builder().id(137L).factoryId("LIUSHANMEN").semiFinishedId(370L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT).sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .sourceRef("TASK-77").quantity(new BigDecimal("-1.25")).unitCostAtTxn(new BigDecimal("1.25"))
                .reportId(900L).createdAt(t.minusSeconds(3)).build());
        when(logs.findById(18L)).thenReturn(Optional.of(reversal));
        when(entityManager.find(ReportReversalLog.class, 18L, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)).thenReturn(reversal);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(reports.findById(900L)).thenReturn(Optional.of(report));
        when(tasks.findByFactoryIdAndId("LIUSHANMEN", 77L)).thenReturn(Optional.of(task));
        when(wips.findById(370L)).thenReturn(Optional.of(sfi));
        when(wips.findByIdForUpdate(370L)).thenReturn(Optional.of(sfi));
        when(txns.findById(137L)).thenReturn(Optional.of(ledger.get(2)));
        when(txns.findBySemiFinishedIdOrderByCreatedAtAsc(370L)).thenReturn(ledger);
        when(txns.save(any())).thenAnswer(inv -> {
            SemiFinishedInventoryTransaction x = inv.getArgument(0);
            x.setId(savedTxnId.getAndIncrement());
            ledger.add(x);
            return x;
        });
    }

    private WipOutHistoryRepairRequestDTO request() {
        WipOutHistoryRepairRequestDTO r = new WipOutHistoryRepairRequestDTO();
        r.setReversalLogId(18L); r.setOriginalOutTxnIds(List.of(137L)); return r;
    }

    @Test
    void productionShape_repairsAndIsExactIdempotent() {
        WipOutHistoryRepairResultDTO first = service.repairWipOutHistory("LIUSHANMEN", request(), 99L);
        assertThat(first.getItems().get(0).isIdempotent()).isFalse();
        assertThat(first.getItems().get(0).getBeforeAvailable()).isZero();
        assertThat(first.getItems().get(0).getAfterAvailable()).isEqualByComparingTo("1.25");
        assertThat(sfi.getProducedQuantity()).isEqualByComparingTo("1.25");
        assertThat(sfi.getConsumedQuantity()).isZero();
        assertThat(sfi.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
        assertThat(reversal.getRevertedTxnIds()).containsExactly(141L, 142L, 143L, 9000L);

        WipOutHistoryRepairResultDTO second = service.repairWipOutHistory("LIUSHANMEN", request(), 99L);
        assertThat(second.getItems().get(0).isIdempotent()).isTrue();
        assertThat(reversal.getRevertedTxnIds()).containsExactly(141L, 142L, 143L, 9000L);
        verify(txns, times(1)).save(any());
    }

    @Test
    void rejectsUnprovenLineageAndWrongTenant() {
        assertThatThrownBy(() -> service.repairWipOutHistory("OTHER", request(), 99L))
                .hasMessageContaining("工厂");
        reversal.setRevertedTxnIds(List.of(999L));
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("证明");
        verify(txns, never()).save(any());
    }

    @Test
    void rejectsUnknownOutIdBeforeMutation() {
        WipOutHistoryRepairRequestDTO unknown = request();
        unknown.setOriginalOutTxnIds(List.of(999999L));
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", unknown, 99L))
                .hasMessageContaining("原 OUT 流水不存在");
        verify(txns, never()).save(any());
        verify(logs, never()).save(any());
    }

    @Test
    void rejectsInvalidStatusActiveReportAndAmbiguousCompensation() {
        reversal.setStatus(ReportReversalLog.ReversalStatus.PENDING);
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("已完成");
        reversal.setStatus(ReportReversalLog.ReversalStatus.DONE);
        report.setDeletedAt(null);
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("软删除");
        report.setDeletedAt(reversal.getApprovedAt().plusSeconds(2));
        ledger.add(SemiFinishedInventoryTransaction.builder().id(700L).factoryId("LIUSHANMEN").semiFinishedId(370L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE).sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("other-log").reportId(900L).quantity(new BigDecimal("1.25"))
                .unitCostAtTxn(new BigDecimal("1.25")).createdAt(reversal.getApprovedAt()).build());
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("无法判定归属");
        verify(txns, never()).save(any());
    }

    @Test
    void requiresPessimisticReversalLogLock() {
        ReflectionTestUtils.setField(service, "entityManager", null);
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("日志锁");
        verify(logs, never()).findById(18L);
    }

    @Test
    void verifiesReversalLogLockCall() {
        service.repairWipOutHistory("LIUSHANMEN", request(), 99L);
        verify(entityManager).find(ReportReversalLog.class, 18L, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void productionDualLine_repairsTxn137And139WithoutMergingInventoryRows() {
        LocalDateTime t = reversal.getApprovedAt();
        SemiFinishedInventory second = SemiFinishedInventory.builder().id(371L).factoryId("LIUSHANMEN")
                .batchId(10888L).intermediateBatchNo("WIP-371").producedQuantity(BigDecimal.ZERO)
                .consumedQuantity(new BigDecimal("1.00")).availableQuantity(BigDecimal.ZERO)
                .status(SemiFinishedInventory.Status.DEPLETED).build();
        ProductionReport secondReport = new ProductionReport();
        secondReport.setId(901L); secondReport.setFactoryId("LIUSHANMEN"); secondReport.setBatchId(10888L);
        secondReport.setWorkProcessTaskId(78L); secondReport.setDeletedAt(t.plusSeconds(2));
        WorkProcessTask secondTask = WorkProcessTask.builder().id(78L).factoryId("LIUSHANMEN")
                .productionBatchId(10888L).build();
        List<SemiFinishedInventoryTransaction> secondLedger = new ArrayList<>();
        SemiFinishedInventoryTransaction ownedReverse = SemiFinishedInventoryTransaction.builder().id(142L)
                .factoryId("LIUSHANMEN").semiFinishedId(371L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("reversal-log:18").quantity(new BigDecimal("-1.00"))
                .createdAt(t.plusSeconds(1)).build();
        SemiFinishedInventoryTransaction originalOut = SemiFinishedInventoryTransaction.builder().id(139L)
                .factoryId("LIUSHANMEN").semiFinishedId(371L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .sourceRef("TASK-78").quantity(new BigDecimal("-1.00"))
                .unitCostAtTxn(new BigDecimal("32.55")).reportId(901L)
                .createdAt(t.minusSeconds(3)).build();
        secondLedger.add(ownedReverse);
        secondLedger.add(originalOut);

        when(reports.findById(901L)).thenReturn(Optional.of(secondReport));
        when(tasks.findByFactoryIdAndId("LIUSHANMEN", 78L)).thenReturn(Optional.of(secondTask));
        when(wips.findById(371L)).thenReturn(Optional.of(second));
        when(wips.findByIdForUpdate(371L)).thenReturn(Optional.of(second));
        when(txns.findById(139L)).thenReturn(Optional.of(originalOut));
        when(txns.findBySemiFinishedIdOrderByCreatedAtAsc(371L)).thenReturn(secondLedger);
        doAnswer(inv -> {
            SemiFinishedInventoryTransaction x = inv.getArgument(0);
            x.setId(savedTxnId.getAndIncrement());
            secondLedger.add(x);
            return x;
        }).when(txns).save(argThat(x -> x != null && Objects.equals(x.getSemiFinishedId(), 371L)));

        WipOutHistoryRepairRequestDTO dual = request();
        dual.setOriginalOutTxnIds(List.of(137L, 139L));
        WipOutHistoryRepairResultDTO result = service.repairWipOutHistory("LIUSHANMEN", dual, 99L);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems()).extracting(WipOutHistoryRepairResultDTO.Item::getSemiFinishedId)
                .containsExactly(370L, 371L);
        assertThat(sfi.getProducedQuantity()).isEqualByComparingTo("1.25");
        assertThat(sfi.getConsumedQuantity()).isZero();
        assertThat(sfi.getAvailableQuantity()).isEqualByComparingTo("1.25");
        assertThat(sfi.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
        assertThat(second.getProducedQuantity()).isZero();
        assertThat(second.getConsumedQuantity()).isZero();
        assertThat(second.getAvailableQuantity()).isZero();
        assertThat(second.getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
        assertThat(reversal.getRevertedTxnIds()).containsExactly(141L, 142L, 143L, 9000L, 9001L);
    }

    @Test
    void rejectsLineageOutsideTenSecondWindow() {
        ledger.get(1).setCreatedAt(reversal.getApprovedAt().plusSeconds(11));
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("证明");
        ledger.get(1).setCreatedAt(reversal.getApprovedAt().plusSeconds(1));
        report.setDeletedAt(reversal.getApprovedAt().plusSeconds(11));
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("证明");
        verify(txns, never()).save(any());
    }

    @Test
    void rejectsOutCreatedAfterReversalBoundary() {
        ledger.get(2).setCreatedAt(reversal.getApprovedAt().plusNanos(1));
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("证明");
        ledger.get(2).setCreatedAt(report.getDeletedAt().plusNanos(1));
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("证明");
        verify(txns, never()).save(any());
    }

    @Test
    void sameReportAndInventoryMultipleOutsAreRepairedOneToOneAndIdempotent() {
        SemiFinishedInventoryTransaction secondOut = SemiFinishedInventoryTransaction.builder().id(138L)
                .factoryId("LIUSHANMEN").semiFinishedId(370L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .sourceRef("TASK-77").quantity(new BigDecimal("-0.25"))
                .unitCostAtTxn(new BigDecimal("1.25")).reportId(900L)
                .createdAt(reversal.getApprovedAt().minusSeconds(1)).build();
        ledger.add(secondOut);
        when(txns.findById(138L)).thenReturn(Optional.of(secondOut));
        WipOutHistoryRepairRequestDTO two = request();
        two.setOriginalOutTxnIds(List.of(137L, 138L));

        WipOutHistoryRepairResultDTO first = service.repairWipOutHistory("LIUSHANMEN", two, 99L);
        assertThat(first.getItems()).hasSize(2).allMatch(item -> !item.isIdempotent());
        assertThat(reversal.getRevertedTxnIds()).containsExactly(141L, 142L, 143L, 9000L, 9001L);
        assertThat(sfi.getConsumedQuantity()).isZero();
        assertThat(sfi.getAvailableQuantity()).isEqualByComparingTo("1.25");

        WipOutHistoryRepairResultDTO second = service.repairWipOutHistory("LIUSHANMEN", two, 99L);
        assertThat(second.getItems()).hasSize(2).allMatch(WipOutHistoryRepairResultDTO.Item::isIdempotent);
        assertThat(reversal.getRevertedTxnIds()).containsExactly(141L, 142L, 143L, 9000L, 9001L);
        verify(txns, times(2)).save(any());
    }

    @Test
    void exactExistingCompensationIsAtomicallyAddedToAuditLog() {
        ledger.add(SemiFinishedInventoryTransaction.builder().id(700L).factoryId("LIUSHANMEN")
                .semiFinishedId(370L).txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("wip-out-history-repair:reversal-log:18:out-txn:137")
                .reportId(900L).quantity(new BigDecimal("1.25"))
                .unitCostAtTxn(new BigDecimal("1.25")).createdAt(reversal.getApprovedAt()).build());

        WipOutHistoryRepairResultDTO result = service.repairWipOutHistory("LIUSHANMEN", request(), 99L);
        assertThat(result.getItems().get(0).isIdempotent()).isTrue();
        assertThat(reversal.getRevertedTxnIds()).containsExactly(141L, 142L, 143L, 700L);
        verify(txns, never()).save(any());
        verify(logs).save(reversal);
    }

    @Test
    void rejectsTransferNullReportAndCrossBatchTaskBeforeMutation() {
        SemiFinishedInventoryTransaction original = ledger.get(2);
        original.setSourceType(SemiFinishedInventoryTransaction.SourceType.TRANSFER_OUT);
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("SECONDARY_CONSUME");
        original.setSourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME);
        original.setReportId(null);
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("reportId");
        original.setReportId(900L);
        WorkProcessTask wrongBatch = WorkProcessTask.builder().id(77L).factoryId("LIUSHANMEN")
                .productionBatchId(10889L).build();
        when(tasks.findByFactoryIdAndId("LIUSHANMEN", 77L)).thenReturn(Optional.of(wrongBatch));
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("跨批");
        verify(txns, never()).save(any());
    }

    @Test
    void rejectsCrossTenantInventoryBeforeMutation() {
        sfi.setFactoryId("OTHER");
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("半成品库存不属于");
        verify(txns, never()).save(any());
    }

    @Test
    void rejectsPartialDuplicateAndCostMismatchedExactCompensation() {
        String expected = "wip-out-history-repair:reversal-log:18:out-txn:137";
        SemiFinishedInventoryTransaction existing = SemiFinishedInventoryTransaction.builder().id(700L)
                .factoryId("LIUSHANMEN").semiFinishedId(370L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef(expected).reportId(900L).quantity(new BigDecimal("0.50"))
                .unitCostAtTxn(new BigDecimal("1.25")).createdAt(reversal.getApprovedAt()).build();
        ledger.add(existing);
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("部分补偿");

        existing.setQuantity(new BigDecimal("1.25"));
        existing.setUnitCostAtTxn(new BigDecimal("9.99"));
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("异成本");

        existing.setUnitCostAtTxn(new BigDecimal("1.25"));
        ledger.add(SemiFinishedInventoryTransaction.builder().id(701L).factoryId("LIUSHANMEN")
                .semiFinishedId(370L).txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef(expected).reportId(900L).quantity(new BigDecimal("1.25"))
                .unitCostAtTxn(new BigDecimal("1.25")).createdAt(reversal.getApprovedAt()).build());
        assertThatThrownBy(() -> service.repairWipOutHistory("LIUSHANMEN", request(), 99L))
                .hasMessageContaining("多条");
        verify(txns, never()).save(any());
    }
}
