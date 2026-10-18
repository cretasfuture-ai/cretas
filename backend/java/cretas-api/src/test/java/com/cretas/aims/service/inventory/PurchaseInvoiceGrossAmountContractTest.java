package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.PurchaseInvoiceStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.inventory.PurchaseInvoiceChaseLogRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.impl.PurchaseInvoiceServiceImpl;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PurchaseInvoiceGrossAmountContractTest {
    final PurchaseInvoiceRepository invoices = mock(PurchaseInvoiceRepository.class);
    final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    final PurchaseInvoiceChaseLogRepository chases = mock(PurchaseInvoiceChaseLogRepository.class);
    final PurchaseInvoiceServiceImpl service = new PurchaseInvoiceServiceImpl(invoices, orders, chases);
    PurchaseOrder order(String net, String tax) {
        var order = new PurchaseOrder();
        order.setId("PO-1"); order.setFactoryId("TEST-A"); order.setSupplierId("SUP-1");
        order.setStatus(PurchaseOrderStatus.COMPLETED);
        order.setTotalAmount(net == null ? null : new BigDecimal(net));
        order.setTaxAmount(tax == null ? null : new BigDecimal(tax));
        when(orders.findByIdAndFactoryIdForUpdate("PO-1", "TEST-A")).thenReturn(Optional.of(order));
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));
        return order;
    }
    PurchaseInvoice upload(String amount) {
        return service.upload("TEST-A", "PO-1", "SUP-1", "TEST-INVOICE", new BigDecimal(amount), null, 8L);
    }
    @Test void matchesGross165RatherThanNet146_01() {
        var order = order("146.01", "18.99");
        var invoice = upload("165.00");
        assertThat(invoice.getReconcileStatus()).isEqualTo("MATCHED");
        assertThat(order.getInvoiceStatus()).isEqualTo(PurchaseInvoiceStatus.RECONCILED);
        assertThat(invoice.getReconciledBy()).isEqualTo(8L);
    }
    @Test void netOnlyInvoiceMustRemainPendingWhenTaxIsPresent() {
        var order = order("146.01", "18.99");
        assertThat(upload("146.01").getReconcileStatus()).isEqualTo("PENDING");
        assertThat(order.getInvoiceStatus()).isEqualTo(PurchaseInvoiceStatus.RECEIVED);
    }
    @Test void receiptTodoShowsGrossWithoutChangingStoredMoney() {
        var order = order("146.01", "18.99");
        when(orders.findInvoiceChaseCandidates("TEST-A")).thenReturn(List.of(order));
        assertThat(service.listPendingReceiptTodos("TEST-A").getFirst().getOrderAmount()).isEqualByComparingTo("165.00");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("146.01");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("18.99");
        verify(orders, never()).save(any());
    }
    @Test void zeroAndLegacyNullTaxRetainMatchingAndMissingNetDoesNotMatch() {
        order("100", "0"); assertThat(upload("100").getReconcileStatus()).isEqualTo("MATCHED");
        order("100", null); assertThat(upload("100").getReconcileStatus()).isEqualTo("MATCHED");
        order(null, "13"); assertThat(upload("13").getReconcileStatus()).isEqualTo("PENDING");
    }
}
