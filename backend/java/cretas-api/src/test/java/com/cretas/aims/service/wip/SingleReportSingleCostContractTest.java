package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一份报工 = 一份成本 = 一次成本事件 —— 即使 SEMI 与 FINISHED 两个分支<b>同时</b>跑。
 *
 * <h2>缺陷 (2026-08-28 对抗性审计 Critical 2)</h2>
 *
 * <p>{@code postApprovedOutput} 的两个分支判据在 {@code BOTH} 上<b>同时为真</b>:
 * <pre>
 *   isSemi     = "SEMI".equals(kind)     || "BOTH".equals(kind)
 *   isFinished = kind == null || "FINISHED".equals(kind) || "BOTH".equals(kind)
 * </pre>
 * 而 RN 报工屏在<b>有任何半成品产出</b>时发的就是 {@code outputKind='BOTH'}
 * ({@code YieldStepReportScreen.tsx:1590}), 同时照发 {@code outputQuantity} (:1608)。
 * 于是同一份 ¥100 的报工:
 * <ul>
 *   <li>SEMI 分支把 ¥100 按量分摊给 {@code semiOutputs} 的 N 条 (C-1 已修, 之和 = ¥100);</li>
 *   <li>FINISHED 分支<b>又</b>拿 {@code reportTotalCost} 算一次<b>全额</b> ¥100 记进
 *       {@code wipNo} 那一行。</li>
 * </ul>
 * ⇒ 一份 ¥100 的报工总共入账 <b>¥200</b>; 且回料那条的分母只有它自己 (50), 单价 ¥2.00/kg
 * 是良品的 19 倍 —— 正是 C-1 修掉的那个形状, 在 SEMI↔FINISHED 之间原样保留。
 *
 * <p>第三重后果与 C-1 同源: 两个分支<b>各发一次</b> {@code ProductionCostUpdatedEvent}
 * (一个带分摊单价、一个带 {@code producedWip.getUnitCost()}), 而消费方
 * {@code OrderCostBackfillListener} 是 {@code @Async} + 「{@code costUnitPrice == null} 才写」
 * ⇒ 哪条先到哪条赢, 销售订单行成本单价非确定性。
 *
 * <h2>口径 (修法)</h2>
 *
 * <p>两个分支同时跑<b>不是</b>缺陷本身 —— {@code BOTH} 的契约 (见 {@code ProductionReport.outputKind}
 * javadoc) 就是「双产出」: {@code outputQuantity} 与 {@code semiOutputs} 是<b>两个不同的量</b>,
 * 各自入各自的行。缺陷是<b>成本被两个分支各claim一次全额</b>。
 *
 * <p>⇒ 分摊的分母必须是<b>这份报工实际入账的全部量</b> (N 条 semi + FINISHED 那条),
 * 各行拿到同一个单价, N+1 份成本之和恰好等于一份报工的成本; 事件在两个分支都跑完之后发<b>一次</b>。
 *
 * <p>⛔ 单产出路径 ({@code semiOutputs} 为空) 逐字节不变 —— 由
 * {@link #finishedOnly_byteIdentical()} 钉住。
 */
@DisplayName("Critical 2: outputKind=BOTH 时一份报工只记一份成本、只发一次事件")
class SingleReportSingleCostContractTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 8001L;
    private static final Long TASK_ID = 7001L;
    /** 良品那条不填半成品栏 —— 操作员真实行为: 「产出量」填良品, 只在回料栏填不良品数。 */
    private static final String REWORK_CODE = "PTSEMI-LSM-0003";

    private SemiFinishedInventoryRepository wipRepo;
    private SemiFinishedInventoryTransactionRepository txnRepo;
    private ProductionReportRepository reportRepo;
    private ApplicationEventPublisher eventPublisher;
    private WipInventoryServiceImpl svc;

    private final Map<String, SemiFinishedInventory> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        wipRepo = mock(SemiFinishedInventoryRepository.class);
        txnRepo = mock(SemiFinishedInventoryTransactionRepository.class);
        reportRepo = mock(ProductionReportRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        svc = new WipInventoryServiceImpl(
                wipRepo,
                txnRepo,
                reportRepo,
                mock(BatchLineageEdgeRepository.class),
                mock(WorkProcessTaskRepository.class),
                mock(WorkProcessRepository.class),
                mock(ProductTypeRepository.class),
                mock(ProductionWorkflowInstanceRepository.class),
                mock(WorkflowTaskPortRepository.class),
                mock(ProductFamilyResolver.class),
                eventPublisher);

        // ensure-row-then-lock: 第一次 findForUpdate 空 → saveAndFlush 建占位行 → 再 findForUpdate 命中。
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(FACTORY), any()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(1, String.class))));
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            s.setId(1000L + rows.size());
            rows.put(s.getIntermediateBatchNo(), s);
            return s;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.findByFactoryIdAndReportId(eq(FACTORY), anyLong())).thenReturn(Collections.emptyList());
    }

    private WorkProcessTask task() {
        return WorkProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY)
                .productionBatchId(BATCH_ID)
                .productTypeId("PT-BURGER")
                .processOrder(5)
                .plannedUnit("kg")
                .build();
    }

    /**
     * OUTPUT 阶段的成本 rollup: 本 task 自身报工的人工 ¥40 + 同批 INPUT 报工的料 ¥60 = ¥100。
     * (逐道/两点两种模式都走这条 —— 见 {@code outputRollupWithBatchInputMaterial})
     */
    private void stubOutputRollupTotal100() {
        when(reportRepo.findYieldReportsByTask(FACTORY, TASK_ID)).thenReturn(List.of(
                ProductionReport.builder().id(5000L).laborCost(new BigDecimal("40.00")).build()));
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID)).thenReturn(List.of(
                ProductionReport.builder().id(4999L).reportKind("INPUT")
                        .materialCost(new BigDecimal("60.00")).build()));
    }

    /**
     * 🔴 产品真实发的那个组合: {@code outputKind='BOTH'} + {@code reportKind='OUTPUT'} +
     * 非空 {@code semiOutputs} + 照发的 {@code outputQuantity}。
     * (RN {@code YieldStepReportScreen.tsx} doSubmitOutput: hasAnySemi ⇒ 'BOTH', 且恒发 outputQuantity)
     */
    private ProductionReport goldDetectorRealShape() {
        return ProductionReport.builder()
                .id(5001L)
                .factoryId(FACTORY)
                .batchId(BATCH_ID)
                .workProcessTaskId(TASK_ID)
                .reportKind("OUTPUT")
                .outputKind("BOTH")
                .outputQuantity(new BigDecimal("950.00"))   // 良品 (RN 提示: 产出量填良品, 不含不良品)
                .outputUnit("kg")
                .semiOutputUnit("kg")
                .semiOutputs(List.of(
                        Map.of("semiCode", REWORK_CODE, "quantity", new BigDecimal("50.00"),
                                "isRework", true)))
                .build();
    }

    private List<SemiFinishedInventory> savedRows() {
        ArgumentCaptor<SemiFinishedInventory> cap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(wipRepo, times(2)).save(cap.capture());
        return new ArrayList<>(cap.getAllValues());
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 C2-a: BOTH + semiOutputs + OUTPUT → 两行成本之和恰好 == 一份报工的成本 (不是 2×)")
    void bothKind_totalPostedCost_equalsOneReportCost() {
        stubOutputRollupTotal100();

        svc.postApprovedOutput(FACTORY, goldDetectorRealShape(), task(), 42L);

        List<SemiFinishedInventory> saved = savedRows();
        BigDecimal sum = saved.stream()
                .map(SemiFinishedInventory::getAccumulatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum)
                .as("SEMI 那条 + FINISHED 那条, 成本之和必须恰好是这一份报工的 ¥100, ⛔ 不是 ¥200")
                .isEqualByComparingTo(new BigDecimal("100.00"));

        // 回料 50 / 良品 950 ⇒ 同一锅料同一个单价 100/1000 = 0.1000
        SemiFinishedInventory rework = saved.stream()
                .filter(s -> REWORK_CODE.equals(s.getIntermediateBatchNo()))
                .findFirst().orElseThrow();
        assertThat(rework.getAccumulatedCost())
                .as("回料分到的是按量的那一份 (50/1000 × 100), ⛔ 不是自己独吞全额")
                .isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    @DisplayName("🔴 C2-b: BOTH + semiOutputs → 两条 IN 流水单价相同 (回料不是良品的 19 倍)")
    void bothKind_bothRowsShareTheSameUnitCost() {
        stubOutputRollupTotal100();

        svc.postApprovedOutput(FACTORY, goldDetectorRealShape(), task(), 42L);

        ArgumentCaptor<SemiFinishedInventoryTransaction> cap =
                ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(txnRepo, times(2)).save(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(t -> assertThat(t.getUnitCostAtTxn())
                .as("一锅料出来的两条产出必须同一个单价 100/(950+50)")
                .isEqualByComparingTo(new BigDecimal("0.1000")));
    }

    @Test
    @DisplayName("🔴 C2-c: BOTH + semiOutputs → ProductionCostUpdatedEvent 恰好 1 次 (@Async 下多发就是抢跑)")
    void bothKind_publishesCostEventExactlyOnce() {
        stubOutputRollupTotal100();

        svc.postApprovedOutput(FACTORY, goldDetectorRealShape(), task(), 42L);

        verify(eventPublisher, times(1)).publishEvent(any(ApplicationEvent.class));
    }

    /**
     * ⛔ 零回归钉子: {@code semiOutputs} 为空 (旧式/纯成品报工) 时 FINISHED 分支必须逐字节不变 ——
     * 全额 ¥100 记在 {@code wipNo} 那一行, 单价 = 100/950, 事件一次。
     */
    @Test
    @DisplayName("C2-d 零回归: 单产出 (semiOutputs 为空) 路径的值与改动前完全一致")
    void finishedOnly_byteIdentical() {
        stubOutputRollupTotal100();
        ProductionReport r = ProductionReport.builder()
                .id(5002L).factoryId(FACTORY).batchId(BATCH_ID).workProcessTaskId(TASK_ID)
                .reportKind("OUTPUT").outputKind("BOTH")
                .outputQuantity(new BigDecimal("950.00")).outputUnit("kg")
                .build();

        svc.postApprovedOutput(FACTORY, r, task(), 42L);

        ArgumentCaptor<SemiFinishedInventory> cap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(wipRepo, times(1)).save(cap.capture());
        assertThat(cap.getValue().getAccumulatedCost()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(cap.getValue().getUnitCost()).isEqualByComparingTo(new BigDecimal("0.1053"));
        verify(eventPublisher, times(1)).publishEvent(any(ApplicationEvent.class));
    }

    /**
     * 阴性对照: {@code outputKind='SEMI'} 时 FINISHED 分支<b>不该</b>跑 ——
     * 若哪天有人把分支判据改宽 (例如误把 SEMI 也算进 isFinished), 这条会红。
     */
    @Test
    @DisplayName("C2-e 阴性对照: outputKind=SEMI 时不碰 FINISHED 那一行")
    void semiOnly_doesNotTouchFinishedRow() {
        stubOutputRollupTotal100();
        ProductionReport r = ProductionReport.builder()
                .id(5003L).factoryId(FACTORY).batchId(BATCH_ID).workProcessTaskId(TASK_ID)
                .reportKind("OUTPUT").outputKind("SEMI")
                .outputQuantity(new BigDecimal("950.00")).outputUnit("kg").semiOutputUnit("kg")
                .semiOutputs(List.of(
                        Map.of("semiCode", REWORK_CODE, "quantity", new BigDecimal("50.00"),
                                "isRework", true)))
                .build();

        svc.postApprovedOutput(FACTORY, r, task(), 42L);

        ArgumentCaptor<SemiFinishedInventory> cap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(wipRepo, times(1)).save(cap.capture());
        assertThat(cap.getValue().getIntermediateBatchNo())
                .as("SEMI 只入半成品那一行")
                .isEqualTo(REWORK_CODE);
        assertThat(cap.getValue().getAccumulatedCost())
                .as("SEMI 单条 ⇒ 分母就是它自己, 拿全额 (与 C-1 口径一致)")
                .isEqualByComparingTo(new BigDecimal("100.00"));
        verify(wipRepo, never()).findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                FACTORY, "PT-BURGER-B8001-S5-7001");
    }
}
