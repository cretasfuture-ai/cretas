package com.cretas.aims.service.intent.catalog;

import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.enums.InvoiceStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.InvoiceRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Contract for factory-scoped, read-only automatic invoice tax queries. */
class FactoryInvoiceTaxQueryServiceContractTest {
    private SalesOrderRepository salesOrders;
    private InvoiceRecordRepository invoices;
    private FactoryInvoiceTaxQueryService service;

    @BeforeEach
    void setUp() {
        salesOrders = mock(SalesOrderRepository.class);
        invoices = mock(InvoiceRecordRepository.class);
        service = new FactoryInvoiceTaxQueryService();
        ReflectionTestUtils.setField(service, "salesOrderRepository", salesOrders);
        ReflectionTestUtils.setField(service, "invoiceRecordRepository", invoices);
    }

    @Test
    void f006ReturnsRealTaxGroupingAndStatusWithoutWritePath() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-F006");
        order.setStatus(SalesOrderStatus.COMPLETED);
        when(salesOrders.findByIdAndFactoryId("SO-F006", "F006"))
                .thenReturn(Optional.of(order));

        InvoiceRecord record = new InvoiceRecord();
        record.setId("INV-F006");
        record.setSalesOrderId("SO-F006");
        record.setStatus(InvoiceStatus.REQUESTED);
        record.setAmount(new BigDecimal("100.00"));
        record.setTaxAmount(new BigDecimal("13.00"));
        record.setTotalAmount(new BigDecimal("113.00"));
        record.setTaxBreakdown(List.of(new InvoiceRecord.TaxBreakdownEntry(
                new BigDecimal("13.00"), new BigDecimal("100.00"),
                new BigDecimal("13.00"), 1)));
        when(invoices.findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                "F006", "SO-F006")).thenReturn(List.of(record));

        Map<String, Object> result = service.query("F006", "SO-F006");

        assertThat(result).containsEntry("status", "GENERATED")
                .containsEntry("salesOrderId", "SO-F006");
        assertThat(result.get("invoices").toString()).contains("INV-F006", "REQUESTED", "13.00");
        assertThat(result.get("taxBreakdown").toString()).contains("13.00", "100.00");
        // The service has no save dependency or write call; verify only scoped reads occurred.
        verify(salesOrders).findByIdAndFactoryId("SO-F006", "F006");
        verify(invoices).findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                "F006", "SO-F006");
        verifyNoMoreInteractions(salesOrders, invoices);
    }

    @Test
    void f007CannotSeeSameOrderAndInvoiceLookupIsNotAttempted() {
        when(salesOrders.findByIdAndFactoryId("SO-F006", "F007"))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.query("F007", "SO-F006");

        assertThat(result).containsEntry("status", "NOT_FOUND")
                .containsEntry("salesOrderId", "SO-F006");
        verify(salesOrders).findByIdAndFactoryId("SO-F006", "F007");
        verifyNoInteractions(invoices);
    }

    @Test
    void knownOrderWithoutAutomaticRecordReportsPendingWithoutInventedAmounts() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-F006");
        order.setStatus(SalesOrderStatus.COMPLETED);
        when(salesOrders.findByIdAndFactoryId("SO-F006", "F006"))
                .thenReturn(Optional.of(order));
        when(invoices.findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                "F006", "SO-F006")).thenReturn(List.of());

        Map<String, Object> result = service.query("F006", "SO-F006");

        assertThat(result).containsEntry("status", "PENDING")
                .containsEntry("generated", false)
                .doesNotContainKeys("amount", "taxAmount", "totalAmount", "taxBreakdown");
    }

    @Test
    void orderLifecycleAndTodoFailureAreReportedWithoutClaimingGeneration() {
        SalesOrder draft = new SalesOrder();
        draft.setId("SO-DRAFT");
        draft.setStatus(SalesOrderStatus.DRAFT);
        when(salesOrders.findByIdAndFactoryId("SO-DRAFT", "F006"))
                .thenReturn(Optional.of(draft));
        when(invoices.findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                "F006", "SO-DRAFT")).thenReturn(List.of());

        assertThat(service.query("F006", "SO-DRAFT"))
                .containsEntry("status", "NOT_READY")
                .containsEntry("orderStatus", "DRAFT");

        SalesOrder cancelled = new SalesOrder();
        cancelled.setId("SO-CANCELLED");
        cancelled.setStatus(SalesOrderStatus.CANCELLED);
        when(salesOrders.findByIdAndFactoryId("SO-CANCELLED", "F006"))
                .thenReturn(Optional.of(cancelled));
        when(invoices.findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                "F006", "SO-CANCELLED")).thenReturn(List.of());
        assertThat(service.query("F006", "SO-CANCELLED"))
                .containsEntry("status", "CANCELLED");

        SalesOrder failed = new SalesOrder();
        failed.setId("SO-FAILED");
        failed.setStatus(SalesOrderStatus.COMPLETED);
        failed.setInvoiceTodoStatus(SalesOrder.INVOICE_TODO_FAILED);
        failed.setInvoiceTodoAttempts(2);
        failed.setInvoiceTodoLastError("tax service unavailable");
        when(salesOrders.findByIdAndFactoryId("SO-FAILED", "F006"))
                .thenReturn(Optional.of(failed));
        when(invoices.findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                "F006", "SO-FAILED")).thenReturn(List.of());
        assertThat(service.query("F006", "SO-FAILED"))
                .containsEntry("status", "FAILED")
                .containsEntry("invoiceTodoAttempts", 2)
                .containsEntry("invoiceTodoLastError", "tax service unavailable");
    }
}
