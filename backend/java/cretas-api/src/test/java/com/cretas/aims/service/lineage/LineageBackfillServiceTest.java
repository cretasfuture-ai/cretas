package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回填的读数闸 —— 重点不是「能不能写」，而是<b>两个数分不分得开</b>。
 *
 * <p>「新增 0 条」在两种完全不同的情况下长得一模一样：
 * 「已经全在库里」和「一条都没量到」。所以 {@code Counters} 必须分开报，
 * 本测试直接钉这两个数（硬约束 4 / 硬约束 9）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LineageBackfillService — 幂等重跑的两个数分得开")
class LineageBackfillServiceTest {

    private static final String FACTORY = "F006";

    @Mock private BatchLineageEdgeRepository edgeRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private EntityManager entityManager;

    private final List<BatchLineageEdge> store = new ArrayList<>();

    private LineageBackfillService newService() {
        when(edgeRepository.findMatchingEdges(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenAnswer(inv -> store.stream()
                        .filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                                && e.getSourceId().equals(inv.getArgument(3))
                                && e.getTargetId().equals(inv.getArgument(5)))
                        .toList());
        when(edgeRepository.saveAndFlush(any(BatchLineageEdge.class))).thenAnswer(inv -> {
            BatchLineageEdge e = inv.getArgument(0);
            store.add(e);
            return e;
        });
        LineageEdgeRecorder recorder = new LineageEdgeRecorder(new LineageEdgeWriter(edgeRepository),
                new FinishedBatchProductionAligner(productionBatchRepository));
        LineageBackfillService service = new LineageBackfillService(recorder);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        return service;
    }

    @SuppressWarnings("unchecked")
    private void stubConsumptions(List<MaterialConsumption> rows) {
        TypedQuery<MaterialConsumption> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(MaterialConsumption.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
    }

    @Test
    @DisplayName("第一次回填全是新增, 第二次全是已存在 —— 两个数分得开")
    void rerunIsIdempotentAndCountsAreDistinguishable() {
        stubConsumptions(List.of(
                consumption(1, "mb-1", 8967L, new BigDecimal("3")),
                consumption(2, "mb-2", 8967L, new BigDecimal("5"))));
        LineageBackfillService service = newService();

        LineageBackfillService.Counters first = service.backfillRawToProduction(FACTORY, false);
        // 🔴 「必发生」的读数: 没有它, 下面那条 alreadyExists=2 在「一条都没写」时也成立。
        assertThat(first.getInserted()).isEqualTo(2);
        assertThat(first.getAlreadyExists()).isZero();
        assertThat(store).hasSize(2);

        LineageBackfillService.Counters second = service.backfillRawToProduction(FACTORY, false);
        assertThat(second.getInserted()).isZero();
        assertThat(second.getAlreadyExists()).isEqualTo(2);
        assertThat(store).hasSize(2);   // 重跑没有造出第三条边
    }

    @Test
    @DisplayName("负数(冲销)行计入 skipped 而不是 inserted —— 它不是「写失败」")
    void reversalRowsAreCountedAsSkipped() {
        stubConsumptions(List.of(
                consumption(1, "mb-1", 8967L, new BigDecimal("3")),
                consumption(2, "mb-2", 8967L, new BigDecimal("-2"))));
        LineageBackfillService service = newService();

        LineageBackfillService.Counters counters = service.backfillRawToProduction(FACTORY, false);

        assertThat(counters.getScanned()).isEqualTo(2);
        assertThat(counters.getInserted()).isEqualTo(1);
        assertThat(counters.getSkipped()).isEqualTo(1);
        assertThat(counters.getFailed()).isZero();
    }

    @Test
    @DisplayName("dryRun 一条边都不写, 但仍然报出扫到多少行")
    void dryRunWritesNothingButStillReportsScannedCount() {
        stubConsumptions(List.of(
                consumption(1, "mb-1", 8967L, new BigDecimal("3")),
                consumption(2, "mb-2", 8967L, new BigDecimal("5"))));
        LineageBackfillService service = newService();

        LineageBackfillService.Counters counters = service.backfillRawToProduction(FACTORY, true);

        assertThat(counters.getScanned()).isEqualTo(2);
        assertThat(counters.getInserted()).isEqualTo(2);   // 上界, 不是「将新增 2 条」
        assertThat(store).isEmpty();                        // 真的什么都没写
    }

    @SuppressWarnings("unchecked")
    private void stubFinishedBatches(List<FinishedGoodsBatch> rows) {
        TypedQuery<FinishedGoodsBatch> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(FinishedGoodsBatch.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
    }

    @Test
    @DisplayName("🔴 PROD→FIN 的 dry-run 必须跑真实对齐 —— ⛔ 不许无条件报 SKIPPED")
    void productionToFinishedDryRunRunsTheRealAlignment() {
        FinishedGoodsBatch aligned = finishedBatch("fg-1", "plan-1", "PT-A");
        FinishedGoodsBatch ambiguous = finishedBatch("fg-2", "plan-2", "PT-A");
        FinishedGoodsBatch noCarrier = finishedBatch("fg-3", "plan-3", "PT-A");
        stubFinishedBatches(List.of(aligned, ambiguous, noCarrier));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY, "plan-1"))
                .thenReturn(List.of(productionBatch(1L, "PT-A")));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY, "plan-2"))
                .thenReturn(List.of(productionBatch(2L, "PT-A"), productionBatch(3L, "PT-A")));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY, "plan-3"))
                .thenReturn(List.of());
        LineageBackfillService service = newService();

        LineageBackfillService.Counters counters = service.backfillProductionToFinished(FACTORY, true);

        // 原来这一段 dry-run 无条件报 SKIPPED=3 / 新增=0，运维会读成「这一段不会新增边」，
        // 而真跑会插一行。现在三态必须各自落位。
        assertThat(counters.getScanned()).isEqualTo(3);
        assertThat(counters.getInserted()).as("plan-1 唯一对齐 ⇒ 真跑会新增").isEqualTo(1);
        assertThat(counters.getAmbiguous()).as("plan-2 两个同产品批次 ⇒ 对不齐").isEqualTo(1);
        assertThat(counters.getSkipped()).as("plan-3 没有生产批次 ⇒ 无载体").isEqualTo(1);
        // dry-run 仍然零写
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("dry-run 与真跑同口径：已写过的行在 dry-run 里报「已存在」而不是「新增」")
    void dryRunAgreesWithRealRunOnAlreadyExisting() {
        stubConsumptions(List.of(consumption(1, "mb-1", 8967L, new BigDecimal("3"))));
        LineageBackfillService service = newService();

        assertThat(service.backfillRawToProduction(FACTORY, true).getInserted()).isEqualTo(1);
        assertThat(service.backfillRawToProduction(FACTORY, false).getInserted()).isEqualTo(1);
        // 真跑之后再 dry-run：同一行必须翻成「已存在」。
        // ⛔ 如果 dry-run 另写一套判定（原来那样），这条永远是「新增 1」，与事实相反。
        LineageBackfillService.Counters after = service.backfillRawToProduction(FACTORY, true);
        assertThat(after.getInserted()).isZero();
        assertThat(after.getAlreadyExists()).isEqualTo(1);
    }

    private static FinishedGoodsBatch finishedBatch(String id, String planId, String productTypeId) {
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId(id);
        fg.setFactoryId(FACTORY);
        fg.setBatchNumber("FG-" + id);
        fg.setProductionPlanId(planId);
        fg.setProductTypeId(productTypeId);
        fg.setProducedQuantity(new BigDecimal("10"));
        fg.setUnit("盒");
        return fg;
    }

    private static ProductionBatch productionBatch(Long id, String productTypeId) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setProductTypeId(productTypeId);
        return b;
    }

    @Test
    @DisplayName("counters 的 toString 把五个数都打出来（⛔ 不许只报「新增 N」）")
    void countersRenderAllFiveNumbers() {
        stubConsumptions(List.of(consumption(1, "mb-1", 8967L, new BigDecimal("3"))));
        LineageBackfillService.Counters counters = newService().backfillRawToProduction(FACTORY, false);

        String rendered = counters.toString();
        assertThat(rendered)
                .contains("扫描").contains("新增").contains("已存在")
                .contains("跳过").contains("对不齐").contains("失败");
    }

    private static MaterialConsumption consumption(int id, String materialBatchId,
                                                   Long productionBatchId, BigDecimal qty) {
        MaterialConsumption m = new MaterialConsumption();
        m.setId(id);
        m.setFactoryId(FACTORY);
        m.setBatchId(materialBatchId);
        m.setProductionBatchId(productionBatchId);
        m.setQuantity(qty);
        m.setRecordedBy(7L);
        m.setSourceType("PRODUCTION_ISSUE");
        m.setConsumptionTime(LocalDateTime.now());
        return m;
    }

}
