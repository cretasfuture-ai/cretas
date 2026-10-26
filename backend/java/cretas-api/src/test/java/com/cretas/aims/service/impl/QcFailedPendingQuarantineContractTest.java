package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.service.quality.release.QcReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 缺口 11 审查中 5 —— 质检判 FAILED 的计划, 它的待检成品不能事后被放行成可售。两处都做:
 * <ol>
 *   <li>质检失败隔离 ({@code QualityInspectionServiceImpl} 提交质检的真实入口) 把<b>待检</b>成品也隔离成 DEFECTIVE;</li>
 *   <li>质检放行 ({@link QcReleaseService#decide}) 放行成品前查同计划有没有 FAILED 的生产批次, 有就拒。</li>
 * </ol>
 * 阴性对照: PASS 的质检不动待检成品; 计划没有 FAILED 批次时放行照常成功。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检失败 → 待检成品隔离 + 放行被拒")
class QcFailedPendingQuarantineContractTest {

    private static final String FACTORY = "F-QCF";
    private static final Long BATCH_ID = 42L;
    private static final String PLAN_ID = "PLAN-QCF";

    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private SemiFinishedInventoryRepository semiRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private QcReleaseRecordRepository recordRepo;

    @InjectMocks
    private QualityInspectionServiceImpl inspectionService;

    private ProductionBatch batch;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(inspectionService, "productionBatchRepository", productionBatchRepository);
        ReflectionTestUtils.setField(inspectionService, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        when(qualityInspectionRepository.save(any(QualityInspection.class))).thenAnswer(inv -> inv.getArgument(0));
        batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setProductionPlanId(PLAN_ID);
        when(productionBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
    }

    private static FinishedGoodsBatch fg(String id, String status) {
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId(id);
        fg.setBatchNumber("FGN-" + id);
        fg.setFactoryId(FACTORY);
        fg.setProductionPlanId(PLAN_ID);
        fg.setProductTypeId("PT-1");
        fg.setProducedQuantity(BigDecimal.TEN);
        fg.setShippedQuantity(BigDecimal.ZERO);
        fg.setReservedQuantity(BigDecimal.ZERO);
        fg.setUnit("kg");
        fg.setStatus(status);
        return fg;
    }

    private static QualityInspection inspection(String result) {
        QualityInspection qi = new QualityInspection();
        qi.setProductionBatchId(BATCH_ID);
        qi.setResult(result);
        qi.setPassRate(new BigDecimal("50.0"));
        return qi;
    }

    @Nested
    @DisplayName("质检失败隔离")
    class Quarantine {

        @Test
        @DisplayName("FAIL → 待检成品也隔离成 DEFECTIVE (与可售的一样)")
        void failQuarantinesPendingInspection() {
            FinishedGoodsBatch pending = fg("PEND", FinishedGoodsBatch.Status.PENDING_INSPECTION);
            FinishedGoodsBatch sellable = fg("AVAIL", FinishedGoodsBatch.Status.AVAILABLE);
            FinishedGoodsBatch depleted = fg("DEPL", FinishedGoodsBatch.Status.DEPLETED);
            when(finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN_ID))
                    .thenReturn(List.of(pending, sellable, depleted));

            inspectionService.createInspection(FACTORY, inspection("FAIL"));

            assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
            assertThat(pending.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
            assertThat(sellable.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
            assertThat(depleted.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEPLETED);
        }

        @Test
        @DisplayName("阴性对照: PASS → 待检成品保持待检 (仍由质检放行决定)")
        void passLeavesPendingAlone() {
            FinishedGoodsBatch pending = fg("PEND", FinishedGoodsBatch.Status.PENDING_INSPECTION);
            when(finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN_ID))
                    .thenReturn(List.of(pending));
            inspectionService.createInspection(FACTORY, inspection("PASS"));
            assertThat(pending.getStatus()).isEqualTo(FinishedGoodsBatch.Status.PENDING_INSPECTION);
        }
    }

    @Nested
    @DisplayName("放行前查同计划质检结论")
    class ReleaseCheck {

        private QcReleaseService releaseService;
        private FinishedGoodsBatch pending;

        @BeforeEach
        void wire() {
            releaseService = new QcReleaseService(semiRepo, materialBatchRepo, finishedGoodsBatchRepository,
                    productTypeRepo, recordRepo);
            releaseService.setProductionBatchRepo(productionBatchRepository);
            pending = fg("PEND", FinishedGoodsBatch.Status.PENDING_INSPECTION);
            when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("PEND", FACTORY))
                    .thenReturn(Optional.of(pending));
            when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(inv -> inv.getArgument(0));
            when(recordRepo.save(any(QcReleaseRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        private void release() {
            releaseService.decide(FACTORY, new QcReleaseService.DecisionCommand(
                    QcReleaseRecord.TargetType.FINISHED_GOODS, "PEND", "RELEASED", "出厂检验合格"), 7L, null);
        }

        @Test
        @DisplayName("同计划有生产批次质检 FAILED → 放行被拒 (QC_RELEASE_BLOCKED_BY_QC_FAILED), 仍待检")
        void failedPlanBlocksRelease() {
            batch.setQualityStatus(QualityStatus.FAILED);
            when(productionBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN_ID))
                    .thenReturn(List.of(batch));
            assertThatThrownBy(this::release)
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_BLOCKED_BY_QC_FAILED));
            assertThat(pending.getStatus()).isEqualTo(FinishedGoodsBatch.Status.PENDING_INSPECTION);
        }

        @Test
        @DisplayName("阴性对照: 同计划批次质检 PASSED → 放行成功")
        void passedPlanReleases() {
            batch.setQualityStatus(QualityStatus.PASSED);
            when(productionBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN_ID))
                    .thenReturn(List.of(batch));
            release();
            assertThat(pending.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        }
    }
}
