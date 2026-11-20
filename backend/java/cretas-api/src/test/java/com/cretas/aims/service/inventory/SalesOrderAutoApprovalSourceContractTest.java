package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent.ApprovalSource;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-SO-AUTOPLAN-NOAPPROVER: 销售订单财审通过事件要说清「谁 / 怎么」通过的。
 *
 * <p>OA 审批流启动即命中自动通过条件时没有人点「通过」, 事件 {@code approvedBy} 为 null; 原来事件只带它,
 * 下游自动建计划拿不到建单人, 抛「缺少审批人」, 订单通过了计划却没有 (prod 2026-11-10 F006
 * SO-20261110-0005/0014/0015)。现在事件标 {@code SYSTEM_AUTO} 并带上提交审批的人 ({@code triggeredBy});
 * 审批人仍如实为空, 不把提交人冒充成审批人。人工审批不变。
 *
 * <p>下游「有 triggeredBy ⇒ 照常建计划」由 {@code AutoPlanFromSystemAutoApprovalContractTest} 钉;
 * 本类钉上游「OA 自动通过时事件里确实带了它」。夹具与 {@link SalesServiceImplApprovalThresholdN9Test} 相同
 * (那个类在 PR 选择基线里, PR CI 不跑; 本类命名 *ContractTest 以被 PR 作业选中)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("D-SO-AUTOPLAN-NOAPPROVER 财审通过事件带审批来源与触发人")
class SalesOrderAutoApprovalSourceContractTest {

    private static final String FACTORY = "F006";
    private static final String ORDER_ID = "SO-ID-1";
    private static final Long INITIATOR = 901L;

    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private WorkflowEngineService workflowEngine;
    @Mock private ApprovalWorkflowService approvalWorkflowService;

    private SalesServiceImpl salesService;

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository,
                salesOrderItemRepository,
                deliveryRecordRepository,
                finishedGoodsBatchRepository,
                customerRepository,
                productTypeRepository,
                arApService,
                eventPublisher);
        ReflectionTestUtils.setField(salesService, "workflowEngine", workflowEngine);
        ReflectionTestUtils.setField(salesService, "approvalWorkflowService", approvalWorkflowService);
        lenient().when(salesOrderRepository.save(any(SalesOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("🔴 OA 启动即自动通过 → 事件 SYSTEM_AUTO + 触发人 = 提交人, 审批人如实为空")
    void oaAutoApprovalPublishesSystemAutoSourceWithSubmitter() {
        SalesOrder order = order(SalesOrderStatus.DRAFT);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(item()));
        when(workflowEngine.startWorkflowIfConfigured(
                eq(FACTORY), eq("SALES_ORDER"), eq(ORDER_ID), anyMap(), eq(INITIATOR)))
                .thenReturn(Optional.of(instance(InstanceStatus.APPROVED, INITIATOR)));

        SalesOrder result = salesService.confirmOrder(FACTORY, ORDER_ID, INITIATOR);

        assertEquals(SalesOrderStatus.FINANCE_APPROVED, result.getStatus());
        SalesOrderFinanceApprovedEvent event = publishedFinanceApproved();
        assertEquals(ApprovalSource.SYSTEM_AUTO, event.getApprovalSource());
        assertEquals(INITIATOR, event.getTriggeredBy(), "下游建计划要知道是谁触发了这次自动通过");
        assertEquals("销售审批工作流自动通过", event.getAutoApprovalReason());
        assertNull(event.getApprovedBy(), "不把提交人冒充成审批人");
        assertNull(result.getFinanceReviewedBy());
    }

    @Test
    @DisplayName("人工审批不变: OA 节点上有人点通过 → 事件 MANUAL + 真实审批人, 没有触发人")
    void oaManualApprovalIsManualWithTheRealApprover() {
        SalesOrder order = order(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY)).thenReturn(Optional.of(order));
        when(workflowEngine.getInstance(FACTORY, "inst-1"))
                .thenReturn(Optional.of(instance(InstanceStatus.RUNNING, 101L)));
        when(workflowEngine.transitionNode(
                "inst-1", 202L, "finance_manager", HistoryAction.APPROVE, "approved"))
                .thenReturn(instance(InstanceStatus.APPROVED, 101L));

        salesService.applyWorkflowAction(
                FACTORY, ORDER_ID, "inst-1", 202L, "finance_manager", HistoryAction.APPROVE, "approved");

        SalesOrderFinanceApprovedEvent event = publishedFinanceApproved();
        assertEquals(ApprovalSource.MANUAL, event.getApprovalSource());
        assertEquals(202L, event.getApprovedBy());
        assertNull(event.getTriggeredBy());
    }

    /** 发出去的事件不止一种 (如 SalesOrderConfirmedEvent), 只取财审通过那一个, 且必须恰好一个。 */
    private SalesOrderFinanceApprovedEvent publishedFinanceApproved() {
        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, Mockito.atLeastOnce()).publishEvent(captor.capture());
        List<SalesOrderFinanceApprovedEvent> approved = captor.getAllValues().stream()
                .filter(SalesOrderFinanceApprovedEvent.class::isInstance)
                .map(SalesOrderFinanceApprovedEvent.class::cast)
                .toList();
        assertEquals(1, approved.size(), "财审通过事件必须恰好发一次");
        return approved.get(0);
    }

    private SalesOrder order(SalesOrderStatus status) {
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY);
        order.setOrderNumber("SO-20261110-0005");
        order.setCustomerId("C-001");
        order.setOrderDate(LocalDate.of(2026, 11, 10));
        order.setCreatedBy(101L);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("6000.00"));
        order.setItems(new ArrayList<>());
        return order;
    }

    private SalesOrderItem item() {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId("PT-001");
        item.setProductName("Product A");
        item.setQuantity(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("10.00"));
        return item;
    }

    private ApprovalWorkflowInstance instance(InstanceStatus status, Long initiatedBy) {
        return ApprovalWorkflowInstance.builder()
                .id("inst-1")
                .factoryId(FACTORY)
                .workflowId("wf-sales")
                .moduleCode("SALES_ORDER")
                .businessEntityId(ORDER_ID)
                .status(status)
                .currentNodeIds(status == InstanceStatus.RUNNING
                        ? new ArrayList<>(List.of("approval_finance"))
                        : new ArrayList<>())
                .contextJson(Map.of())
                .initiatedBy(initiatedBy)
                .build();
    }
}
