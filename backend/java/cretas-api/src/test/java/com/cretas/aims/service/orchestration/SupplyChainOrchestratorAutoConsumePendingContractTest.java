package com.cretas.aims.service.orchestration;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排查 2026-11-10 A9 调用方那一半 (产品真实入口: 批次完工事件 → {@link SupplyChainOrchestrator#onBatchCompleted})。
 * 自动扣料抛异常 (已整段回滚) → 挂待办; 原来只 {@code log.error} 一行, 界面无任何信号。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("排查 A9: 批次完工自动扣料失败 → 挂待办, 不只写日志")
class SupplyChainOrchestratorAutoConsumePendingContractTest {

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
    @Mock private FactorySettingsRepository factorySettingsRepository;

    @InjectMocks
    private SupplyChainOrchestrator orchestrator;

    private ProductionBatch batch;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(orchestrator, "warehouseResolver", warehouseResolver);
        // 不要求先结单的工厂才会走到批次完工自动扣料
        ReflectionTestUtils.setField(orchestrator, "disciplinePolicy",
                com.cretas.aims.service.discipline.DisciplineFixtures.policy(java.util.Map.of(
                        com.cretas.aims.service.canvas.ThresholdKeys.PRODUCTION_COMPLETION_REQUIRE_SETTLEMENT, "0")));
        batch = new ProductionBatch();
        batch.setId(31L);
        batch.setFactoryId("F006");
        batch.setBatchNumber("QA-PB-31");
        batch.setProductTypeId("PT-1");
        batch.setGoodQuantity(BigDecimal.ZERO);
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        when(productionBatchRepository.countByProductionPlanIdAndStatusNot(any(), any())).thenReturn(0L);
    }

    @Test
    @DisplayName("自动扣料抛异常 → 挂一条 CRITICAL 待办, 写明批次与原因")
    void failureRaisesCriticalPending() {
        doThrow(new IllegalStateException("批次可用数量不足")).when(batchConsumptionService).autoConsumeForBatch(batch);

        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));

        ArgumentCaptor<String> desc = ArgumentCaptor.forClass(String.class);
        verify(batchConsumptionService).raiseAutoConsumeAlert(eq(batch), eq("CRITICAL"), desc.capture());
        assertThat(desc.getValue()).contains("QA-PB-31").contains("批次可用数量不足");
    }

    @Test
    @DisplayName("阴性对照: 自动扣料成功 → 不挂待办")
    void successRaisesNothing() {
        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));
        verify(batchConsumptionService).autoConsumeForBatch(batch);
        verify(batchConsumptionService, never()).raiseAutoConsumeAlert(any(), anyString(), anyString());
    }
}
