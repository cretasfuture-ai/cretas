package com.cretas.aims.controller.finance;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.InvoiceRecordRepository;
import com.cretas.aims.service.finance.InvoiceService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class InvoiceControllerManualRequestTombstoneTest {

    private final InvoiceService invoiceService = mock(InvoiceService.class);
    private final InvoiceController controller = new InvoiceController(
            invoiceService, mock(InvoiceRecordRepository.class), mock(com.cretas.aims.security.DataScopeResolver.class),
            mock(com.cretas.aims.service.OssService.class), mock(com.cretas.aims.repository.inventory.SalesOrderRepository.class));

    @Test
    void amountBasedManualRequest_isGoneAndDoesNotCallService() {
        assertThatThrownBy(() -> controller.requestInvoice(
                "F006", Map.of("salesOrderId", "SO-001", "amount", 100), 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(410);

        verifyNoInteractions(invoiceService);
    }

    @Test
    void orderBasedManualRequest_isGoneAndDoesNotCallService() {
        assertThatThrownBy(() -> controller.requestInvoiceFromOrder(
                "F006", Map.of("salesOrderId", "SO-001"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自动生成待审核开票记录")
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(410);

        verifyNoInteractions(invoiceService);
    }
}
