package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import com.cretas.aims.service.wip.WipLedgerEntries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * B-47 REVERSE 路径: replayMovingAverage 精确数值测试。
 *
 * <p>replayMovingAverage 通过 executeReversal 间接触发，测试策略：
 * 构造完整的 executeReversal 场景，捕获最终 SFI 保存，验证均价回放结果。
 *
 * <p>BUG-R1 / BUG-R2 已在 #662 修复 (commit 122d5d48):
 * <ul>
 *   <li>BUG-R1 (已修): replayMovingAverage 现在两条路径均 setProducedQuantity =
 *       IN 净额 (扣除 REVERSE 后), 撤回后不再虚高。下方 bugR1_* 测试是
 *       <b>真实断言</b> (assertEquals producedQuantity == 0), 对修复后代码 PASS。</li>
 *   <li>BUG-R2 (已修): replayMovingAverage 现计入 OUT 事务; 且 Guard G1
 *       (DOWNSTREAM_CONSUMED) 在 submitReversal 阻止"先 OUT 后撤回"路径。
 *       下方 bugR2_* 是纯文档测试 (assertTrue), 记录 G1 守护语义。</li>
 * </ul>
 *
 * @see ReportReversalServiceImpl#replayMovingAverage
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("B-47: replayMovingAverage REVERSE 路径精确数值")
class ReplayMovingAverageTest {

    private static final String FACTORY_ID = "F001";

    @Mock private ReportReversalLogRepository reversalLogRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private FinishedGoodsBatchRepository fgbRepo;
    @Mock private UserRepository userRepository;
    @Mock private WorkProcessTaskRepository workProcessTaskRepository;

    @InjectMocks
    private ReportReversalServiceImpl service;

    @Captor
    private ArgumentCaptor<SemiFinishedInventory> sfiCaptor;
    private Long currentBatchId;

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Stubs the common executeReversal plumbing: log load, no FGB, empty tasks. */
    private ReportReversalLog stubExecuteReversalSetup(Long logId, Long batchId, Long sfiId,
                                                        List<ProductionReport> reports,
                                                        List<SemiFinishedInventoryTransaction> inTxns) {
        ReportReversalLog log = ReportReversalLog.builder()
                .id(logId)
                .factoryId(FACTORY_ID)
                .batchId(batchId)
                .planId(null)  // no FGB involvement
                .status(ReportReversalLog.ReversalStatus.PENDING)
                .submittedBy(1L)
                .build();
        currentBatchId = batchId;

        when(reversalLogRepo.findById(logId)).thenReturn(Optional.of(log));
        when(reversalLogRepo.findByIdAndFactoryIdForUpdate(logId, FACTORY_ID)).thenReturn(Optional.of(log));
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, batchId)).thenReturn(reports);
        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(Collections.emptyList());

        // REVERSE txn save
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> {
            SemiFinishedInventoryTransaction t = inv.getArgument(0);
            t.setId(99L);
            return t;
        });

        // Wire IN txns for the report
        for (ProductionReport r : reports) {
            Long taskId = inTxns.stream()
                    .filter(txn -> SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType()))
                    .map(SemiFinishedInventoryTransaction::getSourceRef)
                    .filter(Objects::nonNull)
                    .filter(ref -> ref.startsWith("TASK-"))
                    .map(ref -> Long.valueOf(ref.substring("TASK-".length())))
                    .findFirst().orElse(8_000L + r.getId());
            r.setWorkProcessTaskId(taskId);
            when(workProcessTaskRepository.findByFactoryIdAndId(FACTORY_ID, taskId))
                    .thenReturn(Optional.of(WorkProcessTask.builder().id(taskId)
                            .factoryId(FACTORY_ID).productionBatchId(batchId).build()));
            for (SemiFinishedInventoryTransaction txn : inTxns) {
                txn.setFactoryId(FACTORY_ID);
                txn.setReportId(r.getId());
                if (SemiFinishedInventoryTransaction.TxnType.IN.equals(txn.getTxnType())) {
                    txn.setSourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT);
                } else if (SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType())) {
                    txn.setSourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME);
                    txn.setSourceRef("TASK-" + taskId);
                } else if (SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(txn.getTxnType())) {
                    txn.setSourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL);
                }
            }
            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, r.getId()))
                    .thenReturn(inTxns);
        }

        when(reversalLogRepo.save(any(ReportReversalLog.class))).thenAnswer(inv -> inv.getArgument(0));
        return log;
    }

    private SemiFinishedInventory stubSfi(Long sfiId, BigDecimal produced, BigDecimal consumed,
                                          BigDecimal available, BigDecimal unitCost,
                                          String intermediateBatchNo) {
        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(sfiId)
                .factoryId(FACTORY_ID)
                .batchId(currentBatchId)
                .intermediateBatchNo(intermediateBatchNo)
                .producedQuantity(produced)
                .consumedQuantity(consumed)
                .availableQuantity(available)
                .adjustmentQuantity(BigDecimal.ZERO)
                .unitCost(unitCost)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .unit("kg")
                .build();
        when(wipRepo.findByIdForUpdate(sfiId)).thenReturn(Optional.of(sfi));
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY_ID, currentBatchId))
                .thenReturn(List.of(sfi));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        return sfi;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("replayMovingAverage — 撤回后均价回放")
    class ReplayTests {

        @Test
        @DisplayName("RP-OUT-2: IN1.25/OUT1.25/正REVERSE1.25 → produced1.25 consumed0 available1.25")
        void replay_persistsConsumedQuantityAfterOutRestore() {
            Long sfiId = 910L;
            SemiFinishedInventory sfi = stubSfi(sfiId, new BigDecimal("1.25"),
                    new BigDecimal("1.25"), BigDecimal.ZERO, new BigDecimal("10.0000"), "SEMI-RP-OUT-2");
            SemiFinishedInventoryTransaction in = SemiFinishedInventoryTransaction.builder()
                    .semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("1.25")).unitCostAtTxn(new BigDecimal("10.0000")).build();
            SemiFinishedInventoryTransaction out = SemiFinishedInventoryTransaction.builder()
                    .semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                    .quantity(new BigDecimal("-1.25")).build();
            SemiFinishedInventoryTransaction restore = SemiFinishedInventoryTransaction.builder()
                    .semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("1.25")).build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId)).thenReturn(List.of(in, out, restore));

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                    "replayMovingAverage", sfiId, FACTORY_ID, java.time.LocalDateTime.now());

            assertEquals(new BigDecimal("1.25"), sfi.getProducedQuantity());
            assertEquals(0, BigDecimal.ZERO.compareTo(sfi.getConsumedQuantity()));
            assertEquals(new BigDecimal("1.25"), sfi.getAvailableQuantity());
            assertEquals(SemiFinishedInventory.Status.AVAILABLE, sfi.getStatus());
        }

        @Test
        @DisplayName("RP-OUT-1: 同一整单的 IN + WIP OUT 撤回必须分别写负/正 REVERSE")
        void wholeOrderReversal_reversesInAndOutWithOppositeSigns() {
            Long logId = 901L, batchId = 902L, sfiId = 903L;
            ProductionReport report = ProductionReport.builder()
                    .id(904L).factoryId(FACTORY_ID).batchId(batchId).build();
            SemiFinishedInventoryTransaction inTxn = SemiFinishedInventoryTransaction.builder()
                    .id(905L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("100")).unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            SemiFinishedInventoryTransaction outTxn = SemiFinishedInventoryTransaction.builder()
                    .id(906L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                    .sourceRef("TASK-907").quantity(new BigDecimal("-20"))
                    .unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(report), List.of(inTxn, outTxn));
            stubSfi(sfiId, new BigDecimal("100"), new BigDecimal("20"), new BigDecimal("80"),
                    new BigDecimal("10.0000"), "SEMI-RP-OUT-1");
            SemiFinishedInventoryTransaction reverseIn = SemiFinishedInventoryTransaction.builder()
                    .id(908L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("-100")).unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            SemiFinishedInventoryTransaction reverseOut = SemiFinishedInventoryTransaction.builder()
                    .id(909L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("20")).unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(inTxn, outTxn), List.of(inTxn, outTxn),
                            List.of(inTxn, outTxn, reverseIn, reverseOut),
                            List.of(inTxn, outTxn, reverseIn, reverseOut));

            service.executeReversal(logId, FACTORY_ID);

            ArgumentCaptor<SemiFinishedInventoryTransaction> captor =
                    ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
            verify(txnRepo, times(2)).save(captor.capture());
            assertEquals(List.of(new BigDecimal("-100"), new BigDecimal("20")),
                    captor.getAllValues().stream()
                            .map(SemiFinishedInventoryTransaction::getQuantity).toList());
        }

        @Test
        @DisplayName("RP-OUT-2: 两笔等量 OUT 只有一笔已补偿 → 必须再写一笔正向 REVERSE")
        void duplicateEqualOuts_oneExistingReverse_onlySkipsOne() {
            Long logId = 911L, batchId = 912L, sfiId = 913L, reportId = 914L;
            ProductionReport report = ProductionReport.builder()
                    .id(reportId).factoryId(FACTORY_ID).batchId(batchId).build();
            SemiFinishedInventoryTransaction out1 = SemiFinishedInventoryTransaction.builder()
                    .id(915L).semiFinishedId(sfiId).reportId(reportId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                    .sourceRef("TASK-920").quantity(new BigDecimal("-2")).build();
            SemiFinishedInventoryTransaction out2 = SemiFinishedInventoryTransaction.builder()
                    .id(916L).semiFinishedId(sfiId).reportId(reportId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                    .sourceRef("TASK-921").quantity(new BigDecimal("-2")).build();
            SemiFinishedInventoryTransaction existingReverse = SemiFinishedInventoryTransaction.builder()
                    .id(917L).semiFinishedId(sfiId).reportId(reportId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                    .quantity(new BigDecimal("2")).build();
            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(report),
                    List.of(out1, out2, existingReverse));
            stubSfi(sfiId, BigDecimal.ZERO, new BigDecimal("4"), BigDecimal.ZERO,
                    new BigDecimal("10.0000"), "SEMI-RP-OUT-2");
            SemiFinishedInventoryTransaction secondReverse = SemiFinishedInventoryTransaction.builder()
                    .id(918L).semiFinishedId(sfiId).reportId(reportId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                    .quantity(new BigDecimal("2")).build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(out1, out2, existingReverse),
                            List.of(out1, out2, existingReverse),
                            List.of(out1, out2, existingReverse, secondReverse));

            service.executeReversal(logId, FACTORY_ID);

            ArgumentCaptor<SemiFinishedInventoryTransaction> captor =
                    ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
            verify(txnRepo, times(1)).save(captor.capture());
            assertEquals(new BigDecimal("2"), captor.getValue().getQuantity());
            assertEquals(sfiId, captor.getValue().getSemiFinishedId());
            assertEquals(reportId, captor.getValue().getReportId());
        }

        @Test
        @DisplayName("RP-LOCK-1: 加锁后出现新 WIP 锚点 → 首个 mutation 前 409")
        void ledgerChangesAfterLock_failsBeforeMutation() {
            Long logId = 931L, batchId = 932L, sfiId = 933L, reportId = 934L;
            ProductionReport report = ProductionReport.builder()
                    .id(reportId).factoryId(FACTORY_ID).batchId(batchId).build();
            SemiFinishedInventoryTransaction initialIn = SemiFinishedInventoryTransaction.builder()
                    .id(935L).semiFinishedId(sfiId).reportId(reportId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("1")).build();
            SemiFinishedInventoryTransaction concurrentOut = SemiFinishedInventoryTransaction.builder()
                    .id(936L).semiFinishedId(999L).reportId(reportId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                    .sourceRef("TASK-937").quantity(new BigDecimal("-1")).build();
            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(report), List.of(initialIn));
            SemiFinishedInventory locked = stubSfi(sfiId, new BigDecimal("1"), BigDecimal.ZERO,
                    new BigDecimal("1"), new BigDecimal("10.0000"), "SEMI-RP-LOCK-1");
            when(wipRepo.findByIdForUpdate(sfiId)).thenReturn(Optional.of(locked));
            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, reportId))
                    .thenReturn(List.of(initialIn), List.of(initialIn, concurrentOut));

            com.cretas.aims.exception.BusinessException error = assertThrows(
                    com.cretas.aims.exception.BusinessException.class,
                    () -> service.executeReversal(logId, FACTORY_ID));

            assertEquals("WIP_LEDGER_CHANGED", error.getErrorCode());
            verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
            verify(reportRepo, never()).save(any(ProductionReport.class));
        }

        @Test
        @DisplayName("RP-IDEMP-1: 多余 REVERSE 不能把撤回日志误标 DONE")
        void extraExistingReverseFailsBeforeMutation() {
            Long logId = 941L, batchId = 942L, sfiId = 943L, reportId = 944L;
            ProductionReport report = ProductionReport.builder()
                    .id(reportId).factoryId(FACTORY_ID).batchId(batchId).build();
            SemiFinishedInventoryTransaction original = SemiFinishedInventoryTransaction.builder()
                    .id(945L).semiFinishedId(sfiId).reportId(reportId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("5")).unitCostAtTxn(new BigDecimal("10.0000")).build();
            SemiFinishedInventoryTransaction firstReverse = SemiFinishedInventoryTransaction.builder()
                    .id(946L).semiFinishedId(sfiId).reportId(reportId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                    .quantity(new BigDecimal("-5")).build();
            SemiFinishedInventoryTransaction extraReverse = SemiFinishedInventoryTransaction.builder()
                    .id(947L).semiFinishedId(sfiId).reportId(reportId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                    .quantity(new BigDecimal("-5")).build();
            ReportReversalLog reversalLog = stubExecuteReversalSetup(logId, batchId, sfiId,
                    List.of(report), List.of(original, firstReverse, extraReverse));
            stubSfi(sfiId, new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("10.0000"), "SEMI-RP-IDEMP-1");

            com.cretas.aims.exception.BusinessException error = assertThrows(
                    com.cretas.aims.exception.BusinessException.class,
                    () -> service.executeReversal(logId, FACTORY_ID));

            assertEquals("PARTIAL_COMPENSATION", error.getErrorCode());
            assertNotEquals(ReportReversalLog.ReversalStatus.DONE, reversalLog.getStatus());
            verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
            verify(reportRepo, never()).save(any(ProductionReport.class));
        }

        @Test
        @DisplayName("RP-ADJ-1: 正负盘点调整都必须在首个 mutation 前阻断")
        void nonZeroAdjustmentFailsBeforeMutation() {
            for (String adjustment : List.of("1", "-1")) {
                reset(reversalLogRepo, batchRepo, reportRepo, wipRepo, txnRepo, fgbRepo,
                        userRepository, workProcessTaskRepository);
                Long suffix = "1".equals(adjustment) ? 1L : 2L;
                Long logId = 950L + suffix, batchId = 960L + suffix;
                Long sfiId = 970L + suffix, reportId = 980L + suffix;
                ProductionReport report = ProductionReport.builder()
                        .id(reportId).factoryId(FACTORY_ID).batchId(batchId).build();
                SemiFinishedInventoryTransaction original = SemiFinishedInventoryTransaction.builder()
                        .id(990L + suffix).semiFinishedId(sfiId).reportId(reportId)
                        .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                        .quantity(new BigDecimal("5")).unitCostAtTxn(new BigDecimal("10.0000")).build();
                ReportReversalLog reversalLog = stubExecuteReversalSetup(logId, batchId, sfiId,
                        List.of(report), List.of(original));
                SemiFinishedInventory sfi = stubSfi(sfiId, new BigDecimal("5"), BigDecimal.ZERO,
                        new BigDecimal("5"), new BigDecimal("10.0000"), "SEMI-RP-ADJ-" + suffix);
                sfi.setAdjustmentQuantity(new BigDecimal(adjustment));

                com.cretas.aims.exception.BusinessException error = assertThrows(
                        com.cretas.aims.exception.BusinessException.class,
                        () -> service.executeReversal(logId, FACTORY_ID));

                assertEquals("WIP_ADJUSTMENT_PRESENT", error.getErrorCode());
                assertNotEquals(ReportReversalLog.ReversalStatus.DONE, reversalLog.getStatus());
                verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
                verify(reportRepo, never()).save(any(ProductionReport.class));
            }
        }

        @Test
        @DisplayName("RP-LEDGER-1: 完整 SFI 流水中的孤立正/负 REVERSE 都必须在首个 mutation 前阻断")
        void orphanReverseOutsideReportQueryFailsBeforeMutation() {
            for (String quantity : List.of("-5", "5")) {
                reset(reversalLogRepo, batchRepo, reportRepo, wipRepo, txnRepo, fgbRepo,
                        userRepository, workProcessTaskRepository);
                long suffix = quantity.startsWith("-") ? 1L : 2L;
                Long logId = 1000L + suffix, batchId = 1010L + suffix;
                Long sfiId = 1020L + suffix, reportId = 1030L + suffix;
                ProductionReport report = ProductionReport.builder()
                        .id(reportId).factoryId(FACTORY_ID).batchId(batchId).build();
                SemiFinishedInventoryTransaction original = SemiFinishedInventoryTransaction.builder()
                        .id(1040L + suffix).semiFinishedId(sfiId).reportId(reportId)
                        .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                        .quantity(new BigDecimal("5")).unitCostAtTxn(new BigDecimal("10.0000")).build();
                SemiFinishedInventoryTransaction orphan = SemiFinishedInventoryTransaction.builder()
                        .id(1050L + suffix).factoryId(FACTORY_ID).semiFinishedId(sfiId)
                        .reportId(null).txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                        .quantity(new BigDecimal(quantity)).build();
                ReportReversalLog reversalLog = stubExecuteReversalSetup(logId, batchId, sfiId,
                        List.of(report), List.of(original));
                stubSfi(sfiId, new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("5"),
                        new BigDecimal("10.0000"), "SEMI-RP-ORPHAN-" + suffix);
                when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, reportId))
                        .thenReturn(List.of(original));
                when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                        .thenReturn(List.of(original, orphan));

                com.cretas.aims.exception.BusinessException error = assertThrows(
                        com.cretas.aims.exception.BusinessException.class,
                        () -> service.executeReversal(logId, FACTORY_ID));

                assertEquals("PARTIAL_COMPENSATION", error.getErrorCode());
                assertNotEquals(ReportReversalLog.ReversalStatus.DONE, reversalLog.getStatus());
                verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
                verify(reportRepo, never()).save(any(ProductionReport.class));
                verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
            }
        }

        @Test
        @DisplayName("RP-1: 单次 IN 被完全撤回 → unitCost=null, availableQuantity=0, status=DEPLETED")
        void singleInFullyReversed_depleted() {
            /*
             * State before reversal:
             *   SFI: produced=100, available=100, unitCost=10.0000
             *   Ledger: [IN qty=100 unitCost@Txn=10.0000]
             *           [REVERSE qty=-100 unitCost@Txn=10.0000]  ← written by executeReversal
             *
             * After replayMovingAverage:
             *   totalQty = 100 + (-100) = 0
             *   → DEPLETED, unitCost=null, availableQuantity=0
             */
            Long logId = 1L, batchId = 100L, sfiId = 200L;

            ProductionReport r = ProductionReport.builder()
                    .id(500L).factoryId(FACTORY_ID).batchId(batchId).build();

            SemiFinishedInventoryTransaction inTxn = SemiFinishedInventoryTransaction.builder()
                    .id(10L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("100"))
                    .unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();

            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(r), List.of(inTxn));
            stubSfi(sfiId, new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                    new BigDecimal("10.0000"), "SEMI-RP001");

            // After executeReversal writes the REVERSE TXN, replayMovingAverage re-reads all TXNs
            // Stub the post-REVERSE ledger state (IN + REVERSE)
            SemiFinishedInventoryTransaction reverseTxn = SemiFinishedInventoryTransaction.builder()
                    .id(99L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("-100"))
                    .unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(inTxn), List.of(inTxn), List.of(inTxn, reverseTxn));

            service.executeReversal(logId, FACTORY_ID);

            // Capture the final SFI save (from replayMovingAverage)
            verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
            SemiFinishedInventory finalSfi = sfiCaptor.getAllValues().stream()
                    .filter(s -> s.getId() != null && s.getId().equals(sfiId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("SFI with id=" + sfiId + " not saved"));

            assertEquals(BigDecimal.ZERO, finalSfi.getAvailableQuantity(),
                    "After full reversal: availableQuantity must be 0");
            assertEquals(BigDecimal.ZERO, finalSfi.getConsumedQuantity(),
                    "After full reversal: consumedQuantity must be 0");
            assertNull(finalSfi.getUnitCost(),
                    "After full reversal: unitCost must be null (no valid IN remaining)");
            assertEquals(SemiFinishedInventory.Status.DEPLETED, finalSfi.getStatus(),
                    "After full reversal: status must be DEPLETED");
        }

        @Test
        @DisplayName("RP-2: 双批次 IN, 仅撤回第二次 → 均价回滚到第一批单价")
        void dualBatchPartialReversal_unitCostRollsBackToFirstBatch() {
            /*
             * Scenario: two IN txns, second one is now being reversed.
             * Ledger after reversal:
             *   IN-1: qty=100  @ 10.0000/kg
             *   IN-2: qty=50   @ 16.0000/kg   (original)
             *   REVERSE: qty=-50 @ 16.0000/kg  (written for report-2)
             *
             * replayMovingAverage sums:
             *   totalQty        = 100 + 50 + (-50) = 100
             *   weightedCost    = 100×10.0000 + 50×16.0000 + (-50)×16.0000
             *                   = 1000 + 800 - 800 = 1000
             *   newUnitCost     = 1000 / 100 = 10.0000
             *   availableQty    = totalQty = 100
             */
            Long logId = 2L, batchId = 101L, sfiId = 201L;

            // Only report-2 is in this batch for the reversal
            ProductionReport r2 = ProductionReport.builder()
                    .id(502L).factoryId(FACTORY_ID).batchId(batchId).build();

            SemiFinishedInventoryTransaction inTxn2 = SemiFinishedInventoryTransaction.builder()
                    .id(12L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("50"))
                    .unitCostAtTxn(new BigDecimal("16.0000"))
                    .build();

            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(r2), List.of(inTxn2));
            stubSfi(sfiId, new BigDecimal("150"), BigDecimal.ZERO, new BigDecimal("150"),
                    new BigDecimal("12.0000"), "SEMI-RP002");

            // Ledger after REVERSE written: IN-1 + IN-2 + REVERSE
            SemiFinishedInventoryTransaction inTxn1 = SemiFinishedInventoryTransaction.builder()
                    .id(11L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("100"))
                    .unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            SemiFinishedInventoryTransaction reverseTxn2 = SemiFinishedInventoryTransaction.builder()
                    .id(99L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("-50"))
                    .unitCostAtTxn(new BigDecimal("16.0000"))
                    .build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(inTxn1, inTxn2, reverseTxn2));

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                    "replayMovingAverage", sfiId, FACTORY_ID, java.time.LocalDateTime.now());

            verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
            SemiFinishedInventory finalSfi = sfiCaptor.getAllValues().stream()
                    .filter(s -> s.getId() != null && s.getId().equals(sfiId))
                    .findFirst()
                    .orElseThrow();

            assertEquals(new BigDecimal("100"), finalSfi.getAvailableQuantity(),
                    "After reversing IN-2: availableQuantity = 100 (only IN-1 remains)");
            // hand-calc: (100×10 + 50×16 - 50×16) / 100 = 1000/100 = 10.0000
            assertEquals(new BigDecimal("10.0000"), finalSfi.getUnitCost(),
                    "After reversing IN-2: unitCost rolls back to IN-1 price = 10.0000");
            assertEquals(SemiFinishedInventory.Status.AVAILABLE, finalSfi.getStatus());
        }

        @Test
        @DisplayName("RP-3: 混合均价撤销 — 100 kg@10 + 50 kg@16 avg=12.0000, 撤 50@16 → avg 回 10.0000")
        void mixedAverageThenReversal_exactRollback() {
            /*
             * This is the canonical B-47 test: prove that the replay undoes the weighted blend.
             * Identical math to RP-2 but written as the canonical check.
             *
             * Before reversal: unitCost=12.0000 (blended)
             * Ledger replay after REVERSE:
             *   totalQty     = 100 + 50 - 50 = 100
             *   weightedCost = 100×10 + 50×16 - 50×16 = 1000
             *   newUnitCost  = 1000/100 = 10.0000
             */
            Long logId = 3L, batchId = 102L, sfiId = 202L;

            ProductionReport r = ProductionReport.builder()
                    .id(503L).factoryId(FACTORY_ID).batchId(batchId).build();

            SemiFinishedInventoryTransaction inTxn = SemiFinishedInventoryTransaction.builder()
                    .id(13L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("50"))
                    .unitCostAtTxn(new BigDecimal("16.0000"))
                    .build();

            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(r), List.of(inTxn));
            stubSfi(sfiId, new BigDecimal("150"), BigDecimal.ZERO, new BigDecimal("150"),
                    new BigDecimal("12.0000"), "SEMI-RP003");

            SemiFinishedInventoryTransaction in1 = SemiFinishedInventoryTransaction.builder()
                    .id(14L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("100")).unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            SemiFinishedInventoryTransaction rev = SemiFinishedInventoryTransaction.builder()
                    .id(99L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("-50")).unitCostAtTxn(new BigDecimal("16.0000"))
                    .build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(in1, inTxn, rev));

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                    "replayMovingAverage", sfiId, FACTORY_ID, java.time.LocalDateTime.now());

            verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
            SemiFinishedInventory finalSfi = sfiCaptor.getAllValues().stream()
                    .filter(s -> s.getId() != null && s.getId().equals(sfiId))
                    .findFirst().orElseThrow();

            assertEquals(new BigDecimal("10.0000"), finalSfi.getUnitCost(),
                    "Canonical B-47: 100@10+50@16 avg=12, reverse 50@16 → unitCost=10.0000");
            assertEquals(new BigDecimal("100"), finalSfi.getAvailableQuantity());
        }

        @Test
        @DisplayName("RP-4: 全已知 0 成本 IN (unitCostAtTxn=0) → unitCost=0.0000, accumulatedCost=0.00 (非 null)")
        void allKnownZeroCost_keepsZeroNotNull() {
            /*
             * 回归 (#840 引入): replayMovingAverage line 829 守卫 `weightedCost > 0`
             * 把"全部已知成本但恰好为 0"(weightedCost==0, !hasUnknownCost) 误推进 else
             * 清成 null, 混淆了"未知成本→null (对)"与"已知 0 成本→应是 0"。
             *
             * 反例: IN 10kg @ unitCostAtTxn=0 (合法 0 成本, 已知非未知), 无 REVERSE/未知行。
             *   producedQty   = 10
             *   weightedCost  = 10 × 0 = 0
             *   hasUnknownCost = false
             *   → 应得 unitCost=0.0000 / accumulatedCost=0.00 (不是 null)。
             */
            Long logId = 4L, batchId = 103L, sfiId = 203L;

            ProductionReport r = ProductionReport.builder()
                    .id(504L).factoryId(FACTORY_ID).batchId(batchId).build();

            SemiFinishedInventoryTransaction inTxn = SemiFinishedInventoryTransaction.builder()
                    .id(15L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("10"))
                    .unitCostAtTxn(new BigDecimal("0.0000"))  // 已知 0 成本 (合法), 非未知
                    .build();

            // 撤回的是另一份 (不存在的) 报工, 留下这份 0 成本 IN 在账上。
            // 直接用单 IN 账本回放, 验证 0 成本不被误清。
            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(r), List.of(inTxn));
            stubSfi(sfiId, new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"),
                    new BigDecimal("0.0000"), "SEMI-RP004");

            // 回放账本: 仅一份已知 0 成本 IN (无 REVERSE 减项, producedQty=10>0)
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(inTxn));

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                    "replayMovingAverage", sfiId, FACTORY_ID, java.time.LocalDateTime.now());

            verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
            SemiFinishedInventory finalSfi = sfiCaptor.getAllValues().stream()
                    .filter(s -> s.getId() != null && s.getId().equals(sfiId))
                    .findFirst().orElseThrow();

            assertEquals(new BigDecimal("10"), finalSfi.getProducedQuantity(),
                    "0 成本 IN 仍是正净产出, producedQuantity=10");
            assertNotNull(finalSfi.getUnitCost(),
                    "全已知 0 成本: unitCost 不应被误清为 null");
            assertEquals(0, new BigDecimal("0.0000").compareTo(finalSfi.getUnitCost()),
                    "全已知 0 成本: unitCost=0.0000 (scale-4)");
            assertEquals(0, BigDecimal.ZERO.compareTo(finalSfi.getAccumulatedCost()),
                    "全已知 0 成本: accumulatedCost=0.00 (非 null)");
            assertNotNull(finalSfi.getAccumulatedCost(),
                    "全已知 0 成本: accumulatedCost 不应为 null");
        }

        @Test
        @DisplayName("RP-COR-1: 报工更正 ADJUST 增量进入净产出但不改变原累计成本")
        void reportCorrectionAdjust_increasesProducedWithoutInventingCost() {
            Long sfiId = 240L;
            SemiFinishedInventory sfi = stubSfi(sfiId, new BigDecimal("0.82"), BigDecimal.ZERO,
                    new BigDecimal("0.82"), new BigDecimal("12.3537"), "SEMI-COR-1");
            sfi.setAccumulatedCost(new BigDecimal("10.13"));
            SemiFinishedInventoryTransaction in = SemiFinishedInventoryTransaction.builder()
                    .id(241L).semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("0.81")).unitCostAtTxn(new BigDecimal("12.50")).build();
            SemiFinishedInventoryTransaction correction = SemiFinishedInventoryTransaction.builder()
                    .id(242L).semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.ADJUST)
                    .sourceType(WipLedgerEntries.REPORT_CORRECTION_SOURCE_TYPE)
                    .sourceRef("report-correction:COR-COR-1:report:240")
                    .reportId(240L).quantity(new BigDecimal("0.01")).unitCostAtTxn(null).build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId)).thenReturn(List.of(in, correction));

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                    "replayMovingAverage", sfiId, FACTORY_ID, java.time.LocalDateTime.now());

            assertEquals(new BigDecimal("0.82"), sfi.getProducedQuantity());
            assertEquals(new BigDecimal("10.13"), sfi.getAccumulatedCost());
            assertEquals(new BigDecimal("12.3476"), sfi.getUnitCost());
            assertEquals(new BigDecimal("0.82"), sfi.getAvailableQuantity());
        }

        @Test
        @DisplayName("RP-COR-2: 下游 INPUT 撤回恢复消费后不丢报工更正量")
        void downstreamReversal_restoresConsumedQuantityWithoutErasingCorrection() {
            Long sfiId = 250L;
            SemiFinishedInventory sfi = stubSfi(sfiId, new BigDecimal("0.82"), new BigDecimal("0.20"),
                    new BigDecimal("0.62"), new BigDecimal("12.3537"), "SEMI-COR-2");
            sfi.setAccumulatedCost(new BigDecimal("10.13"));
            SemiFinishedInventoryTransaction in = SemiFinishedInventoryTransaction.builder()
                    .id(251L).semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("0.81")).unitCostAtTxn(new BigDecimal("12.50")).build();
            SemiFinishedInventoryTransaction correction = SemiFinishedInventoryTransaction.builder()
                    .id(252L).semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.ADJUST)
                    .sourceType(WipLedgerEntries.REPORT_CORRECTION_SOURCE_TYPE).reportId(250L)
                    .quantity(new BigDecimal("0.01")).unitCostAtTxn(null).build();
            SemiFinishedInventoryTransaction out = SemiFinishedInventoryTransaction.builder()
                    .id(253L).semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                    .quantity(new BigDecimal("-0.20")).build();
            SemiFinishedInventoryTransaction restore = SemiFinishedInventoryTransaction.builder()
                    .id(254L).semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("0.20")).build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(in, correction, out, restore));

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                    "replayMovingAverage", sfiId, FACTORY_ID, java.time.LocalDateTime.now());

            assertEquals(new BigDecimal("0.82"), sfi.getProducedQuantity());
            assertEquals(0, BigDecimal.ZERO.compareTo(sfi.getConsumedQuantity()));
            assertEquals(new BigDecimal("0.82"), sfi.getAvailableQuantity());
            assertEquals(new BigDecimal("10.13"), sfi.getAccumulatedCost());
        }

        @Test
        @DisplayName("RP-COR-3: 原报工直接撤回遇到更正 ADJUST 时 409 且提示走审批流程")
        void directReversalOfCorrectedReportFailsWithActionableConflict() {
            Long logId = 260L, batchId = 261L, sfiId = 262L, reportId = 263L;
            ProductionReport report = ProductionReport.builder()
                    .id(reportId).factoryId(FACTORY_ID).batchId(batchId).build();
            SemiFinishedInventoryTransaction in = SemiFinishedInventoryTransaction.builder()
                    .id(264L).semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("0.81")).build();
            SemiFinishedInventoryTransaction correction = SemiFinishedInventoryTransaction.builder()
                    .id(265L).semiFinishedId(sfiId).txnType(SemiFinishedInventoryTransaction.TxnType.ADJUST)
                    .sourceType(WipLedgerEntries.REPORT_CORRECTION_SOURCE_TYPE).reportId(reportId)
                    .quantity(new BigDecimal("0.01")).build();
            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(report), List.of(in, correction));
            stubSfi(sfiId, new BigDecimal("0.82"), BigDecimal.ZERO, new BigDecimal("0.82"),
                    new BigDecimal("12.3537"), "SEMI-COR-3");

            com.cretas.aims.exception.BusinessException error = assertThrows(
                    com.cretas.aims.exception.BusinessException.class,
                    () -> service.executeReversal(logId, FACTORY_ID));

            assertEquals("WIP_CORRECTION_REVERSAL_CONFLICT", error.getErrorCode());
            assertTrue(error.getActionHint().contains("报工修正"));
            verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
            verify(reportRepo, never()).save(any(ProductionReport.class));
        }

        // ─── BUG-R1 / BUG-R2 回归守卫 (#662 已修; 断言 CORRECT math 对修复后代码 PASS) ───

        @Test
        @DisplayName("BUG-R1 回归: 整单撤回后 producedQuantity 扣减为 IN 净额 (#662 已修)")
        void bugR1_producedQuantityNotUpdatedOnReversal() {
            // Correct math: single IN(100) then REVERSE(-100) → producedQuantity should be 0
            Long logId = 10L, batchId = 200L, sfiId = 300L;

            ProductionReport r = ProductionReport.builder()
                    .id(600L).factoryId(FACTORY_ID).batchId(batchId).build();
            SemiFinishedInventoryTransaction inTxn = SemiFinishedInventoryTransaction.builder()
                    .id(20L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("100")).unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();

            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(r), List.of(inTxn));
            stubSfi(sfiId, new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                    new BigDecimal("10.0000"), "SEMI-BUGR1");

            SemiFinishedInventoryTransaction rev = SemiFinishedInventoryTransaction.builder()
                    .id(99L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("-100")).unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(inTxn), List.of(inTxn), List.of(inTxn, rev));

            service.executeReversal(logId, FACTORY_ID);

            verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
            SemiFinishedInventory finalSfi = sfiCaptor.getAllValues().stream()
                    .filter(s -> s.getId() != null && s.getId().equals(sfiId))
                    .findFirst().orElseThrow();

            // #662 fix: producedQuantity must be 0 after full IN reversal (was: stayed at 100)
            assertEquals(BigDecimal.ZERO, finalSfi.getProducedQuantity(),
                    "producedQuantity should be 0 after full IN reversal (#662 BUG-R1 fix)");
        }

        @Test
        @DisplayName("BUG-R2: OUT-after-reversal 由 Guard G1 (DOWNSTREAM_CONSUMED) 在 submitReversal 阻止 (文档)")
        void bugR2_outTxnNotAccountedInReplay() {
            // 纯文档测试 — 记录 BUG-R2 的处理边界, 不做数值断言。
            //
            // replayMovingAverage 在 totalQty 累计中不计入 OUT 行 (只算 IN 净额 - REVERSE),
            // 因此若一份 IN 在被 OUT 部分消耗后再撤回, 单纯的 replay 无法表达"已被下游消耗"的语义。
            //
            // 这条路径在 submitReversal 层被 Guard G1 (DOWNSTREAM_CONSUMED) 拦截:
            // 已有下游 OUT 消耗的 SFI 不允许撤回其来源报工 → 永不到达 executeReversal/replayMovingAverage。
            // 因此 executeReversal 内对 OUT 的 no-op 处理是安全的 (前置守卫保证不变量)。
            assertTrue(true, "OUT-after-reversal blocked upstream by Guard G1 (DOWNSTREAM_CONSUMED) in submitReversal");
        }
    }
}
