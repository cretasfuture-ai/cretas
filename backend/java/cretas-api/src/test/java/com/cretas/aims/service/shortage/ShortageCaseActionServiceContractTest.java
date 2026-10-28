package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.unit.UnitContractService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 缺料实例的采购交接与人工决定 (S4-06 / S4-09 / S5-03): 量 = 缺口 − 在途、起订量取整、单位不同不换算、
 * 点几次都只有一张在走的草稿、只有本厂用户能下单、决定必须写原因。
 */
class ShortageCaseActionServiceContractTest {

    private static final ActorRef BUYER = new ActorRef(AuthenticatedActorType.FACTORY_USER, 9L);

    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseLineRepository lines = mock(ShortageCaseLineRepository.class);
    private final ShortageCaseEventRepository events = mock(ShortageCaseEventRepository.class);
    private final ShortageCaseActionRepository actions = mock(ShortageCaseActionRepository.class);
    private final SupplierMaterialRepository supplierMaterials = mock(SupplierMaterialRepository.class);
    private final PurchaseOrderRepository purchaseOrders = mock(PurchaseOrderRepository.class);
    private final PurchaseService purchaseService = mock(PurchaseService.class);
    private final UnitContractService units = mock(UnitContractService.class);
    private final UserRepository users = mock(UserRepository.class);
    private ShortageCaseActionService service;

    private ShortageCase theCase;
    private ShortageCaseLine line;
    private SupplierMaterial supplier;
    private final List<ShortageCaseAction> actionStore = new ArrayList<>();
    private final Map<String, PurchaseOrder> poStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new ShortageCaseActionService(cases, lines, events, actions, supplierMaterials, purchaseOrders, purchaseService, units, users);
        when(users.findByIdAndFactoryId(77L, "F1")).thenReturn(Optional.of(new User()));
        when(units.areEquivalent(eq("F1"), anyString(), anyString())).thenAnswer(i -> {
            String a = ((String) i.getArgument(1)).trim().toLowerCase(), b = ((String) i.getArgument(2)).trim().toLowerCase();
            return a.replace("千克", "kg").equals(b.replace("千克", "kg"));
        });
        theCase = new ShortageCase();
        theCase.setId("C1");
        theCase.setFactoryId("F1");
        theCase.setSourceType(ShortageCase.SourceType.SALES_ORDER);
        theCase.setSourceId("SO-1");
        theCase.setStage(Stage.DETECTED);
        theCase.setOwnerRole("procurement_manager");
        when(cases.findByIdAndFactoryId("C1", "F1")).thenReturn(Optional.of(theCase));
        when(cases.findOpenForUpdate("F1", ShortageCase.SourceType.SALES_ORDER, "SO-1"))
                .thenAnswer(i -> theCase.getClosedAt() == null ? Optional.of(theCase) : Optional.empty());
        when(cases.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        line = new ShortageCaseLine();
        line.setCaseId("C1");
        line.setMaterialTypeId("M1");
        line.setMaterialName("猪大骨");
        line.setUnit("kg");
        line.setRequiredQty(new BigDecimal("10"));
        line.setReleasedQty(new BigDecimal("2"));
        line.setInspectingQty(BigDecimal.ZERO);
        line.setOnOrderQty(new BigDecimal("3"));
        line.setGapQty(new BigDecimal("8"));
        when(lines.findByCaseIdOrderByIdAsc("C1")).thenAnswer(i -> List.of(line));

        supplier = new SupplierMaterial();
        supplier.setId("SM-1");
        supplier.setSupplierId("SUP-1");
        supplier.setMaterialTypeId("M1");
        supplier.setActive(true);
        supplier.setPurchaseUnit("kg");
        supplier.setMinOrderQuantity(new BigDecimal("20"));
        supplier.setLeadTimeDays(3);
        when(supplierMaterials.findByFactoryIdAndMaterialTypeIdOrderByActiveDescPreferredDescCreatedAtDesc("F1", "M1"))
                .thenAnswer(i -> List.of(supplier));

        when(actions.saveAndFlush(any())).thenAnswer(i -> {
            ShortageCaseAction a = i.getArgument(0);
            if (!actionStore.contains(a)) actionStore.add(a);
            return a;
        });
        when(actions.findByCaseIdAndMaterialTypeIdAndActionTypeAndStatus(eq("C1"), anyString(), any(), any()))
                .thenAnswer(i -> actionStore.stream()
                        .filter(a -> a.getMaterialTypeId() != null && a.getMaterialTypeId().equals(i.getArgument(1)))
                        .filter(a -> a.getActionType() == i.getArgument(2) && a.getStatus() == i.getArgument(3))
                        .findFirst());
        when(actions.findByCaseIdOrderByIdAsc("C1")).thenAnswer(i -> List.copyOf(actionStore));
        when(purchaseOrders.findByIdAndFactoryId(anyString(), eq("F1")))
                .thenAnswer(i -> Optional.ofNullable(poStore.get((String) i.getArgument(0))));
        when(purchaseService.createPurchaseOrder(eq("F1"), any(), anyLong())).thenAnswer(i -> {
            PurchaseOrder po = new PurchaseOrder();
            po.setId("PO-" + (poStore.size() + 1));
            po.setOrderNumber("PO-NO-" + (poStore.size() + 1));
            po.setStatus(PurchaseOrderStatus.DRAFT);
            poStore.put(po.getId(), po);
            return po;
        });
    }

    @Test
    void theDraftOrdersGapMinusOnOrderRoundedUpToMoqThroughThePurchaseService() {
        var r = service.createProcurementDraft("F1", "C1", "M1", BUYER);

        ArgumentCaptor<CreatePurchaseOrderRequest> req = ArgumentCaptor.forClass(CreatePurchaseOrderRequest.class);
        verify(purchaseService).createPurchaseOrder(eq("F1"), req.capture(), eq(9L));
        var item = req.getValue().getItems().get(0);
        assertThat(item.getQuantity()).isEqualByComparingTo("20");          // 缺 8 − 在途 3 = 5 < 起订量 20
        assertThat(item.getUnit()).isEqualTo("kg");
        assertThat(item.getSupplierMaterialId()).isEqualTo("SM-1");          // 价格交给采购服务自己的带价链
        assertThat(req.getValue().getSupplierId()).isEqualTo("SUP-1");
        assertThat(req.getValue().getSalesOrderId()).isEqualTo("SO-1");
        assertThat(req.getValue().getExpectedDeliveryDate()).isEqualTo(LocalDate.now().plusDays(3));
        assertThat(r.notes()).anyMatch(n -> n.contains("起订量"));
        assertThat(r.reused()).isFalse();
        assertThat(theCase.getStage()).isEqualTo(Stage.EXECUTING);
        assertThat(actionStore).singleElement().satisfies(a -> {
            assertThat(a.getResultRef()).isEqualTo(r.purchaseOrderId());
            assertThat(a.getActorId()).isEqualTo(9L);
        });
    }

    @Test
    void clickingAgainReusesTheLiveDraft() {
        var first = service.createProcurementDraft("F1", "C1", "M1", BUYER);
        var second = service.createProcurementDraft("F1", "C1", "M1", BUYER);

        assertThat(second.reused()).isTrue();
        assertThat(second.purchaseOrderId()).isEqualTo(first.purchaseOrderId());
        verify(purchaseService, times(1)).createPurchaseOrder(any(), any(), anyLong());
    }

    @Test
    void aCancelledDraftCanBeReplaced() {
        var first = service.createProcurementDraft("F1", "C1", "M1", BUYER);
        poStore.get(first.purchaseOrderId()).setStatus(PurchaseOrderStatus.CANCELLED);

        var second = service.createProcurementDraft("F1", "C1", "M1", BUYER);

        assertThat(second.reused()).isFalse();
        assertThat(second.purchaseOrderId()).isNotEqualTo(first.purchaseOrderId());
        assertThat(actionStore.get(0).getStatus()).isEqualTo(ShortageCaseAction.Status.SUPERSEDED);
    }

    @Test
    void aSupplierUnitThatDiffersIsNotConvertedAndNothingIsOrdered() {
        supplier.setPurchaseUnit("箱");

        assertThatThrownBy(() -> service.createProcurementDraft("F1", "C1", "M1", BUYER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不换算");
        verify(purchaseService, never()).createPurchaseOrder(any(), any(), anyLong());
    }

    @Test
    void noSupplierMeansNoAutomaticOrder() {
        supplier.setActive(false);

        assertThatThrownBy(() -> service.createProcurementDraft("F1", "C1", "M1", BUYER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("供应商");
        verify(purchaseService, never()).createPurchaseOrder(any(), any(), anyLong());
    }

    @Test
    void whenOnOrderAlreadyCoversTheGapTheProposalSaysSo() {
        line.setOnOrderQty(new BigDecimal("8"));

        var p = service.proposals("F1", "C1").get(0);

        assertThat(p.canDraft()).isFalse();
        assertThat(p.notes()).anyMatch(n -> n.contains("在途量已覆盖"));
    }

    @Test
    void onlyAFactoryUserCanPlaceTheDraft() {
        assertThatThrownBy(() -> service.createProcurementDraft("F1", "C1", "M1",
                new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 9L)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("工厂用户");
        verify(purchaseService, never()).createPurchaseOrder(any(), any(), anyLong());
    }

    @Test
    void decisionsNeedAReasonAndCloseNeedsAResolvedGap() {
        assertThatThrownBy(() -> service.decide("F1", "C1", ShortageCaseAction.Decision.CANCEL, "  ", null, BUYER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原因");
        assertThatThrownBy(() -> service.decide("F1", "C1", ShortageCaseAction.Decision.CLOSE, "做完了", null, BUYER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("缺口还没解决");
        assertThat(actionStore).isEmpty();

        service.decide("F1", "C1", ShortageCaseAction.Decision.MANUAL, "客户改了配方, 人工跟进", 77L, BUYER);
        assertThat(theCase.getStage()).isEqualTo(Stage.MANUAL);
        assertThat(theCase.getOwnerUserId()).isEqualTo(77L);
        assertThat(actionStore).singleElement().satisfies(a -> {
            assertThat(a.getDecision()).isEqualTo(ShortageCaseAction.Decision.MANUAL);
            assertThat(a.getReason()).isEqualTo("客户改了配方, 人工跟进");
        });
        assertThatThrownBy(() -> service.createProcurementDraft("F1", "C1", "M1", BUYER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已结束");
    }

    @Test
    void cancelClosesTheCase() {
        service.decide("F1", "C1", ShortageCaseAction.Decision.CANCEL, "订单作废", null, BUYER);

        assertThat(theCase.getStage()).isEqualTo(Stage.CANCELLED);
        assertThat(theCase.getClosedAt()).isNotNull();
    }

    @Test
    void aCaseMovedToManualCanStillBeClosedOrCancelledWhichFreesTheOrdersSlot() {
        service.decide("F1", "C1", ShortageCaseAction.Decision.MANUAL, "人工跟进", 77L, BUYER);
        assertThat(theCase.getClosedAt()).isNull();

        service.decide("F1", "C1", ShortageCaseAction.Decision.CLOSE, "供应商已直接送货", null, BUYER);

        assertThat(theCase.getStage()).isEqualTo(Stage.CLOSED);
        assertThat(theCase.getClosedAt()).isNotNull();   // 不再是「未关闭」⇒ 同一订单的唯一位置释放, 下次缺料可建新实例
        assertThat(cases.findOpenForUpdate("F1", ShortageCase.SourceType.SALES_ORDER, "SO-1")).isEmpty();
        assertThatThrownBy(() -> service.decide("F1", "C1", ShortageCaseAction.Decision.CANCEL, "再取消", null, BUYER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已结束");

        theCase.setClosedAt(null);
        theCase.setStage(Stage.DETECTED);
        service.decide("F1", "C1", ShortageCaseAction.Decision.MANUAL, "人工跟进", null, BUYER);
        service.decide("F1", "C1", ShortageCaseAction.Decision.CANCEL, "订单作废", null, BUYER);
        assertThat(theCase.getStage()).isEqualTo(Stage.CANCELLED);
        assertThat(theCase.getClosedAt()).isNotNull();
    }

    @Test
    void onOrderLinesInAnotherUnitBlockTheAutomaticDraft() {
        line.setGapQty(new BigDecimal("50"));
        line.setOnOrderQty(BigDecimal.ZERO);
        line.setOnOrderUnitMismatchLines(1);   // 一张按「箱」下的在途单, 没计进在途量

        var p = service.proposals("F1", "C1").get(0);

        assertThat(p.canDraft()).isFalse();
        assertThat(p.notes()).anyMatch(n -> n.contains("有 1 行在途采购单位不同"));
        assertThatThrownBy(() -> service.createProcurementDraft("F1", "C1", "M1", BUYER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("在途采购单位不同");
        verify(purchaseService, never()).createPurchaseOrder(any(), any(), anyLong());
    }

    @Test
    void aMinimumOrderWithoutAPurchaseUnitIsNotApplied() {
        supplier.setPurchaseUnit(null);   // 起订量 20 —— 20 箱还是 20 kg 不知道

        var p = service.proposals("F1", "C1").get(0);

        assertThat(p.suggestedQty()).isEqualByComparingTo("5");   // 缺 8 − 在途 3, 没被抬成 20
        assertThat(p.notes()).anyMatch(n -> n.contains("没填采购单位, 未套用"));
    }

    @Test
    void aManualOwnerMustBelongToThisFactory() {
        assertThatThrownBy(() -> service.decide("F1", "C1", ShortageCaseAction.Decision.MANUAL, "人工跟进", 88L, BUYER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不是本厂用户");
        assertThat(theCase.getStage()).isEqualTo(Stage.DETECTED);
        assertThat(actionStore).isEmpty();
    }

    @Test
    void cancellingSaysHowManyLiveDraftsAreLeft() {
        service.createProcurementDraft("F1", "C1", "M1", BUYER);
        ArgumentCaptor<com.cretas.aims.entity.shortage.ShortageCaseEvent> ev =
                ArgumentCaptor.forClass(com.cretas.aims.entity.shortage.ShortageCaseEvent.class);

        service.decide("F1", "C1", ShortageCaseAction.Decision.CANCEL, "订单作废", null, BUYER);

        verify(events, atLeastOnce()).save(ev.capture());
        assertThat(ev.getValue().getTriggerReason()).isEqualTo("DECISION_CANCEL");
        assertThat(ev.getValue().getDetail()).contains("仍有 1 张在走的采购单");
    }
}
