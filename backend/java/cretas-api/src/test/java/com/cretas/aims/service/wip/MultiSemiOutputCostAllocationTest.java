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
    @Mock
    private ApplicationEventPublisher eventPublisher;

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
        for (String code : semiCodes) {
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, code))
                    .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(1, String.class))));
        }
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            s.setId(1000L + rows.size());
            rows.put(s.getIntermediateBatchNo(), s);
            return s;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 金检报工: 良品 950 + 回料 50, 本道成本 = 人工 4000 + 材料 6000 = ¥10,000。 */
    private ProductionReport goldDetectorReport() {
        return ProductionReport.builder()
                .id(5001L)
                .factoryId(FACTORY_ID)
                .batchId(8001L)
                .workProcessTaskId(7001L)
                .outputKind("SEMI")
                .semiOutputUnit("kg")
                .laborCost(new BigDecimal("4000.00"))
                .materialCost(new BigDecimal("6000.00"))
                .semiOutputs(List.of(
                        Map.of("semiCode", "PTSEMI-LSM-0010", "quantity", new BigDecimal("950.00"), "isRework", false),
                        Map.of("semiCode", "PTSEMI-LSM-0003", "quantity", new BigDecimal("50.00"), "isRework", true)))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C-1a: 两个产出 → 两条 unitCostAtTxn 相等, 且 accumulated_cost 增量之和 == totalCost (不是 2×)")
    void twoOutputs_costIsAllocatedByQuantity_notDuplicated() {
        stubFirstInFor("PTSEMI-LSM-0010", "PTSEMI-LSM-0003");

        service.postApprovedOutput(FACTORY_ID, goldDetectorReport(), task(), 42L);

        verify(txnRepo, times(2)).save(txnCaptor.capture());
        List<SemiFinishedInventoryTransaction> txns = txnCaptor.getAllValues();

        // 回料是几乎全价的肉, 和良品出自同一锅料 ⇒ 同一个单价 (10000 / 1000 = 10.0000)
        assertThat(txns.get(0).getUnitCostAtTxn())
                .as("良品那条的入账单价")
                .isEqualByComparingTo(new BigDecimal("10.0000"));
        assertThat(txns.get(1).getUnitCostAtTxn())
                .as("回料那条的入账单价必须与良品相等 —— 分摊前它是 200.00 (19 倍)")
                .isEqualByComparingTo(new BigDecimal("10.0000"));

        verify(wipRepo, times(2)).save(sfiCaptor.capture());
        List<SemiFinishedInventory> saved = sfiCaptor.getAllValues();
        // 分摊: 950/1000 × 10000 = 9500.00; 尾数归最后一条 → 10000 - 9500 = 500.00
        assertThat(saved.get(0).getAccumulatedCost()).isEqualByComparingTo(new BigDecimal("9500.00"));
        assertThat(saved.get(1).getAccumulatedCost()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(saved.get(0).getAccumulatedCost().add(saved.get(1).getAccumulatedCost()))
                .as("两条入账的成本之和必须恰好是这份报工的成本, ⛔ 不是 2×")
                .isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    @Test
    @DisplayName("C-1b: 多产出下 ProductionCostUpdatedEvent 只发一次 (发多次 ⇒ @Async 下哪条先到哪条赢)")
    void multiOutput_publishesCostEventExactlyOnce() {
        stubFirstInFor("PTSEMI-LSM-0010", "PTSEMI-LSM-0003");

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
        ProductionReport r = ProductionReport.builder()
                .id(5004L)
                .factoryId(FACTORY_ID)
                .batchId(8001L)
                .workProcessTaskId(7001L)
                .outputKind("SEMI")
                .semiOutputUnit("kg")
                .laborCost(null)
                .materialCost(null)
                .semiOutputs(List.of(
                        Map.of("semiCode", "PTSEMI-LSM-0010", "quantity", new BigDecimal("950.00")),
                        Map.of("semiCode", "PTSEMI-LSM-0003", "quantity", new BigDecimal("50.00"))))
                .build();

        service.postApprovedOutput(FACTORY_ID, r, task(), 42L);

        verify(txnRepo, times(2)).save(txnCaptor.capture());
        assertThat(txnCaptor.getAllValues()).allSatisfy(t ->
                assertThat(t.getUnitCostAtTxn()).as("成本未知 ⇒ 诚实 null").isNull());
        verify(wipRepo, times(2)).save(sfiCaptor.capture());
        assertThat(sfiCaptor.getAllValues()).allSatisfy(s ->
                assertThat(s.getAccumulatedCost()).as("成本未知 ⇒ 诚实 null, ⛔ 不是 0").isNull());
    }
}
