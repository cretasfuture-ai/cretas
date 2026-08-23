package com.cretas.aims.controller.finance;

import com.cretas.aims.service.finance.impl.SalesOrderInvoiceTodoProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.Map;

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

    @Test
    void financeAdminCanSeeTenantScopedDeadLetters() {
        SalesOrderInvoiceTodoProcessor processor = mock(SalesOrderInvoiceTodoProcessor.class);
        when(processor.listFailedTodos("F006")).thenReturn(List.of(Map.of(
                "salesOrderId", "SO-1", "orderNumber", "SO-001", "attempts", 5,
                "lastError", "部分已开票", "updatedAt", "2026-08-23T22:00:00")));
        SalesOrderInvoiceTodoController controller = new SalesOrderInvoiceTodoController(processor);

        var response = controller.failed("F006");

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getData()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("salesOrderId", "SO-1"));
        verify(processor).listFailedTodos("F006");
    }
}
