package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.enums.InvoiceStatus;
import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.InvoiceRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.finance.InvoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoiceDownloadUrlContractTest {
    final InvoiceService invoices = mock(InvoiceService.class);
    final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    final OssService oss = mock(OssService.class);
    final DataScopeResolver scope = mock(DataScopeResolver.class);
    final InvoiceController controller = new InvoiceController(invoices, mock(InvoiceRecordRepository.class), scope, oss, orders);
    InvoiceRecord invoice;
    SalesOrder order;
    @BeforeEach void setup() {
        invoice = new InvoiceRecord(); invoice.setFactoryId("TEST-A"); invoice.setSalesOrderId("SO-1");
        invoice.setStatus(InvoiceStatus.ISSUED); invoice.setInvoicePdfUrl("https://example.invalid/stored.pdf");
        order = new SalesOrder(); order.setFactoryId("TEST-A"); order.setCreatedBy(7L);
        when(invoices.getInvoice("TEST-A", "INV-1")).thenReturn(invoice);
        when(orders.findById("SO-1")).thenReturn(Optional.of(order));
    }
    @Test void signsStoredPdfAfterSourceOrderAuthorizationAndDisablesCaching() throws Exception {
        when(oss.generatePresignedUrl(invoice.getInvoicePdfUrl(), 300)).thenReturn("https://example.invalid/fresh.pdf");
        var response = controller.downloadUrl("TEST-A", "INV-1");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(java.util.Map.of("success", true, "data", java.util.Map.of("url", "https://example.invalid/fresh.pdf")));
        var sequence = inOrder(scope, oss);
        sequence.verify(scope).assertCanAccess("TEST-A", 7L);
        sequence.verify(oss).generatePresignedUrl(invoice.getInvoicePdfUrl(), 300);
        assertThat(InvoiceController.class.getMethod("downloadUrl", String.class, String.class)
                .getAnnotation(RequirePermission.class).value()).containsExactly("finance:read_write", "finance:read", "sales:read_write", "sales:read");
    }
    @Test void rejectsInvoiceOutsideFactoryBeforeSigning() {
        when(invoices.getInvoice("TEST-A", "INV-1")).thenThrow(new IllegalArgumentException("not found"));
        assertThatThrownBy(() -> controller.downloadUrl("TEST-A", "INV-1")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(oss, scope); verify(orders, never()).findById(any());
    }
    @Test void rejectsAnotherCreatorsInvoiceBeforeSigning() {
        doThrow(new BusinessException(403, "out of scope")).when(scope).assertCanAccess("TEST-A", 7L);
        assertThatThrownBy(() -> controller.downloadUrl("TEST-A", "INV-1")).isInstanceOf(BusinessException.class).hasMessageContaining("out of scope");
        verifyNoInteractions(oss);
    }
    @Test void rejectsOrderFromAnotherFactoryBeforeSigning() {
        order.setFactoryId("TEST-B");
        assertThatThrownBy(() -> controller.downloadUrl("TEST-A", "INV-1")).isInstanceOf(BusinessException.class).hasMessageContaining("关联订单");
        verifyNoInteractions(oss, scope);
    }
    @Test void rejectsMissingSourceOrderBeforeSigning() {
        when(orders.findById("SO-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.downloadUrl("TEST-A", "INV-1")).isInstanceOf(BusinessException.class);
        invoice.setSalesOrderId(null);
        assertThatThrownBy(() -> controller.downloadUrl("TEST-A", "INV-1")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(oss, scope);
    }
    @ParameterizedTest @EnumSource(value=InvoiceStatus.class, names="ISSUED", mode=EnumSource.Mode.EXCLUDE)
    void rejectsUnissuedInvoiceEvenWithStoredUrl(InvoiceStatus status) {
        invoice.setStatus(status);
        assertThatThrownBy(() -> controller.downloadUrl("TEST-A", "INV-1")).isInstanceOf(BusinessException.class).hasMessageContaining("尚未出具")
                .satisfies(error -> assertThat(((BusinessException) error).getActionHint()).contains("开票记录"));
        verifyNoInteractions(oss);
    }
    @Test void rejectsMissingPdfAndSigningFailure() {
        invoice.setInvoicePdfUrl(null);
        assertThatThrownBy(() -> controller.downloadUrl("TEST-A", "INV-1")).isInstanceOf(BusinessException.class);
        invoice.setInvoicePdfUrl(" ");
        assertThatThrownBy(() -> controller.downloadUrl("TEST-A", "INV-1")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(oss);
        invoice.setInvoicePdfUrl("https://example.invalid/stored.pdf");
        assertThatThrownBy(() -> controller.downloadUrl("TEST-A", "INV-1")).isInstanceOf(BusinessException.class).hasMessageContaining("生成失败");
    }
}
