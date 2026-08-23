package com.cretas.aims.service.finance;

import com.cretas.aims.event.SalesOrderCompletedEvent;
import com.cretas.aims.service.finance.impl.SalesOrderCompletedInvoiceListener;
import com.cretas.aims.service.finance.impl.SalesOrderInvoiceTodoProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import static org.mockito.Mockito.*;

class SalesOrderCompletedInvoiceTransactionBoundaryTest {

    private AnnotationConfigApplicationContext context;
    private SalesOrderInvoiceTodoProcessor processor;
    private ApplicationEventPublisher publisher;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        processor = context.getBean(SalesOrderInvoiceTodoProcessor.class);
        publisher = context;
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void committedCompletion_runsListenerOnlyAfterCommit() {
        transactions.executeWithoutResult(status -> {
            publisher.publishEvent(new SalesOrderCompletedEvent(this, "F006", "SO-COMMIT"));
            verifyNoInteractions(processor);
        });

        verify(processor).process("F006", "SO-COMMIT");
    }

    @Test
    void rolledBackCompletion_doesNotCreateInvoiceTodo() {
        transactions.executeWithoutResult(status -> {
            publisher.publishEvent(new SalesOrderCompletedEvent(this, "F006", "SO-ROLLBACK"));
            status.setRollbackOnly();
        });

        verifyNoInteractions(processor);
    }

    @Test
    void listenerFailure_doesNotTurnCommittedDeliveryIntoFailure() {
        when(processor.process("F006", "SO-FAIL"))
                .thenThrow(new IllegalStateException("simulated invoice outage"));

        transactions.executeWithoutResult(status ->
                publisher.publishEvent(new SalesOrderCompletedEvent(this, "F006", "SO-FAIL")));

        verify(processor).process("F006", "SO-FAIL");
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        SalesOrderInvoiceTodoProcessor processor() {
            return mock(SalesOrderInvoiceTodoProcessor.class);
        }

        @Bean
        SalesOrderCompletedInvoiceListener listener(SalesOrderInvoiceTodoProcessor processor) {
            return new SalesOrderCompletedInvoiceListener(processor);
        }

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:invoice-event-boundary;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
