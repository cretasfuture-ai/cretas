package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PaymentRecordRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.discipline.DisciplineFixtures;
import com.cretas.aims.service.inventory.impl.ApprovedSalesOrderCancellation;
import com.cretas.aims.service.scheduling.CancelledPlanScheduleCascade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 销售单财务批准后取消 (工厂开关 sales.order.cancel_after_approval, 2026-11-11): 开关、权限、原因、
 * 发货 / 收款 / 已完成 / 合并计划各道闸, 以及只连带取消本单自动生成的计划。
 */
class ApprovedSalesOrderCancellationContractTest {

    private static final String F = "F1";
    private static final Long BOSS = 7L;

    private final Map<String, String> config = new HashMap<>();
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SalesDeliveryRecordRepository deliveries = mock(SalesDeliveryRecordRepository.class);
    private final ArApTransactionRepository arAp = mock(ArApTransactionRepository.class);
    private final PaymentRecordRepository payments = mock(PaymentRecordRepository.class);
    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final ProductionPlanService planService = mock(ProductionPlanService.class);
    private final CancelledPlanScheduleCascade cascade = mock(CancelledPlanScheduleCascade.class);
    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseEventRepository caseEvents = mock(ShortageCaseEventRepository.class);
    private final com.cretas.aims.repository.InvoiceRecordRepository invoices = mock(com.cretas.aims.repository.InvoiceRecordRepository.class);
    private final com.cretas.aims.repository.MaterialBatchRepository materialBatches = mock(com.cretas.aims.repository.MaterialBatchRepository.class);
    private final com.cretas.aims.service.shortage.ShortageCaseActionService caseActions =
            mock(com.cretas.aims.service.shortage.ShortageCaseActionService.class);
    private ApprovedSalesOrderCancellation service;

    private SalesOrder order;
    private final List<ProductionPlan> linked = new ArrayList<>();
    private final List<SalesDeliveryRecord> deliveryRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ApprovedSalesOrderCancellation(DisciplineFixtures.policy(config), orders, permissions, users,
                deliveries, arAp, payments, plans, planService, cascade, cases, caseEvents);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "invoiceRecordRepository", invoices);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatches);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "shortageCaseActionService", caseActions);
        order = new SalesOrder();
        order.setId("SO-1");
        order.setFactoryId(F);
        order.setOrderNumber("SO-20261111-001");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        when(orders.findByIdAndFactoryIdForUpdate("SO-1", F)).thenAnswer(i -> Optional.of(order));
        User boss = new User();
        boss.setId(BOSS);
        boss.setFactoryId(F);
        when(users.findByIdAndFactoryId(BOSS, F)).thenReturn(Optional.of(boss));
        when(permissions.hasPermission(boss, PermissionService.SALES_ORDER_CANCEL_APPROVED_PERMISSION)).thenReturn(true);
        when(deliveries.findBySalesOrderId("SO-1")).thenReturn(deliveryRows);
        when(plans.findByFactoryIdAndSourceOrderIdExact(F, "SO-1")).thenReturn(linked);
        when(cascade.summarize(eq(F), anyString())).thenReturn(new CancelledPlanScheduleCascade.Outcome(2, 1));
        when(cases.findOpenForUpdate(F, ShortageCase.SourceType.SALES_ORDER, "SO-1")).thenReturn(Optional.empty());
    }

    private void switchOn() {
        config.put(ThresholdKeys.SALES_ORDER_CANCEL_AFTER_APPROVAL, "1");
    }

    private ProductionPlan plan(String id, PlanSourceType source, ProductionPlanStatus status) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setPlanNumber("PP-" + id);
        p.setFactoryId(F);
        p.setSourceOrderId("SO-1");
        p.setSourceType(source);
        p.setStatus(status);
        linked.add(p);
        return p;
    }

    @Test
    void switchOffDefaultRefusesExactlyLikeBefore() {
        plan("A", PlanSourceType.CUSTOMER_ORDER, ProductionPlanStatus.PENDING);

        assertThat(service.enabled(F)).isFalse();
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("驳回/退款");
                });
        verify(planService, never()).cancelProductionPlan(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aMistypedSwitchValueStaysOff() {
        config.put(ThresholdKeys.SALES_ORDER_CANCEL_AFTER_APPROVAL, "2");
        assertThat(service.enabled(F)).isFalse();
    }

    @Test
    void switchOnCancelsOnlyTheAutoGeneratedPlansAndReportsWhatHappened() {
        switchOn();
        plan("A", PlanSourceType.CUSTOMER_ORDER, ProductionPlanStatus.PENDING);
        plan("B", PlanSourceType.CUSTOMER_ORDER, ProductionPlanStatus.CANCELLED);   // 已取消: 跳过
        plan("M", PlanSourceType.MANUAL, ProductionPlanStatus.PENDING);             // 手工关联: 不动, 说出来

        ApprovedSalesOrderCancellation.Outcome o = service.cancelDownstream(F, "SO-1", " 客户撤单 ", BOSS);

        verify(planService).cancelProductionPlan(eq(F), eq("A"), eq("销售订单 SO-20261111-001 财务批准后取消: 客户撤单"), eq(BOSS), eq("SO-1"));
        verify(planService, never()).cancelProductionPlan(eq(F), eq("B"), anyString(), any(), any());
        verify(planService, never()).cancelProductionPlan(eq(F), eq("M"), anyString(), any(), any());
        assertThat(o.lockedOrder()).isSameAs(order);
        assertThat(o.cancelledPlanNumbers()).containsExactly("PP-A");
        assertThat(o.manualPlansLeft()).containsExactly("PP-M");
        assertThat(o.cancelledSchedules()).isEqualTo(2);
        assertThat(o.describe()).contains("PP-A").contains("2 条未开工").contains("1 条排程任务已开工").contains("PP-M");
    }

    @Test
    void switchOnButNoPermissionIsForbidden() {
        switchOn();
        User clerk = new User();
        clerk.setId(8L);
        clerk.setFactoryId(F);
        when(users.findByIdAndFactoryId(8L, F)).thenReturn(Optional.of(clerk));

        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", 8L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", null))   // 无操作人 (AI 工具旧入口)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        verify(planService, never()).cancelProductionPlan(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void switchOnStillRequiresAReason() {
        switchOn();
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "  ", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
    }

    @Test
    void aLiveDeliveryBlocksButACancelledOneDoesNot() {
        switchOn();
        SalesDeliveryRecord cancelled = new SalesDeliveryRecord();
        cancelled.setFactoryId(F);
        cancelled.setStatus(SalesDeliveryStatus.CANCELLED);
        deliveryRows.add(cancelled);
        service.cancelDownstream(F, "SO-1", "客户撤单", BOSS);   // 只有已取消的发货单: 放行

        SalesDeliveryRecord live = new SalesDeliveryRecord();
        live.setFactoryId(F);
        live.setStatus(SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        deliveryRows.add(live);
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("发货单"));
    }

    @Test
    void anyPaymentOrReceivableSendsItToRefund() {
        switchOn();
        when(payments.countLiveBySalesOrder(F, "SO-1")).thenReturn(1L);
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("收款"));

        when(payments.countLiveBySalesOrder(F, "SO-1")).thenReturn(0L);
        when(arAp.countLiveBySalesOrder(F, "SO-1")).thenReturn(1L);
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOf(BusinessException.class);
        verify(planService, never()).cancelProductionPlan(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aCompletedOrMergedPlanBlocksBeforeAnyPlanIsTouched() {
        switchOn();
        plan("A", PlanSourceType.CUSTOMER_ORDER, ProductionPlanStatus.PENDING);
        plan("C", PlanSourceType.CUSTOMER_ORDER, ProductionPlanStatus.COMPLETED);
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("已完成"));

        linked.clear();
        plan("A", PlanSourceType.CUSTOMER_ORDER, ProductionPlanStatus.PENDING);
        ProductionPlan merged = plan("G", PlanSourceType.CUSTOMER_ORDER, ProductionPlanStatus.PENDING);
        merged.setSourceOrderIds(new ArrayList<>(List.of("SO-1", "SO-OTHER")));
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("合并"));
        verify(planService, never()).cancelProductionPlan(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void anOrderThatIsNoLongerApprovedIsRefusedAfterTheLock() {
        switchOn();
        order.setStatus(SalesOrderStatus.PROCESSING);
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
    }

    @Test
    void theOpenShortageCaseOfTheOrderIsCancelledThroughTheFormalDecision() {
        switchOn();
        ShortageCase c = new ShortageCase();
        c.setId("CASE-1");
        c.setFactoryId(F);
        c.setStage(ShortageCase.Stage.DETECTED);
        when(cases.findOpenForUpdate(F, ShortageCase.SourceType.SALES_ORDER, "SO-1")).thenReturn(Optional.of(c));

        ApprovedSalesOrderCancellation.Outcome o = service.cancelDownstream(F, "SO-1", "客户撤单", BOSS);

        assertThat(o.shortageCaseCancelled()).isTrue();
        // 和人在缺料页点「取消」同一条路: 写决定台账、数在走的采购单 (独立审查 #1589-5)
        verify(caseActions).decide(eq(F), eq("CASE-1"), eq(com.cretas.aims.entity.shortage.ShortageCaseAction.Decision.CANCEL),
                org.mockito.ArgumentMatchers.contains("客户撤单"), org.mockito.ArgumentMatchers.isNull(),
                eq(new com.cretas.aims.security.ActorRef(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, BOSS)));
    }

    @Test
    void anInvoiceRequestedApprovedOrIssuedBlocks() {
        switchOn();
        plan("A", PlanSourceType.CUSTOMER_ORDER, ProductionPlanStatus.PENDING);
        when(invoices.findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(eq(F), eq("SO-1"), any()))
                .thenReturn(List.of(new com.cretas.aims.entity.finance.InvoiceRecord()));
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("发票"));

        when(invoices.findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(eq(F), eq("SO-1"), any()))
                .thenReturn(List.of());
        order.setInvoicedAmount(new java.math.BigDecimal("10"));
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("发票"));
        verify(planService, never()).cancelProductionPlan(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void receivedCustomerSuppliedMaterialBlocks() {
        switchOn();
        when(materialBatches.countCustomerOwnedWithStockForSalesOrder(F, "SO-1")).thenReturn(2L);
        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("客户提供的原料 2 批"));
    }

    @Test
    void anOrderMergedIntoAnotherOrdersPlanBlocks() {
        switchOn();
        ProductionPlan other = new ProductionPlan();
        other.setId("OTHER");
        other.setPlanNumber("PP-OTHER");
        other.setStatus(ProductionPlanStatus.PENDING);
        other.setSourceOrderId("SO-MAIN");
        when(plans.findByFactoryIdAndSourceOrderIdsContaining(F, "[\"SO-1\"]")).thenReturn(List.of(other));
        // 锁顺序: 缺料实例紧跟销售单锁住, 在任何计划之前 (与缺料重排「实例 → 计划」同序)
        org.mockito.InOrder lockOrder = org.mockito.Mockito.inOrder(orders, cases);

        assertThatThrownBy(() -> service.cancelDownstream(F, "SO-1", "客户撤单", BOSS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("PP-OTHER"));
        lockOrder.verify(orders).findByIdAndFactoryIdForUpdate("SO-1", F);
        lockOrder.verify(cases).findOpenForUpdate(F, ShortageCase.SourceType.SALES_ORDER, "SO-1");

        other.setStatus(ProductionPlanStatus.CANCELLED);   // 那个计划已取消: 不再挡
        service.cancelDownstream(F, "SO-1", "客户撤单", BOSS);
    }

    @Test
    void mayCancelApprovedNeedsBothTheSwitchAndThePermission() {
        assertThat(service.currentUserMayCancelApproved(F, BOSS)).as("开关关").isFalse();
        switchOn();
        assertThat(service.currentUserMayCancelApproved(F, BOSS)).isTrue();
        assertThat(service.currentUserMayCancelApproved(F, 99L)).as("不是本厂用户 / 没权限").isFalse();
        assertThat(service.currentUserMayCancelApproved(F, null)).isFalse();
    }
}
