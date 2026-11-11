package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.LineItemMatch;
import com.cretas.aims.dto.orchestration.StockCheckResult;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.sales.SellableLineDomainResolver;
import com.cretas.aims.service.sales.SellableLineDomainResolver.SellableDomain;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-S411-03: 销售订单财审通过后自动生成的生产计划 (PP-AUTO-*) 要带上订单交期。
 *
 * <p>原来自动计划一个日期都不设, 而「按日产能自动排程」的候选列表按预计完成日过滤
 * ({@code findByFactoryIdAndStatusAndExpectedCompletionDateBetween}), 于是自动计划不出现在
 * 候选里, 生产经理必须先进计划编辑手填日期才能排。
 *
 * <p>入口走真实的 {@link SupplyChainOrchestrator#onSalesOrderFinanceApproved} (不是直接调私有方法),
 * 装配与 {@link MaterialLineNotAProductionPlanContractTest} 相同。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("D-S411-03 财审自动生成的生产计划带订单交期")
class AutoPlanCarriesOrderDeliveryDateContractTest {

    private static final String FACTORY = "F006";
    private static final String SO_ID = "SO-20261110-0123";
    private static final String PRODUCT_ID = "PT-FINISHED";

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
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SellableLineDomainResolver sellableLineDomainResolver;
    @Mock private ProductionPlanService productionPlanService;

    @InjectMocks
    private SupplyChainOrchestrator orchestrator;

    private ProductionPlan approveOrderWithDeliveryDate(LocalDate requiredDeliveryDate) {
        SalesOrder order = new SalesOrder();
        order.setId(SO_ID);
        order.setFactoryId(FACTORY);
        order.setProcessingMode(SalesProcessingMode.STANDARD_SALE);
        order.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        order.setRequiredDeliveryDate(requiredDeliveryDate);
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(order));

        LineItemMatch line = new LineItemMatch();
        line.setSalesOrderItemId("ITEM-1");
        line.setProductTypeId(PRODUCT_ID);
        line.setProductTypeName("卤猪蹄 200g");
        line.setRequiredQuantity(new BigDecimal("50"));
        line.setAvailableQuantity(BigDecimal.ZERO);
        line.setShortfallQuantity(new BigDecimal("50"));
        StockCheckResult result = new StockCheckResult();
        result.setSalesOrderId(SO_ID);
        result.setLineItems(List.of(line));
        when(inventoryMatchingService.checkAvailability(FACTORY, SO_ID)).thenReturn(result);

        when(sellableLineDomainResolver.resolve(FACTORY, PRODUCT_ID)).thenReturn(SellableDomain.FINISHED_GOOD);
        ProductType pt = new ProductType();
        pt.setId(PRODUCT_ID);
        pt.setUnit("kg");
        when(productTypeRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(pt));
        when(productionPlanRepository.saveAndFlush(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.onSalesOrderFinanceApproved(new SalesOrderFinanceApprovedEvent(this, FACTORY, SO_ID, 1309L));

        ArgumentCaptor<ProductionPlan> captor = ArgumentCaptor.forClass(ProductionPlan.class);
        // 阳性对照: 计划确实建出来了 (否则下面对日期的断言没有意义)
        verify(productionPlanRepository, times(1)).saveAndFlush(captor.capture());
        ProductionPlan plan = captor.getValue();
        assertThat(plan.getSourceType()).isEqualTo(PlanSourceType.CUSTOMER_ORDER);
        assertThat(plan.getSourceOrderId()).isEqualTo(SO_ID);
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.PENDING);
        return plan;
    }

    @Test
    @DisplayName("订单有交期 → 自动计划的预计完成日 = 订单交期")
    void orderDeliveryDateBecomesPlanExpectedCompletionDate() {
        LocalDate due = LocalDate.of(2026, 11, 20);

        ProductionPlan plan = approveOrderWithDeliveryDate(due);

        assertThat(plan.getExpectedCompletionDate())
                .as("排程候选列表按 expected_completion_date 过滤, 自动计划必须带上订单交期")
                .isEqualTo(due);
    }

    @Test
    @DisplayName("订单有交期 → 只填完成日, 不凭空编开始日期 / 生产日期")
    void doesNotInventAStartDate() {
        ProductionPlan plan = approveOrderWithDeliveryDate(LocalDate.of(2026, 11, 20));

        assertThat(plan.getPlannedDate()).as("计划生产日没有依据, 不编").isNull();
        assertThat(plan.getStartTime()).isNull();
        assertThat(plan.getBatchDate()).isNull();
    }

    @Test
    @DisplayName("订单没填交期 → 计划照常建出, 预计完成日保持为空 (不编日期; 排程页按「未定日期」列出)")
    void orderWithoutDeliveryDateStillCreatesPlanWithoutInventedDate() {
        ProductionPlan plan = approveOrderWithDeliveryDate(null);

        assertThat(plan.getExpectedCompletionDate()).isNull();
        assertThat(plan.getPlannedDate()).isNull();
    }

    @Test
    @DisplayName("异步联动跑起来时订单已被取消 (财务批准后取消开关) → 不预留、不建计划")
    void aCancelledOrderGetsNoReservationAndNoPlan() {
        SalesOrder order = new SalesOrder();
        order.setId(SO_ID);
        order.setFactoryId(FACTORY);
        order.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        order.setStatus(com.cretas.aims.entity.enums.SalesOrderStatus.FINANCE_APPROVED);
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(order));
        // 不加锁读到的还是已批准; 拿到行锁后读到的是取消已提交的状态 —— 判断必须看锁后的那次
        SalesOrder locked = new SalesOrder();
        locked.setId(SO_ID);
        locked.setFactoryId(FACTORY);
        locked.setStatus(com.cretas.aims.entity.enums.SalesOrderStatus.CANCELLED);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(SO_ID, FACTORY)).thenReturn(Optional.of(locked));

        orchestrator.onSalesOrderFinanceApproved(new SalesOrderFinanceApprovedEvent(this, FACTORY, SO_ID, 1309L));

        verify(inventoryMatchingService, org.mockito.Mockito.never()).checkAvailability(any(), any());
        verify(productionPlanRepository, org.mockito.Mockito.never()).saveAndFlush(any(ProductionPlan.class));
    }
}
