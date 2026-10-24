package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 半成品层的四个缺口在 lineage 层的落点：成品对齐的批次号线索、web 半成品投料写边、两段新回填。
 *
 * <p>边写入全部走真的 {@link LineageEdgeRecorder} / {@link LineageEdgeWriter}（仓库内存桩），
 * 所以幂等键、五态计数与在线路径是同一份实现。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("半成品层血缘缺口: 对齐线索 / SFI 投料写边 / 回填")
class SemiFinishedLineageGapsTest {

    private static final String FACTORY = "F006";

    @Mock private BatchLineageEdgeRepository edgeRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private SemiFinishedInventoryRepository sfiRepository;
    @Mock private WorkProcessTaskRepository taskRepository;
    @Mock private EntityManager entityManager;

    private final List<BatchLineageEdge> store = new ArrayList<>();
    private LineageEdgeRecorder recorder;

    @BeforeEach
    void wire() {
        when(edgeRepository.findMatchingEdges(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenAnswer(inv -> store.stream()
                        .filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                                && e.getSourceId().equals(inv.getArgument(3))
                                && e.getTargetId().equals(inv.getArgument(5)))
                        .toList());
        when(edgeRepository.saveAndFlush(any(BatchLineageEdge.class))).thenAnswer(inv -> {
            store.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(taskRepository.findByFactoryIdAndId(anyString(), any())).thenReturn(Optional.empty());
        recorder = new LineageEdgeRecorder(new LineageEdgeWriter(edgeRepository),
                new FinishedBatchProductionAligner(productionBatchRepository));
    }

    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("缺口 3: 结单逐行入库的成品批 —— 按报工批号对齐")
    class FinishedAlignmentByBatchNumber {

        @BeforeEach
        void threeSameProductBatchesInOnePlan() {
            // prod 原形: 计划 8cf3fa56 下 3 个同产品 REGULAR 批次 ⇒ 只按计划对齐必然 AMBIGUOUS
            when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY, "plan-A"))
                    .thenReturn(List.of(pb(10922L, "plan-A"), pb(10926L, "plan-A"), pb(10927L, "plan-A")));
            when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "CLK-B-20260903-624"))
                    .thenReturn(Optional.of(pb(10927L, "plan-A")));
            when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "CLK-B-OTHER-PLAN"))
                    .thenReturn(Optional.of(pb(555L, "plan-Z")));
        }

        @Test
        @DisplayName("带批号线索 ⇒ 精确写 10927 → 成品批, meta.alignedBy=BATCH_NUMBER")
        void hintResolvesExactProducer() {
            LineageRecordOutcome outcome = recorder.recordProductionToFinishedByPlan(FACTORY, "plan-A", "PT-FIN",
                    "CLK-B-20260903-624", "fg-624", BigDecimal.ONE, "盒", 7L, "finishedBatch:fg-624", null);

            assertThat(outcome).isEqualTo(LineageRecordOutcome.INSERTED);
            assertThat(store).singleElement().satisfies(e -> {
                assertThat(e.getSourceId()).isEqualTo("10927");
                assertThat(e.getTargetId()).isEqualTo("fg-624");
                assertThat(e.getMeta()).containsEntry("alignedBy", FinishedBatchProductionAligner.Alignment.BY_BATCH_NUMBER);
            });
        }

        @Test
        @DisplayName("阴性对照: 不带线索 ⇒ 仍 AMBIGUOUS, 一条不写 (证明是线索让它对上的)")
        void withoutHintStillAmbiguous() {
            LineageRecordOutcome outcome = recorder.recordProductionToFinishedByPlan(FACTORY, "plan-A", "PT-FIN",
                    "fg-624", BigDecimal.ONE, "盒", 7L, "finishedBatch:fg-624", null);

            assertThat(outcome).isEqualTo(LineageRecordOutcome.AMBIGUOUS);
            assertThat(store).isEmpty();
        }

        @Test
        @DisplayName("同号批次属于别的计划 ⇒ 线索不采纳 (撞号不是同一件事), 退回计划规则 = AMBIGUOUS")
        void hintFromAnotherPlanIsIgnored() {
            LineageRecordOutcome outcome = recorder.recordProductionToFinishedByPlan(FACTORY, "plan-A", "PT-FIN",
                    "CLK-B-OTHER-PLAN", "fg-x", BigDecimal.ONE, "盒", 7L, "finishedBatch:fg-x", null);

            assertThat(outcome).isEqualTo(LineageRecordOutcome.AMBIGUOUS);
            assertThat(store).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("缺口 5: web 半成品投料写 WIP_CONSUME")
    class SemiFeed {

        private SemiFeedLineageRecorder feed;

        @BeforeEach
        void build() {
            feed = new SemiFeedLineageRecorder(recorder,
                    new SemiSourceBatchResolver(sfiRepository, productionBatchRepository, taskRepository));
        }

        @Test
        @DisplayName("来源行带 batch_id ⇒ 写 来源批 → 目标批, 单位取来源行")
        void resolvedSourceWritesEdge() {
            when(sfiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, "SEMI-1"))
                    .thenReturn(Optional.of(SemiFinishedInventory.builder()
                            .factoryId(FACTORY).intermediateBatchNo("SEMI-1").batchId(301L).unit("盒").build()));

            LineageRecordOutcome outcome = feed.recordSemiFeed(FACTORY, "SEMI-1", 555L, new BigDecimal("2"),
                    null, 7L, "settleSfiFeed:P:row1:SEMI-1", "SETTLEMENT_SFI_FEED");

            assertThat(outcome).isEqualTo(LineageRecordOutcome.INSERTED);
            assertThat(store).singleElement().satisfies(e -> {
                assertThat(e.getEdgeType()).isEqualTo(LineageEdgeRecorder.EDGE_WIP_CONSUME);
                assertThat(e.getSourceId()).isEqualTo("301");
                assertThat(e.getTargetId()).isEqualTo("555");
                assertThat(e.getUnit()).isEqualTo("盒");
                assertThat(e.getMeta()).containsEntry("origin", "SETTLEMENT_SFI_FEED");
            });
        }

        @Test
        @DisplayName("池化锚解析不了 ⇒ AMBIGUOUS, 不写 (⛔ 不拿目标批次顶替)")
        void unresolvedSourceWritesNothing() {
            when(sfiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, "CLK-SEMI-a-b"))
                    .thenReturn(Optional.of(SemiFinishedInventory.builder()
                            .factoryId(FACTORY).intermediateBatchNo("CLK-SEMI-a-b").build()));

            LineageRecordOutcome outcome = feed.recordSemiFeed(FACTORY, "CLK-SEMI-a-b", 555L, BigDecimal.ONE,
                    null, 7L, "k", "INTERIM_SETTLE_SFI_FEED");

            assertThat(outcome).isEqualTo(LineageRecordOutcome.AMBIGUOUS);
            assertThat(store).isEmpty();
        }

        @Test
        @DisplayName("纯 SFI 道没有目标生产批 ⇒ SKIPPED (与「解析不了」分开数)")
        void missingTargetIsSkipped() {
            LineageRecordOutcome outcome = feed.recordSemiFeed(FACTORY, "SEMI-1", null, BigDecimal.ONE,
                    null, 7L, "k", "INTERIM_SETTLE_SFI_FEED");

            assertThat(outcome).isEqualTo(LineageRecordOutcome.SKIPPED);
            assertThat(store).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("缺口 6: 回填 PRODUCTION_TO_WIP + App 报工领料")
    class Backfill {

        private LineageBackfillService service;

        @BeforeEach
        void build() {
            service = new LineageBackfillService(recorder);
            ReflectionTestUtils.setField(service, "entityManager", entityManager);
        }

        @Test
        @DisplayName("WIP 库存批: 有效来源新增 / 来源批次不存在跳过; 重跑全部已存在; 键与在线同一个")
        void wipStockBackfillIsIdempotentAndSkipsDanglingSource() {
            stubQuery(MaterialBatch.class, List.of(wip("wip-1", "10923"), wip("wip-2", "99999")));
            stubQuery(Long.class, List.of(10923L));   // 本厂只有 10923 存在

            LineageBackfillService.Counters dry = service.backfillProductionToWipStock(FACTORY, true);
            assertThat(dry.getInserted()).isEqualTo(1);
            assertThat(dry.getSkipped()).isEqualTo(1);
            assertThat(store).as("dry-run 零写").isEmpty();

            LineageBackfillService.Counters first = service.backfillProductionToWipStock(FACTORY, false);
            assertThat(first.getInserted()).isEqualTo(1);
            assertThat(first.getSkipped()).isEqualTo(1);
            assertThat(store).singleElement().satisfies(e -> {
                assertThat(e.getEdgeType()).isEqualTo(LineageEdgeRecorder.EDGE_PRODUCTION_TO_WIP);
                assertThat(e.getSourceId()).isEqualTo("10923");
                assertThat(e.getTargetId()).isEqualTo("wip-1");
                assertThat(e.getMeta()).containsEntry(LineageEdgeRecorder.META_REF_KEY,
                        LineageEdgeRecorder.wipStockRefKey("wip-1"));
            });

            LineageBackfillService.Counters second = service.backfillProductionToWipStock(FACTORY, false);
            assertThat(second.getInserted()).isZero();
            assertThat(second.getAlreadyExists()).isEqualTo(1);
            assertThat(store).hasSize(1);
        }

        @Test
        @DisplayName("App 报工领料: 同批多条引用求和成一条边; 已驳回报工跳过; 重跑已存在")
        void reportMaterialBackfill() {
            ProductionReport live = report(501L, 10930L, "PENDING",
                    List.of(ref("mb-1", "3"), ref("mb-1", "2"), ref("mb-2", "4")));
            ProductionReport rejected = report(502L, 10930L, "REJECTED", List.of(ref("mb-3", "1")));
            ProductionReport noRefs = report(503L, 10930L, "APPROVED", null);
            stubQuery(ProductionReport.class, List.of(live, rejected, noRefs));

            LineageBackfillService.Counters first = service.backfillReportMaterialToProduction(FACTORY, false);
            assertThat(first.getScanned()).as("无引用的报工不是样本, 不计数").isEqualTo(3);
            assertThat(first.getInserted()).isEqualTo(2);
            assertThat(first.getSkipped()).as("已驳回的那一条").isEqualTo(1);
            BatchLineageEdge mb1 = store.stream().filter(e -> e.getSourceId().equals("mb-1")).findFirst().orElseThrow();
            assertThat(mb1.getQuantityUsed()).isEqualByComparingTo("5");
            assertThat(mb1.getTargetId()).isEqualTo("10930");
            assertThat(mb1.getMeta()).containsEntry(LineageEdgeRecorder.META_REF_KEY,
                    LineageEdgeRecorder.reportMaterialRefKey(501L, "mb-1"));

            LineageBackfillService.Counters second = service.backfillReportMaterialToProduction(FACTORY, false);
            assertThat(second.getInserted()).isZero();
            assertThat(second.getAlreadyExists()).isEqualTo(2);
        }

        @Test
        @DisplayName("成品回填带批号线索: 与在线逐行入库同一条对齐规则")
        void finishedBackfillUsesBatchNumberHint() {
            when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY, "plan-A"))
                    .thenReturn(List.of(pb(10922L, "plan-A"), pb(10927L, "plan-A")));
            when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "CLK-B-20260903-624"))
                    .thenReturn(Optional.of(pb(10927L, "plan-A")));
            FinishedGoodsBatch fg = new FinishedGoodsBatch();
            fg.setId("fg-624");
            fg.setFactoryId(FACTORY);
            fg.setBatchNumber("CLK-B-20260903-624");
            fg.setProductionPlanId("plan-A");
            fg.setProductTypeId("PT-FIN");
            fg.setProducedQuantity(BigDecimal.ONE);
            fg.setUnit("盒");
            stubQuery(FinishedGoodsBatch.class, List.of(fg));

            LineageBackfillService.Counters c = service.backfillProductionToFinished(FACTORY, false);

            assertThat(c.getInserted()).isEqualTo(1);
            assertThat(c.getAmbiguous()).isZero();
            assertThat(store).singleElement().satisfies(e -> assertThat(e.getSourceId()).isEqualTo("10927"));
        }
    }

    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> void stubQuery(Class<T> type, List<T> rows) {
        TypedQuery<T> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(type))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
    }

    private static ProductionBatch pb(Long id, String planId) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setProductionPlanId(planId);
        b.setProductTypeId("PT-FIN");
        return b;
    }

    private static MaterialBatch wip(String id, String sourceDocId) {
        MaterialBatch m = new MaterialBatch();
        m.setId(id);
        m.setFactoryId(FACTORY);
        m.setBatchNumber("WIP-" + id);
        m.setSourceDocType("PRODUCTION_BATCH");
        m.setSourceDocId(sourceDocId);
        m.setReceiptQuantity(new BigDecimal("8"));
        m.setQuantityUnit("kg");
        m.setCreatedBy(7L);
        return m;
    }

    private static Map<String, Object> ref(String mbId, String qty) {
        return Map.of("materialBatchId", mbId, "quantity", new BigDecimal(qty), "unit", "kg");
    }

    private static ProductionReport report(Long id, Long batchId, String approval, List<Map<String, Object>> refs) {
        ProductionReport r = ProductionReport.builder().id(id).factoryId(FACTORY).batchId(batchId)
                .workerId(7L).materialBatchRefs(refs).build();
        r.setApprovalStatus(approval);
        return r;
    }
}
