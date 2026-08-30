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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

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
            .batchId(10761L).intermediateBatchNo("WIP-370").producedQuantity(new BigDecimal("1.25"))
            .consumedQuantity(new BigDecimal("1.25")).availableQuantity(BigDecimal.ZERO)
            .status(SemiFinishedInventory.Status.AVAILABLE).build();
    private ReportReversalLog reversal;

    @BeforeEach
    void setUp() {
        service = new ReportReversalServiceImpl(logs, mock(ProductionBatchRepository.class), reports, wips, txns,
                mock(FinishedGoodsBatchRepository.class), mock(UserRepository.class), tasks, mock(NotificationService.class));
        LocalDateTime t = LocalDateTime.of(2026, 8, 30, 10, 0);
        reversal = ReportReversalLog.builder().id(18L).factoryId("LIUSHANMEN").batchId(10761L)
                .status(ReportReversalLog.ReversalStatus.DONE).approvedAt(t)
                .revertedTxnIds(new ArrayList<>(List.of(141L, 142L, 143L))).build();
        ProductionReport report = new ProductionReport();
        report.setId(900L); report.setFactoryId("LIUSHANMEN"); report.setBatchId(10761L);
        report.setWorkProcessTaskId(77L); report.setDeletedAt(t.plusSeconds(2));
        WorkProcessTask task = WorkProcessTask.builder().id(77L).factoryId("LIUSHANMEN")
                .productionBatchId(10761L).build();
        ledger.add(SemiFinishedInventoryTransaction.builder().id(10L).factoryId("LIUSHANMEN").semiFinishedId(370L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN).sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .quantity(new BigDecimal("2.50")).unitCostAtTxn(new BigDecimal("1.25")).createdAt(t.minusSeconds(2)).build());
        ledger.add(SemiFinishedInventoryTransaction.builder().id(141L).factoryId("LIUSHANMEN").semiFinishedId(370L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE).sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("reversal-log:18").quantity(new BigDecimal("-1.25")).createdAt(t.plusSeconds(1)).build());
        ledger.add(SemiFinishedInventoryTransaction.builder().id(137L).factoryId("LIUSHANMEN").semiFinishedId(370L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT).sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .sourceRef("TASK-77").quantity(new BigDecimal("-1.25")).unitCostAtTxn(new BigDecimal("1.25"))
                .reportId(900L).createdAt(t.plusSeconds(1)).build());
        when(logs.findById(18L)).thenReturn(Optional.of(reversal));
        when(reports.findById(900L)).thenReturn(Optional.of(report));
        when(tasks.findByFactoryIdAndId("LIUSHANMEN", 77L)).thenReturn(Optional.of(task));
        when(wips.findById(370L)).thenReturn(Optional.of(sfi));
        when(wips.findByIdForUpdate(370L)).thenReturn(Optional.of(sfi));
        when(txns.findById(137L)).thenReturn(Optional.of(ledger.get(2)));
        when(txns.findBySemiFinishedIdOrderByCreatedAtAsc(370L)).thenReturn(ledger);
        when(txns.save(any())).thenAnswer(inv -> { SemiFinishedInventoryTransaction x = inv.getArgument(0); x.setId(9000L); ledger.add(x); return x; });
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
}
