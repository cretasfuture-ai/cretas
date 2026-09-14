package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LineageEdgeRecorder} 的行为闸。
 *
 * <h2>每条断言都配了一条能让它红的对照</h2>
 *
 * <p>「幂等」这种断言最容易写成恒真式：如果 recorder 压根没调 save，
 * 「只存了一条」也成立。所以幂等那一组<b>先断言第一次真的写了</b>，再断言第二次没写 ——
 * 一个必发生的读数配一个不该发生的读数（硬约束 9）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LineageEdgeRecorder — 三段写边 + 幂等 + 五态")
class LineageEdgeRecorderTest {

    private static final String FACTORY = "F006";

    @Mock private com.cretas.aims.repository.lineage.BatchLineageEdgeRepository edgeRepository;
    @Mock private com.cretas.aims.repository.ProductionBatchRepository productionBatchRepository;

    /**
     * ⚠️ 这里装的是**真的** {@link LineageEdgeWriter}（只有仓库是 mock）。
     *
     * <p>不是为了好看：writer 持有幂等判据，把它也 mock 掉这一整组断言就全变成
     * 「我让 mock 返回什么它就返回什么」。真事务语义（afterCommit / REQUIRES_NEW /
     * flush 时机）由 {@code LineageEdgeRecorderTransactionContractTest} 在真 H2 上守，
     * ⛔ 本文件不声称覆盖那一层。
     */
    private LineageEdgeRecorder newRecorder() {
        return new LineageEdgeRecorder(new LineageEdgeWriter(edgeRepository),
                new FinishedBatchProductionAligner(productionBatchRepository));
    }

    /** 让 repo 表现得像真库：save 进去的边下次 findMatchingEdges 能查到。 */
    private List<BatchLineageEdge> wireInMemoryRepo() {
        List<BatchLineageEdge> store = new ArrayList<>();
        when(edgeRepository.findMatchingEdges(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenAnswer(inv -> store.stream()
                        .filter(e -> e.getFactoryId().equals(inv.getArgument(0))
                                && e.getEdgeType().equals(inv.getArgument(1))
                                && e.getSourceType().equals(inv.getArgument(2))
                                && e.getSourceId().equals(inv.getArgument(3))
                                && e.getTargetType().equals(inv.getArgument(4))
                                && e.getTargetId().equals(inv.getArgument(5)))
                        .toList());
        when(edgeRepository.saveAndFlush(any(BatchLineageEdge.class))).thenAnswer(inv -> {
            BatchLineageEdge e = inv.getArgument(0);
            store.add(e);
            return e;
        });
        return store;
    }

    // ── ① 幂等 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("同一个 refKey 写两次只留一条边 —— 且第一次确实写进去了")
    void sameRefKeyWritesOnlyOnce() {
        List<BatchLineageEdge> store = wireInMemoryRepo();
        LineageEdgeRecorder recorder = newRecorder();

        LineageRecordOutcome first = recorder.recordRawToProduction(
                FACTORY, "mb-1", 8967L, new BigDecimal("12.5"), "kg", 7L, "consumption:42", null);
        // 🔴 「必发生」的那个读数: 没有它, 下面那条 ALREADY_EXISTS 在「recorder 什么都没做」时同样成立。
        assertThat(first).isEqualTo(LineageRecordOutcome.INSERTED);
        assertThat(store).hasSize(1);

        LineageRecordOutcome second = recorder.recordRawToProduction(
                FACTORY, "mb-1", 8967L, new BigDecimal("12.5"), "kg", 7L, "consumption:42", null);
        assertThat(second).isEqualTo(LineageRecordOutcome.ALREADY_EXISTS);
        assertThat(store).hasSize(1);
    }

    @Test
    @DisplayName("同一对批次不同 refKey 是两次真实投料 —— 各成一条边")
    void differentRefKeysAreTwoRealEdges() {
        List<BatchLineageEdge> store = wireInMemoryRepo();
        LineageEdgeRecorder recorder = newRecorder();

        recorder.recordRawToProduction(FACTORY, "mb-1", 8967L, BigDecimal.ONE, "kg", 7L, "consumption:1", null);
        recorder.recordRawToProduction(FACTORY, "mb-1", 8967L, BigDecimal.TEN, "kg", 7L, "consumption:2", null);

        assertThat(store).hasSize(2);
        assertThat(store).extracting(e -> e.getMeta().get(LineageEdgeRecorder.META_REF_KEY))
                .containsExactly("consumption:1", "consumption:2");
    }

    // ── ② 三种 edge_type 各一条正向 ────────────────────────────────────────

    @Test
    @DisplayName("RAW_TO_PRODUCTION: MATERIAL_BATCH → PRODUCTION_BATCH")
    void rawToProductionShape() {
        wireInMemoryRepo();
        newRecorder().recordRawToProduction(FACTORY, "mb-1", 8967L,
                new BigDecimal("3.0"), "kg", 7L, "consumption:1", Map.of("origin", "TEST"));

        BatchLineageEdge e = capture();
        assertThat(e.getEdgeType()).isEqualTo("RAW_TO_PRODUCTION");
        assertThat(e.getSourceType()).isEqualTo("MATERIAL_BATCH");
        assertThat(e.getSourceId()).isEqualTo("mb-1");
        assertThat(e.getTargetType()).isEqualTo("PRODUCTION_BATCH");
        assertThat(e.getTargetId()).isEqualTo("8967");
        assertThat(e.getQuantityUsed()).isEqualByComparingTo("3.0");
        assertThat(e.getUnit()).isEqualTo("kg");
        assertThat(e.getMeta()).containsEntry("origin", "TEST");
    }

    @Test
    @DisplayName("PRODUCTION_TO_FINISHED: PRODUCTION_BATCH → FINISHED_BATCH")
    void productionToFinishedShape() {
        wireInMemoryRepo();
        newRecorder().recordProductionToFinished(FACTORY, 8967L, "fg-1",
                new BigDecimal("100"), "盒", 7L, "finishedBatch:fg-1", null);

        BatchLineageEdge e = capture();
        assertThat(e.getEdgeType()).isEqualTo("PRODUCTION_TO_FINISHED");
        assertThat(e.getSourceType()).isEqualTo("PRODUCTION_BATCH");
        assertThat(e.getSourceId()).isEqualTo("8967");
        assertThat(e.getTargetType()).isEqualTo("FINISHED_BATCH");
        assertThat(e.getTargetId()).isEqualTo("fg-1");
    }

    @Test
    @DisplayName("FINISHED_TO_SHIPMENT: FINISHED_BATCH → SHIPMENT_RECORD, 且 meta 标明载体是发货单")
    void finishedToShipmentShapeAndCarrier() {
        wireInMemoryRepo();
        newRecorder().recordFinishedToShipment(FACTORY, "fg-1", "dr-1",
                new BigDecimal("20"), "盒", 7L, "delivery:dr-1:fg-1", null);

        BatchLineageEdge e = capture();
        assertThat(e.getEdgeType()).isEqualTo("FINISHED_TO_SHIPMENT");
        assertThat(e.getSourceType()).isEqualTo("FINISHED_BATCH");
        assertThat(e.getTargetType()).isEqualTo("SHIPMENT_RECORD");
        assertThat(e.getTargetId()).isEqualTo("dr-1");
        // 🔴 shipment_records 全库 0 行 + 写入端点 410 GONE; 载体必须在 meta 里写死,
        //    否则下一个人拿这个 id 去 join shipment_records 会查出 0 行然后读成「没有出货」。
        assertThat(e.getMeta())
                .containsEntry(LineageEdgeRecorder.META_SHIPMENT_CARRIER,
                        LineageEdgeRecorder.SHIPMENT_CARRIER_SALES_DELIVERY);
    }

    // ── ③ 不写的那几种情况（禁止降级） ────────────────────────────────────

    @Test
    @DisplayName("缺 productionBatchId ⇒ SKIPPED 且一条边都不写（⛔ 不兜底造 id）")
    void missingTargetIsSkippedNotFabricated() {
        LineageRecordOutcome outcome = newRecorder().recordRawToProduction(
                FACTORY, "mb-1", null, BigDecimal.ONE, "kg", 7L, "consumption:1", null);

        assertThat(outcome).isEqualTo(LineageRecordOutcome.SKIPPED);
        verify(edgeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("负数（冲销）⇒ SKIPPED —— append-only 的边表达不了「减少」")
    void reversalQuantityIsSkipped() {
        LineageRecordOutcome outcome = newRecorder().recordRawToProduction(
                FACTORY, "mb-1", 8967L, new BigDecimal("-5"), "kg", 7L, "consumption:9", null);

        assertThat(outcome).isEqualTo(LineageRecordOutcome.SKIPPED);
        verify(edgeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("写库抛异常 ⇒ FAILED 而不是往上抛（fail-soft 不阻塞报工/发货）")
    void writeFailureIsFailSoft() {
        when(edgeRepository.findMatchingEdges(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(List.of());
        when(edgeRepository.saveAndFlush(any(BatchLineageEdge.class)))
                .thenThrow(new RuntimeException("db down"));

        LineageRecordOutcome outcome = newRecorder().recordRawToProduction(
                FACTORY, "mb-1", 8967L, BigDecimal.ONE, "kg", 7L, "consumption:1", null);

        assertThat(outcome).isEqualTo(LineageRecordOutcome.FAILED);
    }

    // ── ④ 成品→生产批次对齐（一计划多批次） ──────────────────────────────

    @Test
    @DisplayName("计划下只有一个生产批次 ⇒ 对齐成功并写边")
    void byPlanResolvesSingleBatch() {
        wireInMemoryRepo();
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY, "plan-1"))
                .thenReturn(List.of(batch(8967L, "PT-A")));

        LineageRecordOutcome outcome = newRecorder().recordProductionToFinishedByPlan(
                FACTORY, "plan-1", "PT-A", "fg-1", BigDecimal.TEN, "盒", 7L, "finishedBatch:fg-1", null);

        assertThat(outcome).isEqualTo(LineageRecordOutcome.INSERTED);
        assertThat(capture().getSourceId()).isEqualTo("8967");
    }

    @Test
    @DisplayName("多批次但只有一个同产品 ⇒ 二次对齐成功")
    void byPlanDisambiguatesByProductType() {
        wireInMemoryRepo();
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY, "plan-1"))
                .thenReturn(List.of(batch(1L, "PT-A"), batch(2L, "PT-B")));

        LineageRecordOutcome outcome = newRecorder().recordProductionToFinishedByPlan(
                FACTORY, "plan-1", "PT-B", "fg-1", BigDecimal.TEN, "盒", 7L, "finishedBatch:fg-1", null);

        assertThat(outcome).isEqualTo(LineageRecordOutcome.INSERTED);
        assertThat(capture().getSourceId()).isEqualTo("2");
    }

    @Test
    @DisplayName("多批次同产品分不出 ⇒ AMBIGUOUS 且一条边都不写（⛔ 不挑一个）")
    void byPlanAmbiguousWritesNothing() {
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY, "plan-1"))
                .thenReturn(List.of(batch(1L, "PT-A"), batch(2L, "PT-A")));

        LineageRecordOutcome outcome = newRecorder().recordProductionToFinishedByPlan(
                FACTORY, "plan-1", "PT-A", "fg-1", BigDecimal.TEN, "盒", 7L, "finishedBatch:fg-1", null);

        // AMBIGUOUS 与 SKIPPED 必须分得开: 前者是「有东西但分不出」, 是要报出来的缺口。
        assertThat(outcome).isEqualTo(LineageRecordOutcome.AMBIGUOUS);
        verify(edgeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("计划下没有生产批次 ⇒ SKIPPED（没有载体，不是对不齐）")
    void byPlanNoCarrierIsSkipped() {
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY, "plan-1"))
                .thenReturn(List.of());

        LineageRecordOutcome outcome = newRecorder().recordProductionToFinishedByPlan(
                FACTORY, "plan-1", "PT-A", "fg-1", BigDecimal.TEN, "盒", 7L, "finishedBatch:fg-1", null);

        assertThat(outcome).isEqualTo(LineageRecordOutcome.SKIPPED);
        verify(edgeRepository, never()).saveAndFlush(any());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private BatchLineageEdge capture() {
        ArgumentCaptor<BatchLineageEdge> captor = ArgumentCaptor.forClass(BatchLineageEdge.class);
        verify(edgeRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static ProductionBatch batch(Long id, String productTypeId) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setProductTypeId(productTypeId);
        return b;
    }
}
