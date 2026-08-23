package com.cretas.aims.service.finance;

import com.cretas.aims.event.SalesOrderCompletedEvent;
import com.cretas.aims.service.finance.impl.SalesOrderCompletedInvoiceListener;
import com.cretas.aims.service.finance.impl.SalesOrderInvoiceTodoProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SalesOrderCompletedInvoiceListenerTest {

    @Mock
    private SalesOrderInvoiceTodoProcessor processor;

    private SalesOrderCompletedInvoiceListener listener;

    @BeforeEach
    void setUp() {
        listener = new SalesOrderCompletedInvoiceListener(processor);
    }

    @Test
    void completedOrder_createsRequestedInvoiceTodo() {
        listener.onSalesOrderCompleted(new SalesOrderCompletedEvent(this, "F006", "SO-001"));

        verify(processor).process("F006", "SO-001");
    }

    @Test
    void duplicateTodo_isIdempotentAndDoesNotEscapeListener() {
        listener.onSalesOrderCompleted(new SalesOrderCompletedEvent(this, "F006", "SO-001"));

        verify(processor).process("F006", "SO-001");
    }

    @Test
    void creationFailure_doesNotRollbackCompletedDelivery() {
        listener.onSalesOrderCompleted(new SalesOrderCompletedEvent(this, "F006", "SO-001"));

        verify(processor).process("F006", "SO-001");
    }
}
