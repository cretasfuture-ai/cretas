package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.dto.inventory.PurchaseReturnAvailability;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.security.DataScopeRouteInterceptor;
import com.cretas.aims.service.finance.ArApService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseReturnCapacityContractTest {
    private static final String FACTORY = "F-CAP";
    private static final String PO_ID = "PO-CAP";
    private static final String MATERIAL = "MT-FLOUR";

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private ReturnOrderItemRepository returnOrderItemRepository;
    @Mock private DataScopeResolver dataScopeResolver;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private PurchaseReturnCapacityService service;
    private PurchaseOrder source;

    @BeforeEach
    void setUp() {
        service = new PurchaseReturnCapacityService(purchaseOrderRepository, purchaseOrderItemRepository,
                returnOrderRepository, returnOrderItemRepository);
        ReflectionTestUtils.setField(service, "dataScopeResolver", dataScopeResolver);
        source = new PurchaseOrder();
        source.setId(PO_ID);
        source.setFactoryId(FACTORY);
        source.setSupplierId("SUP-1");
        source.setCreatedBy(7L);
        source.setStatus(PurchaseOrderStatus.COMPLETED);
        PurchaseOrderItem line = new PurchaseOrderItem();
        line.setPurchaseOrderId(PO_ID);
        line.setMaterialTypeId(MATERIAL);
        line.setUnit("kg");
        line.setPurchasePackageUnitSnapshot("kg");
        line.setInventoryBaseUnitSnapshot("kg");
        line.setReceivedQuantity(new BigDecimal("120.00"));
        when(purchaseOrderRepository.findByIdAndFactoryIdForUpdate(PO_ID, FACTORY)).thenReturn(Optional.of(source));
        when(purchaseOrderRepository.findByIdAndFactoryId(PO_ID, FACTORY)).thenReturn(Optional.of(source));
        when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(line));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void availabilityUsesReceivedBaseAndExcludesRejected() {
        ReturnOrder completed = order("RO-C", ReturnOrderStatus.COMPLETED, "30.00");
        ReturnOrder draft = order("RO-D", ReturnOrderStatus.DRAFT, "10.00");
        ReturnOrder rejected = order("RO-R", ReturnOrderStatus.REJECTED, "99.00");
        ReturnOrder refundOnly = order("RO-F", ReturnOrderStatus.COMPLETED, "90.00");
        refundOnly.setWithGoods(false);
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, com.cretas.aims.entity.enums.ReturnType.PURCHASE_RETURN, PO_ID))
                .thenReturn(List.of(completed, draft, rejected, refundOnly));
        when(returnOrderItemRepository.findByReturnOrderId("RO-C")).thenReturn(List.of(returnItem("30.00")));
        when(returnOrderItemRepository.findByReturnOrderId("RO-D")).thenReturn(List.of(returnItem("10.00")));
        when(returnOrderItemRepository.findByReturnOrderId("RO-R")).thenReturn(List.of(returnItem("99.00")));
        when(returnOrderItemRepository.findByReturnOrderId("RO-F")).thenReturn(List.of(returnItem("90.00")));

        PurchaseReturnAvailability result = service.availability(FACTORY, PO_ID);

        assertThat(result.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getBaseUnit()).isEqualTo("kg");
            assertThat(item.getReceivedQuantity()).isEqualByComparingTo("120");
            assertThat(item.getReturnedQuantity()).isEqualByComparingTo("40");
            assertThat(item.getRemainingQuantity()).isEqualByComparingTo("80");
        });
    }

    @Test
    void createGuardRejectsOverCapacityBeforeAnyReturnWrite() {
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of(order("RO-C", ReturnOrderStatus.COMPLETED, "30.00")));
        when(returnOrderItemRepository.findByReturnOrderId("RO-C")).thenReturn(List.of(returnItem("30.00")));

        ReturnOrderServiceImpl returnOrderService = new ReturnOrderServiceImpl(returnOrderRepository,
                returnOrderItemRepository, arApService, applicationEventPublisher);
        ReflectionTestUtils.setField(returnOrderService, "purchaseReturnCapacityService", service);
        assertThatThrownBy(() -> returnOrderService.createReturnOrder(FACTORY, request("91.00", null), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过");
        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));
    }

    @Test
    void duplicateMaterialRowsAreSummedUnderTheSourceLock() {
        CreateReturnOrderRequest request = request("40.00", null);
        request.setItems(List.of(item("20.00"), item("20.00")));

        assertThat(service.prepare(FACTORY, request, 7L).isReplay()).isFalse();
    }

    @Test
    void exactRemainingCapacityIsAccepted() {
        assertThat(service.prepare(FACTORY, request("120.00", null), 7L).isReplay()).isFalse();
    }

    @Test
    void refundOnlyReturnDoesNotConsumePhysicalCapacity() {
        CreateReturnOrderRequest request = request("999.00", null);
        request.setWithGoods(false);

        assertThat(service.prepare(FACTORY, request, 7L).isReplay()).isFalse();
    }

    @Test
    void availabilityAggregatesSameMaterialAcrossSourceLines() {
        PurchaseOrderItem first = new PurchaseOrderItem();
        first.setPurchaseOrderId(PO_ID);
        first.setMaterialTypeId(MATERIAL);
        first.setUnit("kg");
        first.setPurchasePackageUnitSnapshot("kg");
        first.setInventoryBaseUnitSnapshot("kg");
        first.setReceivedQuantity(new BigDecimal("20.00"));
        PurchaseOrderItem second = new PurchaseOrderItem();
        second.setPurchaseOrderId(PO_ID);
        second.setMaterialTypeId(MATERIAL);
        second.setUnit("kg");
        second.setPurchasePackageUnitSnapshot("kg");
        second.setInventoryBaseUnitSnapshot("kg");
        second.setReceivedQuantity(new BigDecimal("30.00"));
        when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(first, second));

        assertThat(service.availability(FACTORY, PO_ID).getItems()).singleElement()
                .satisfies(item -> assertThat(item.getReceivedQuantity()).isEqualByComparingTo("50"));
    }

    @Test
    void sameRequestIdReturnsExistingOrderAndChangedPayloadConflicts() {
        CreateReturnOrderRequest request = request("10.00", "550e8400-e29b-41d4-a716-446655440000");
        String id = java.util.UUID.nameUUIDFromBytes((FACTORY + "\nFACTORY_USER:7\n" + request.getRequestId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        ReturnOrder existing = order(id, ReturnOrderStatus.DRAFT, "10.00");
        existing.setFactoryId(FACTORY);
        existing.setCounterpartyId("SUP-1");
        existing.setSourceOrderId(PO_ID);
        existing.setReturnType(com.cretas.aims.entity.enums.ReturnType.PURCHASE_RETURN);
        existing.setReturnDate(LocalDate.of(2026, 10, 16));
        existing.setReason("quality");
        existing.setRemark(null);
        existing.setWithGoods(true);
        existing.setCreatedBy(7L);
        existing.setTotalAmount(new BigDecimal("50.00"));
        when(returnOrderRepository.findById(id)).thenReturn(Optional.of(existing));
        when(returnOrderItemRepository.findByReturnOrderId(id)).thenReturn(List.of(returnItem("10.00")));

        assertThat(service.prepare(FACTORY, request, 7L).existingOrder()).isSameAs(existing);
        request.setReason("changed");
        assertThatThrownBy(() -> service.prepare(FACTORY, request, 7L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不一致");
    }

    @Test
    void typedPlatformAndFactoryActorsWithSameNumericIdGetDifferentIdempotencyIds() {
        CreateReturnOrderRequest request = request("10.00", "550e8400-e29b-41d4-a716-446655440001");
        String factoryId;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, FACTORY, "purchasing"))) {
            factoryId = service.prepare(FACTORY, request, 7L).deterministicId();
        }
        String platformId;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 7L, "PLATFORM", "platform_admin"))) {
            platformId = service.prepare(FACTORY, request, 7L).deterministicId();
        }

        assertThat(factoryId).isNotEqualTo(platformId);
        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));
    }

    @Test
    void httpSourceGuardUsesOwnSourceAndRejectsHiddenSourceBeforeAnyWrite() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute(DataScopeRouteInterceptor.RESOURCE_ATTRIBUTE, "return_orders");
        servletRequest.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, FACTORY, "purchasing"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        try {
            assertThat(service.prepare(FACTORY, request("10.00", null), 7L).isReplay()).isFalse();

            source.setCreatedBy(99L);
            doThrow(new BusinessException(403, "hidden source"))
                    .when(dataScopeResolver).assertCanAccess(FACTORY, 99L);
            assertThatThrownBy(() -> service.prepare(FACTORY, request("10.00", null), 7L))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("hidden source");
            verify(returnOrderRepository, never()).save(any(ReturnOrder.class));
            verify(returnOrderItemRepository, never()).save(any(ReturnOrderItem.class));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private CreateReturnOrderRequest request(String quantity, String requestId) {
        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setReturnType("PURCHASE_RETURN");
        request.setCounterpartyId("SUP-1");
        request.setSourceOrderId(PO_ID);
        request.setReturnDate(LocalDate.of(2026, 10, 16));
        request.setReason("quality");
        request.setWithGoods(true);
        request.setRequestId(requestId);
        request.setItems(List.of(item(quantity)));
        return request;
    }

    private CreateReturnOrderRequest.ReturnOrderItemDTO item(String quantity) {
        CreateReturnOrderRequest.ReturnOrderItemDTO item = new CreateReturnOrderRequest.ReturnOrderItemDTO();
        item.setMaterialTypeId(MATERIAL);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal("5.00"));
        item.setReason("quality");
        return item;
    }

    private ReturnOrder order(String id, ReturnOrderStatus status, String quantity) {
        ReturnOrder order = new ReturnOrder();
        order.setId(id);
        order.setStatus(status);
        order.setFactoryId(FACTORY);
        order.setWithGoods(true);
        order.setItems(List.of(returnItem(quantity)));
        return order;
    }

    private ReturnOrderItem returnItem(String quantity) {
        ReturnOrderItem item = new ReturnOrderItem();
        item.setMaterialTypeId(MATERIAL);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal("5.00"));
        item.setReason("quality");
        return item;
    }
}
