package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.PaymentRecordStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.PaymentRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.security.DataScopeRouteInterceptor;
import com.cretas.aims.service.finance.ArApService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentRecordWriteScopeContractTest {

    @ParameterizedTest @ValueSource(booleans={true,false})
    void asynchronousTrustedUserChecksSourceOwnerBeforeAnyWrite(boolean owned) {
        PaymentRecordRepository payments = mock(PaymentRecordRepository.class);
        SalesOrderRepository orders = mock(SalesOrderRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        ArApService arAp = mock(ArApService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        PaymentRecordServiceImpl service = new PaymentRecordServiceImpl(payments, orders, customers, arAp, events);
        ReflectionTestUtils.setField(service, "dataScopeResolver", resolver);
        long owner = owned ? 56L : 66L;
        var actor = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","sales_manager");
        SalesOrder order = sourceOrder("SO-ASYNC", "F1", owner);
        when(orders.findByIdAndFactoryIdForUpdate("SO-ASYNC", "F1")).thenReturn(Optional.of(order));
        when(payments.findRecentDuplicatePayments(any(),any(),any(),any())).thenReturn(List.of());
        when(payments.save(any(PaymentRecord.class))).thenAnswer(call -> call.getArgument(0));
        if (!owned) doThrow(new BusinessException(403,"scope deny-control")).when(resolver)
                .assertActorCanAccess("F1",owner,actor);
        RequestContextHolder.resetRequestAttributes();
        try (var typed = AuthenticatedActor.trustedAsyncScope(actor);
             var identity = com.cretas.aims.utils.SecurityUtils.trustedUserIdScope(56L)) {
            if (owned) assertThat(service.recordPayment("F1","SO-ASYNC",new BigDecimal("40"),
                    PaymentMethod.BANK_TRANSFER,null,null,null,56L,null).getStatus()).isEqualTo(PaymentRecordStatus.PENDING);
            else assertThatThrownBy(() -> service.recordPayment("F1","SO-ASYNC",new BigDecimal("40"),
                    PaymentMethod.BANK_TRANSFER,null,null,null,56L,null))
                    .isInstanceOfSatisfying(BusinessException.class,error -> assertThat(error.getCode()).isEqualTo(403));
            verify(resolver).assertActorCanAccess("F1",owner,actor);
            if (owned) verify(payments).save(any(PaymentRecord.class));
            else verifyNoInteractions(payments);
            verifyNoInteractions(customers,arAp,events);
        }
        assertThat(com.cretas.aims.utils.SecurityUtils.currentUserId()).isNull();
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void hiddenSourceOrderIsRejectedBeforePaymentSideEffectsWithOrWithoutRestMarker(boolean restMarker) {
        PaymentRecordRepository payments = mock(PaymentRecordRepository.class);
        SalesOrderRepository orders = mock(SalesOrderRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        ArApService arAp = mock(ArApService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        PaymentRecordServiceImpl service = new PaymentRecordServiceImpl(payments, orders, customers, arAp, events);
        ReflectionTestUtils.setField(service, "dataScopeResolver", resolver);

        SalesOrder hidden = sourceOrder("SO-HIDDEN", "F1", 66L);
        when(orders.findByIdAndFactoryIdForUpdate("SO-HIDDEN", "F1")).thenReturn(Optional.of(hidden));
        doThrow(new BusinessException(403, "scope deny-control"))
                .when(resolver).assertCanAccess("F1", 66L);
        bindActor("F1", 56L);
        if (!restMarker) ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest().removeAttribute(DataScopeRouteInterceptor.RESOURCE_ATTRIBUTE);
        try {
            assertThatThrownBy(() -> service.recordPayment("F1", "SO-HIDDEN", new BigDecimal("40.00"),
                    PaymentMethod.BANK_TRANSFER, null, "REF-HIDDEN", null, 56L, "hidden"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getCode()).isEqualTo(403));
            verify(resolver).assertCanAccess("F1", 66L);
            verify(payments, never()).save(any(PaymentRecord.class));
            verify(payments, never()).findRecentDuplicatePayments(any(), any(), any(), any());
            verifyNoInteractions(customers, arAp, events);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void ownedSourceOrderCanRecordPendingPaymentAfterExactScopeCheck() {
        PaymentRecordRepository payments = mock(PaymentRecordRepository.class);
        SalesOrderRepository orders = mock(SalesOrderRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        ArApService arAp = mock(ArApService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        PaymentRecordServiceImpl service = new PaymentRecordServiceImpl(payments, orders, customers, arAp, events);
        ReflectionTestUtils.setField(service, "dataScopeResolver", resolver);

        SalesOrder owned = sourceOrder("SO-OWN", "F1", 56L);
        owned.setCustomerId(null);
        owned.setTotalAmount(new BigDecimal("100.00"));
        when(orders.findByIdAndFactoryIdForUpdate("SO-OWN", "F1")).thenReturn(Optional.of(owned));
        when(payments.findRecentDuplicatePayments(eq("F1"), eq("SO-OWN"), eq(new BigDecimal("40.00")), any()))
                .thenReturn(List.of());
        when(payments.save(any(PaymentRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        bindActor("F1", 56L);
        try {
            PaymentRecord saved = service.recordPayment("F1", "SO-OWN", new BigDecimal("40.00"),
                    PaymentMethod.BANK_TRANSFER, null, "REF-OWN", null, 56L, "owned");

            verify(resolver).assertCanAccess("F1", 56L);
            verify(payments).save(any(PaymentRecord.class));
            assertThat(saved.getStatus()).isEqualTo(PaymentRecordStatus.PENDING);
            assertThat(saved.getRecordedBy()).isEqualTo(56L);
            assertThat(saved.getSalesOrderId()).isEqualTo("SO-OWN");
            assertThat(saved.getAmount()).isEqualByComparingTo("40.00");
            verifyNoInteractions(customers, arAp, events);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static SalesOrder sourceOrder(String id, String factoryId, Long createdBy) {
        SalesOrder order = new SalesOrder();
        order.setId(id);
        order.setFactoryId(factoryId);
        order.setCreatedBy(createdBy);
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        order.setTotalAmount(new BigDecimal("100.00"));
        return order;
    }

    private static void bindActor(String factoryId, long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, factoryId, "warehouse_manager"));
        request.setAttribute(DataScopeRouteInterceptor.RESOURCE_ATTRIBUTE, "payment_records");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
