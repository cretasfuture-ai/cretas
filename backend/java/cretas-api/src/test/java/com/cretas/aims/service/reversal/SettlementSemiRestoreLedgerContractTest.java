package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import com.cretas.aims.service.wip.WipLedgerEntries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 整单撤回把<b>结单领走的半成品</b>还回去时，那笔补正量必须在流水账上留一条
 * <b>正量 REVERSE</b>（「还回领用」= OUT 的逆）。
 *
 * <h2>为什么单开这一条（2026-08-18 取证）</h2>
 *
 * <p>{@code ReportReversalServiceImpl.restoreSemiFinishedConsumption} 直接改库存行
 * （{@code consumedQuantity -= qty}、{@code availableQuantity += qty}）却<b>一行流水都不写</b>。
 * 普查全仓改 {@code SemiFinishedInventory} 余额的 15 个点，只有<b>两个</b>是哑的：
 * 本方法，和它的正向 {@code ProductionPlanServiceImpl.postSemiFinishedConsumption}。
 * 其余 13 个都配了 IN / OUT / REVERSE / ADJUST 行。
 * ⇒ 余额动了而账本解释不了它，正是 {@code WipLedgerEntries.productionRow} javadoc 里
 * 那个 phantom WIP 的形状：<b>只有余额，没有事实</b>。
 *
 * <h2>生产实测（F006 {@code cretas_prod_db}，{@code public} schema，只读，2026-08-18）</h2>
 *
 * <pre>
 * production_settlement_consumptions 全表 4 行:  RAW_MATERIAL 4 / <b>SEMI_FINISHED 0</b>
 *                                                 semi_finished_inventory_id 非空: <b>0</b>
 * semi_finished_inventory_transactions 全表 8 行: OUT 4 / REVERSE 4 / IN 0
 *                                                 REVERSE 按符号: 负量 4 / <b>正量 0</b>
 * report_reversal_logs: DONE 14 行  (阳性对照: 撤回确实跑过)
 * </pre>
 *
 * <p>⇒ 这条路<b>在数据上一次都没走过</b>（SEMI_FINISHED 消耗行 0 条 ⇒
 * {@code restoreSemiFinishedConsumption} 在 prod 从未执行）。上面三个读数各自都有阳性对照
 * （同一条查询读得出 RAW_MATERIAL 的 4 行、读得出 SIT 的 8 行、读得出 14 条撤回），
 * ⇒ 「0」是<b>真的没有</b>，不是仪器坏了。
 *
 * <p>写进测试是因为它<b>可达且已接线</b>：结单请求带
 * {@code semiFinishedConsumptions} 就会生成 SEMI_FINISHED 消耗行
 * （{@code appendConsumptionLines} → {@code postSemiFinishedConsumption}，
 * 后者对 {@code semiFinishedInventoryId} 为空直接 400），撤回读的就是这些行。
 * ⚠️ 而且「某功能零实例」这种结论<b>有保质期</b> —— 本仓三天前刚被真实使用推翻过一次。
 *
 * <h2>桩的形状对得上真实上游吗</h2>
 *
 * <p>逐格核对过：{@code sourceType='SEMI_FINISHED'} + {@code semiFinishedInventoryId != null}
 * （{@code resolveAvailableBefore} 为空即 400）+ {@code quantity > 0} 且 {@code ≤ available}
 * （{@code ensureQuantityWithinAvailable}）+ scale-2（列定义 {@code precision=12, scale=2}）。
 * 桩一律用 {@code "30.00"} 这种两位小数，不用 {@code "30"}。
 *
 * <h2>断言不是恒真式</h2>
 *
 * <p>每个场景里「正确答案」都<b>刻意不等于</b>库存行上的任何一个账面值；
 * 数「写了几条 REVERSE」的场景都配了一条<b>必然发生</b>的读数
 * （settlement / 消耗行被软删、撤回置 DONE），
 * 否则「没写流水」和「整个方法根本没跑到」在计数上长得一模一样。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("结单半成品还回：补正量必须记一条正量 REVERSE 流水")
class SettlementSemiRestoreLedgerContractTest {

    private static final String FACTORY_ID = "F006";
    private static final Long BATCH_ID = 10761L;
    private static final Long LOG_ID = 91L;
    private static final Long SUBMITTED_BY = 1309L;
    private static final String PLAN_ID = "PP-SEMI-RESTORE";
    private static final String SETTLEMENT_ID = "ST-SEMI-RESTORE";
    private static final Long SFI_ID = 777L;

    @InjectMocks private ReportReversalServiceImpl service;

    @Mock private ReportReversalLogRepository reversalLogRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private FinishedGoodsBatchRepository fgbRepo;
    @Mock private UserRepository userRepository;
    @Mock private WorkProcessTaskRepository workProcessTaskRepository;
    @Mock private com.cretas.aims.service.NotificationService notificationService;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;

    private ProductionSettlement settlement;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository",
                productionSettlementConsumptionRepository);

        ReportReversalLog log = ReportReversalLog.builder()
                .factoryId(FACTORY_ID)
                .batchId(BATCH_ID)
                .planId(PLAN_ID)
                .submittedBy(SUBMITTED_BY)
                .status(ReportReversalLog.ReversalStatus.APPROVED)
                .build();
        log.setId(LOG_ID);
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(reversalLogRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        // 本条测试隔离的是【结单消耗还回】那一段: 该批次没有 YIELD 报工 ⇒ executeReversal ② 不参与,
        // 捕到的每一条流水都只可能来自 ③.6。(②.5 那道 WIP_NOT_LEDGERED 闸要求 reports 非空, 这里不触发。)
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID)).thenReturn(Collections.emptyList());
        when(workProcessTaskRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());
        when(fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(i -> {
            SemiFinishedInventoryTransaction t = i.getArgument(0);
            t.setId(9100L);
            return t;
        });

        settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));
    }

    // ────────────────────────── 装置 ──────────────────────────

    /** 真实上游形状: SEMI_FINISHED + 非空 sfiId + 正的 scale-2 数量。 */
    private ProductionSettlementConsumption semiLine(BigDecimal qty) {
        ProductionSettlementConsumption line = new ProductionSettlementConsumption();
        line.setId(1L);
        line.setSettlementId(SETTLEMENT_ID);
        line.setFactoryId(FACTORY_ID);
        line.setProductionPlanId(PLAN_ID);
        line.setSourceType("SEMI_FINISHED");
        line.setSemiFinishedInventoryId(SFI_ID);
        line.setQuantity(qty);
        line.setUnit("kg");
        return line;
    }

    private SemiFinishedInventory sfiOnRecord(String produced, String consumed, String available) {
        return SemiFinishedInventory.builder()
                .id(SFI_ID)
                .factoryId(FACTORY_ID)
                .batchId(BATCH_ID)
                .intermediateBatchNo("SEMI-RESTORE-CASE")
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .availableQuantity(new BigDecimal(available))
                .adjustmentQuantity(BigDecimal.ZERO)
                .unitCost(new BigDecimal("4.6667"))
                .status(SemiFinishedInventory.Status.DEPLETED)
                .build();
    }

    private List<SemiFinishedInventoryTransaction> savedTxns() {
        ArgumentCaptor<SemiFinishedInventoryTransaction> captor =
                ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(txnRepo, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    // ────────────────────────── 场景 ──────────────────────────

    @Test
    @DisplayName("结单领走 30 被撤回 → 写一条【正量】REVERSE/REVERSAL 流水, 余额快照是改完之后的")
    void restoredSettlementConsumptionWritesPositiveReverseLedgerRow() {
        // 结单领走 30 ⇒ 库存行 produced 100 / consumed 30 / available 70。
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "30.00", "70.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        ProductionSettlementConsumption line = semiLine(new BigDecimal("30.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(line));

        service.executeReversal(LOG_ID, FACTORY_ID);

        List<SemiFinishedInventoryTransaction> txns = savedTxns();
        assertThat(txns)
                .as("补正量必须留一条流水 —— 没有它, 余额从 70 变回 100 在账本上无法解释 (phantom WIP)")
                .hasSize(1);

        SemiFinishedInventoryTransaction row = txns.get(0);
        assertThat(row.getTxnType()).isEqualTo(SemiFinishedInventoryTransaction.TxnType.REVERSE);
        assertThat(row.getSourceType()).isEqualTo(SemiFinishedInventoryTransaction.SourceType.REVERSAL);
        assertThat(row.getSemiFinishedId()).isEqualTo(SFI_ID);
        assertThat(row.getFactoryId()).isEqualTo(FACTORY_ID);

        assertThat(row.getQuantity().signum())
                .as("🔴 符号是语义: REVERSE 正量 = 还回领用 (OUT 的逆, 抵扣 consumedOut); "
                        + "负量 = 退回产出 (IN 的逆, 冲减 producedQuantity)。"
                        + "这里做的是 consumedQuantity -= 30, 是【还回领用】⇒ 必须为正。"
                        + "写成负量会被回放当成减产, 把一次归还记成一次减产")
                .isEqualTo(1);
        assertThat(row.getQuantity())
                .as("还回量 = 实际反冲掉的消耗量 30 (账面 produced 100 / available 70 / consumed 30 "
                        + "四个数互不相同 ⇒ 断言不是恒真式)")
                .isEqualByComparingTo(new BigDecimal("30.00"));

        assertThat(row.getBalanceAfter())
                .as("余额快照口径 = 改完之后的 availableQuantity (70 + 30 = 100), "
                        + "与 reversalRow / clerkProductionRow 同口径; 读扣减【前】会记出 70 这个假数")
                .isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(row.getSourceRef())
                .as("sourceRef 必须带前缀 —— G1 守卫 existsDownstreamConsumed 按裸 batchId 精确相等判"
                        + "「被下道领用了没有」, 裸批次号会凭空把撤回拦成 409")
                .startsWith(WipLedgerEntries.SETTLEMENT_RESTORE_REF_PREFIX)
                .contains(String.valueOf(LOG_ID))
                .isNotEqualTo(String.valueOf(BATCH_ID));

        assertThat(row.getReportId())
                .as("结单消耗行不挂在任何一条报工上 —— 硬塞一个报工只会留一个撒谎的 report_id")
                .isNull();
        assertThat(row.getOperatorId())
                .as("记账人: 审批人优先, 其次提交人 (本例未审批 ⇒ 提交人)")
                .isEqualTo(SUBMITTED_BY);

        // 库存行确实被还回去了 (证明流水描述的是真的发生了的事, 不是一条孤立的账)
        assertThat(sfi.getConsumedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(sfi.getAvailableQuantity()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("接上没有：我们写出来的这一行, 被【真实回放】读成「还回领用」而不是「又产出一批」")
    void theRowWeWriteIsReadBackByReplayAsRestoredConsumption() {
        // Phase A — 真实入口跑一次结单半成品还回, 捕获它写出来的那条流水 (不是自己 new 一条)。
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "30.00", "70.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(semiLine(new BigDecimal("30.00"))));

        service.executeReversal(LOG_ID, FACTORY_ID);

        List<SemiFinishedInventoryTransaction> phaseA = savedTxns();
        assertThat(phaseA).as("Phase A 阳性对照: 没写出行, 后面喂给回放的就是空气").hasSize(1);
        SemiFinishedInventoryTransaction restoreRow = phaseA.get(0);
        Mockito.clearInvocations(txnRepo, wipRepo);

        // Phase B — 把 Phase A 那一行【原样】放进流水账, 走【同一个真实入口】触发回放。
        final Long logB = 92L;
        final Long batchB = 10762L;
        final String planB = "PP-REPLAY";
        ReportReversalLog logRecB = ReportReversalLog.builder()
                .factoryId(FACTORY_ID).batchId(batchB).planId(planB)
                .submittedBy(SUBMITTED_BY)
                .status(ReportReversalLog.ReversalStatus.APPROVED).build();
        logRecB.setId(logB);
        when(reversalLogRepo.findById(logB)).thenReturn(Optional.of(logRecB));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, planB)).thenReturn(Optional.empty());   // Phase B 不再走 ③.6
        when(fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, planB))
                .thenReturn(Collections.emptyList());
        when(workProcessTaskRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchB))
                .thenReturn(Collections.emptyList());

        ProductionReport report = ProductionReport.builder()
                .id(23841L).factoryId(FACTORY_ID).batchId(batchB).build();
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, batchB)).thenReturn(List.of(report));
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> i.getArgument(0));
        SemiFinishedInventoryTransaction trigger = SemiFinishedInventoryTransaction.builder()
                .factoryId(FACTORY_ID).semiFinishedId(SFI_ID)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("1.00")).unitCostAtTxn(new BigDecimal("2.00")).build();
        trigger.setId(1L);
        when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, report.getId())).thenReturn(List.of(trigger));

        // 账面刻意 ≠ 正确答案: 回放没被触发的话捕获到的就是这两个数, 断言会红。
        SemiFinishedInventory replayTarget = sfiOnRecord("55.00", "0.00", "25.00");
        when(wipRepo.findById(SFI_ID)).thenReturn(Optional.of(replayTarget));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(SFI_ID)).thenReturn(List.of(
                SemiFinishedInventoryTransaction.builder()
                        .factoryId(FACTORY_ID).semiFinishedId(SFI_ID)
                        .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                        .quantity(new BigDecimal("100.00")).unitCostAtTxn(new BigDecimal("2.00")).build(),
                SemiFinishedInventoryTransaction.builder()
                        .factoryId(FACTORY_ID).semiFinishedId(SFI_ID)
                        .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                        .quantity(new BigDecimal("-30.00")).unitCostAtTxn(new BigDecimal("2.00")).build(),
                restoreRow));

        service.executeReversal(logB, FACTORY_ID);

        ArgumentCaptor<SemiFinishedInventory> sfiCaptor = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
        SemiFinishedInventory replayed = sfiCaptor.getValue();

        assertThat(replayed.getProducedQuantity())
                .as("我们写的那一行是【还回领用】: 净产出只能是 ΣIN 100。"
                        + "若被当成产出一律相加会得 130, 账面是 55 ⇒ 三个数互不相同")
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(replayed.getAvailableQuantity())
                .as("可领余量 = 净产出 100 − (领走 30 − 还回 30) = 100 (账面 25 ⇒ 非恒真)")
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(replayed.getProducedQuantity())
                .as("🔴 回放写回的 produced 一旦大于 ΣIN, 下一次回放必被 WIP_LEDGER_INCOMPLETE 拦死, "
                        + "这条库存行从此撤不了")
                .isLessThanOrEqualTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("正向 OUT 还没补上时（今天的 prod 就是这样）：我们这一行 ⛔ 不许把库存行算坏 / 锁死")
    void unpairedRestoreRowIsNumericallyHarmlessUntilTheForwardOutExists() {
        // 现状: 正向 postSemiFinishedConsumption 不写 OUT ⇒ 这条库存行的流水账里 Σ|OUT| = 0,
        // 而我们写的正量 REVERSE 会去抵扣一个本来就是 0 的 consumedOut。
        // 这一条把「无害」从推断变成读数 —— 无害的话它绿, 有害的话它红。
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "30.00", "70.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(semiLine(new BigDecimal("30.00"))));

        service.executeReversal(LOG_ID, FACTORY_ID);
        List<SemiFinishedInventoryTransaction> phaseA = savedTxns();
        assertThat(phaseA).as("阳性对照: 没写出行, 下面喂给回放的就是空气").hasSize(1);
        SemiFinishedInventoryTransaction restoreRow = phaseA.get(0);
        Mockito.clearInvocations(txnRepo, wipRepo);

        final Long logB = 93L;
        final Long batchB = 10763L;
        final String planB = "PP-UNPAIRED";
        ReportReversalLog logRecB = ReportReversalLog.builder()
                .factoryId(FACTORY_ID).batchId(batchB).planId(planB)
                .submittedBy(SUBMITTED_BY)
                .status(ReportReversalLog.ReversalStatus.APPROVED).build();
        logRecB.setId(logB);
        when(reversalLogRepo.findById(logB)).thenReturn(Optional.of(logRecB));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, planB)).thenReturn(Optional.empty());
        when(fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, planB))
                .thenReturn(Collections.emptyList());
        when(workProcessTaskRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchB))
                .thenReturn(Collections.emptyList());
        ProductionReport report = ProductionReport.builder()
                .id(23842L).factoryId(FACTORY_ID).batchId(batchB).build();
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, batchB)).thenReturn(List.of(report));
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> i.getArgument(0));
        SemiFinishedInventoryTransaction trigger = SemiFinishedInventoryTransaction.builder()
                .factoryId(FACTORY_ID).semiFinishedId(SFI_ID)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("1.00")).unitCostAtTxn(new BigDecimal("2.00")).build();
        trigger.setId(1L);
        when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, report.getId())).thenReturn(List.of(trigger));

        SemiFinishedInventory replayTarget = sfiOnRecord("55.00", "0.00", "25.00");
        when(wipRepo.findById(SFI_ID)).thenReturn(Optional.of(replayTarget));
        // ⚠️ 账本里【一条 OUT 都没有】—— 这正是今天 prod 上那条正向缺口的长相。
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(SFI_ID)).thenReturn(List.of(
                SemiFinishedInventoryTransaction.builder()
                        .factoryId(FACTORY_ID).semiFinishedId(SFI_ID)
                        .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                        .quantity(new BigDecimal("100.00")).unitCostAtTxn(new BigDecimal("2.00")).build(),
                restoreRow));

        service.executeReversal(logB, FACTORY_ID);

        ArgumentCaptor<SemiFinishedInventory> sfiCaptor = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
        SemiFinishedInventory replayed = sfiCaptor.getValue();

        assertThat(replayed.getProducedQuantity())
                .as("我们这一行是【还回领用】, 不进净产出 ⇒ 净产出仍是 ΣIN 100 (账面 55 ⇒ 非恒真)")
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(replayed.getAvailableQuantity())
                .as("Σ|OUT| = 0 时还回量被 clamp 到 0 ⇒ 可用量 = 100, "
                        + "⛔ 不许倒挤成 130 那种凭空多出来的幻影库存 (账面 25 ⇒ 非恒真)")
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(replayed.getAvailableQuantity())
                .as("可用量任何时候都不许超过净产出")
                .isLessThanOrEqualTo(replayed.getProducedQuantity());
        assertThat(replayed.getProducedQuantity())
                .as("🔴 最要紧的一条: 回写的 produced 一旦大于 ΣIN, 下一次回放必被 "
                        + "WIP_LEDGER_INCOMPLETE 拦死, 这条库存行从此撤不了 —— "
                        + "我们这一行绝不许把它推过 ΣIN")
                .isLessThanOrEqualTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("脏数据 clamp：流水记【实际反冲量】, ⛔ 不记消耗行上写的请求量")
    void clampedRestoreRecordsActualReversalNotRequestedQuantity() {
        // 消耗行说领了 30, 而库存行上只剩 10 的已领量 (例: 另一条路已经还过一部分)。
        // restoreSemiFinishedConsumption 把 consumed clamp 到 0 ⇒ 真正反冲掉的只有 10。
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "10.00", "90.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(semiLine(new BigDecimal("30.00"))));

        service.executeReversal(LOG_ID, FACTORY_ID);

        List<SemiFinishedInventoryTransaction> txns = savedTxns();
        assertThat(txns).hasSize(1);
        assertThat(txns.get(0).getQuantity())
                .as("clamp 之后真正从 consumedQuantity 反冲掉的是 10, 不是消耗行写的 30 —— "
                        + "记请求量 = 账本自己在说谎 (与 clerkConsumptionRow 同一条纪律)")
                .isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(sfi.getConsumedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("阴性对照：一笔都没反冲掉 → ⛔ 不凭空造流水 (配一条【必然发生】的读数证明它真的跑到了)")
    void nothingActuallyRestoredWritesNoLedgerRow() {
        // 库存行上 consumed 已经是 0 ⇒ 无可反冲。
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "0.00", "100.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        ProductionSettlementConsumption line = semiLine(new BigDecimal("30.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(line));

        service.executeReversal(LOG_ID, FACTORY_ID);

        verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));

        // 🔴 硬约束: 「X 没发生」必须配一个「Y 一定会发生」的读数。
        //    这三个是设计上的必然 —— 全部恢复成功后才软删, 最后置 DONE。
        //    它们没动就说明根本没跑到写流水那一步, 那么上面那条 never() 毫无意义。
        assertThat(line.getDeletedAt()).as("必发生 1: 恢复成功 → 消耗行被软删").isNotNull();
        assertThat(settlement.getDeletedAt()).as("必发生 2: 恢复成功 → settlement 被软删").isNotNull();
        ArgumentCaptor<ReportReversalLog> logCaptor = ArgumentCaptor.forClass(ReportReversalLog.class);
        verify(reversalLogRepo, atLeastOnce()).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus())
                .as("必发生 3: 撤回跑完置 DONE")
                .isEqualTo(ReportReversalLog.ReversalStatus.DONE);
    }

    @Test
    @DisplayName("阴性对照：RAW_MATERIAL 消耗行 ⛔ 不许写半成品流水 (它动的是原料批次)")
    void materialLineWritesNoWipLedgerRow() {
        ProductionSettlementConsumption line = new ProductionSettlementConsumption();
        line.setId(2L);
        line.setSettlementId(SETTLEMENT_ID);
        line.setFactoryId(FACTORY_ID);
        line.setProductionPlanId(PLAN_ID);
        line.setSourceType("RAW_MATERIAL");
        line.setMaterialBatchId("MB-1");
        line.setQuantity(new BigDecimal("20.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(line));

        MaterialBatch batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId(FACTORY_ID);
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        batch.setUsedQuantity(new BigDecimal("20.00"));
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.USED_UP);
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-1", FACTORY_ID))
                .thenReturn(Optional.of(batch));

        service.executeReversal(LOG_ID, FACTORY_ID);

        verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
        // 必发生: 原料确实被回扣了 ⇒ 上面那条 never() 是「没写」而不是「没跑」。
        assertThat(batch.getUsedQuantity())
                .as("必发生: RAW_MATERIAL 行回扣 usedQuantity 20 → 0")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }
}
