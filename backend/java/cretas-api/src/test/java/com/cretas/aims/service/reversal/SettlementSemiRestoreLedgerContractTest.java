package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * <p>{@code ReportReversalServiceImpl.restoreSemiFinishedConsumption} 更新库存行并写入
 * 与结单消耗行一一对应的正量 REVERSE；正向
 * {@code ProductionPlanServiceImpl.postSemiFinishedConsumption} 写入对应 OUT。
 * 撤回前必须证明这两个事实都存在且数量一致，否则 fail closed。
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
    @Mock private ProductionPlanRepository productionPlanRepository;

    private ProductionSettlement settlement;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository",
                productionSettlementConsumptionRepository);
        ReflectionTestUtils.setField(service, "productionPlanRepository", productionPlanRepository);

        ReportReversalLog log = ReportReversalLog.builder()
                .factoryId(FACTORY_ID)
                .batchId(BATCH_ID)
                .planId(PLAN_ID)
                .submittedBy(SUBMITTED_BY)
                .status(ReportReversalLog.ReversalStatus.APPROVED)
                .build();
        log.setId(LOG_ID);
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(reversalLogRepo.findByIdAndFactoryIdForUpdate(LOG_ID, FACTORY_ID)).thenReturn(Optional.of(log));
        when(reversalLogRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        // 本条测试隔离的是【结单消耗还回】那一段: 该批次没有 YIELD 报工 ⇒ executeReversal ② 不参与,
        // 捕到的每一条流水都只可能来自 ③.6。(②.5 那道 WIP_NOT_LEDGERED 闸要求 reports 非空, 这里不触发。)
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID)).thenReturn(Collections.emptyList());
        when(workProcessTaskRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());
        when(fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());
        ProductionBatch batch = ProductionBatch.builder()
                .id(BATCH_ID).factoryId(FACTORY_ID).productionPlanId(PLAN_ID)
                .status(com.cretas.aims.entity.enums.ProductionBatchStatus.IN_PROGRESS)
                .build();
        when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdForUpdate(
                FACTORY_ID, PLAN_ID)).thenAnswer(invocation ->
                productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                        FACTORY_ID, PLAN_ID));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(i -> {
            SemiFinishedInventoryTransaction t = i.getArgument(0);
            t.setId(9100L);
            return t;
        });
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(SFI_ID)).thenReturn(List.of(
                SemiFinishedInventoryTransaction.builder()
                        .id(9001L).factoryId(FACTORY_ID).semiFinishedId(SFI_ID)
                        .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                        .sourceRef(WipLedgerEntries.SETTLEMENT_CONSUMPTION_REF_PREFIX
                                + SETTLEMENT_ID + ":1")
                        .quantity(new BigDecimal("-30.00"))
                        .build()));

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
        return semiLine(1L, qty);
    }

    private ProductionSettlementConsumption semiLine(Long id, BigDecimal qty) {
        ProductionSettlementConsumption line = new ProductionSettlementConsumption();
        line.setId(id);
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
                .contains(SETTLEMENT_ID)
                .contains(":1")
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
        when(reversalLogRepo.findByIdAndFactoryIdForUpdate(logB, FACTORY_ID)).thenReturn(Optional.of(logRecB));
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
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(replayTarget));
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

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                "replayMovingAverage", SFI_ID, FACTORY_ID, java.time.LocalDateTime.now());

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
    @DisplayName("结单缺少正向 OUT 配对时首个 mutation 前 fail-closed")
    void unpairedSettlementOutFailsBeforeMutation() {
        // 旧结单若缺少正向 OUT，撤回无法证明其来源，必须在任何库存/软删 mutation 前拒绝。
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "30.00", "70.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(semiLine(new BigDecimal("30.00"))));

        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(SFI_ID)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("WIP_LEDGER_INCOMPLETE"));
        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
        verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
    }

    @Test
    @DisplayName("同一 SFI 的多个结单消耗行各自精确配对 OUT 后允许撤回")
    void multipleSettlementLinesForSameSfiPairIndividually() {
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "30.00", "70.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        ProductionSettlementConsumption first = semiLine(1L, new BigDecimal("10.00"));
        ProductionSettlementConsumption second = semiLine(2L, new BigDecimal("20.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(first, second));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(SFI_ID)).thenReturn(List.of(
                SemiFinishedInventoryTransaction.builder().id(9001L).factoryId(FACTORY_ID)
                        .semiFinishedId(SFI_ID).txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                        .sourceRef(WipLedgerEntries.SETTLEMENT_CONSUMPTION_REF_PREFIX + SETTLEMENT_ID + ":1")
                        .quantity(new BigDecimal("-10.00")).build(),
                SemiFinishedInventoryTransaction.builder().id(9002L).factoryId(FACTORY_ID)
                        .semiFinishedId(SFI_ID).txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                        .sourceRef(WipLedgerEntries.SETTLEMENT_CONSUMPTION_REF_PREFIX + SETTLEMENT_ID + ":2")
                        .quantity(new BigDecimal("-20.00")).build()));

        service.executeReversal(LOG_ID, FACTORY_ID);

        assertThat(sfi.getConsumedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(sfi.getAvailableQuantity()).isEqualByComparingTo(new BigDecimal("100.00"));
        verify(txnRepo, org.mockito.Mockito.times(2)).save(any(SemiFinishedInventoryTransaction.class));
        assertThat(settlement.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("其他结单的合法 OUT 不会污染当前结单的一一配对证明")
    void unrelatedSettlementOutDoesNotInvalidateCurrentPair() {
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "30.00", "70.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(semiLine(new BigDecimal("30.00"))));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(SFI_ID)).thenReturn(List.of(
                SemiFinishedInventoryTransaction.builder().id(9001L).factoryId(FACTORY_ID)
                        .semiFinishedId(SFI_ID).txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                        .sourceRef(WipLedgerEntries.SETTLEMENT_CONSUMPTION_REF_PREFIX + SETTLEMENT_ID + ":1")
                        .quantity(new BigDecimal("-30.00")).build(),
                SemiFinishedInventoryTransaction.builder().id(9002L).factoryId(FACTORY_ID)
                        .semiFinishedId(SFI_ID).txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                        .sourceRef(WipLedgerEntries.SETTLEMENT_CONSUMPTION_REF_PREFIX + "OLDER-SETTLEMENT:9")
                        .quantity(new BigDecimal("-12.00")).build()));

        service.executeReversal(LOG_ID, FACTORY_ID);

        verify(txnRepo).save(any(SemiFinishedInventoryTransaction.class));
        assertThat(settlement.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("当前结单重复 OUT 配对时首个 mutation 前 fail-closed")
    void duplicateCurrentSettlementOutFailsBeforeMutation() {
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "30.00", "70.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(semiLine(new BigDecimal("30.00"))));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(SFI_ID)).thenReturn(List.of(
                SemiFinishedInventoryTransaction.builder().id(9001L).factoryId(FACTORY_ID)
                        .semiFinishedId(SFI_ID).txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                        .sourceRef(WipLedgerEntries.SETTLEMENT_CONSUMPTION_REF_PREFIX + SETTLEMENT_ID + ":1")
                        .quantity(new BigDecimal("-30.00")).build(),
                SemiFinishedInventoryTransaction.builder().id(9002L).factoryId(FACTORY_ID)
                        .semiFinishedId(SFI_ID).txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                        .sourceRef(WipLedgerEntries.SETTLEMENT_CONSUMPTION_REF_PREFIX + SETTLEMENT_ID + ":1")
                        .quantity(new BigDecimal("-30.00")).build()));

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("WIP_LEDGER_INVALID"));
        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
        verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
        assertThat(settlement.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("qty > consumed 无法一一证明时 fail-closed")
    void excessiveSettlementQuantityFailsClosed() {
        // 消耗行说领了 30, 而库存行上只剩 10 的已领量 (例: 另一条路已经还过一部分)。
        // restoreSemiFinishedConsumption 把 consumed clamp 到 0 ⇒ 真正反冲掉的只有 10。
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "10.00", "90.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(semiLine(new BigDecimal("30.00"))));

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("WIP_LEDGER_INCOMPLETE"));
        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
        verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
    }

    @Test
    @DisplayName("跨租户结单 SFI 在首个 mutation 前 fail-closed")
    void crossFactorySettlementSfiFailsBeforeMutation() {
        SemiFinishedInventory foreign = sfiOnRecord("100.00", "30.00", "70.00");
        foreign.setFactoryId("OTHER_FACTORY");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(foreign));
        ProductionSettlementConsumption line = semiLine(new BigDecimal("30.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(line));

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("WIP_SCOPE_INVALID"));

        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
        verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
        verify(reportRepo, never()).save(any(ProductionReport.class));
        verify(productionSettlementConsumptionRepository, never()).save(any());
        verify(productionSettlementRepository, never()).save(any());
        assertThat(line.getDeletedAt()).isNull();
        assertThat(settlement.getDeletedAt()).isNull();
        verify(reversalLogRepo, never()).save(any(ReportReversalLog.class));
    }

    @Test
    @DisplayName("结单 SFI 正负盘点调整都在首个 mutation 前 fail-closed")
    void settlementSfiAdjustmentFailsBeforeMutation() {
        for (String adjustment : List.of("1.00", "-1.00")) {
            Mockito.clearInvocations(wipRepo, txnRepo, reportRepo,
                    productionSettlementConsumptionRepository, productionSettlementRepository,
                    reversalLogRepo);
            SemiFinishedInventory sfi = sfiOnRecord("100.00", "30.00", "70.00");
            sfi.setAdjustmentQuantity(new BigDecimal(adjustment));
            when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
            ProductionSettlementConsumption line = semiLine(new BigDecimal("30.00"));
            when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                    .thenReturn(List.of(line));

            assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                            .isEqualTo("WIP_ADJUSTMENT_PRESENT"));

            verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
            verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
            verify(reportRepo, never()).save(any(ProductionReport.class));
            verify(productionSettlementConsumptionRepository, never()).save(any());
            verify(productionSettlementRepository, never()).save(any());
            verify(reversalLogRepo, never()).save(any(ReportReversalLog.class));
            assertThat(line.getDeletedAt()).isNull();
        }
    }

    @Test
    @DisplayName("结单消耗无已领量时 fail-closed，不软删")
    void nothingActuallyRestoredFailsClosed() {
        // 库存行上 consumed 已经是 0 ⇒ 无可反冲。
        SemiFinishedInventory sfi = sfiOnRecord("100.00", "0.00", "100.00");
        when(wipRepo.findByIdForUpdate(SFI_ID)).thenReturn(Optional.of(sfi));
        ProductionSettlementConsumption line = semiLine(new BigDecimal("30.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(line));

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("WIP_LEDGER_INCOMPLETE"));
        verify(txnRepo, never()).save(any(SemiFinishedInventoryTransaction.class));
        assertThat(line.getDeletedAt()).isNull();
        assertThat(settlement.getDeletedAt()).isNull();
        verify(reversalLogRepo, never()).save(any(ReportReversalLog.class));
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
