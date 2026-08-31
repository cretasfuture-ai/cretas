package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportReversalPlanStateTest {

    private static final String FACTORY_ID = "F006";
    private static final String OTHER_FACTORY = "OTHER";
    private static final String PLAN_ID = "plan-001";
    private static final Long BATCH_ID = 1924L;
    private static final Long LOG_ID = 42L;
    private static final String SETTLEMENT_ID = "settlement-001";

    @InjectMocks
    private ReportReversalServiceImpl service;

    @Mock private ReportReversalLogRepository reversalLogRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private FinishedGoodsBatchRepository fgbRepo;
    @Mock private com.cretas.aims.repository.UserRepository userRepository;
    @Mock private WorkProcessTaskRepository workProcessTaskRepository;
    @Mock private NotificationService notificationService;
    @Mock private ProcessSheetRowRepository processSheetRowRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionSettlementRepository settlementRepo;
    @Mock private ProductionSettlementConsumptionRepository consumptionRepo;
    @Mock private ProductionPlanRepository planRepo;
    @Mock private SalesOrderItemRepository salesOrderItemRepo;

    @BeforeEach
    void injectOptionalRepositories() {
        ReflectionTestUtils.setField(service, "processSheetRowRepository", processSheetRowRepository);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", settlementRepo);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository", consumptionRepo);
        ReflectionTestUtils.setField(service, "productionPlanRepository", planRepo);
        ReflectionTestUtils.setField(service, "salesOrderItemRepository", salesOrderItemRepo);

        lenient().when(reversalLogRepo.findByIdAndFactoryIdForUpdate(anyLong(), anyString()))
                .thenAnswer(invocation -> reversalLogRepo.findById(invocation.getArgument(0)));
        lenient().when(reversalLogRepo.save(any(ReportReversalLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());
        lenient().when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());
        lenient().when(workProcessTaskRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());
        lenient().when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());
        lenient().when(fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());
        lenient().when(consumptionRepo.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void settlementReversal_reopensPlanAndCompletedBatch_clearingOnlyDerivedFields() {
        ReportReversalLog log = pendingLog();
        ProductionPlan plan = completedPlan();
        ProductionBatch batch = completedBatch();
        ProductionSettlement settlement = activeSettlement();
        stubScope(log, plan, batch, settlement);

        service.executeReversal(LOG_ID, FACTORY_ID);

        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.IN_PROGRESS);
        assertThat(plan.getActualQuantity()).isNull();
        assertThat(plan.getEndTime()).isNull();
        assertThat(plan.getStartTime()).isEqualTo(planStart());
        assertThat(plan.getProductTypeId()).isEqualTo("product-1");
        assertThat(plan.getSelectedWorkflowId()).isEqualTo(7L);

        assertThat(batch.getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
        assertThat(batch.getActualQuantity()).isNull();
        assertThat(batch.getGoodQuantity()).isNull();
        assertThat(batch.getDefectQuantity()).isNull();
        assertThat(batch.getEndTime()).isNull();
        assertThat(batch.getStartTime()).isEqualTo(batchStart());
        assertThat(batch.getProductTypeId()).isEqualTo("product-1");
        assertThat(batch.getWorkflowSelectionMode()).isEqualTo(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        assertThat(settlement.getDeletedAt()).isNotNull();
        verify(planRepo).save(plan);
        verify(batchRepo).save(batch);
    }

    @Test
    void reportOnlyReversal_doesNotResetPlanOrBatchWithoutActiveSettlement() {
        ReportReversalLog log = pendingLog();
        ProductionPlan plan = completedPlan();
        ProductionBatch batch = completedBatch();
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(settlementRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(Optional.empty());

        service.executeReversal(LOG_ID, FACTORY_ID);

        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.COMPLETED);
        assertThat(plan.getActualQuantity()).isEqualByComparingTo("8");
        assertThat(plan.getEndTime()).isEqualTo(planEnd());
        assertThat(batch.getStatus()).isEqualTo(ProductionBatchStatus.COMPLETED);
        assertThat(batch.getActualQuantity()).isEqualByComparingTo("8");
        assertThat(batch.getDefectQuantity()).isEqualByComparingTo("1");
        verify(planRepo, never()).save(any(ProductionPlan.class));
        verify(batchRepo, never()).save(any(ProductionBatch.class));
    }

    @Test
    void settlementReversal_runningPlanAndBatch_preservePartialDerivedStateAndIdentity() {
        ReportReversalLog log = pendingLog();
        ProductionPlan plan = completedPlan();
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        ProductionBatch batch = completedBatch();
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        ProductionSettlement settlement = activeSettlement();
        stubScope(log, plan, batch, settlement);

        service.executeReversal(LOG_ID, FACTORY_ID);

        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.IN_PROGRESS);
        assertThat(plan.getActualQuantity()).isEqualByComparingTo("8");
        assertThat(plan.getEndTime()).isEqualTo(planEnd());
        assertThat(batch.getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
        assertThat(batch.getActualQuantity()).isEqualByComparingTo("8");
        assertThat(batch.getGoodQuantity()).isEqualByComparingTo("7");
        assertThat(batch.getDefectQuantity()).isEqualByComparingTo("1");
        assertThat(batch.getEndTime()).isNotNull();
        verify(planRepo, never()).save(any(ProductionPlan.class));
        verify(batchRepo, never()).save(any(ProductionBatch.class));
    }

    @Test
    void settlementReversal_completedPlanWithRunningBatch_reopensPlanOnly() {
        ReportReversalLog log = pendingLog();
        ProductionPlan plan = completedPlan();
        ProductionBatch batch = completedBatch();
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        batch.setActualQuantity(new BigDecimal("6.25"));
        batch.setGoodQuantity(new BigDecimal("5.75"));
        batch.setDefectQuantity(new BigDecimal("0.50"));
        LocalDateTime batchEnd = LocalDateTime.of(2026, 8, 30, 11, 30);
        batch.setEndTime(batchEnd);
        stubScope(log, plan, batch, activeSettlement());

        service.executeReversal(LOG_ID, FACTORY_ID);

        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.IN_PROGRESS);
        assertThat(plan.getActualQuantity()).isNull();
        assertThat(plan.getEndTime()).isNull();
        verify(planRepo, times(1)).save(plan);

        assertThat(batch.getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
        assertThat(batch.getActualQuantity()).isEqualByComparingTo("6.25");
        assertThat(batch.getGoodQuantity()).isEqualByComparingTo("5.75");
        assertThat(batch.getDefectQuantity()).isEqualByComparingTo("0.50");
        assertThat(batch.getEndTime()).isEqualTo(batchEnd);
        assertThat(batch.getStartTime()).isEqualTo(batchStart());
        assertThat(batch.getProductTypeId()).isEqualTo("product-1");
        verify(batchRepo, never()).save(any(ProductionBatch.class));
    }

    @Test
    void settlementReversal_crossFactorySettlement_failsBeforeAnyMutation() {
        ReportReversalLog log = pendingLog();
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        ProductionSettlement settlement = activeSettlement();
        settlement.setFactoryId(OTHER_FACTORY);
        when(settlementRepo.findByFactoryIdAndProductionPlanIdForUpdate(FACTORY_ID, PLAN_ID))
                .thenReturn(Optional.of(settlement));

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .hasMessageContaining("结单不属于当前工厂")
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("REVERSAL_SCOPE_INVALID");
                });
        verify(reportRepo, never()).save(any());
        verify(settlementRepo, never()).save(any());
        verify(reversalLogRepo, never()).save(any());
    }

    @Test
    void settlementReversal_batchPlanMismatch_failsBeforeAnyMutation() {
        ReportReversalLog log = pendingLog();
        ProductionSettlement settlement = activeSettlement();
        ProductionBatch batch = completedBatch();
        batch.setProductionPlanId("another-plan");
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(settlementRepo.findByFactoryIdAndProductionPlanIdForUpdate(FACTORY_ID, PLAN_ID))
                .thenReturn(Optional.of(settlement));
        when(batchRepo.findByIdAndFactoryIdForUpdate(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch));

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .hasMessageContaining("批次工厂或计划关联不匹配")
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("REVERSAL_SCOPE_INVALID"));
        verify(reportRepo, never()).save(any());
        verify(settlementRepo, never()).save(any());
        verify(reversalLogRepo, never()).save(any());
    }

    @Test
    void settlementReversal_missingPlan_failsBeforeAnyMutation() {
        ReportReversalLog log = pendingLog();
        ProductionSettlement settlement = activeSettlement();
        ProductionBatch batch = completedBatch();
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(settlementRepo.findByFactoryIdAndProductionPlanIdForUpdate(FACTORY_ID, PLAN_ID))
                .thenReturn(Optional.of(settlement));
        when(batchRepo.findByIdAndFactoryIdForUpdate(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch));
        when(planRepo.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .hasMessageContaining("生产计划不存在")
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("REVERSAL_SCOPE_INVALID"));
        verify(reportRepo, never()).save(any());
        verify(settlementRepo, never()).save(any());
        verify(reversalLogRepo, never()).save(any());
    }

    @Test
    void settlementReversal_planBelongsToOtherFactory_failsBeforeAnyMutation() {
        ReportReversalLog log = pendingLog();
        ProductionSettlement settlement = activeSettlement();
        ProductionBatch batch = completedBatch();
        ProductionPlan foreignPlan = completedPlan();
        foreignPlan.setFactoryId(OTHER_FACTORY);
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(settlementRepo.findByFactoryIdAndProductionPlanIdForUpdate(FACTORY_ID, PLAN_ID))
                .thenReturn(Optional.of(settlement));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch));
        when(batchRepo.findByIdAndFactoryIdForUpdate(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
        when(planRepo.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(foreignPlan));

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .hasMessageContaining("生产计划不属于当前工厂")
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("REVERSAL_SCOPE_INVALID");
                });
        verify(reportRepo, never()).save(any());
        verify(workProcessTaskRepository, never()).saveAll(any());
        verify(settlementRepo, never()).save(any());
        verify(consumptionRepo, never()).save(any());
        verify(materialBatchRepository, never()).save(any());
        verify(wipRepo, never()).save(any());
        verify(planRepo, never()).save(any());
        verify(batchRepo, never()).save(any());
        verify(reversalLogRepo, never()).save(any());
    }

    @Test
    void settlementReversal_multipleFormalBatches_failsBeforeAnyMutation() {
        ReportReversalLog log = pendingLog();
        ProductionSettlement settlement = activeSettlement();
        ProductionBatch batch = completedBatch();
        ProductionBatch second = completedBatch();
        second.setId(BATCH_ID + 1);
        second.setBatchNumber("B-1924-2");
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(settlementRepo.findByFactoryIdAndProductionPlanIdForUpdate(FACTORY_ID, PLAN_ID))
                .thenReturn(Optional.of(settlement));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch, second));

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .hasMessageContaining("唯一对应一个可撤回正式批次")
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode())
                            .isEqualTo("REVERSAL_SETTLEMENT_BATCH_SCOPE_INVALID");
                });
        verify(reportRepo, never()).save(any());
        verify(settlementRepo, never()).save(any());
        verify(reversalLogRepo, never()).save(any());
    }

    @Test
    void settlementReversal_missingDependency_failsClosedBeforeAnyMutation() {
        ReportReversalLog log = pendingLog();
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        ReflectionTestUtils.setField(service, "productionPlanRepository", null);

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, FACTORY_ID))
                .hasMessageContaining("结单恢复依赖未配置")
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode())
                            .isEqualTo("REVERSAL_SETTLEMENT_DEPENDENCY_MISSING");
                });
        verify(reportRepo, never()).findYieldReportsByBatch(anyString(), anyLong());
        verify(reportRepo, never()).save(any());
        verify(reversalLogRepo, never()).save(any());
    }

    @Test
    void doneReversal_isIdempotent_withoutLoadingOrSavingPlan() {
        ReportReversalLog done = pendingLog();
        done.setStatus(ReportReversalLog.ReversalStatus.DONE);
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(done));

        service.executeReversal(LOG_ID, FACTORY_ID);

        verifyNoInteractions(planRepo, batchRepo, settlementRepo, consumptionRepo, reportRepo);
    }

    private void stubScope(ReportReversalLog log, ProductionPlan plan,
                           ProductionBatch batch, ProductionSettlement settlement) {
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(batchRepo.findByIdAndFactoryIdForUpdate(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch));
        when(planRepo.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(settlementRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(Optional.of(settlement));
        when(settlementRepo.findByFactoryIdAndProductionPlanIdForUpdate(FACTORY_ID, PLAN_ID))
                .thenReturn(Optional.of(settlement));
    }

    private ReportReversalLog pendingLog() {
        return ReportReversalLog.builder()
                .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID).planId(PLAN_ID)
                .status(ReportReversalLog.ReversalStatus.APPROVED)
                .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                .build();
    }

    private ProductionSettlement activeSettlement() {
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        return settlement;
    }

    private ProductionPlan completedPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId("product-1");
        plan.setSelectedWorkflowId(7L);
        plan.setStatus(ProductionPlanStatus.COMPLETED);
        plan.setActualQuantity(new BigDecimal("8"));
        plan.setStartTime(planStart());
        plan.setEndTime(planEnd());
        return plan;
    }

    private ProductionBatch completedBatch() {
        ProductionBatch batch = ProductionBatch.builder()
                .id(BATCH_ID).factoryId(FACTORY_ID).productionPlanId(PLAN_ID)
                .productTypeId("product-1").productName("Test")
                .batchNumber("B-1924").quantity(new BigDecimal("8"))
                .unit("kg").status(ProductionBatchStatus.COMPLETED)
                .actualQuantity(new BigDecimal("8"))
                .goodQuantity(new BigDecimal("7"))
                .defectQuantity(new BigDecimal("1"))
                .startTime(batchStart()).endTime(LocalDateTime.now())
                .workflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW)
                .build();
        return batch;
    }

    private static LocalDateTime planStart() {
        return LocalDateTime.of(2026, 8, 30, 8, 0);
    }

    private static LocalDateTime planEnd() {
        return LocalDateTime.of(2026, 8, 30, 10, 0);
    }

    private static LocalDateTime batchStart() {
        return LocalDateTime.of(2026, 8, 30, 8, 5);
    }
}
