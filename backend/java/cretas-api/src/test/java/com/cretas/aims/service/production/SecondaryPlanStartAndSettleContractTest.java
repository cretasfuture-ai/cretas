package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementLaborRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-S315-01 在计划入口上的接线: 开工 (两个入口) 和结单走真的 {@link ProductionPlanServiceImpl}。
 * 占用怎么算在 {@link SecondaryReworkFeedContractTest} 里验, 这里只验「谁在什么时候调了它」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecondaryPlanStartAndSettleContractTest {

    private static final String F = "F006";
    private static final String PLAN = "SEC-PLAN";
    private static final Long WIP_ID = 403L;
    private static final String WIP_NO = "CLK-SEMI-1";

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper planMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private WorkProcessTaskService workProcessTaskService;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private SecondaryPlanFeedService feed;
    @Mock private ProductionSettlementRepository settlementRepository;
    @Mock private ProductionSettlementConsumptionRepository consumptionRepository;
    @Mock private ProductionSettlementLaborRepository laborRepository;
    @Mock private ProcessSheetRowRepository rowRepository;
    @Mock private WorkshopReportReservationService reservationService;
    @Mock private CustomerSuppliedSettlementCoverageGuard coverageGuard;

    private ProductionPlanServiceImpl service;
    private ProductionPlan plan;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(planRepository, batchRepository, materialBatchRepository,
                materialConsumptionRepository, planBatchUsageRepository, productTypeRepository, planMapper,
                conversionRepository, schedulingService, productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        ReflectionTestUtils.setField(service, "workProcessTaskService", workProcessTaskService);
        ReflectionTestUtils.setField(service, "wipInventoryService", wipInventoryService);
        ReflectionTestUtils.setField(service, "secondaryPlanFeedService", feed);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", settlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository", consumptionRepository);
        ReflectionTestUtils.setField(service, "productionSettlementLaborRepository", laborRepository);
        ReflectionTestUtils.setField(service, "processSheetRowRepository", rowRepository);
        ReflectionTestUtils.setField(service, "workshopReservationService", reservationService);
        ReflectionTestUtils.setField(service, "customerSuppliedSettlementCoverageGuard", coverageGuard);

        plan = new ProductionPlan();
        plan.setId(PLAN);
        plan.setFactoryId(F);
        plan.setProductTypeId("FG-1");
        plan.setPlanNumber("SEC-F006-1");
        plan.setPlannedQuantity(new BigDecimal("0.6"));
        plan.setPlannedUnit("kg");
        plan.setPlanSourceType("SECONDARY");
        plan.setSecondarySourceWipId(WIP_ID);
        plan.setStatus(ProductionPlanStatus.PENDING);

        when(planRepository.findByIdForUpdate(PLAN)).thenAnswer(inv -> Optional.of(plan));
        when(planRepository.findByIdAndFactoryId(PLAN, F)).thenAnswer(inv -> Optional.of(plan));
        when(planRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchRepository.save(any(ProductionBatch.class))).thenAnswer(inv -> {
            ProductionBatch batch = inv.getArgument(0);
            batch.setId(500L);
            return batch;
        });
        when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
        when(planMapper.toDTO(any(ProductionPlan.class))).thenReturn(new com.cretas.aims.dto.production.ProductionPlanDTO());
        when(workProcessTaskService.spawnTasks(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(feed.forPlan(any(), any())).thenAnswer(inv -> Optional.of(new SecondaryPlanFeedService.Source(
                inv.getArgument(1), WIP_ID, WIP_NO, "SEMI-1", new BigDecimal("0.6"), "kg")));
    }

    private void routeCannotReceiveTheSource() {
        doThrow(new BusinessException(409, "接不住").withCode("SECONDARY_SOURCE_NOT_ACCEPTED"))
                .when(feed).assertAcceptedBy(eq(F), any(), any());
    }

    @Test
    @DisplayName("开始: 料接不住 → 明确错误码, 半成品一克都没扣")
    void startRejectsBeforeDeducting() {
        routeCannotReceiveTheSource();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startProduction(F, PLAN, 7L));

        assertThat(ex.getErrorCode()).isEqualTo("SECONDARY_SOURCE_NOT_ACCEPTED");
        verify(feed).assertAcceptedBy(eq(F), eq(plan), eq(500L));
        verify(feed, never()).takeHold(any(), any(), any());
    }

    @Test
    @DisplayName("开始: 接得住 → 先判接料再扣库存, 流水记到本计划")
    void startChecksThenDeducts() {
        service.startProduction(F, PLAN, 7L);

        InOrder order = Mockito.inOrder(feed);
        order.verify(feed).assertAcceptedBy(eq(F), eq(plan), eq(500L));
        order.verify(feed).takeHold(F, plan, 7L);
    }

    @Test
    @DisplayName("/create-batch 开工同样占用, 也是先判接料再占用")
    void createBatchChecksThenHolds() {
        service.createBatchFromPlan(F, PLAN, 7L, null);

        InOrder order = Mockito.inOrder(feed);
        order.verify(feed).assertAcceptedBy(eq(F), eq(plan), eq(500L));
        order.verify(feed).takeHold(F, plan, 7L);
    }

    @Test
    @DisplayName("/create-batch 开工入口同一道闸: 料接不住同样拦")
    void createBatchEntryIsGatedToo() {
        routeCannotReceiveTheSource();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBatchFromPlan(F, PLAN));

        assertThat(ex.getErrorCode()).isEqualTo("SECONDARY_SOURCE_NOT_ACCEPTED");
        verify(feed, never()).takeHold(any(), any(), any());
    }

    @Test
    @DisplayName("阴性: 普通计划开工不问接料")
    void ordinaryPlanSkipsTheGate() {
        plan.setPlanSourceType("NORMAL");
        plan.setSecondarySourceWipId(null);

        service.startProduction(F, PLAN, 7L);

        verify(feed, never()).assertAcceptedBy(any(), any(), any());
        verify(feed, never()).takeHold(any(), any(), any());
    }

    @Test
    @DisplayName("取消: 开工后没报工, 占用按流水余额还回, 计划取消")
    void cancelReturnsTheHold() {
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of());

        service.cancelProductionPlan(F, PLAN, "不做了");

        verify(feed).closeHold(eq(F), eq(plan), any());
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.CANCELLED);
    }

    @Test
    @DisplayName("取消: 暂停的计划也占着料, 一样还回")
    void cancelFromPausedReturnsTheHold() {
        plan.setStatus(ProductionPlanStatus.PAUSED);
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of());

        service.cancelProductionPlan(F, PLAN, "不做了");

        verify(feed).closeHold(eq(F), eq(plan), any());
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.CANCELLED);
    }

    @Test
    @DisplayName("取消: 有已提交的逐道行 → 走报工撤回, 不全额还占用")
    void cancelWithSubmittedRowIsRejected() {
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of());
        when(rowRepository.findByFactoryIdAndPlanId(F, PLAN)).thenReturn(List.of(reworkRow()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelProductionPlan(F, PLAN, "不做了"));

        assertThat(ex.getErrorCode()).isEqualTo("PLAN_HAS_PRODUCTION_DATA");
        verify(feed, never()).closeHold(any(), any(), any());
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("取消: 没有报工但已有消耗记录 → 同样走报工撤回, 不全额还占用")
    void cancelWithConsumptionButNoReportIsRejected() {
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of());
        var consumption = new com.cretas.aims.entity.MaterialConsumption();
        consumption.setFactoryId(F);
        when(materialConsumptionRepository.findByProductionPlanId(PLAN)).thenReturn(List.of(consumption));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelProductionPlan(F, PLAN, "不做了"));

        assertThat(ex.getErrorCode()).isEqualTo("PLAN_HAS_PRODUCTION_DATA");
        verify(feed, never()).closeHold(any(), any(), any());
    }

    @Test
    @DisplayName("不走结单的完工: 没用完的占用还回")
    void completeWithoutSettlementClosesTheHold() {
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        var settings = Mockito.mock(com.cretas.aims.repository.FactorySettingsRepository.class);
        when(settings.findSkipProcessReportingDefaultByFactoryId(F)).thenReturn(false);
        ReflectionTestUtils.setField(service, "factorySettingsRepository", settings);
        when(planRepository.findById(PLAN)).thenAnswer(inv -> Optional.of(plan));
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of());

        service.completeProduction(F, PLAN, new BigDecimal("0.5"), null);

        verify(feed).closeHold(eq(F), eq(plan), any());
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.COMPLETED);
    }

    private ProcessSheetRow reworkRow() {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(F);
        row.setPlanId(PLAN);
        row.setBatchId(502L);
        row.setBatchNumber("CLK-B-1");
        row.setProcessOrder(2);
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        row.setRowPayload("{\"clientRowId\":\"r\",\"processCode\":\"rework\",\"processOrder\":2,"
                + "\"productTypeId\":\"FG-1\",\"finished\":true,\"outputQuantity\":0.5,\"unit\":\"kg\","
                + "\"upstreamSources\":[{\"sourceBatchNumber\":\"" + WIP_NO + "\",\"semiFinished\":true,"
                + "\"feedQuantityKg\":0.4}]}");
        return row;
    }

    private void readyToSettle() {
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of());
        when(rowRepository.findByFactoryIdAndPlanId(F, PLAN)).thenReturn(List.of(reworkRow()));
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                any(), any(), any())).thenReturn(Optional.empty());
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN))
                .thenReturn(Optional.empty());
        when(settlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(consumptionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(laborRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(reservationService.releaseForPlan(any(), any())).thenReturn(BigDecimal.ZERO);
        when(coverageGuard.audit(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
        when(wipInventoryService.consumeClerkSemiStrict(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(3));
    }

    private ProductionSettlementRequest settle() {
        return ProductionSettlementRequest.builder()
                .idempotencyKey("key-1").confirm(true)
                .materialVarianceReason("料来自半成品").laborDeferredReason("工时稍后补录")
                .build();
    }

    @Test
    @DisplayName("结单: 先把占用全部还回, 再按实际投料扣一次 —— 没用的留在库存, 用的不重扣")
    void settleReleasesHoldThenDeductsActualFeedOnce() {
        readyToSettle();

        service.settleProduction(F, PLAN, settle(), 7L);

        InOrder order = Mockito.inOrder(feed, wipInventoryService);
        order.verify(feed).settleHold(eq(F), eq(plan), eq(7L));
        order.verify(wipInventoryService).consumeClerkSemiStrict(eq(F), any(), eq(WIP_NO), eq(new BigDecimal("0.4")));
        verify(wipInventoryService).consumeClerkSemiStrict(any(), any(), any(), any());
    }

    @Test
    @DisplayName("confirm=false 预览返工计划: 占用一点不还")
    void previewDoesNotReleaseTheHold() {
        readyToSettle();
        ProductionSettlementRequest preview = settle();
        preview.setConfirm(false);

        service.settleProduction(F, PLAN, preview, 7L);

        verify(feed, never()).settleHold(any(), any(), any());
        verify(feed, never()).closeHold(any(), any(), any());
        verify(wipInventoryService, never()).consumeClerkSemiStrict(any(), any(), any(), any());
    }

    private Boolean appLegComplete(com.cretas.aims.entity.workprocess.WorkProcessTask.Status firstStep,
                                   com.cretas.aims.entity.workprocess.WorkProcessTask.Status secondStep,
                                   int firstReceivingOrder) {
        var taskRepo = Mockito.mock(com.cretas.aims.repository.workprocess.WorkProcessTaskRepository.class);
        ReflectionTestUtils.setField(service, "workProcessTaskRepository", taskRepo);
        ProductionBatch batch = new ProductionBatch();
        batch.setId(500L);
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of(batch));
        var cure = new com.cretas.aims.entity.workprocess.WorkProcessTask();
        cure.setProcessOrder(1);
        cure.setStatus(firstStep);
        var rework = new com.cretas.aims.entity.workprocess.WorkProcessTask();
        rework.setProcessOrder(2);
        rework.setStatus(secondStep);
        List<com.cretas.aims.entity.workprocess.WorkProcessTask> tasks = List.of(cure, rework);
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(F, 500L)).thenReturn(tasks);
        when(feed.firstReceivingOrder(F, plan, tasks)).thenReturn(firstReceivingOrder);
        return ReflectionTestUtils.invokeMethod(service, "hasCompletedTaskLevelReporting", F, plan);
    }

    @Test
    @DisplayName("App 腿结单: 返工计划接料工序之前的道不要求报完, 接料工序本身必须报完; 普通计划每道都要报完")
    void appLegOnlyRequiresStepsFromTheReceivingOne() {
        var pending = com.cretas.aims.entity.workprocess.WorkProcessTask.Status.PENDING;
        var completed = com.cretas.aims.entity.workprocess.WorkProcessTask.Status.COMPLETED;

        assertThat(appLegComplete(pending, completed, 2)).isTrue();
        assertThat(appLegComplete(pending, pending, 2)).isFalse();

        plan.setPlanSourceType("NORMAL");
        assertThat(appLegComplete(pending, completed, 2)).isFalse();
    }

    @Test
    @DisplayName("阴性: 普通计划结单不碰占用")
    void ordinarySettleDoesNotTouchHold() {
        readyToSettle();
        plan.setPlanSourceType("NORMAL");
        plan.setSecondarySourceWipId(null);

        service.settleProduction(F, PLAN, settle(), 7L);

        verify(feed, never()).settleHold(any(), any(), any());
    }
}
