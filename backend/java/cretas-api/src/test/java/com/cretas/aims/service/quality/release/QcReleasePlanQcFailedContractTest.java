package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 缺口 11 第二轮审查 M2 —— 同计划有生产批次质检 FAILED 时, 半成品行和在制批次也不能放行 (与成品同一口径)。
 *
 * <p>半成品行看的是入过这一行的全部生产批次的计划 (行上 batchId + 入库流水反查; 逐道录入锚号按计划前缀);
 * 在制批次看它物化来源批次的计划。判不合格不受影响。计数查询按桩里的「哪些计划有 FAILED 批次」作答,
 * 查询本身另由 H2 上的仓储测试执行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检 FAILED 的计划: 半成品行 / 在制批次也不能放行")
class QcReleasePlanQcFailedContractTest {

    private static final String F = "F-QPF";

    @Mock private SemiFinishedInventoryRepository semiRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private FinishedGoodsBatchRepository fgRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private QcReleaseRecordRepository recordRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;

    private QcReleaseService service;
    /** 有质检 FAILED 批次的计划。 */
    private final Set<String> failedPlans = new HashSet<>();

    private static ProductionBatch pb(long id, String plan) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(F);
        b.setBatchNumber("PB-" + id);
        b.setProductionPlanId(plan);
        return b;
    }

    @BeforeEach
    void setUp() {
        service = new QcReleaseService(semiRepo, materialBatchRepo, fgRepo, productTypeRepo, recordRepo);
        service.setSemiTxnRepo(txnRepo);
        service.setProductionBatchRepo(productionBatchRepo);
        when(recordRepo.save(any(QcReleaseRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(semiRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionBatchRepo.findByIdAndFactoryId(101L, F)).thenReturn(Optional.of(pb(101L, "P1")));
        when(productionBatchRepo.findByIdAndFactoryId(202L, F)).thenReturn(Optional.of(pb(202L, "P2")));
        when(productionBatchRepo.countQcFailedInPlans(eq(F), any(), anyString())).thenAnswer(inv -> {
            Collection<?> plans = inv.getArgument(1);
            String prefixPattern = inv.getArgument(2);
            long n = failedPlans.stream().filter(p -> plans.contains(p)
                    || (prefixPattern.endsWith("%") && p.startsWith(prefixPattern.substring(0, prefixPattern.length() - 1))))
                    .count();
            return n;
        });
    }

    private SemiFinishedInventory semi(String no, Long batchId) {
        SemiFinishedInventory s = SemiFinishedInventory.builder()
                .id(51L).factoryId(F).intermediateBatchNo(no).batchId(batchId).unit("kg")
                .producedQuantity(new BigDecimal("50")).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("50")).status(SemiFinishedInventory.Status.AVAILABLE)
                .inspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING)
                .inspectionPendingQuantity(new BigDecimal("20")).build();
        when(semiRepo.findByIdAndFactoryIdForUpdate(51L, F)).thenReturn(Optional.of(s));
        return s;
    }

    private QcReleaseRecord decide(String type, String id, String decision) {
        return service.decide(F, new QcReleaseService.DecisionCommand(type, id, decision, "结论"), 7L, null);
    }

    @Test
    @DisplayName("半成品行: 入过账的批次 202 所在计划 P2 有 FAILED → 放行被拒 (QC_RELEASE_BLOCKED_BY_QC_FAILED)")
    void semiReleaseBlockedByPostedBatchPlan() {
        SemiFinishedInventory s = semi("SEMI-X", 101L);
        when(semiRepo.findProductionBatchIdsPostedToSemi(F, 51L)).thenReturn(List.of(101L, 202L));
        failedPlans.add("P2");
        assertThatThrownBy(() -> decide(QcReleaseRecord.TargetType.SEMI_FINISHED, "51", "RELEASED"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_BLOCKED_BY_QC_FAILED));
        assertThat(s.getInspectionStatus()).isEqualTo(SemiFinishedInventory.InspectionStatus.PENDING);
    }

    @Test
    @DisplayName("阴性对照: 同一行, 计划都没有 FAILED → 放行成功")
    void semiReleasesWhenNoPlanFailed() {
        SemiFinishedInventory s = semi("SEMI-X", 101L);
        when(semiRepo.findProductionBatchIdsPostedToSemi(F, 51L)).thenReturn(List.of(101L, 202L));
        decide(QcReleaseRecord.TargetType.SEMI_FINISHED, "51", "RELEASED");
        assertThat(s.getInspectionStatus()).isNull();
    }

    @Test
    @DisplayName("逐道录入锚号 CLK-SEMI-<计划前8位>-…: 按计划前缀判, 计划 FAILED → 放行被拒")
    void anchorRowUsesPlanPrefix() {
        semi("CLK-SEMI-PLANABCD-PTSEMI01", null);
        when(semiRepo.findProductionBatchIdsPostedToSemi(F, 51L)).thenReturn(List.of());
        failedPlans.add("PLANABCD-1234-5678");
        assertThatThrownBy(() -> decide(QcReleaseRecord.TargetType.SEMI_FINISHED, "51", "RELEASED"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_BLOCKED_BY_QC_FAILED));
    }

    @Test
    @DisplayName("判不合格不受影响: 计划 FAILED 时半成品照样能判不合格 (转出待检量)")
    void rejectStillAllowed() {
        SemiFinishedInventory s = semi("SEMI-X", 202L);
        when(semiRepo.findProductionBatchIdsPostedToSemi(F, 51L)).thenReturn(List.of(202L));
        failedPlans.add("P2");
        decide(QcReleaseRecord.TargetType.SEMI_FINISHED, "51", "REJECTED");
        assertThat(s.getConsumedQuantity()).isEqualByComparingTo("20");
    }

    private MaterialBatch wip() {
        MaterialBatch w = new MaterialBatch();
        w.setId("W-1");
        w.setFactoryId(F);
        w.setBatchNumber("WIP-CLK-W-1");
        w.setSourceDocType(QcInspectionGuard.WIP_SOURCE_DOC_TYPE);
        w.setSourceDocId("202");
        w.setStatus(MaterialBatchStatus.INSPECTING);
        w.setReceiptQuantity(BigDecimal.TEN);
        w.setUsedQuantity(BigDecimal.ZERO);
        w.setReservedQuantity(BigDecimal.ZERO);
        when(materialBatchRepo.findByIdAndFactoryIdForUpdate("W-1", F)).thenReturn(Optional.of(w));
        return w;
    }

    @Test
    @DisplayName("在制批次: 来源批次 202 所在计划 P2 有 FAILED → 放行被拒, 仍质检中")
    void wipReleaseBlocked() {
        MaterialBatch w = wip();
        failedPlans.add("P2");
        assertThatThrownBy(() -> decide(QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, "W-1", "RELEASED"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_BLOCKED_BY_QC_FAILED));
        assertThat(w.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
    }

    @Test
    @DisplayName("阴性对照: 在制批次所在计划没有 FAILED → 放行成可用")
    void wipReleasesWhenPlanOk() {
        MaterialBatch w = wip();
        failedPlans.add("P1");   // 别的计划 FAILED 不连坐
        decide(QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, "W-1", "RELEASED");
        assertThat(w.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }
}
