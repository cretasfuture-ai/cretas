package com.cretas.aims.service.foodsafety.ccp;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.service.quality.release.QcReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 缺口 13 审查高 3 —— App 半成品行按产品码跨批次累加: 行上的 batchId 只是第一次建行的那个批次。
 * 第二个批次 (另一个计划) 出了 CCP 偏离并把产出入进同一行, 这一行也必须挡放行。
 *
 * <p>判据走<b>产品真实入口</b> {@link QcReleaseService#decide} (放行前查 QcReleaseBlocker = 本 lookup)。
 * 阴性对照: 同一行, 入库流水里只有建行批次 (偏离批次没入过账) → 不挡 —— 证明挡住它的是「反查入账批次」这一步。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CCP · 跨批次累加的半成品行: 任一入账批次的计划有未处理偏离就挡放行")
class CcpSharedSemiDeviationContractTest {

    private static final String F = "F-CSS";

    @Mock private HaccpMonitoringRecordRepository recordRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private SemiFinishedInventoryRepository semiRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private FinishedGoodsBatchRepository fgRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private QcReleaseRecordRepository releaseRecordRepo;

    private CcpOpenDeviationLookup lookup;
    private QcReleaseService releaseService;
    private SemiFinishedInventory shared;

    private static ProductionBatch pb(long id, String no, String plan) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(F);
        b.setBatchNumber(no);
        b.setProductionPlanId(plan);
        return b;
    }

    @BeforeEach
    void setUp() {
        lookup = new CcpOpenDeviationLookup(recordRepo, productionBatchRepo);
        lookup.setSemiRepo(semiRepo);
        releaseService = new QcReleaseService(semiRepo, materialBatchRepo, fgRepo, productTypeRepo, releaseRecordRepo);
        ReflectionTestUtils.setField(releaseService, "releaseBlocker", lookup);

        shared = SemiFinishedInventory.builder()
                .id(41L).factoryId(F).intermediateBatchNo("SEMI-CODE-X").batchId(101L)   // 第一次建行: 批次 101 (计划 P1)
                .producedQuantity(new BigDecimal("50")).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("50")).unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .inspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING)
                .inspectionPendingQuantity(new BigDecimal("20"))
                .build();
        when(semiRepo.findByIdAndFactoryIdForUpdate(41L, F)).thenReturn(Optional.of(shared));
        when(productionBatchRepo.findByIdAndFactoryId(101L, F)).thenReturn(Optional.of(pb(101L, "PB-101", "P1")));
        when(productionBatchRepo.findByIdAndFactoryId(202L, F)).thenReturn(Optional.of(pb(202L, "PB-202", "P2")));
        // 只有计划 P2 有未处理偏离; 批号维度无偏离
        when(recordRepo.countOpenDeviationsByBatchNumbers(eq(F), anyCollection())).thenReturn(0L);
        when(recordRepo.countOpenDeviationsByPlanIdPrefix(eq(F), anyString())).thenReturn(0L);
        when(recordRepo.countOpenDeviationsByPlanIds(eq(F), anyCollection())).thenAnswer(inv -> {
            Collection<?> plans = inv.getArgument(1);
            return plans.contains("P2") ? 1L : 0L;
        });
        when(releaseRecordRepo.save(any(QcReleaseRecord.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void release() {
        releaseService.decide(F, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.SEMI_FINISHED, "41", "RELEASED", "复检合格"), 7L, null);
    }

    @Test
    @DisplayName("偏离批次 202 (计划 P2) 入过这一行 → 放行被拒 (QC_RELEASE_BLOCKED_BY_CCP_DEVIATION)")
    void deviationOfAnotherPostedBatchBlocksRelease() {
        when(semiRepo.findProductionBatchIdsPostedToSemi(F, 41L)).thenReturn(List.of(101L, 202L));
        long n = lookup.openDeviationsForSemi(F, shared);
        System.out.println("SHARED_SEMI_OPEN_DEVIATIONS=" + n);
        assertThat(n).isPositive();
        assertThatThrownBy(this::release)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_BLOCKED_BY_CCP));
        assertThat(shared.getInspectionStatus()).isEqualTo(SemiFinishedInventory.InspectionStatus.PENDING);
    }

    @Test
    @DisplayName("阴性对照: 入库流水里只有建行批次 101 (P1 无偏离) → 不挡, 放行成功")
    void onlyTheCreatingBatchPostedReleases() {
        when(semiRepo.findProductionBatchIdsPostedToSemi(F, 41L)).thenReturn(List.of(101L));
        assertThat(lookup.openDeviationsForSemi(F, shared)).isZero();
        when(semiRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        release();
        assertThat(shared.getInspectionStatus()).isNull();
    }
}
