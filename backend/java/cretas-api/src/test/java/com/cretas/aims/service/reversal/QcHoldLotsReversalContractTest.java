package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.foodsafety.ccp.CcpMonitoringService;
import com.cretas.aims.service.quality.release.InMemoryHoldLots;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 11/13 第二轮审查 M1 —— 整单撤回报工 ({@link ReportReversalServiceImpl#executeReversal} 真实入口):
 * 撤回的那笔入账若还在待检, 只解除它自己未放行的量 (已放行的存量不再被误挡, 别人的待检不被抵消);
 * 它随带的 CCP 偏离作废。装置沿用 {@code ReversalPhantomWipContractTest} 的 ReversalRig 写法。
 */
@DisplayName("整单撤回: 只解除被撤回报工自己的待检 + 作废它的 CCP 偏离")
class QcHoldLotsReversalContractTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH = 10761L;
    private static final Long REPORT = 23837L;

    private final ReportReversalLogRepository logRepo = mock(ReportReversalLogRepository.class);
    private final ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
    private final SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);
    private final SemiFinishedInventoryTransactionRepository txnRepo = mock(SemiFinishedInventoryTransactionRepository.class);
    private final WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
    private final CcpMonitoringService ccp = mock(CcpMonitoringService.class);
    private final List<SemiFinishedInventoryTransaction> saved = new ArrayList<>();
    private final InMemoryHoldLots lots = new InMemoryHoldLots();

    private ReportReversalServiceImpl rig(SemiFinishedInventory sfi, SemiFinishedInventoryTransaction in) {
        ReportReversalServiceImpl service = new ReportReversalServiceImpl(
                logRepo, mock(ProductionBatchRepository.class), reportRepo, wipRepo, txnRepo,
                mock(FinishedGoodsBatchRepository.class), mock(UserRepository.class), taskRepo,
                mock(com.cretas.aims.service.NotificationService.class));
        ReflectionTestUtils.setField(service, "semiQcHoldLots", lots.service);
        ReflectionTestUtils.setField(service, "ccpMonitoringService", ccp);
        ReportReversalLog log = ReportReversalLog.builder()
                .id(15L).factoryId(FACTORY).batchId(BATCH).status(ReportReversalLog.ReversalStatus.APPROVED)
                .submittedBy(1309L).build();
        when(logRepo.findById(15L)).thenReturn(Optional.of(log));
        when(logRepo.findByIdAndFactoryIdForUpdate(15L, FACTORY)).thenReturn(Optional.of(log));
        lenient().when(logRepo.save(any(ReportReversalLog.class))).thenAnswer(i -> i.getArgument(0));
        ProductionReport r = ProductionReport.builder().id(REPORT).factoryId(FACTORY).batchId(BATCH).build();
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH)).thenReturn(List.of(r));
        lenient().when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> i.getArgument(0));
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY, BATCH))
                .thenReturn(Collections.emptyList());
        lenient().when(txnRepo.findByFactoryIdAndReportId(FACTORY, REPORT)).thenReturn(List.of(in));
        lenient().when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(i -> {
            SemiFinishedInventoryTransaction t = i.getArgument(0);
            t.setId(9000L + saved.size());
            saved.add(t);
            return t;
        });
        lenient().when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(anyString(), anyLong()))
                .thenReturn(Collections.emptyList());
        when(wipRepo.findById(342L)).thenReturn(Optional.of(sfi));
        when(wipRepo.findByIdForUpdate(342L)).thenReturn(Optional.of(sfi));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(342L)).thenAnswer(i -> {
            List<SemiFinishedInventoryTransaction> all = new ArrayList<>(List.of(in));
            all.addAll(saved);
            return all;
        });
        return service;
    }

    private static SemiFinishedInventoryTransaction in() {
        return SemiFinishedInventoryTransaction.builder()
                .id(101L).factoryId(FACTORY).semiFinishedId(342L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .quantity(new BigDecimal("40")).unitCostAtTxn(new BigDecimal("2")).reportId(REPORT).build();
    }

    /** 整单撤回要求这一行最后清空 (validateFinalWipState); 这一行就是被撤回报工的 40, 全部待检。 */
    private SemiFinishedInventory row() {
        return SemiFinishedInventory.builder()
                .id(342L).factoryId(FACTORY).intermediateBatchNo("B10761-S1-1788").batchId(BATCH)
                .producedQuantity(new BigDecimal("40")).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("40")).adjustmentQuantity(BigDecimal.ZERO).unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .inspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING)
                .inspectionPendingQuantity(new BigDecimal("40")).build();
    }

    /** 撤回之后这一行再入一笔不需检的 30 (例: 重新报工), 看它能不能领。 */
    private static void laterFreeOutput(SemiFinishedInventory sfi) {
        sfi.setProducedQuantity(sfi.getProducedQuantity().add(new BigDecimal("30")));
        sfi.refreshAvailableQuantity();
        sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);
    }

    @Test
    @DisplayName("撤回还在待检的报工 → 解除它的 40 (行上不留陈旧待检), 之后入的不需检产出照常可领; CCP 偏离作废")
    void reversalUnwindsOwnHold() {
        SemiFinishedInventory sfi = row();
        lots.service.record(sfi, new BigDecimal("40"), REPORT, null);
        rig(sfi, in()).executeReversal(15L, FACTORY);
        System.out.println("M1-reversal balance=" + sfi.computeBalance() + " storedPending="
                + sfi.getInspectionPendingQuantity());
        assertThat(sfi.computeBalance()).isEqualByComparingTo("0");
        assertThat(sfi.getInspectionPendingQuantity()).isEqualByComparingTo("0");
        laterFreeOutput(sfi);
        assertThat(sfi.drawableBalance()).isEqualByComparingTo("30");
        verify(ccp).voidDeviationsOfReport(eq(FACTORY), eq("YR:" + REPORT), any(), anyString());
    }

    @Test
    @DisplayName("阴性对照 (保守): 这 40 的待检认不出是哪一笔 (没有分笔, 例如 CCP 偏离整行扣留) → 撤回不解除")
    void reversalWithoutLotKeepsHold() {
        SemiFinishedInventory sfi = row();
        rig(sfi, in()).executeReversal(15L, FACTORY);
        assertThat(sfi.getInspectionPendingQuantity()).isEqualByComparingTo("40");
        laterFreeOutput(sfi);
        assertThat(sfi.drawableBalance()).isEqualByComparingTo("0");
    }
}
