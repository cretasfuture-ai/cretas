package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.LineItemMatch;
import com.cretas.aims.dto.orchestration.StockCheckResult;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-SO-AUTOPLAN-NOAPPROVER: 销售订单被 OA 审批流<b>自动</b>通过时, 也要照常生成生产计划。
 *
 * <p>prod 2026-11-10 F006 SO-20261110-0005/0014/0015: 自动通过时事件里 {@code approvedBy = null},
 * {@code createProductionPlanFromSO} 抛「自动创建生产计划缺少审批人」, 被
 * 「财务审核通过联动失败(不影响审批状态)」吞进日志 —— 订单通过了, 计划没有。人工审批正常。
 *
 * <p>入口走真实的 {@link SupplyChainOrchestrator#onSalesOrderFinanceApproved}; 装配与
 * {@link MaterialLineNotAProductionPlanContractTest} 相同。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("D-SO-AUTOPLAN-NOAPPROVER 系统自动审批通过的销售订单也生成生产计划")
class AutoPlanFromSystemAutoApprovalTest {

    private static final String FACTORY = "F006";
    private static final String SO_ID = "SO-20261110-0005";
    private static final String PRODUCT_ID = "PT-FINISHED";
    private static final Long SUBMITTER = 1401L;
    private static final Long FINANCE_APPROVER = 1309L;

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

    @BeforeEach
    void givenAnApprovedOrderWithAShortFinishedGood() {
        SalesOrder order = new SalesOrder();
        order.setId(SO_ID);
        order.setFactoryId(FACTORY);
        order.setProcessingMode(SalesProcessingMode.STANDARD_SALE);
        order.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(order));

        LineItemMatch line = new LineItemMatch();
        line.setSalesOrderItemId("ITEM-1");
        line.setProductTypeId(PRODUCT_ID);
        line.setProductTypeName("卤猪蹄 200g");
        line.setRequiredQuantity(new BigDecimal("30"));
        line.setAvailableQuantity(BigDecimal.ZERO);
        line.setShortfallQuantity(new BigDecimal("30"));
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
    }

    private ProductionPlan theSavedPlan() {
        ArgumentCaptor<ProductionPlan> captor = ArgumentCaptor.forClass(ProductionPlan.class);
        verify(productionPlanRepository, times(1)).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("🔴 OA 自动通过 (approvedBy 为空) → 照常生成计划, 建单人 = 提交审批的人, 备注可读出「系统自动审批」")
    void systemAutoApprovedOrderStillGetsAPlan() {
        orchestrator.onSalesOrderFinanceApproved(SalesOrderFinanceApprovedEvent.systemAutoApproved(
                this, FACTORY, SO_ID, null, SUBMITTER, "销售审批工作流自动通过"));

        ProductionPlan plan = theSavedPlan();
        assertThat(plan.getCreatedBy())
                .as("没有人工审批人时, 建单人是触发这次自动通过的提交人 (真实发生的操作主体), 不是写死的 id")
                .isEqualTo(SUBMITTER);
        assertThat(plan.getNotes())
                .contains("系统自动审批通过")
                .contains("销售审批工作流自动通过")
                .contains("无人工审批人")
                .contains(String.valueOf(SUBMITTER));
    }

    @Test
    @DisplayName("人工审批不变: 建单人 = 审批人, 备注里没有「系统自动审批」")
    void manualApprovalUnchanged() {
        orchestrator.onSalesOrderFinanceApproved(
                new SalesOrderFinanceApprovedEvent(this, FACTORY, SO_ID, FINANCE_APPROVER));

        ProductionPlan plan = theSavedPlan();
        assertThat(plan.getCreatedBy()).isEqualTo(FINANCE_APPROVER);
        assertThat(plan.getNotes())
                .isEqualTo("自动创建：来自销售订单 " + SO_ID + "，产品 卤猪蹄 200g")
                .doesNotContain("系统自动审批");
    }

    @Test
    @DisplayName("阴性对照: 人工审批却没有审批人 → 仍然显式不建 (没有加任何兜底 id)")
    void manualEventWithoutApproverStillRefuses() {
        orchestrator.onSalesOrderFinanceApproved(
                new SalesOrderFinanceApprovedEvent(this, FACTORY, SO_ID, null));

        verify(productionPlanRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("阴性对照: 系统自动通过但连触发人也没有 → 不建, 不编一个人")
    void systemAutoWithoutTriggerStillRefuses() {
        orchestrator.onSalesOrderFinanceApproved(SalesOrderFinanceApprovedEvent.systemAutoApproved(
                this, FACTORY, SO_ID, null, null, "销售审批工作流自动通过"));

        verify(productionPlanRepository, never()).saveAndFlush(any());
    }
}
