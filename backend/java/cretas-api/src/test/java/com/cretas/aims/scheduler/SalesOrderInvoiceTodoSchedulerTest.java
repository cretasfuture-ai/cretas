package com.cretas.aims.scheduler;

import com.cretas.aims.service.finance.impl.SalesOrderInvoiceTodoProcessor;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class SalesOrderInvoiceTodoSchedulerTest {

    @Test
    void scheduledSweepDelegatesToDurableProcessor() {
        SalesOrderInvoiceTodoProcessor processor = mock(SalesOrderInvoiceTodoProcessor.class);
        when(processor.processDueTodos()).thenReturn(1);
        SalesOrderInvoiceTodoScheduler scheduler = new SalesOrderInvoiceTodoScheduler(processor);

        scheduler.processDueTodos();

        verify(processor).processDueTodos();
    }
}
