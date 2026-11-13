package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PaymentRecordRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.DeliveryRiskCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseOutcomeCheckRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.discipline.DisciplineFixtures;
import com.cretas.aims.service.inventory.impl.ApprovedSalesOrderCancellation;
import com.cretas.aims.service.scheduling.CancelledPlanScheduleCascade;
import com.cretas.aims.service.shortage.DeliveryRiskCaseStore;
import com.cretas.aims.service.shortage.ShortageCaseActionService;
import com.cretas.aims.service.unit.UnitContractService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单取消的收尾 (2026-11-13): 取消权限与销售写权限的关系、交付风险实例随订单关闭、
 * 缺料采购草稿随订单取消 (已审批的不动并说出来)、锁顺序不变。
 */
class SalesOrderCancelTailContractTest {

    private static final String F = "F1";
    private static final Long BOSS = 7L;
    private static final ActorRef BOSS_ACTOR = new ActorRef(AuthenticatedActorType.FACTORY_USER, BOSS);

    private final Map<String, String> config = new HashMap<>();
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final ProductionPlanService planService = mock(ProductionPlanService.class);
    private final CancelledPlanScheduleCascade cascade = mock(CancelledPlanScheduleCascade.class);
    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseActionService caseActions = mock(ShortageCaseActionService.class);
    private final DeliveryRiskCaseStore riskStore = mock(DeliveryRiskCaseStore.class);
    private ApprovedSalesOrderCancellation service;
    private SalesOrder order;
    private final List<ProductionPlan> linked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ApprovedSalesOrderCancellation(DisciplineFixtures.policy(config), orders, permissions, users,
                mock(SalesDeliveryRecordRepository.class), mock(ArApTransactionRepository.class),
                mock(PaymentRecordRepository.class), plans, planService, cascade, cases,
                mock(ShortageCaseEventRepository.class));
        ReflectionTestUtils.setField(service, "invoiceRecordRepository", mock(com.cretas.aims.repository.InvoiceRecordRepository.class));
        ReflectionTestUtils.setField(service, "materialBatchRepository", mock(com.cretas.aims.repository.MaterialBatchRepository.class));
        ReflectionTestUtils.setField(service, "shortageCaseActionService", caseActions);
        ReflectionTestUtils.setField(service, "deliveryRiskCaseStore", riskStore);
        order = new SalesOrder();
        order.setId("SO-1");
        order.setFactoryId(F);
        order.setOrderNumber("SO-1-NO");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        config.put(ThresholdKeys.SALES_ORDER_CANCEL_AFTER_APPROVAL, "1");
        when(orders.findByIdAndFactoryIdForUpdate("SO-1", F)).thenAnswer(i -> Optional.of(order));
        User boss = new User();
        boss.setId(BOSS);
        boss.setFactoryId(F);
        when(users.findByIdAndFactoryId(BOSS, F)).thenReturn(Optional.of(boss));
        when(permissions.hasPermission(boss, PermissionService.SALES_ORDER_CANCEL_APPROVED_PERMISSION)).thenReturn(true);
        when(plans.findByFactoryIdAndSourceOrderIdExact(F, "SO-1")).thenReturn(linked);
        when(cascade.summarize(eq(F), anyString())).thenReturn(new CancelledPlanScheduleCascade.Outcome(0, 0));
        when(cases.findOpenForUpdate(F, ShortageCase.SourceType.SALES_ORDER, "SO-1")).thenReturn(Optional.empty());
        when(cases.findOpenForUpdate(F, ShortageCase.SourceType.DELIVERY_RISK, "SO-1")).thenReturn(Optional.empty());
    }

    private ShortageCase openCase(String id, ShortageCase.SourceType type) {
        ShortageCase c = new ShortageCase();
        c.setId(id);
        c.setFactoryId(F);
        c.setSourceType(type);
        c.setSourceId("SO-1");
        c.setStage(ShortageCase.Stage.DETECTED);
        when(cases.findOpenForUpdate(F, type, "SO-1")).thenReturn(Optional.of(c));
        return c;
    }

    private ProductionPlan autoPlan(String id) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setPlanNumber("PP-" + id);
        p.setFactoryId(F);
        p.setSourceOrderId("SO-1");
        p.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        p.setStatus(ProductionPlanStatus.PENDING);
        linked.add(p);
        return p;
    }

    // ------------------------------------------------------------------ 2. 取消权限 vs 销售写权限

    @Test
    void aCancelApprovedHolderWithoutSalesWriteCannotCancelOrdinaryOrders() {
        User clerk = new User();
        clerk.setId(8L);
        clerk.setFactoryId(F);
        when(users.findByIdAndFactoryId(8L, F)).thenReturn(Optional.of(clerk));
        when(permissions.hasPermission(clerk, PermissionService.SALES_ORDER_CANCEL_APPROVED_PERMISSION)).thenReturn(true);
        when(permissions.hasPermission(clerk, "sales:read_write")).thenReturn(false);

        assertThatThrownBy(() -> service.requireSalesWriteForOrdinaryCancel(F, 8L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
    }

    @Test
    void salesWriteHoldersAndInternalCallsPassTheOrdinaryCancelGate() {
        User seller = new User();
        seller.setId(9L);
        seller.setFactoryId(F);
        when(users.findByIdAndFactoryId(9L, F)).thenReturn(Optional.of(seller));
        when(permissions.hasPermission(seller, "sales:read_write")).thenReturn(true);

        service.requireSalesWriteForOrdinaryCancel(F, 9L);
        service.requireSalesWriteForOrdinaryCancel(F, null);   // 无操作人 (内部 / 旧调用) 保持原行为
    }

    @Test
    void thePlansOfTheCancelledOrderAreCancelledWithTheOrderIdSoTheCancellersRowScopeDoesNotBlockThem() {
        autoPlan("A");

        service.cancelDownstream(F, "SO-1", "客户撤单", BOSS);

        verify(planService).cancelProductionPlan(eq(F), eq("A"), anyString(), eq(BOSS), eq("SO-1"));
        verify(planService, never()).cancelProductionPlan(anyString(), anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------ 3. 交付风险实例 / 缺料采购单

    @Test
    void theDeliveryRiskCaseIsCancelledWithTheOrderAndLockedBeforeAnyPlan() {
        ShortageCase risk = openCase("RISK-1", ShortageCase.SourceType.DELIVERY_RISK);
        autoPlan("A");

        ApprovedSalesOrderCancellation.Outcome o = service.cancelDownstream(F, "SO-1", "客户撤单", BOSS);

        assertThat(o.deliveryRiskCancelled()).isTrue();
        assertThat(o.describe()).contains("交付风险");
        verify(riskStore).cancelWithSalesOrder(eq(risk), org.mockito.ArgumentMatchers.contains("客户撤单"), eq(BOSS_ACTOR));
        // 实例 → 计划 的加锁顺序: 销售单 → 缺料实例 → 交付风险实例 → 计划
        InOrder lockOrder = inOrder(orders, cases, planService);
        lockOrder.verify(orders).findByIdAndFactoryIdForUpdate("SO-1", F);
        lockOrder.verify(cases).findOpenForUpdate(F, ShortageCase.SourceType.SALES_ORDER, "SO-1");
        lockOrder.verify(cases).findOpenForUpdate(F, ShortageCase.SourceType.DELIVERY_RISK, "SO-1");
        lockOrder.verify(planService).cancelProductionPlan(eq(F), eq("A"), anyString(), eq(BOSS), eq("SO-1"));
    }

    @Test
    void noDeliveryRiskCaseMeansNothingToCancel() {
        ApprovedSalesOrderCancellation.Outcome o = service.cancelDownstream(F, "SO-1", "客户撤单", BOSS);
        assertThat(o.deliveryRiskCancelled()).isFalse();
        verify(riskStore, never()).cancelWithSalesOrder(any(), anyString(), any());
    }

    @Test
    void shortageDraftsAreHandledBeforeTheCaseIsCancelledAndTheOutcomeNamesThem() {
        ShortageCase c = openCase("CASE-1", ShortageCase.SourceType.SALES_ORDER);
        when(caseActions.cancelDraftsForCancelledOrder(eq(c), anyString(), eq(BOSS_ACTOR)))
                .thenReturn(new ShortageCaseActionService.DraftsCancelled(List.of("PO-DRAFT"), List.of("PO-APPROVED")));

        ApprovedSalesOrderCancellation.Outcome o = service.cancelDownstream(F, "SO-1", "客户撤单", BOSS);

        InOrder draftsFirst = inOrder(caseActions);
        draftsFirst.verify(caseActions).cancelDraftsForCancelledOrder(eq(c), anyString(), eq(BOSS_ACTOR));
        draftsFirst.verify(caseActions).decide(eq(F), eq("CASE-1"), eq(ShortageCaseAction.Decision.CANCEL), anyString(), any(), eq(BOSS_ACTOR));
        assertThat(o.purchasesCancelled()).containsExactly("PO-DRAFT");
        assertThat(o.purchasesLeftLive()).containsExactly("PO-APPROVED");
        assertThat(o.describe()).contains("PO-DRAFT").contains("已一并取消").contains("PO-APPROVED").contains("请采购自行处理");
    }

    // ------------------------------------------------------------------ ShortageCaseActionService: 只收没审批的采购草稿

    private ShortageCaseActionService actionService(ShortageCaseEventRepository events, ShortageCaseActionRepository actions,
                                                    PurchaseOrderRepository pos, PurchaseService purchase) {
        return new ShortageCaseActionService(mock(ShortageCaseRepository.class), mock(ShortageCaseLineRepository.class),
                events, actions, mock(SupplierMaterialRepository.class), pos, purchase,
                mock(UnitContractService.class), users);
    }

    @Test
    void onlyUnapprovedShortageDraftsAreCancelledAndEverythingIsRecorded() {
        ShortageCaseEventRepository events = mock(ShortageCaseEventRepository.class);
        ShortageCaseActionRepository actions = mock(ShortageCaseActionRepository.class);
        PurchaseOrderRepository pos = mock(PurchaseOrderRepository.class);
        PurchaseService purchase = mock(PurchaseService.class);
        ShortageCaseActionService svc = actionService(events, actions, pos, purchase);
        ShortageCase c = new ShortageCase();
        c.setId("CASE-1");
        c.setFactoryId(F);
        c.setStage(ShortageCase.Stage.EXECUTING);
        List<ShortageCaseAction> rows = new ArrayList<>();
        for (String[] spec : new String[][]{{"P-DRAFT", "PO-D", "DRAFT"}, {"P-SUB", "PO-S", "SUBMITTED"},
                {"P-APP", "PO-A", "APPROVED"}, {"P-PART", "PO-P", "PARTIAL_RECEIVED"}, {"P-CAN", "PO-C", "CANCELLED"}}) {
            ShortageCaseAction a = new ShortageCaseAction();
            a.setActionType(ShortageCaseAction.ActionType.PURCHASE_DRAFT);
            a.setStatus(ShortageCaseAction.Status.DONE);
            a.setResultRef(spec[0]);
            rows.add(a);
            PurchaseOrder po = new PurchaseOrder();
            po.setId(spec[0]);
            po.setOrderNumber(spec[1]);
            po.setStatus(PurchaseOrderStatus.valueOf(spec[2]));
            when(pos.findByIdAndFactoryId(spec[0], F)).thenReturn(Optional.of(po));
        }
        ShortageCaseAction task = new ShortageCaseAction();
        task.setActionType(ShortageCaseAction.ActionType.HANDLING_TASK);
        task.setStatus(ShortageCaseAction.Status.DONE);
        task.setResultRef("9");
        rows.add(task);
        when(actions.findByCaseIdOrderByIdAsc("CASE-1")).thenReturn(rows);

        ShortageCaseActionService.DraftsCancelled r = svc.cancelDraftsForCancelledOrder(c, "客户撤单", BOSS_ACTOR);

        assertThat(r.cancelled()).containsExactly("PO-D", "PO-S");
        assertThat(r.keptLive()).containsExactly("PO-A（已审批）", "PO-P（部分到货）");   // 按实际状态说      // 已审批 / 已部分到货: 有供应商承诺, 不自动撤
        verify(purchase).cancelOrder(F, "P-DRAFT");
        verify(purchase).cancelOrder(F, "P-SUB");
        verify(purchase, never()).cancelOrder(F, "P-APP");
        verify(purchase, never()).cancelOrder(F, "P-PART");
        verify(purchase, never()).cancelOrder(F, "P-CAN");              // 本来就取消了的不碰
        ArgumentCaptor<ShortageCaseEvent> ev = ArgumentCaptor.forClass(ShortageCaseEvent.class);
        verify(events).save(ev.capture());
        assertThat(ev.getValue().getTriggerReason()).isEqualTo("PURCHASE_DRAFTS_CANCELLED_WITH_ORDER");
        assertThat(ev.getValue().getDetail()).contains("客户撤单").contains("PO-D").contains("PO-S").contains("PO-A（已审批）").contains("PO-P（部分到货）").doesNotContain("已到货");
        assertThat(ev.getValue().getActorId()).isEqualTo(BOSS);
    }

    @Test
    void noShortageDraftsMeansNoPurchaseCallAndNoEvent() {
        ShortageCaseEventRepository events = mock(ShortageCaseEventRepository.class);
        ShortageCaseActionRepository actions = mock(ShortageCaseActionRepository.class);
        PurchaseService purchase = mock(PurchaseService.class);
        ShortageCaseActionService svc = actionService(events, actions, mock(PurchaseOrderRepository.class), purchase);
        ShortageCase c = new ShortageCase();
        c.setId("CASE-2");
        c.setFactoryId(F);
        when(actions.findByCaseIdOrderByIdAsc("CASE-2")).thenReturn(List.of());

        ShortageCaseActionService.DraftsCancelled r = svc.cancelDraftsForCancelledOrder(c, "客户撤单", BOSS_ACTOR);

        assertThat(r.cancelled()).isEmpty();
        assertThat(r.keptLive()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(purchase, events);
    }

    // ------------------------------------------------------------------ DeliveryRiskCaseStore: 关闭并留痕

    private DeliveryRiskCaseStore realStore(ShortageCaseRepository repo, ShortageCaseEventRepository events,
                                            ShortageCaseActionRepository actions) {
        return new DeliveryRiskCaseStore(repo, events, actions, mock(ShortageCaseOutcomeCheckRepository.class),
                mock(DeliveryRiskCaseDetailRepository.class), users, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void cancellingARiskCaseWithTheOrderClosesItAndLeavesADecisionAndAnEvent() {
        ShortageCaseRepository repo = mock(ShortageCaseRepository.class);
        ShortageCaseEventRepository events = mock(ShortageCaseEventRepository.class);
        ShortageCaseActionRepository actions = mock(ShortageCaseActionRepository.class);
        DeliveryRiskCaseStore store = realStore(repo, events, actions);
        ShortageCase c = new ShortageCase();
        c.setId("RISK-1");
        c.setFactoryId(F);
        c.setSourceType(ShortageCase.SourceType.DELIVERY_RISK);
        c.setStage(ShortageCase.Stage.EXECUTING);

        store.cancelWithSalesOrder(c, "销售订单财务批准后取消: 客户撤单", BOSS_ACTOR);

        assertThat(c.getStage()).isEqualTo(ShortageCase.Stage.CANCELLED);
        assertThat(c.getClosedAt()).isNotNull();
        verify(repo).saveAndFlush(c);
        ArgumentCaptor<ShortageCaseAction> act = ArgumentCaptor.forClass(ShortageCaseAction.class);
        verify(actions).saveAndFlush(act.capture());
        assertThat(act.getValue().getDecision()).isEqualTo(ShortageCaseAction.Decision.CANCEL);
        assertThat(act.getValue().getActionType()).isEqualTo(ShortageCaseAction.ActionType.DECISION);
        ArgumentCaptor<ShortageCaseEvent> ev = ArgumentCaptor.forClass(ShortageCaseEvent.class);
        verify(events).save(ev.capture());
        assertThat(ev.getValue().getFromStage()).isEqualTo(ShortageCase.Stage.EXECUTING);
        assertThat(ev.getValue().getToStage()).isEqualTo(ShortageCase.Stage.CANCELLED);
        assertThat(ev.getValue().getTriggerReason()).isEqualTo("SALES_ORDER_CANCELLED");
    }

    @Test
    void anAlreadyClosedOrForeignCaseIsRefusedAndLeftAlone() {
        ShortageCaseRepository repo = mock(ShortageCaseRepository.class);
        DeliveryRiskCaseStore store = realStore(repo, mock(ShortageCaseEventRepository.class),
                mock(ShortageCaseActionRepository.class));
        ShortageCase closed = new ShortageCase();
        closed.setSourceType(ShortageCase.SourceType.DELIVERY_RISK);
        closed.setStage(ShortageCase.Stage.CLOSED);
        closed.setClosedAt(java.time.LocalDateTime.now());
        ShortageCase shortage = new ShortageCase();
        shortage.setSourceType(ShortageCase.SourceType.SALES_ORDER);
        shortage.setStage(ShortageCase.Stage.DETECTED);

        assertThatThrownBy(() -> store.cancelWithSalesOrder(closed, "x", BOSS_ACTOR)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.cancelWithSalesOrder(shortage, "x", BOSS_ACTOR)).isInstanceOf(BusinessException.class);
        verify(repo, never()).saveAndFlush(any());
        assertThat(shortage.getStage()).isEqualTo(ShortageCase.Stage.DETECTED);
    }
}
