package com.cretas.aims.service.orchestration;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.factory.WarehouseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplyChainOrchestrator production settlement FG guard")
class SupplyChainOrchestratorSettlementGuardTest {

    @Mock private InventoryMatchingService inventoryMatchingService;
    @Mock private BomExpansionService bomExpansionService;
    @Mock private ProcurementSuggestionService procurementSuggestionService;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private BatchConsumptionService batchConsumptionService;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private WarehouseResolver warehouseResolver;

    @InjectMocks
    private SupplyChainOrchestrator orchestrator;

    @BeforeEach
    void injectFieldDependencies() {
        ReflectionTestUtils.setField(orchestrator, "warehouseResolver", warehouseResolver);
    }

    @Test
    @DisplayName("A7 switch on (default): batch completion consumes materials but skips automatic FG")
    void batchCompleted_settlementFactorySkipsAutomaticFinishedGoodsButKeepsConsumption() {
        ProductionBatch batch = completedBatch(101L, "F006");
        discipline(java.util.Map.of());

        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));

        verify(batchConsumptionService).autoConsumeForBatch(batch);
        verify(finishedGoodsBatchRepository, never()).findByFactoryIdAndBatchNumber(anyString(), anyString());
        verify(finishedGoodsBatchRepository, never()).save(any(FinishedGoodsBatch.class));
    }

    @Test
    @DisplayName("A7 switch off: batch completion still creates automatic FG")
    void batchCompleted_nonSettlementFactoryStillCreatesAutomaticFinishedGoods() {
        ProductionBatch batch = completedBatch(102L, "F001");
        discipline(SWITCH_OFF);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findById(batch.getProductTypeId())).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId("F001")).thenReturn("WH-WKS-F001");
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));

        verify(batchConsumptionService).autoConsumeForBatch(batch);
        ArgumentCaptor<FinishedGoodsBatch> cap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository).save(cap.capture());
        assertThat(cap.getValue().getFactoryId()).isEqualTo("F001");
        assertThat(cap.getValue().getProducedQuantity()).isEqualByComparingTo("25");
    }

    // ═══ 纪律排查 A7: 批次全部完工不能绕过「完工必须先结单」 (独立审查高 #1) ═══

    private static final java.util.Map<String, String> SWITCH_OFF = java.util.Map.of(
            com.cretas.aims.service.canvas.ThresholdKeys.PRODUCTION_COMPLETION_REQUIRE_SETTLEMENT, "0");

    private void discipline(java.util.Map<String, String> cfg) {
        ReflectionTestUtils.setField(orchestrator, "disciplinePolicy",
                com.cretas.aims.service.discipline.DisciplineFixtures.policy(cfg));
    }

    private com.cretas.aims.entity.ProductionPlan planWithLastBatch(ProductionBatch batch) {
        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        plan.setId("PP-A7");
        plan.setFactoryId(batch.getFactoryId());
        plan.setPlanNumber("PP-A7");
        plan.setStatus(com.cretas.aims.entity.enums.ProductionPlanStatus.IN_PROGRESS);
        plan.setActualQuantity(BigDecimal.ZERO);
        batch.setProductionPlanId("PP-A7");
        when(productionPlanRepository.findById("PP-A7")).thenReturn(Optional.of(plan));
        when(productionBatchRepository.countByProductionPlanIdAndStatusNot(
                "PP-A7", ProductionBatchStatus.COMPLETED)).thenReturn(0L);   // 这是最后一个批次
        when(productionPlanRepository.save(any(com.cretas.aims.entity.ProductionPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return plan;
    }

    @Test
    @DisplayName("A7 开关开 (默认, F001 也一样): 最后一个批次完工, 计划不自动完工、不发完工事件, 不自动建成品 —— 等结单")
    void a7_switchOn_lastBatchDoesNotCompleteThePlan() {
        ProductionBatch batch = completedBatch(201L, "F001");
        com.cretas.aims.entity.ProductionPlan plan = planWithLastBatch(batch);
        discipline(java.util.Map.of());

        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));

        assertThat(plan.getStatus()).isEqualTo(com.cretas.aims.entity.enums.ProductionPlanStatus.IN_PROGRESS);
        assertThat(plan.getEndTime()).isNull();
        assertThat(plan.getActualQuantity()).isEqualByComparingTo("25");   // 进度照常累加
        verify(finishedGoodsBatchRepository, never()).save(any(FinishedGoodsBatch.class));
        verify(applicationEventPublisher, never()).publishEvent(any(com.cretas.aims.event.ProductionCompletedEvent.class));
    }

    @Test
    @DisplayName("A7 阴性对照 开关关: 最后一个批次完工, 计划照旧自动完工并发完工事件, 自动建成品")
    void a7_switchOff_lastBatchCompletesThePlanAsBefore() {
        ProductionBatch batch = completedBatch(202L, "F001");
        com.cretas.aims.entity.ProductionPlan plan = planWithLastBatch(batch);
        discipline(SWITCH_OFF);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findById(batch.getProductTypeId())).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId("F001")).thenReturn("WH-WKS-F001");
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));

        assertThat(plan.getStatus()).isEqualTo(com.cretas.aims.entity.enums.ProductionPlanStatus.COMPLETED);
        assertThat(plan.getEndTime()).isNotNull();
        verify(finishedGoodsBatchRepository).save(any(FinishedGoodsBatch.class));
        verify(applicationEventPublisher).publishEvent(any(com.cretas.aims.event.ProductionCompletedEvent.class));
    }

    private ProductionBatch completedBatch(long batchId, String factoryId) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(batchId);
        batch.setFactoryId(factoryId);
        batch.setProductTypeId("PT-SETTLEMENT-GUARD");
        batch.setProductName("Settlement Guard Product");
        batch.setGoodQuantity(new BigDecimal("25"));
        batch.setActualQuantity(new BigDecimal("25"));
        batch.setUnit("kg");
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        return batch;
    }
}
