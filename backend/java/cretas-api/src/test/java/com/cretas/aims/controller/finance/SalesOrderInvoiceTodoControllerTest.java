package com.cretas.aims.controller.finance;

import com.cretas.aims.service.finance.impl.SalesOrderInvoiceTodoProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesOrderInvoiceTodoControllerTest {

    @Test
    void financeAdminRetryResetsOnlyFailedTodo() {
        SalesOrderInvoiceTodoProcessor processor = mock(SalesOrderInvoiceTodoProcessor.class);
        when(processor.retryFailedTodo("F006", "SO-1")).thenReturn(true);
        SalesOrderInvoiceTodoController controller = new SalesOrderInvoiceTodoController(processor);

        var response = controller.retry("F006", "SO-1");

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getData()).containsEntry("status", "PENDING");
        verify(processor).retryFailedTodo("F006", "SO-1");
    }
}
