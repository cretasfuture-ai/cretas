package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.production.ForceInsertApproverDescriber;
import com.cretas.aims.service.production.PendingApprovalStartGuard;
import com.cretas.aims.service.shortage.ShortageStartGate;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.*;

/**
 * 1551-11 红线 1: 批次级开工 ({@link ProcessingServiceImpl#startProduction}) 与计划开工过同一道
 * {@link PendingApprovalStartGuard}。
 *
 * <p>缺陷 (修复前): 手工 / AI 建的批次 (POST /processing/batches 带 productionPlanId) 挂在一张待审批的强制插单上,
 * 对批次调 /batches/{id}/start (App 签到 / 班组报工页 / AI ProcessingBatchStartTool / BatchStatusChangeTool)
 * 只过缺料闸, 审批没批就开工了 —— 而这张插单要到批准那一刻才排进产线、顺延被挤的计划。
 *
 * <p>缺料闸用真实 {@link ShortageStartGate} 且工厂开关打开、带强制开工原因: 审批闸必须在它之前,
 * 否则被拦时缺料实例里已经写进了一条「强制开工」记录 (半截状态)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchStartPendingApprovalGuardTest {

    private static final String F = "F006";

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

    @InjectMocks private ProcessingServiceImpl service;

    private final FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseLineRepository caseLines = mock(ShortageCaseLineRepository.class);
    private final ShortageCaseEventRepository caseEvents = mock(ShortageCaseEventRepository.class);
    private final WorkflowEngineService workflowEngine = mock(WorkflowEngineService.class);
    private final ApprovalWorkflowService approvalWorkflowService = mock(ApprovalWorkflowService.class);

    private ProductionPlan plan;
    private ProductionBatch batch;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "shortageStartGate",
                new ShortageStartGate(settings, cases, caseLines, caseEvents));
        ReflectionTestUtils.setField(service, "forceInsertApproverDescriber",
                new ForceInsertApproverDescriber(workflowEngine, approvalWorkflowService));
        when(workflowEngine.getCurrentInstance(any(), any(), any())).thenReturn(Optional.empty());

        plan = new ProductionPlan();
        plan.setId("PP-QA-URG-1");
        plan.setFactoryId(F);
        plan.setPlanNumber("QA-URG-0001");
        plan.setSourceOrderId("SO-QA-1");
        plan.setStatus(ProductionPlanStatus.PENDING);
        when(productionPlanRepository.findById("PP-QA-URG-1")).thenReturn(Optional.of(plan));

        batch = new ProductionBatch();
        batch.setId(4401L);
        batch.setFactoryId(F);
        batch.setBatchNumber("QA-PB-4401");
        batch.setProductionPlanId("PP-QA-URG-1");   // 手工建批时挂上的计划
        batch.setStatus(ProductionBatchStatus.PLANNED);
        batch.setPlannedQuantity(new BigDecimal("100"));
        batch.setUnit("kg");
        batch.setPlannedUnit("kg");
        when(productionBatchRepository.findByIdAndFactoryIdForUpdate(4401L, F)).thenReturn(Optional.of(batch));
        when(productionBatchRepository.save(any(ProductionBatch.class))).thenAnswer(i -> i.getArgument(0));

        // 缺料闸开着且计划有未解决缺料: 带原因会放行并往缺料实例写一条「强制开工」
        when(settings.findBlockStartOnOpenShortageByFactoryId(F)).thenReturn(true);
        ShortageCase open = new ShortageCase();
        open.setId("CASE-QA-1");
        open.setFactoryId(F);
        open.setStage(ShortageCase.Stage.DETECTED);
        when(cases.findOpenForUpdate(F, ShortageCase.SourceType.SALES_ORDER, "SO-QA-1")).thenReturn(Optional.of(open));
        ShortageCaseLine gap = new ShortageCaseLine();
        gap.setMaterialName("猪大骨");
        gap.setGapQty(new BigDecimal("6"));
        gap.setUnit("kg");
        when(caseLines.findByCaseIdOrderByIdAsc("CASE-QA-1")).thenReturn(List.of(gap));
    }

    @Test
    void batchOnAPendingForceInsertIsRefusedBeforeAnyWriteAndSaysWhichPlanWaitsForWhom() {
        plan.markAsForceInsert(1L, "客户急单", true);

        assertThatThrownBy(() -> service.startProduction(F, "4401", 7, "缺的料下午到"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo(PendingApprovalStartGuard.CODE);
                    assertThat(e.getMessage())
                            .contains("QA-URG-0001")
                            .contains("强制插单")
                            .contains("强制插单审批")   // 没有画布 ⇒ 直接审批入口
                            .contains("批准后才能开工");
                });
        assertThat(batch.getStatus()).isEqualTo(ProductionBatchStatus.PLANNED);
        assertThat(batch.getStartTime()).isNull();
        verify(productionBatchRepository, never()).save(any());
        verify(caseEvents, never()).save(any());   // 缺料实例里没留「强制开工」记录
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.PENDING);
    }

    @Test
    void withAnApprovalCanvasTheMessageNamesTheCurrentStep() {
        plan.markAsForceInsert(1L, "客户急单", true);
        ApprovalWorkflowInstance running = ApprovalWorkflowInstance.builder()
                .id("INST-QA-1").factoryId(F).workflowId("WF-QA-1")
                .status(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .currentNodeIds(new java.util.ArrayList<>(List.of("n2")))
                .build();
        when(workflowEngine.getCurrentInstance(F, ForceInsertApproverDescriber.MODULE, "PP-QA-URG-1"))
                .thenReturn(Optional.of(running));
        ApprovalWorkflow wf = new ApprovalWorkflow();
        wf.setNodesJson("[]");
        when(approvalWorkflowService.getById(F, "WF-QA-1")).thenReturn(Optional.of(wf));
        when(approvalWorkflowService.deserializeNodes("[]")).thenReturn(List.of(
                ApprovalWorkflowNode.builder().id("n1").type("start").label("提交").build(),
                ApprovalWorkflowNode.builder().id("n2").type("approval").label("厂长审批").build()));

        assertThatThrownBy(() -> service.startProduction(F, "4401", 7))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage())
                        .contains("QA-URG-0001").contains("「厂长审批」").doesNotContain("「提交」"));
        verify(productionBatchRepository, never()).save(any());
    }

    @Test
    void afterApprovalTheSameBatchStarts() {
        plan.markAsForceInsert(1L, "客户急单", true);
        plan.approve(9L, "生产经理", "同意");

        ProductionBatch started = service.startProduction(F, "4401", 7, "缺的料下午到");

        assertThat(started.getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
        verify(productionBatchRepository).save(batch);
    }

    @Test
    void negativeControlOrdinaryPlanIsNotAffected() {
        // 普通计划 requiresApproval=false: 只看缺料闸 (这里带原因放行)
        ProductionBatch started = service.startProduction(F, "4401", 7, "缺的料下午到");

        assertThat(started.getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
        verify(caseEvents).save(any());
    }

    @Test
    void negativeControlBatchWithoutAPlanIsNotAffected() {
        batch.setProductionPlanId(null);

        ProductionBatch started = service.startProduction(F, "4401", 7);

        assertThat(started.getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
    }
}
