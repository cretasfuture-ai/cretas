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
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code REVERSE} 是<b>两种</b>语义共用的一个 {@code txn_type}，靠<b>符号</b>区分。
 * 回放净产出时必须按符号分流，⛔ 不许一律 {@code producedQty.add(q)}。
 *
 * <h2>为什么单开这一条（2026-08-18 取证）</h2>
 *
 * <p>PR#2870 把「产出不记 IN 流水」修了，同时<b>登记了一条它没修的缺陷</b>：
 * {@code replayMovingAverage} 把<b>所有</b> {@code REVERSE} 一律计进 {@code producedQty}，
 * 而正量 REVERSE 的语义是「还回领用」，不是「又产出了一批」。
 *
 * <p>全仓 5 个写 REVERSE 的地方，两种符号各占一半：
 *
 * <table border="1">
 *   <caption>REVERSE 行的两种语义</caption>
 *   <tr><th>符号</th><th>语义</th><th>写它的地方</th></tr>
 *   <tr><td><b>负量</b></td><td>退回产出（IN 的逆）</td>
 *       <td>{@code executeReversal} ②、{@code WipInventoryServiceImpl.reverseClerkOutput}、
 *           {@code reverseReportPosting} ①</td></tr>
 *   <tr><td><b>正量</b></td><td>还回领用（OUT 的逆）</td>
 *       <td>{@code WipInventoryServiceImpl.restoreClerkSemi}、{@code reverseReportPosting} ②</td></tr>
 * </table>
 *
 * <h2>生产实测（F006 {@code cretas_prod_db}，{@code public} schema，只读）</h2>
 *
 * <pre>
 * semi_finished_inventory_transactions 全表 8 行:  OUT 4 / REVERSE 4 / IN 0
 * REVERSE 4 行按符号拆:  负量 4 行 / <b>正量 0 行</b>
 * </pre>
 *
 * <p>⇒ 这条缺陷<b>在数据上还没引爆</b>。写进测试是因为它<b>已经可达</b>：
 * {@code reverseReportPosting} ② 写的正量 REVERSE 落在<b>上道</b>库存行上，
 * 而那条行自己是有 report 关联的 IN 流水的（PR#2870 起 FINISHED/legacy 路径也记 IN 了）
 * ⇒ 只要那批报工之后被整单撤回，回放就会读到它。
 * 「0 行」是<b>还没发生</b>，不是<b>不会发生</b> —— 而且「某功能零实例」这种结论有保质期。
 *
 * <h2>🔴 第二个后果比第一个阴（这是本条最该守住的东西）</h2>
 *
 * <p>虚增的净产出会被<b>回写</b>库存行 ⇒ {@code producedQuantity} 变得<b>大于</b> ΣIN
 * ⇒ <b>下一次</b>回放撞上 PR#2870 那道 {@code WIP_LEDGER_INCOMPLETE} 闸（它比的正是
 * 「账面 produced vs ΣIN」）⇒ 这条库存行被<b>永久锁死</b>，之后任何撤回都 409，
 * 而根因是我们自己算出来的数。所以每个场景都额外断言一条不变式：
 * <b>回放写回去的 produced 不许超过 ΣIN</b>。
 *
 * <h2>断言不是恒真式</h2>
 *
 * <p>每个场景里「正确答案」都<b>刻意不等于</b>库存行上的账面值 ——
 * 如果回放根本没被触发（rig 坏了 / 桩没接上），捕获到的就是账面值，断言会红。
 * 场景二额外充当<b>阳性对照</b>：它不含任何正量 REVERSE，修与不修结果相同，
 * 它红就说明是 rig 坏了而不是本次改动错了。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("REVERSE 按符号分流：还回领用不是重新产出")
class ReverseSignSemanticsContractTest {

    private static final String FACTORY = "F006";

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

    // ────────────────────────── 装置 ──────────────────────────

    private static SemiFinishedInventoryTransaction txn(String type, String qty, String unitCost) {
        return SemiFinishedInventoryTransaction.builder()
                .factoryId(FACTORY)
                .semiFinishedId(501L)
                .txnType(type)
                .quantity(new BigDecimal(qty))
                .unitCostAtTxn(unitCost == null ? null : new BigDecimal(unitCost))
                .build();
    }

    /**
     * 把 executeReversal 需要的桩全部装上，跑一次，返回回放<b>写回去</b>的那条库存行。
     *
     * @param onRecordProduced 库存行账面产出（⚠️ 每个场景都让它 ≠ 正确答案，防恒真）
     * @param ledger           回放读到的完整流水（{@code findBySemiFinishedIdOrderByCreatedAtAsc}）
     */
    private SemiFinishedInventory replayWith(BigDecimal onRecordProduced, BigDecimal onRecordConsumed,
                                             BigDecimal onRecordAvailable,
                                             List<SemiFinishedInventoryTransaction> ledger) {
        final Long sfiId = 501L;
        final Long logId = 77L;
        final Long batchId = 10761L;

        ReportReversalLog log = ReportReversalLog.builder()
                .id(logId).factoryId(FACTORY).batchId(batchId).planId(null)
                .status(ReportReversalLog.ReversalStatus.APPROVED)
                .submittedBy(1309L)
                .build();
        when(reversalLogRepo.findById(logId)).thenReturn(Optional.of(log));
        when(reversalLogRepo.findByIdAndFactoryIdForUpdate(logId, FACTORY)).thenReturn(Optional.of(log));
        when(reversalLogRepo.save(any(ReportReversalLog.class))).thenAnswer(i -> i.getArgument(0));

        ProductionReport report = ProductionReport.builder()
                .id(23841L).factoryId(FACTORY).batchId(batchId).build();
        when(reportRepo.findYieldReportsByBatch(FACTORY, batchId)).thenReturn(List.of(report));
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> i.getArgument(0));
        when(workProcessTaskRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY, batchId))
                .thenReturn(Collections.emptyList());

        // executeReversal ② 按 report 找 IN 行 → 写一条 REVERSE → 触发回放。
        // ⚠️ 这条新写的 REVERSE 不进下面的 ledger —— 本条测试隔离的是【回放对既有流水的算法】,
        //    与既有 ReplayMovingAverageTest 同一策略。
        SemiFinishedInventoryTransaction inForReport = txn(
                SemiFinishedInventoryTransaction.TxnType.IN, "1", "2.00");
        inForReport.setId(1L);
        when(txnRepo.findByFactoryIdAndReportId(FACTORY, report.getId())).thenReturn(List.of(inForReport));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(i -> {
            SemiFinishedInventoryTransaction t = i.getArgument(0);
            t.setId(9001L);
            return t;
        });

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(sfiId).factoryId(FACTORY).batchId(batchId)
                .intermediateBatchNo("SIGN-CASE")
                .producedQuantity(onRecordProduced)
                .consumedQuantity(onRecordConsumed)
                .availableQuantity(onRecordAvailable)
                .adjustmentQuantity(BigDecimal.ZERO)
                .unitCost(new BigDecimal("2.0000"))
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findByIdForUpdate(sfiId)).thenReturn(Optional.of(sfi));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId)).thenReturn(ledger);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                "replayMovingAverage", sfiId, FACTORY, java.time.LocalDateTime.now());

        verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
        return sfiCaptor.getValue();
    }

    /** 场景里 ΣIN 是多少（断言「回放的输出不许超过它」用）。 */
    private static BigDecimal sumIn(List<SemiFinishedInventoryTransaction> ledger) {
        return ledger.stream()
                .filter(t -> SemiFinishedInventoryTransaction.TxnType.IN.equals(t.getTxnType()))
                .map(SemiFinishedInventoryTransaction::getQuantity)
                .filter(q -> q.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ────────────────────────── 场景 ──────────────────────────

    @Test
    @DisplayName("正量 REVERSE（还回领用）⛔ 不许计进净产出 —— 它抵扣的是已领用量")
    void positiveReverseMustNotInflateProducedQuantity() {
        // 账面 100；流水: 入 100 / 退回产出 25 / 领走 30 / 还回领用 30(成本未知)
        List<SemiFinishedInventoryTransaction> ledger = List.of(
                txn(SemiFinishedInventoryTransaction.TxnType.IN, "100", "2.00"),
                txn(SemiFinishedInventoryTransaction.TxnType.REVERSE, "-25", "2.00"),
                txn(SemiFinishedInventoryTransaction.TxnType.OUT, "-30", "2.00"),
                txn(SemiFinishedInventoryTransaction.TxnType.REVERSE, "30", null));

        SemiFinishedInventory out = replayWith(
                new BigDecimal("100"), new BigDecimal("30"), new BigDecimal("70"), ledger);

        // 正确: 100 − 25 = 75。
        // ⛔ 一律相加会得 100 − 25 + 30 = 105；账面是 100 ⇒ 三个数互不相同, 断言不是恒真式。
        assertThat(out.getProducedQuantity())
                .as("净产出 = ΣIN + Σ(负量REVERSE)。正量 REVERSE 是【还回领用】, "
                        + "把它算成产出 = 同一批货被记了两次生产")
                .isEqualByComparingTo(new BigDecimal("75"));

        // 成本口径: 正量 REVERSE 不带入库成本, 也就不该因为它 unitCostAtTxn 为 null 就把整行成本毒化。
        assertThat(out.getUnitCost())
                .as("正量 REVERSE 不是一笔入库, 不参与单价加权 —— "
                        + "它成本未知不该把已经算得清的均价冲成 null")
                .isEqualByComparingTo(new BigDecimal("2.0000"));

        assertThat(out.getAvailableQuantity())
                .as("可领余量 = 净产出 75 − (领走 30 − 还回 30) = 75")
                .isEqualByComparingTo(new BigDecimal("75"));

        // 🔴 锁死不变式: 回放自己写回去的 produced 不许超过 ΣIN,
        //    否则下一次回放会撞上 WIP_LEDGER_INCOMPLETE, 这条库存行从此撤不了。
        assertThat(out.getProducedQuantity())
                .as("回放写回的 produced 一旦大于 ΣIN, 下一次回放必被 WIP_LEDGER_INCOMPLETE 拦死 "
                        + "—— 缺陷会把库存行永久锁死, 而根因是我们自己算出来的数")
                .isLessThanOrEqualTo(sumIn(ledger));
    }

    @Test
    @DisplayName("阳性对照 + 回归：只有负量 REVERSE 时，回放行为与修复前<b>完全一致</b>")
    void negativeReverseStillReducesProducedQuantity() {
        // 这一条【不含】正量 REVERSE ⇒ 本次改动对它零影响。
        // 它同时是阳性对照: 正确答案 60 ≠ 账面 100 ⇒ 它绿就证明回放确实被触发且写回了。
        List<SemiFinishedInventoryTransaction> ledger = List.of(
                txn(SemiFinishedInventoryTransaction.TxnType.IN, "100", "2.00"),
                txn(SemiFinishedInventoryTransaction.TxnType.REVERSE, "-40", "2.00"));

        SemiFinishedInventory out = replayWith(
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"), ledger);

        assertThat(out.getProducedQuantity())
                .as("阳性对照: 回放确实跑到了并写回了 (60 ≠ 账面 100)。"
                        + "这一条红 ⇒ 是 rig / 桩坏了, 不是符号分流改错了")
                .isEqualByComparingTo(new BigDecimal("60"));
        assertThat(out.getUnitCost())
                .as("负量 REVERSE 仍按原口径参与加权: (100×2 − 40×2) / 60 = 2.0000")
                .isEqualByComparingTo(new BigDecimal("2.0000"));
        assertThat(out.getAvailableQuantity()).isEqualByComparingTo(new BigDecimal("60"));
    }

    @Test
    @DisplayName("还回量超过领用量（脏数据）→ 已领用量 clamp 到 0，⛔ 不许倒挤出幻影可用量")
    void restoredConsumptionIsClampedSoAvailableNeverExceedsProduced() {
        // 领走 10 却还回 40 —— 只可能是脏数据。
        // 若不 clamp: consumedOut = 10 − 40 = −30 ⇒ available = 100 + 30 = 130 (凭空多出 30)。
        List<SemiFinishedInventoryTransaction> ledger = List.of(
                txn(SemiFinishedInventoryTransaction.TxnType.IN, "100", "2.00"),
                txn(SemiFinishedInventoryTransaction.TxnType.OUT, "-10", "2.00"),
                txn(SemiFinishedInventoryTransaction.TxnType.REVERSE, "40", "2.00"));

        SemiFinishedInventory out = replayWith(
                new BigDecimal("50"), new BigDecimal("10"), new BigDecimal("40"), ledger);

        assertThat(out.getProducedQuantity())
                .as("净产出只由 IN 与负量 REVERSE 决定 = 100 (账面 50 ⇒ 非恒真)")
                .isEqualByComparingTo(new BigDecimal("100"));
        assertThat(out.getAvailableQuantity())
                .as("可用量不许超过净产出 —— 还回比领用多是脏数据, clamp 到 0 而不是倒挤出 130 的幻影库存")
                .isEqualByComparingTo(new BigDecimal("100"));
        assertThat(out.getAvailableQuantity())
                .as("可用量 ≤ 净产出, 任何情况下都成立")
                .isLessThanOrEqualTo(out.getProducedQuantity());
        assertThat(out.getProducedQuantity()).isLessThanOrEqualTo(sumIn(ledger));
    }

    @Test
    @DisplayName("阴性对照：0 量 REVERSE 既不算产出也不算还回")
    void zeroQuantityReverseIsInert() {
        List<SemiFinishedInventoryTransaction> ledger = List.of(
                txn(SemiFinishedInventoryTransaction.TxnType.IN, "80", "2.00"),
                txn(SemiFinishedInventoryTransaction.TxnType.REVERSE, "0", null),
                txn(SemiFinishedInventoryTransaction.TxnType.OUT, "-20", "2.00"));

        SemiFinishedInventory out = replayWith(
                new BigDecimal("50"), new BigDecimal("20"), new BigDecimal("30"), ledger);

        assertThat(out.getProducedQuantity())
                .as("0 量 REVERSE 不动净产出 (80; 账面 50 ⇒ 非恒真)")
                .isEqualByComparingTo(new BigDecimal("80"));
        assertThat(out.getUnitCost())
                .as("0 量 REVERSE 的 unitCostAtTxn 为 null 也不该毒化成本")
                .isEqualByComparingTo(new BigDecimal("2.0000"));
        assertThat(out.getAvailableQuantity())
                .as("可领余量 = 80 − 20 = 60")
                .isEqualByComparingTo(new BigDecimal("60"));
    }
}
