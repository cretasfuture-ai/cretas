package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderSettledEvent;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.finance.ArApPaymentAllocationRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuotePaymentMoneyContractTest {
    final PaymentRecordRepository payments = mock(PaymentRecordRepository.class);
    final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    final CustomerRepository customers = mock(CustomerRepository.class);
    final ArApService ar = mock(ArApService.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final PaymentRecordServiceImpl service = new PaymentRecordServiceImpl(payments, orders, customers, ar, events);
    final SalesOrder order = order();
    static SalesOrder order() {
        var o = new SalesOrder(); o.setId("SO-1"); o.setFactoryId("TEST-A"); o.setCustomerId("C-1");
        o.setStatus(SalesOrderStatus.COMPLETED); o.setTotalAmount(new BigDecimal("109.73"));
        o.setTaxAmount(new BigDecimal("14.27")); o.setPaidAmount(BigDecimal.ZERO); return o;
    }
    void stubRecord() {
        when(orders.findByIdAndFactoryIdForUpdate("SO-1", "TEST-A")).thenReturn(Optional.of(order));
        when(payments.save(any())).thenAnswer(i -> i.getArgument(0));
    }
    PaymentRecord record(String amount) {
        return service.recordPayment("TEST-A", "SO-1", new BigDecimal(amount), PaymentMethod.BANK_TRANSFER,
                null, "TEST-ONLY", null, 1L, "TEST ONLY");
    }
    @Test void acceptsFullQuotedGross124InsteadOfRejectingAgainstNet109_73() {
        stubRecord(); assertThatCode(() -> record("124.00")).doesNotThrowAnyException();
        verify(payments).save(any()); verifyNoInteractions(ar, events);
    }
    @Test void rejectsAmountAboveGrossBeforeAnyWrite() {
        stubRecord();
        assertThatThrownBy(() -> record("124.01")).hasMessageContaining("124");
        verify(payments, never()).save(any()); verifyNoInteractions(ar, events);
    }
    @Test void acceptsRemaining14AfterVerified110() {
        stubRecord(); order.setPaidAmount(new BigDecimal("110.00"));
        assertThatCode(() -> record("14.00")).doesNotThrowAnyException();
    }
    @Test void netAmountDoesNotMeanFullyPaidAndDiscountIsRespected() {
        order.setPaidAmount(new BigDecimal("110.00")); assertThat(order.getPaymentStatus()).isEqualTo("PARTIAL");
        order.setPaidAmount(new BigDecimal("124.00")); assertThat(order.getPaymentStatus()).isEqualTo("PAID");
        order.setDiscountAmount(new BigDecimal("10.00")); order.setPaidAmount(new BigDecimal("110.00"));
        assertThat(order.getPaymentStatus()).isEqualTo("PARTIAL");
        order.setPaidAmount(new BigDecimal("114.00")); assertThat(order.getPaymentStatus()).isEqualTo("PAID");
    }
    @Test void preservesUntaxedLegacySettlement() {
        stubRecord(); order.setTaxAmount(null); order.setDiscountAmount(null);
        assertThat(record("109.73").getAmount()).isEqualByComparingTo("109.73");
        order.setPaidAmount(new BigDecimal("109.73")); assertThat(order.getPaymentStatus()).isEqualTo("PAID");
    }
    void stubVerify(String amount) {
        var p = new PaymentRecord(); p.setId("P-1"); p.setFactoryId("TEST-A"); p.setSalesOrderId("SO-1");
        p.setCustomerId("C-1"); p.setAmount(new BigDecimal(amount)); p.setStatus(PaymentRecordStatus.PENDING);
        when(payments.findById("P-1")).thenReturn(Optional.of(p)); when(payments.save(any())).thenAnswer(i->i.getArgument(0));
        when(orders.findById("SO-1")).thenReturn(Optional.of(order));
        when(payments.sumVerifiedAmountBySalesOrderId("SO-1")).thenReturn(new BigDecimal(amount));
    }
    @Test void verification110DoesNotPublishSettlementBeforeGross124() {
        stubVerify("110.00"); service.verifyPayment("TEST-A", "P-1", 2L);
        assertThat(order.getSettlementFlag()).isFalse(); verify(events, never()).publishEvent(any(SalesOrderSettledEvent.class));
    }
    @Test void verification124SettlesAndPublishesOnce() {
        stubVerify("124.00"); service.verifyPayment("TEST-A", "P-1", 2L);
        assertThat(order.getSettlementFlag()).isTrue(); verify(events).publishEvent(any(SalesOrderSettledEvent.class));
    }
    @Test void arPosting110PreservesRemaining14AndDoesNotMarkOrderSettled() {
        var transactions = mock(ArApTransactionRepository.class);
        var customer = new Customer(); customer.setId("C-1"); customer.setFactoryId("TEST-A");
        customer.setName("TEST ONLY"); customer.setCurrentBalance(new BigDecimal("124.00"));
        when(customers.findByIdAndFactoryId("C-1", "TEST-A")).thenReturn(Optional.of(customer));
        when(transactions.save(any())).thenAnswer(i->i.getArgument(0));
        when(orders.findById("SO-1")).thenReturn(Optional.of(order));
        when(transactions.sumArPaymentsBySalesOrderId("TEST-A", "SO-1")).thenReturn(new BigDecimal("110.00"));
        var actualAr = new ArApServiceImpl(transactions, customers, mock(SupplierRepository.class));
        ReflectionTestUtils.setField(actualAr, "salesOrderRepository", orders);
        ReflectionTestUtils.setField(actualAr, "allocationRepository", mock(ArApPaymentAllocationRepository.class));
        var referenceLock = mock(com.cretas.aims.service.finance.FinancePaymentReferenceLock.class);
        when(referenceLock.acquire("TEST-A", "TEST-PART")).thenReturn("TEST-PART");
        ReflectionTestUtils.setField(actualAr, "paymentReferenceLock", referenceLock);
        actualAr.recordArPayment("TEST-A", "C-1", "SO-1", new BigDecimal("110.00"), PaymentMethod.BANK_TRANSFER, "TEST-PART", 1L, "TEST ONLY");
        assertThat(customer.getCurrentBalance()).isEqualByComparingTo("14.00");
        assertThat(order.getSettlementFlag()).isFalse();
    }
}
