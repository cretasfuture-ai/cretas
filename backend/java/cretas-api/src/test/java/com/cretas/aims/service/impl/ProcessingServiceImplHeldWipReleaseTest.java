package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlanBatchUsage;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.BatchEquipmentUsageRepository;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SystemLogRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.quality.release.MaterialBatchHoldService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * #1689 独立终审 B1: 生产预留的批次被冻结 / 改质检中, 扣留期间工序完工 (或取消) ——
 * 真实的在制释放入口 ({@code releaseLinkedMaterialBatchesFromWip}) 原来只翻状态仍是生产预留的批次, 被扣留的这批被跳过;
 * 放行后又还原成生产预留, 此后没有任何路径再翻回来 (搁死在在制列表, 按可用筛的领料 / FEFO / 调拨都看不到)。
 * 现在释放入口把扣留记录改成离开在制后的状态, 放行就回到那个状态。
 */
@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplHeldWipReleaseTest {

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private EquipmentAlertRepository equipmentAlertRepository;
    @Mock private BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SystemLogRepository systemLogRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private BatchWorkSessionRepository batchWorkSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AIAnalysisService aiAnalysisService;
    @Mock private CacheService cacheService;
    @Mock private ProcessingStageRecordService processingStageRecordService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;

    @InjectMocks
    private ProcessingServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String PLAN = "QA-PLAN-B1";

    @Test
    @DisplayName("生产预留 → 冻结 → 工序完工 → 放行 = 可用 (不再还原成生产预留)")
    void heldWipBatchReleasedAfterProcessCompletesBecomesAvailable() {
        MaterialBatch b = linked(batch(MaterialBatchStatus.PRODUCING_RESERVED, "50", "20", "0"));
        MaterialBatchHoldService.hold(b);
        assertThat(b.getHoldPriorStatus()).isEqualTo(MaterialBatchStatus.PRODUCING_RESERVED);

        exitWip();

        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);   // 扣留期间状态不动
        assertThat(b.getHoldPriorStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
        assertThat(MaterialBatchHoldService.releaseTarget(b, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("生产预留 → 冻结 → 工序完工, 仍有预留 → 放行回已预留; 已领完 → 已耗尽")
    void heldWipBatchExitRecomputesByQuantity() {
        MaterialBatch reserved = linked(batch(MaterialBatchStatus.PRODUCING_RESERVED, "50", "20", "10"));
        MaterialBatchHoldService.hold(reserved);
        exitWip();
        assertThat(MaterialBatchHoldService.releaseTarget(reserved, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.RESERVED);

        MaterialBatch drained = linked(batch(MaterialBatchStatus.PRODUCING_RESERVED, "50", "30", "20"));
        MaterialBatchHoldService.hold(drained);
        exitWip();
        assertThat(MaterialBatchHoldService.releaseTarget(drained, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.DEPLETED);
    }

    @Test
    @DisplayName("阳性对照: 冻结期间工序没完工 (在制关联仍在) → 放行还原成生产预留, 完工时由正常释放翻回")
    void heldWipBatchReleasedWhileStillInProcessStaysProducingReserved() {
        MaterialBatch b = batch(MaterialBatchStatus.PRODUCING_RESERVED, "50", "20", "0");
        MaterialBatchHoldService.hold(b);
        assertThat(MaterialBatchHoldService.releaseTarget(b, MaterialBatchStatus.AVAILABLE))
                .isEqualTo(MaterialBatchStatus.PRODUCING_RESERVED);
        // 放行后完工: 走原来的生产预留释放
        b.setStatus(MaterialBatchStatus.PRODUCING_RESERVED);
        linked(b);
        exitWip();
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("已预留 → 冻结 → 开工: 扣留记录跟进在制 (状态仍是质检中), 放行回生产预留")
    void heldReservedBatchFollowsProcessStart() {
        MaterialBatch b = linked(batch(MaterialBatchStatus.RESERVED, "50", "0", "10"));
        MaterialBatchHoldService.hold(b);
        ReflectionTestUtils.invokeMethod(service, "transitionLinkedMaterialBatchesToWip", FACTORY, PLAN, 1L);
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        assertThat(b.getHoldPriorStatus()).isEqualTo(MaterialBatchStatus.PRODUCING_RESERVED);
    }

    private void exitWip() {
        ReflectionTestUtils.invokeMethod(service, "releaseLinkedMaterialBatchesFromWip", FACTORY, PLAN, 1L);
    }

    private MaterialBatch linked(MaterialBatch b) {
        ProductionPlanBatchUsage usage = new ProductionPlanBatchUsage();
        usage.setMaterialBatchId(b.getId());
        when(productionPlanBatchUsageRepository.findByProductionPlanId(PLAN)).thenReturn(List.of(usage));
        when(materialBatchRepository.findByIdAndFactoryId(b.getId(), FACTORY)).thenReturn(Optional.of(b));
        lenient().when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        return b;
    }

    private static MaterialBatch batch(MaterialBatchStatus status, String receipt, String used, String reserved) {
        MaterialBatch b = new MaterialBatch();
        b.setId("QA-MB-" + status.name() + "-" + receipt + used + reserved);
        b.setFactoryId(FACTORY);
        b.setBatchNumber("QA-B1-" + status.name());
        b.setReceiptQuantity(new BigDecimal(receipt));
        b.setUsedQuantity(new BigDecimal(used));
        b.setReservedQuantity(new BigDecimal(reserved));
        b.setStatus(status);
        return b;
    }
}
