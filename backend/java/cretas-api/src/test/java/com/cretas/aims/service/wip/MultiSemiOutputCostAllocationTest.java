package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.ProductionCostUpdatedEvent;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多产出 (画布 1 进 N 出) 的成本必须<b>按量分摊</b>, 不是每条各拿一份全额。
 *
 * <h2>它守什么 (2026-08-28 整分支终审 C-1)</h2>
 *
 * <p>{@code postSemiOutputLedgerFor} 被 for 循环对每个产出条目各调一次, 而它内部算出的
 * {@code totalCost} 与条目无关 —— 每次都重算成同一个<b>全额</b>值。金检报工 (良品 950 / 回料 50,
 * 本道成本 ¥10,000) 于是变成:
 *
 * <pre>
 *   良品 950 kg  unit_cost_at_txn = 10.53   accumulated_cost += 10,000
 *   回料  50 kg  unit_cost_at_txn = 200.00  accumulated_cost += 10,000   ← 再来一份全额
 * </pre>
 *
 * <p>三重后果: (1) ¥10,000 被记成 ¥20,000; (2) 回料单价是良品的 19 倍, 而回料按配方要占下一批的
 * 5%~19.5%, 按移动加权领进去下一批材料成本翻数倍; (3) 每条各发一次
 * {@link ProductionCostUpdatedEvent}, 而 {@code OrderCostBackfillListener} 是 {@code @Async} +
 * 「{@code costUnitPrice == null} 才写」⇒ 哪条先到哪条赢, 销售订单行的成本单价非确定性。
 *
 * <p>正确口径: 回料是几乎全价的肉, 和良品出自同一锅料 ⇒ 同一个单价。
 * {@code thisEntryCost = totalCost × thisQty / Σqty}, 各条 {@code inUnitCost} 都等于
 * {@code totalCost / Σqty}。
 *
 * <p>⛔ 单产出路径必须逐字节不变 ({@code Σqty == inQty} ⇒ 分摊后与改动前完全一致),
 * 由 {@link #singleOutput_byteIdentical_legacyPath()} /
 * {@link #singleOutput_byteIdentical_semiOutputsPath()} 两条零回归钉子钉住。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("C-1: 多产出成本按量分摊 (不是每条各拿全额)")
class MultiSemiOutputCostAllocationTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private SemiFinishedInventoryRepository wipRepo;
    @Mock
    private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock
    private ProductionReportRepository reportRepo;
    @Mock
    private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock
    private WorkProcessTaskRepository taskRepo;
    @Mock
    private WorkProcessRepository workProcessRepo;
    /**
     * 2026-08-28 Critical 1 起必须声明 —— 「这一条是不是回料」的判据要回查画布
     * ({@code workflow_instances.nodes_json} + {@code workflow_task_ports}), 不声明会 NPE。
     * ⚠️ 本类不打桩它们 (mock 默认 empty/Optional.empty ⇒ 没有回料端口), 恰好是「画布上没标回料」
     * 这个阴性场景, 与本类要守的成本分摊无关。
     */
    @Mock
    private com.cretas.aims.repository.ProductTypeRepository productTypeRepo;
    @Mock
    private com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository workflowInstanceRepo;
    @Mock
    private com.cretas.aims.repository.workflow.WorkflowTaskPortRepository workflowTaskPortRepo;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @org.mockito.Mock
    private com.cretas.aims.service.processentry.IntermediateOwnershipGuard ownershipGuard;
    @InjectMocks
    private WipInventoryServiceImpl service;

    @Captor
    private ArgumentCaptor<SemiFinishedInventory> sfiCaptor;
    @Captor
    private ArgumentCaptor<SemiFinishedInventoryTransaction> txnCaptor;
    @Captor
    private ArgumentCaptor<ApplicationEvent> eventCaptor;

    /** 每个 semiCode 一个独立的 0 量占位行 holder (first-IN: findForUpdate empty → saveAndFlush 建行 → 再 findForUpdate)。 */
    private final Map<String, SemiFinishedInventory> rows = new HashMap<>();

    private WorkProcessTask task() {
        return WorkProcessTask.builder()
                .id(7001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(8001L)
                .productTypeId("PT-JJ")
                .processOrder(9)
                .plannedUnit("kg")
                .build();
    }

    private void stubFirstInFor(String... semiCodes) {
        when(txnRepo.findByFactoryIdAndReportId(eq(FACTORY_ID), anyLong()))
                .thenReturn(java.util.Collections.emptyList());
        // 真实形状下 FINISHED 那条也要建行 (wipNo = generateBatchNo(task)), 故按任意 key 应答,
        // ⛔ 不再只列 semiCode —— 只列 semiCode 会让 FINISHED 那条 "row missing after ensureRowExists"。
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(FACTORY_ID), any()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(1, String.class))));
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            s.setId(1000L + rows.size());
            rows.put(s.getIntermediateBatchNo(), s);
            return s;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * OUTPUT 阶段的成本 rollup: 本 task 报工的人工 ¥4,000 + 同批 INPUT 报工的料 ¥6,000 = ¥10,000。
     * (真实形状是 {@code reportKind='OUTPUT'} —— 料记在 INPUT 报工上, 不在这份 OUTPUT 报工自己身上)
     */
    private void stubOutputRollup10000() {
        when(reportRepo.findYieldReportsByTask(FACTORY_ID, 7001L)).thenReturn(List.of(
                ProductionReport.builder().id(4998L).laborCost(new BigDecimal("4000.00")).build()));
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, 8001L)).thenReturn(List.of(
                ProductionReport.builder().id(4999L).reportKind("INPUT")
                        .materialCost(new BigDecimal("6000.00")).build()));
    }

    /**
     * 金检报工: 良品 950 (走 {@code outputQuantity}) + 两个回料去向 (金属异物 30 / 非金属异物 20),
     * 本道成本 = 人工 4000 + 材料 6000 = ¥10,000。
     *
     * <h3>2026-08-28 Critical 2: 为什么把 fixture 从 {@code outputKind="SEMI"} 换成真实形状</h3>
     *
     * <p>原 fixture 是 {@code outputKind("SEMI")} + {@code reportKind} 为 null。
     * <b>没有任何客户端发这个组合</b>: RN 报工屏 ({@code YieldStepReportScreen.tsx:1590}) 只发
     * {@code 'BOTH'}(有半成品产出) / {@code 'FINISHED'} / 不发, 且恒带 {@code reportKind:'OUTPUT'}
     * 与 {@code outputQuantity}; web-admin 只<b>展示</b> outputKind 不写它。
     * 于是这几条断言此前守的是一个<b>生产上不会出现的形状</b> —— 而真实形状恰好是那个有缺陷的分支组合
     * (SEMI 与 FINISHED 同时跑, 各拿一次全额成本)。
     *
     * <p>⚠️ 精确说: 「SEMI 单产出」对<b>存量报工</b>仍然可达 (postApprovedOutput 在审批时跑,
     * 报工可能是旧版 App 写的) —— 不可达的是「SEMI + semiOutputs 同时出现」, 因为 semiOutputs
     * 是新版才有的字段, 而写它的那个版本只发 BOTH/FINISHED。故本类的两条 legacy 单产出零回归钉子
     * 保持原样, 只换多产出这一个 fixture。
     *
     * <p>两个回料去向不是杜撰: 见 {@code yieldStepOutputPayload.ts} 的 I-4 说明 ——
     * 「一道工序配两个回料去向 (金属异物 / 非金属异物分开回收)」是画布支持且合法的配置。
     */
    private ProductionReport goldDetectorReport() {
        return ProductionReport.builder()
                .id(5001L)
                .factoryId(FACTORY_ID)
                .batchId(8001L)
                .workProcessTaskId(7001L)
                .reportKind("OUTPUT")
                .outputKind("BOTH")
                .outputQuantity(new BigDecimal("950.00"))   // 良品 (RN 提示: 产出量填良品, 不含不良品)
                .outputUnit("kg")
                .semiOutputUnit("kg")
                .semiOutputs(List.of(
                        Map.of("semiCode", "PTSEMI-LSM-0010", "quantity", new BigDecimal("30.00"), "isRework", true),
                        Map.of("semiCode", "PTSEMI-LSM-0003", "quantity", new BigDecimal("20.00"), "isRework", true)))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C-1a: 三条入账 (两回料 + 良品) → unitCostAtTxn 全相等, 且成本之和 == totalCost (不是 N×)")
    void twoOutputs_costIsAllocatedByQuantity_notDuplicated() {
        stubFirstInFor("PTSEMI-LSM-0010", "PTSEMI-LSM-0003");
        stubOutputRollup10000();

        service.postApprovedOutput(FACTORY_ID, goldDetectorReport(), task(), 42L);

        // 两条 semi + FINISHED 那条 = 3 笔 IN
        verify(txnRepo, times(3)).save(txnCaptor.capture());
        List<SemiFinishedInventoryTransaction> txns = txnCaptor.getAllValues();

        // 回料是几乎全价的肉, 和良品出自同一锅料 ⇒ 同一个单价 (10000 / (30+20+950) = 10.0000)
        assertThat(txns).allSatisfy(t -> assertThat(t.getUnitCostAtTxn())
                .as("三条入账必须同一个单价 —— 分摊前回料那两条各是 333.33 / 500.00 (几十倍)")
                .isEqualByComparingTo(new BigDecimal("10.0000")));

        verify(wipRepo, times(3)).save(sfiCaptor.capture());
        List<SemiFinishedInventory> saved = sfiCaptor.getAllValues();
        // 分摊: 30/1000 × 10000 = 300.00; 20/1000 × 10000 = 200.00; 尾数归最后一槽 (FINISHED) = 9500.00
        assertThat(saved.get(0).getAccumulatedCost()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(saved.get(1).getAccumulatedCost()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(saved.get(2).getAccumulatedCost()).isEqualByComparingTo(new BigDecimal("9500.00"));
        assertThat(saved.stream().map(SemiFinishedInventory::getAccumulatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("三条入账的成本之和必须恰好是这份报工的成本, ⛔ 不是 N×")
                .isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    @Test
    @DisplayName("C-1b: 多产出下 ProductionCostUpdatedEvent 只发一次 (发多次 ⇒ @Async 下哪条先到哪条赢)")
    void multiOutput_publishesCostEventExactlyOnce() {
        stubFirstInFor("PTSEMI-LSM-0010", "PTSEMI-LSM-0003");
        stubOutputRollup10000();

        service.postApprovedOutput(FACTORY_ID, goldDetectorReport(), task(), 42L);

        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        ApplicationEvent ev = eventCaptor.getValue();
        assertThat(ev).isInstanceOf(ProductionCostUpdatedEvent.class);
        ProductionCostUpdatedEvent cost = (ProductionCostUpdatedEvent) ev;
        assertThat(cost.getActualUnitCost())
                .as("发的是分摊后的单价 (各条相同 ⇒ 与条目顺序无关, 确定性)")
                .isEqualByComparingTo(new BigDecimal("10.0000"));
        assertThat(cost.getActualTotalCost())
                .as("总成本是这份报工的全额 (一份, 不是 N 份)")
                .isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    /**
     * 零回归钉子 ①: 旧式单产出 (semiCode / semiOutputQuantity, semiOutputs 为空)。
     * Σqty == inQty ⇒ 分摊后必须与改动前完全一致。
     */
    @Test
    @DisplayName("C-1c 零回归: 旧式单产出路径的值与改动前完全一致")
    void singleOutput_byteIdentical_legacyPath() {
        stubFirstInFor("PTSEMI-LSM-0010");
        ProductionReport r = ProductionReport.builder()
                .id(5002L)
                .factoryId(FACTORY_ID)
                .batchId(8001L)
                .workProcessTaskId(7001L)
                .outputKind("SEMI")
                .semiCode("PTSEMI-LSM-0010")
                .semiOutputQuantity(new BigDecimal("1000.00"))
                .semiOutputUnit("kg")
                .laborCost(new BigDecimal("4000.00"))
                .materialCost(new BigDecimal("6000.00"))
                .build();

        service.postApprovedOutput(FACTORY_ID, r, task(), 42L);

        verify(wipRepo).save(sfiCaptor.capture());
        assertThat(sfiCaptor.getValue().getAccumulatedCost()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(sfiCaptor.getValue().getUnitCost()).isEqualByComparingTo(new BigDecimal("10.0000"));
        verify(txnRepo).save(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getUnitCostAtTxn()).isEqualByComparingTo(new BigDecimal("10.0000"));
        // 单产出仍然发一次事件 (原行为), 且带的是这条 SFI 行的移动均价
        verify(eventPublisher, times(1)).publishEvent(any(ApplicationEvent.class));
    }

    /**
     * 零回归钉子 ②: {@code semiOutputs} 里只有一条 —— 分摊的分母就是它自己,
     * 结果必须与旧式单产出逐字节相同 (尾数归最后一条 ⇒ 唯一那条拿到全额, 无舍入损失)。
     */
    @Test
    @DisplayName("C-1d 零回归: semiOutputs 只有一条时拿到全额 (Σqty == inQty)")
    void singleOutput_byteIdentical_semiOutputsPath() {
        stubFirstInFor("PTSEMI-LSM-0010");
        ProductionReport r = ProductionReport.builder()
                .id(5003L)
                .factoryId(FACTORY_ID)
                .batchId(8001L)
                .workProcessTaskId(7001L)
                .outputKind("SEMI")
                .semiOutputUnit("kg")
                .laborCost(new BigDecimal("4000.00"))
                .materialCost(new BigDecimal("6000.00"))
                .semiOutputs(List.of(
                        Map.of("semiCode", "PTSEMI-LSM-0010", "quantity", new BigDecimal("1000.00"))))
                .build();

        service.postApprovedOutput(FACTORY_ID, r, task(), 42L);

        verify(wipRepo).save(sfiCaptor.capture());
        assertThat(sfiCaptor.getValue().getAccumulatedCost()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(sfiCaptor.getValue().getUnitCost()).isEqualByComparingTo(new BigDecimal("10.0000"));
        verify(txnRepo).save(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getUnitCostAtTxn()).isEqualByComparingTo(new BigDecimal("10.0000"));
        verify(eventPublisher, times(1)).publishEvent(any(ApplicationEvent.class));
    }

    /**
     * 诚实 null: 成本读不出来时分摊不许兜底成 0 —— 「不知道」不是「零成本」。
     * 一个假的 0 会顺着移动加权把整条链的单价摊薄, 而且不报错。
     */
    @Test
    @DisplayName("C-1e: totalCost 未知时两条都是诚实 null, ⛔ 不摊成 0")
    void unknownCost_staysNull_notZero() {
        stubFirstInFor("PTSEMI-LSM-0010", "PTSEMI-LSM-0003");
        // 真实形状 (BOTH + OUTPUT) 且本 task / 本批 INPUT 都查不到成本 ⇒ rollup 全 null。
        // ⛔ 不打 rollup 桩 —— mock 默认返回空列表, 那正是「成本读不出来」这件事本身。
        ProductionReport r = ProductionReport.builder()
                .id(5004L)
                .factoryId(FACTORY_ID)
                .batchId(8001L)
                .workProcessTaskId(7001L)
                .reportKind("OUTPUT")
                .outputKind("BOTH")
                .outputQuantity(new BigDecimal("950.00"))
                .outputUnit("kg")
                .semiOutputUnit("kg")
                .semiOutputs(List.of(
                        Map.of("semiCode", "PTSEMI-LSM-0010", "quantity", new BigDecimal("30.00")),
                        Map.of("semiCode", "PTSEMI-LSM-0003", "quantity", new BigDecimal("20.00"))))
                .build();

        service.postApprovedOutput(FACTORY_ID, r, task(), 42L);

        verify(txnRepo, times(3)).save(txnCaptor.capture());
        assertThat(txnCaptor.getAllValues()).allSatisfy(t ->
                assertThat(t.getUnitCostAtTxn()).as("成本未知 ⇒ 诚实 null").isNull());
        verify(wipRepo, times(3)).save(sfiCaptor.capture());
        assertThat(sfiCaptor.getAllValues()).allSatisfy(s ->
                assertThat(s.getAccumulatedCost()).as("成本未知 ⇒ 诚实 null, ⛔ 不是 0").isNull());
    }
}
