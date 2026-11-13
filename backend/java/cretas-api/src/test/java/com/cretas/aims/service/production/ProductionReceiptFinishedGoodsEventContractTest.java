package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.event.FinishedGoodsCreatedEvent;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.ProductionSettlementOutputLineRepository;
import com.cretas.aims.repository.ProductionTransitLedgerRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ProductionCostConsistency;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仓库确认实收建成品后必须发「成品已生成」事件 (结单后入库路径原先漏发):
 * 恰好一次、事务提交后才发、回滚的入库不发。
 */
@DisplayName("结单后仓库确认入库 → 成品已生成事件")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionReceiptFinishedGoodsEventContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PP-EVT-1";

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private ProductionSettlementOutputLineRepository productionSettlementOutputLineRepository;
    @Mock private ProductionTransitLedgerRepository productionTransitLedgerRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private ProductionCostConsistency productionCostConsistency;
    @Mock private com.cretas.aims.repository.ProcessSheetRowRepository processSheetRowRepository;
    @Mock private com.cretas.aims.service.unit.UnitContractService unitContractService;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        com.cretas.aims.service.PermissionService permissionService =
                org.mockito.Mockito.mock(com.cretas.aims.service.PermissionService.class);
        when(permissionService.hasPermission(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementOutputLineRepository",
                productionSettlementOutputLineRepository);
        ReflectionTestUtils.setField(service, "productionTransitLedgerRepository", productionTransitLedgerRepository);
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
        ReflectionTestUtils.setField(service, "productionCostConsistency", productionCostConsistency);
        ReflectionTestUtils.setField(service, "processSheetRowRepository", processSheetRowRepository);
        ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
        when(unitContractService.storageUnit(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv ->
                com.cretas.aims.service.unit.impl.UnitContractServiceImpl.crossLanguageCode(inv.getArgument(1)));
        when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
        when(userRepository.findById(any())).thenReturn(Optional.of(new com.cretas.aims.entity.User()));

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("P-EVT");
        plan.setProductTypeId("PT-1");
        plan.setSourceOrderId("SO-1");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.COMPLETED);
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId("settlement-evt");
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        settlement.setPlanNumber("P-EVT");
        settlement.setPlannedQuantity(new BigDecimal("100"));
        settlement.setActualFinishedQuantity(new BigDecimal("90"));
        settlement.setActualSemiFinishedQuantity(BigDecimal.ZERO);
        settlement.setQuantityUnit("kg");
        settlement.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        settlement.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdForUpdate(FACTORY_ID, PLAN_ID))
                .thenReturn(Optional.of(settlement));
        when(productionSettlementRepository.save(any(ProductionSettlement.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(any(), any()))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findByIdAndFactoryId("PT-1", FACTORY_ID)).thenReturn(Optional.empty());
        when(warehouseResolver.resolveFinishedGoodsId(FACTORY_ID)).thenReturn("WH-FG-ID");
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(inv -> {
            FinishedGoodsBatch b = inv.getArgument(0);
            b.setId("fg-evt-1");
            return b;
        });
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ProductionWarehouseReceiptRequest request() {
        ProductionWarehouseReceiptRequest request = new ProductionWarehouseReceiptRequest();
        request.setIdempotencyKey("receipt-evt-1");
        request.setReceivedQuantity(new BigDecimal("90"));
        request.setQuantityUnit("kg");
        return request;
    }

    @Test
    @DisplayName("入库确认提交后恰好发一次成品已生成事件, 带批次与实收数量")
    void receiptCommitPublishesExactlyOneEvent() {
        TransactionSynchronizationManager.initSynchronization();
        service.confirmWarehouseReceipt(FACTORY_ID, PLAN_ID, request(), 11L);

        // 提交前不发
        verify(applicationEventPublisher, never()).publishEvent(any(FinishedGoodsCreatedEvent.class));
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<FinishedGoodsCreatedEvent> captor = ArgumentCaptor.forClass(FinishedGoodsCreatedEvent.class);
        verify(applicationEventPublisher, times(1)).publishEvent(captor.capture());
        FinishedGoodsCreatedEvent event = captor.getValue();
        assertEquals(FACTORY_ID, event.getFactoryId());
        assertEquals("SO-1", event.getSourceOrderId());
        assertEquals("PT-1", event.getProductTypeId());
        assertEquals("fg-evt-1", event.getBatchId());
        assertEquals(0, new BigDecimal("90").compareTo(event.getQuantity()));
        assertEquals("PRODUCTION", event.getSourceType());
    }

    @Test
    @DisplayName("回滚的入库不发事件")
    void rolledBackReceiptPublishesNothing() {
        TransactionSynchronizationManager.initSynchronization();
        service.confirmWarehouseReceipt(FACTORY_ID, PLAN_ID, request(), 11L);

        TransactionSynchronizationManager.getSynchronizations().forEach(
                s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(applicationEventPublisher, never()).publishEvent(any(FinishedGoodsCreatedEvent.class));
    }
}
