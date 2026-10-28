package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.PurchaseInvoiceStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.inventory.PurchaseInvoiceChaseLogRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceLineRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.impl.PurchaseInvoiceServiceImpl;
import com.cretas.aims.service.inventory.impl.PurchaseInvoiceThreeWayMatcher;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 含税口径契约。S5-05 起自动相符只看逐行三单, 表头「发票总额 == 订单含税总额」不再自动相符;
 * 含税口径挪到了发票行的应开金额上 (采购单行单价 × 数量 × (1+税率))。
 */
class PurchaseInvoiceGrossAmountContractTest {
    final PurchaseInvoiceRepository invoices = mock(PurchaseInvoiceRepository.class);
    final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    final PurchaseInvoiceChaseLogRepository chases = mock(PurchaseInvoiceChaseLogRepository.class);
    final PurchaseInvoiceLineRepository lines = mock(PurchaseInvoiceLineRepository.class);
    final PurchaseInvoiceThreeWayMatcher matcher = mock(PurchaseInvoiceThreeWayMatcher.class);
    final PurchaseInvoiceServiceImpl service = new PurchaseInvoiceServiceImpl(invoices, orders, chases, lines, matcher);
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
    @Test void headerGrossEqualWithoutLinesNoLongerAutoMatches() {
        var order = order("146.01", "18.99");
        var invoice = upload("165.00");
        assertThat(invoice.getReconcileStatus()).isEqualTo("PENDING");
        assertThat(invoice.getReconciledBy()).isNull();
        assertThat(order.getInvoiceStatus()).isNotEqualTo(PurchaseInvoiceStatus.RECONCILED);
    }
    @Test void lineExpectedAmountIsTaxInclusive() {
        var item = new PurchaseOrderItem();
        item.setUnitPrice(new BigDecimal("14.601"));
        item.setQuantity(new BigDecimal("10"));
        item.setTaxRate(new BigDecimal("13"));
        // 10 × 14.601 = 146.01 未税, ×1.13 = 164.99 含税 —— 不能按未税 146.01 判
        assertThat(PurchaseInvoiceThreeWayMatcher.expectedAmount(item, new BigDecimal("10")))
                .isEqualByComparingTo("164.99");
        item.setTaxRate(null);
        assertThat(PurchaseInvoiceThreeWayMatcher.expectedAmount(item, new BigDecimal("10")))
                .isEqualByComparingTo("146.01");
        item.setUnitPrice(null);
        assertThat(PurchaseInvoiceThreeWayMatcher.expectedAmount(item, new BigDecimal("10"))).isNull();
    }
    @Test void receiptTodoShowsGrossWithoutChangingStoredMoney() {
        var order = order("146.01", "18.99");
        when(orders.findInvoiceChaseCandidates("TEST-A")).thenReturn(List.of(order));
        assertThat(service.listPendingReceiptTodos("TEST-A").getFirst().getOrderAmount()).isEqualByComparingTo("165.00");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("146.01");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("18.99");
        verify(orders, never()).save(any());
    }
}
