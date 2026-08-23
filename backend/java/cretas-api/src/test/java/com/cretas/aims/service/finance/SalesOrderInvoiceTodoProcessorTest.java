package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.impl.SalesOrderInvoiceTodoProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.cretas.aims.exception.BusinessException;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void partialInvoicedConflictRemainsRetryableAndIsNotMarkedDone() {
        when(salesOrderRepository.claimInvoiceTodo(eq("SO-1"), eq("F006"),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(5))).thenReturn(1);
        when(invoiceService.requestInvoiceFromOrder(eq("F006"), eq("SO-1"),
                isNull(), isNull(), anyString())).thenThrow(new BusinessException(409, "partial")
                .withCode("AUTO_INVOICE_PARTIAL_CONFLICT"));
        when(salesOrderRepository.findInvoiceTodoAttempts("SO-1", "F006"))
                .thenReturn(Optional.of(2));

        assertThat(processor.process("F006", "SO-1")).isFalse();

        verify(salesOrderRepository).markInvoiceTodoFailure(eq("SO-1"), eq("F006"),
                any(LocalDateTime.class), contains("partial"), eq(5));
        verify(salesOrderRepository, never()).markInvoiceTodoDone(anyString(), anyString());
    }

    @Test
    void fullyInvoicedConflictIsTheOnlyBusinessExceptionTerminalPath() {
        when(salesOrderRepository.claimInvoiceTodo(eq("SO-1"), eq("F006"),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(5))).thenReturn(1);
        when(invoiceService.requestInvoiceFromOrder(eq("F006"), eq("SO-1"),
                isNull(), isNull(), anyString())).thenThrow(new BusinessException(409, "full")
                .withCode("AUTO_INVOICE_ALREADY_FULL"));

        assertThat(processor.process("F006", "SO-1")).isTrue();
        verify(salesOrderRepository).markInvoiceTodoDone("SO-1", "F006");
        verify(salesOrderRepository, never()).markInvoiceTodoFailure(anyString(), anyString(),
                any(LocalDateTime.class), anyString(), anyInt());
    }

    @Test
    void failedTodoCanBeExplicitlyRequeued() {
        when(salesOrderRepository.retryFailedInvoiceTodo("SO-1", "F006")).thenReturn(1);

        assertThat(processor.retryFailedTodo("F006", "SO-1")).isTrue();

        verify(salesOrderRepository).retryFailedInvoiceTodo("SO-1", "F006");
    }

    @Test
    void staleProcessingLeaseIsClaimedAfterRestartAndMarkedDoneOnlyAfterRequest() {
        SalesOrder stale = new SalesOrder();
        stale.setId("SO-LEASE");
        stale.setFactoryId("F006");
        stale.setInvoiceTodoStatus(SalesOrder.INVOICE_TODO_PROCESSING);
        stale.setInvoiceTodoNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        when(salesOrderRepository.findInvoiceTodoCandidates(any(LocalDateTime.class), eq(5), any()))
                .thenReturn(List.of(stale));
        when(salesOrderRepository.claimInvoiceTodo(eq("SO-LEASE"), eq("F006"),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(5))).thenReturn(1);
        when(invoiceService.requestInvoiceFromOrder(eq("F006"), eq("SO-LEASE"),
                isNull(), isNull(), anyString())).thenReturn(new InvoiceRecord());

        assertThat(processor.processDueTodos()).isEqualTo(1);

        verify(salesOrderRepository).markInvoiceTodoDone("SO-LEASE", "F006");
        org.mockito.Mockito.verify(salesOrderRepository).claimInvoiceTodo(eq("SO-LEASE"), eq("F006"),
                any(LocalDateTime.class), argThat(lease -> lease.isAfter(LocalDateTime.now().plusMinutes(9))), eq(5));
    }
}
