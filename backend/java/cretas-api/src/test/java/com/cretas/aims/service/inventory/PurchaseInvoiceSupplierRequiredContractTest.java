package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseInvoiceChaseLogRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.impl.PurchaseInvoiceServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登记发票必须说出开票方: 不传就默认成订单的供应商, 「来票供应商与订单不符」就永远查不出来 (S5-08)。
 */
class PurchaseInvoiceSupplierRequiredContractTest {
    final PurchaseInvoiceRepository invoices = mock(PurchaseInvoiceRepository.class);
    final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    final PurchaseInvoiceChaseLogRepository chases = mock(PurchaseInvoiceChaseLogRepository.class);
    final PurchaseInvoiceServiceImpl service = new PurchaseInvoiceServiceImpl(invoices, orders, chases,
            mock(com.cretas.aims.repository.inventory.PurchaseInvoiceLineRepository.class),
            mock(com.cretas.aims.service.inventory.impl.PurchaseInvoiceThreeWayMatcher.class));

    void order() {
        var order = new PurchaseOrder();
        order.setId("PO-1"); order.setFactoryId("TEST-A"); order.setSupplierId("SUP-1");
        order.setStatus(PurchaseOrderStatus.COMPLETED);
        order.setTotalAmount(new BigDecimal("100"));
        order.setTaxAmount(BigDecimal.ZERO);
        when(orders.findByIdAndFactoryIdForUpdate("PO-1", "TEST-A")).thenReturn(Optional.of(order));
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void aMissingSupplierIsRejectedAndNothingIsSaved() {
        order();
        for (String supplier : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> service.upload("TEST-A", "PO-1", supplier, "INV-1", new BigDecimal("100"), null, 8L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("发票供应商不能为空");
        }
        verify(invoices, never()).save(any());
    }

    @Test
    void aDifferentSupplierIsStillRejected() {
        order();
        assertThatThrownBy(() -> service.upload("TEST-A", "PO-1", "SUP-OTHER", "INV-2", new BigDecimal("100"), null, 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
        verify(invoices, never()).save(any());
    }

    @Test
    void theOrdersOwnSupplierIsAccepted() {
        order();
        var invoice = service.upload("TEST-A", "PO-1", "SUP-1", "INV-3", new BigDecimal("100"), null, 8L,
                java.util.List.of(new com.cretas.aims.dto.inventory.PurchaseInvoiceLineRequest(
                        1L, null, "MAT-A", BigDecimal.ONE, null, new BigDecimal("100"))));
        assertThat(invoice.getSupplierId()).isEqualTo("SUP-1");
    }
}
