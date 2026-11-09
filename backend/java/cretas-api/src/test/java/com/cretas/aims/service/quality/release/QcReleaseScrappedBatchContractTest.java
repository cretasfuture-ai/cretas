package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Owner 2026-11-09: 质检放行不能因为计划里有一批报废就拒绝整个计划 —— 与计划级放行同一口径,
 * 报废批次只扣它自己的货; 其它「不合格未解除」(FAILED / 返工) 照旧整计划拒绝; 分不清这批货是不是报废批的就不放。
 *
 * <p>计划 P2: 批次 202 已合格, 批次 999 已报废。旧口径下 P2 里任何货都放不出 (countQcFailedInPlans 把 SCRAPPED 算进去)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检放行: 报废批次只扣它自己的货")
class QcReleaseScrappedBatchContractTest {

    private static final String F = "F-QRS";

    @Mock private SemiFinishedInventoryRepository semiRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private FinishedGoodsBatchRepository fgRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private QcReleaseRecordRepository recordRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private BatchLineageEdgeRepository edgeRepo;

    private final List<ProductionBatch> batches = new ArrayList<>();
    private final List<BatchLineageEdge> edges = new ArrayList<>();
    private QcReleaseService service;

    @BeforeEach
    void setUp() {
        service = new QcReleaseService(semiRepo, materialBatchRepo, fgRepo, productTypeRepo, recordRepo);
        service.setSemiTxnRepo(txnRepo);
        service.setProductionBatchRepo(productionBatchRepo);
        service.setBatchLineageEdgeRepository(edgeRepo);
        when(recordRepo.save(any(QcReleaseRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(semiRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fgRepo.save(any(FinishedGoodsBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        pb(202L, "P2", QualityStatus.PASSED);
        pb(999L, "P2", QualityStatus.SCRAPPED);
        pb(101L, "P1", QualityStatus.PASSED);

        when(productionBatchRepo.findByIdAndFactoryId(any(), eq(F))).thenAnswer(inv -> batches.stream()
                .filter(b -> b.getId().equals(inv.getArgument(0))).findFirst());
        when(productionBatchRepo.findPlanIdsByIds(eq(F), any())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(1);
            return batches.stream().filter(b -> ids.contains(b.getId()))
                    .map(ProductionBatch::getProductionPlanId).distinct().toList();
        });
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(eq(F), anyString())).thenAnswer(inv -> batches
                .stream().filter(b -> b.getProductionPlanId().equals(inv.getArgument(1))).toList());
        // 与真实查询同一集合: isDispositionLocked 去掉 SCRAPPED
        when(productionBatchRepo.countUnclearedNotScrappedInPlans(eq(F), any(), anyString())).thenAnswer(inv -> {
            Collection<?> plans = inv.getArgument(1);
            return batches.stream().filter(b -> plans.contains(b.getProductionPlanId())
                    && b.getQualityStatus().isDispositionLocked()
                    && b.getQualityStatus() != QualityStatus.SCRAPPED).count();
        });
        when(productionBatchRepo.findScrappedInPlans(eq(F), any(), anyString())).thenAnswer(inv -> {
            Collection<?> plans = inv.getArgument(1);
            return batches.stream().filter(b -> plans.contains(b.getProductionPlanId())
                    && b.getQualityStatus() == QualityStatus.SCRAPPED).map(QcReleaseScrappedBatchContractTest::ref).toList();
        });
        when(edgeRepo.findByFactoryIdAndTargetIdIn(eq(F), any())).thenAnswer(inv -> {
            Collection<?> targets = inv.getArgument(1);
            return edges.stream().filter(e -> targets.contains(e.getTargetId())).toList();
        });
    }

    // ==================== 在制批次 ====================

    @Test
    @DisplayName("复现旧口径: 来源批次 202 已合格, 同计划 999 报废 —— 现在放行成可用 (报废批不挡别的批次)")
    void wipFromHealthyBatchIsReleasedDespiteScrappedSibling() {
        MaterialBatch w = wip("202");
        decide(QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, "W-1");
        assertThat(w.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("阴性: 在制批次出自报废批 999 —— 拒绝, 仍质检中")
    void wipFromScrappedBatchStaysHeld() {
        MaterialBatch w = wip("999");
        assertThatThrownBy(() -> decide(QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, "W-1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(QcReleaseService.CODE_BLOCKED_BY_SCRAPPED_BATCH));
        assertThat(w.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
    }

    @Test
    @DisplayName("其它检查不变: 同计划还有 FAILED 批次 (不是报废) —— 照旧整计划拒绝")
    void failedSiblingStillBlocksTheWholePlan() {
        pb(303L, "P2", QualityStatus.FAILED);
        MaterialBatch w = wip("202");
        assertThatThrownBy(() -> decide(QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, "W-1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(QcReleaseService.CODE_BLOCKED_BY_QC_FAILED));
        assertThat(w.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
    }

    // ==================== 半成品行 ====================

    @Test
    @DisplayName("半成品行: 待检来自 202 —— 放行; 待检来自报废的 999 —— 拒绝且仍待检")
    void semiFollowsTheSourceBatches() {
        SemiFinishedInventory healthy = semi(List.of(202L));
        decide(QcReleaseRecord.TargetType.SEMI_FINISHED, "51");
        assertThat(healthy.getInspectionStatus()).isNull();

        SemiFinishedInventory fromScrapped = semi(List.of(202L, 999L));
        assertThatThrownBy(() -> decide(QcReleaseRecord.TargetType.SEMI_FINISHED, "51"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(QcReleaseService.CODE_BLOCKED_BY_SCRAPPED_BATCH));
        assertThat(fromScrapped.getInspectionStatus()).isEqualTo(SemiFinishedInventory.InspectionStatus.PENDING);
    }

    // ==================== 成品 ====================

    @Test
    @DisplayName("成品: 血缘指到合格的 202 —— 放行成可售")
    void finishedGoodsFromHealthyBatchAreReleased() {
        FinishedGoodsBatch f = fg("FG-P2-S1");
        edge(202L, f.getId());
        decide(QcReleaseRecord.TargetType.FINISHED_GOODS, f.getId());
        assertThat(f.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    /** #1548 第二轮审查 7: 两条认定路各钉一条, 去掉任何一条都要红。这条只走「血缘指到报废批」。 */
    @Test
    @DisplayName("阴性: 血缘指到报废批 999 (批号与它不同) —— 拒绝, 仍待检")
    void finishedGoodsTracedByLineageToScrappedBatchStayHeld() {
        FinishedGoodsBatch f = fg("FG-P2-S9");
        edge(999L, f.getId());
        assertThatThrownBy(() -> decide(QcReleaseRecord.TargetType.FINISHED_GOODS, f.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(QcReleaseService.CODE_BLOCKED_BY_SCRAPPED_BATCH));
        assertThat(f.getStatus()).isEqualTo(FinishedGoodsBatch.Status.PENDING_INSPECTION);
    }

    /** 这条只走「成品批号就是报废批批号」: 血缘指到合格的 202, 归属判据认的是 202。召回冻结认的也是批号, 同一口径。 */
    @Test
    @DisplayName("阴性: 成品批号就是报废批的批号 (血缘却指到 202) —— 仍按报废批的货拒绝")
    void finishedGoodsWithScrappedBatchNumberStayHeld() {
        FinishedGoodsBatch f = fg("PB-999");
        edge(202L, f.getId());
        assertThatThrownBy(() -> decide(QcReleaseRecord.TargetType.FINISHED_GOODS, f.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(QcReleaseService.CODE_BLOCKED_BY_SCRAPPED_BATCH));
        assertThat(f.getStatus()).isEqualTo(FinishedGoodsBatch.Status.PENDING_INSPECTION);
    }

    /** #1548 第二轮审查 2: 一笔来自 202 (10), 一笔没记批次 (10), 同计划 999 报废 —— 没记的那笔可能就是 999 的, 不放。 */
    @Test
    @DisplayName("半成品: 有一笔待检没记生产批次、计划有报废批 —— 分不清, 不放, 仍待检")
    void semiWithAnUnattributedLotIsNotGuessed() {
        SemiFinishedInventory s = semi(List.of());
        SemiQcHoldLots lots = org.mockito.Mockito.mock(SemiQcHoldLots.class);
        when(lots.openLots(F, 51L)).thenReturn(List.of(lot(202L, "10"), lot(null, "10")));
        service.setSemiQcHoldLots(lots);

        assertThatThrownBy(() -> decide(QcReleaseRecord.TargetType.SEMI_FINISHED, "51"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_OWNERSHIP_UNCLEAR);
                    assertThat(e.getMessage()).contains("没记来自哪个生产批次");
                });
        assertThat(s.getInspectionStatus()).isEqualTo(SemiFinishedInventory.InspectionStatus.PENDING);
    }

    @Test
    @DisplayName("阳性对照: 两笔都记了批次 (都是 202) —— 同一行照常放行")
    void semiWithAllLotsAttributedIsReleased() {
        SemiFinishedInventory s = semi(List.of());
        SemiQcHoldLots lots = org.mockito.Mockito.mock(SemiQcHoldLots.class);
        when(lots.openLots(F, 51L)).thenReturn(List.of(lot(202L, "10"), lot(202L, "10")));
        service.setSemiQcHoldLots(lots);

        decide(QcReleaseRecord.TargetType.SEMI_FINISHED, "51");
        assertThat(s.getInspectionStatus()).isNull();
    }

    private static com.cretas.aims.entity.quality.SemiQcHoldLot lot(Long productionBatchId, String qty) {
        com.cretas.aims.entity.quality.SemiQcHoldLot lot = new com.cretas.aims.entity.quality.SemiQcHoldLot();
        lot.setProductionBatchId(productionBatchId);
        lot.setQuantityOpen(new BigDecimal(qty));
        return lot;
    }

    @Test
    @DisplayName("分不清: 没有血缘、批号对不上、计划不止一批 —— 不放并说清原因 (不猜)")
    void unattributableFinishedGoodsAreNotGuessed() {
        FinishedGoodsBatch f = fg("FG-P2-UNKNOWN");
        assertThatThrownBy(() -> decide(QcReleaseRecord.TargetType.FINISHED_GOODS, f.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_OWNERSHIP_UNCLEAR);
                    assertThat(e.getMessage()).contains("分不清");
                });
        assertThat(f.getStatus()).isEqualTo(FinishedGoodsBatch.Status.PENDING_INSPECTION);
    }

    // ==================== 夹具 ====================

    private void decide(String type, String id) {
        service.decide(F, new QcReleaseService.DecisionCommand(type, id, "RELEASED", "复检合格"), 7L, "质检员");
    }

    private static ProductionBatchRepository.ScrappedBatchRef ref(ProductionBatch b) {
        return new ProductionBatchRepository.ScrappedBatchRef() {
            @Override
            public Long getId() {
                return b.getId();
            }

            @Override
            public String getBatchNumber() {
                return b.getBatchNumber();
            }
        };
    }

    private void pb(long id, String plan, QualityStatus status) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(F);
        b.setBatchNumber("PB-" + id);
        b.setProductionPlanId(plan);
        b.setQualityStatus(status);
        batches.add(b);
    }

    private MaterialBatch wip(String sourceBatchId) {
        MaterialBatch w = new MaterialBatch();
        w.setId("W-1");
        w.setFactoryId(F);
        w.setBatchNumber("WIP-CLK-W-1");
        w.setSourceDocType(QcInspectionGuard.WIP_SOURCE_DOC_TYPE);
        w.setSourceDocId(sourceBatchId);
        w.setStatus(MaterialBatchStatus.INSPECTING);
        w.setReceiptQuantity(BigDecimal.TEN);
        w.setUsedQuantity(BigDecimal.ZERO);
        w.setReservedQuantity(BigDecimal.ZERO);
        when(materialBatchRepo.findByIdAndFactoryIdForUpdate("W-1", F)).thenReturn(Optional.of(w));
        return w;
    }

    private SemiFinishedInventory semi(List<Long> postedBatches) {
        SemiFinishedInventory s = SemiFinishedInventory.builder()
                .id(51L).factoryId(F).intermediateBatchNo("SEMI-QRS").unit("kg")
                .producedQuantity(new BigDecimal("50")).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("50")).status(SemiFinishedInventory.Status.AVAILABLE)
                .inspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING)
                .inspectionPendingQuantity(new BigDecimal("20")).build();
        when(semiRepo.findByIdAndFactoryIdForUpdate(51L, F)).thenReturn(Optional.of(s));
        when(semiRepo.findLiveProductionBatchIdsPostedToSemiSince(eq(F), eq(51L), any())).thenReturn(postedBatches);
        return s;
    }

    private FinishedGoodsBatch fg(String batchNumber) {
        FinishedGoodsBatch f = new FinishedGoodsBatch();
        f.setId("FG-" + batchNumber);
        f.setFactoryId(F);
        f.setBatchNumber(batchNumber);
        f.setProductTypeId("SKU-1");
        f.setProductionPlanId("P2");
        f.setProducedQuantity(BigDecimal.TEN);
        f.setShippedQuantity(BigDecimal.ZERO);
        f.setReservedQuantity(BigDecimal.ZERO);
        f.setUnit("kg");
        f.setStatus(FinishedGoodsBatch.Status.PENDING_INSPECTION);
        when(fgRepo.findByIdAndFactoryIdForUpdate(f.getId(), F)).thenReturn(Optional.of(f));
        return f;
    }

    private void edge(Long productionBatchId, String finishedGoodsId) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(F);
        e.setEdgeType(LineageEdgeRecorder.EDGE_PRODUCTION_TO_FINISHED);
        e.setSourceType(LineageEdgeRecorder.NODE_PRODUCTION_BATCH);
        e.setSourceId(String.valueOf(productionBatchId));
        e.setTargetType(LineageEdgeRecorder.NODE_FINISHED_BATCH);
        e.setTargetId(finishedGoodsId);
        edges.add(e);
    }
}
