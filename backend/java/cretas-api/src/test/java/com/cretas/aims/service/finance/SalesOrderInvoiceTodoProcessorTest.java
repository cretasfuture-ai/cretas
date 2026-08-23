package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.impl.SalesOrderInvoiceTodoProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SalesOrderInvoiceTodoProcessorTest {

    @Mock SalesOrderRepository salesOrderRepository;
    @Mock InvoiceService invoiceService;
    @InjectMocks SalesOrderInvoiceTodoProcessor processor;

    @Test
    void successfulProcessingMarksTodoDoneAfterInvoiceRequest() {
        when(salesOrderRepository.claimInvoiceTodo(eq("SO-1"), eq("F006"),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(5))).thenReturn(1);
        when(invoiceService.requestInvoiceFromOrder(eq("F006"), eq("SO-1"),
                isNull(), isNull(), anyString())).thenReturn(new InvoiceRecord());

        processor.process("F006", "SO-1");

        verify(salesOrderRepository).markInvoiceTodoDone("SO-1", "F006");
        verify(salesOrderRepository, never()).markInvoiceTodoFailure(anyString(), anyString(),
                any(LocalDateTime.class), anyString(), anyInt());
    }

    @Test
    void failedProcessingLeavesDurableRetryState() {
        when(salesOrderRepository.claimInvoiceTodo(eq("SO-1"), eq("F006"),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(5))).thenReturn(1);
        when(invoiceService.requestInvoiceFromOrder(eq("F006"), eq("SO-1"),
                isNull(), isNull(), anyString())).thenThrow(new IllegalStateException("database unavailable"));

        processor.process("F006", "SO-1");

        verify(salesOrderRepository).markInvoiceTodoFailure(eq("SO-1"), eq("F006"),
                any(LocalDateTime.class), contains("database unavailable"), eq(5));
        verify(salesOrderRepository, never()).markInvoiceTodoDone(anyString(), anyString());
    }

    @Test
    void unclaimedTodoIsNotProcessedTwice() {
        when(salesOrderRepository.claimInvoiceTodo(eq("SO-1"), eq("F006"),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(5))).thenReturn(0);

        processor.process("F006", "SO-1");

        verifyNoInteractions(invoiceService);
    }
}
