package com.cretas.aims.service.finance;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderCompletedEvent;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.impl.InvoiceServiceImpl;
import com.cretas.aims.service.finance.impl.SalesOrderCompletedInvoiceListener;
import com.cretas.aims.service.finance.impl.SalesOrderInvoiceTodoProcessor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 33 项: 从「发货事务提交」一路走到「待办 DONE 落库」的真 JPA 链路 (监听器 + 处理器 +
 * 真仓储, 只把开票服务换成 mock)。
 *
 * <p>为什么要有这一条: 单测各自绿不代表接上了 —— AFTER_COMMIT 阶段原事务的资源仍绑在
 * 线程上, 处理器的认领 / 标记若「加入」那个已提交的事务, 写入就不会被提交, 而每个单测都
 * 看不见这件事。这里每一步都在测试方法自己提交的事务里做, 再在新事务里读回。
 *
 * <p>从 Assembly (cretas-factory-assembly #129) 移植。修复前在本仓实测: 前两条红
 * (DONE 期望得 PENDING / attempts 期望 1 得 0), 日志里认领 UPDATE 抛
 * {@code TransactionRequiredException: no transaction is in progress}; 后两条 (补偿扫描 /
 * 回滚) 在同一夹具里绿, 是夹具本身可用的阳性对照。
 *
 * <p>独立 H2 库名: 不与共享 testdb 上的其它 @DataJpaTest 上下文互相 create-drop。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:invoice_todo_after_commit;MODE=PostgreSQL;"
        + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({SalesOrderCompletedInvoiceListener.class, SalesOrderInvoiceTodoProcessor.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SalesOrderInvoiceTodoAfterCommitJpaTest {

    @Autowired SalesOrderRepository salesOrderRepository;
    @Autowired SalesOrderInvoiceTodoProcessor processor;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;

    @MockBean InvoiceService invoiceService;

    @Test
    void committedCompletionIsInvoicedOnceAndTheTodoIsDurablyDone() {
        String factory = "F-AC-1";
        Long creator = tenant(factory);
        String other = createOrder("SO-AC-OTHER", "F-AC-1B", tenant("F-AC-1B"), SalesOrderStatus.PARTIAL_DELIVERED);
        createOrder("SO-AC-1", factory, creator, SalesOrderStatus.PARTIAL_DELIVERED);
        when(invoiceService.requestInvoiceFromOrder(eq(factory), eq("SO-AC-1"), isNull(), isNull(),
                eq(InvoiceServiceImpl.AUTOMATIC_INVOICE_REMARK))).thenReturn(new InvoiceRecord());

        completeAndPublish("SO-AC-1", factory);

        SalesOrder after = reload("SO-AC-1");
        assertThat(after.getInvoiceTodoStatus()).isEqualTo(SalesOrder.INVOICE_TODO_DONE);
        assertThat(after.getInvoiceTodoAttempts()).isEqualTo(1);
        verify(invoiceService, times(1)).requestInvoiceFromOrder(eq(factory), eq("SO-AC-1"), isNull(), isNull(),
                eq(InvoiceServiceImpl.AUTOMATIC_INVOICE_REMARK));

        // 补偿扫描再跑两遍: 已 DONE, 不会再开第二张 (返回值含同库其它测试的订单, 只看本单的调用数)。
        processor.processDueTodos();
        processor.processDueTodos();
        assertThat(reload("SO-AC-1").getInvoiceTodoAttempts()).isEqualTo(1);
        verify(invoiceService, times(1)).requestInvoiceFromOrder(anyString(), eq("SO-AC-1"), isNull(), isNull(),
                anyString());
        // 别厂的订单一行没动。
        SalesOrder untouched = reload(other);
        assertThat(untouched.getInvoiceTodoStatus()).isNull();
        assertThat(untouched.getStatus()).isEqualTo(SalesOrderStatus.PARTIAL_DELIVERED);
    }

    @Test
    void failedImmediateAttemptLeavesADurableRetryableTodo() {
        String factory = "F-AC-2";
        createOrder("SO-AC-2", factory, tenant(factory), SalesOrderStatus.PARTIAL_DELIVERED);
        when(invoiceService.requestInvoiceFromOrder(eq(factory), eq("SO-AC-2"), isNull(), isNull(), anyString()))
                .thenThrow(new IllegalStateException("invoice outage"));

        completeAndPublish("SO-AC-2", factory);

        SalesOrder after = reload("SO-AC-2");
        assertThat(after.getStatus()).isEqualTo(SalesOrderStatus.COMPLETED);
        assertThat(after.getInvoiceTodoStatus()).isEqualTo(SalesOrder.INVOICE_TODO_PENDING);
        assertThat(after.getInvoiceTodoAttempts()).isEqualTo(1);
        assertThat(after.getInvoiceTodoLastError()).contains("invoice outage");
        assertThat(after.getInvoiceTodoNextAttemptAt()).isNotNull();
    }

    @Test
    void lostEventIsCompensatedByTheScannerExactlyOnce() {
        String factory = "F-AC-3";
        createOrder("SO-AC-3", factory, tenant(factory), SalesOrderStatus.PARTIAL_DELIVERED);
        when(invoiceService.requestInvoiceFromOrder(eq(factory), eq("SO-AC-3"), isNull(), isNull(), anyString()))
                .thenReturn(new InvoiceRecord());

        // 完成了, 但事件没发 (例如完成发生在不含财务域的进程里)。
        tx().executeWithoutResult(s -> {
            SalesOrder order = salesOrderRepository.findById("SO-AC-3").orElseThrow();
            order.setStatus(SalesOrderStatus.COMPLETED);
            salesOrderRepository.save(order);
        });
        assertThat(reload("SO-AC-3").getInvoiceTodoStatus()).isEqualTo(SalesOrder.INVOICE_TODO_PENDING);
        verify(invoiceService, never()).requestInvoiceFromOrder(anyString(), eq("SO-AC-3"), isNull(), isNull(),
                anyString());

        assertThat(processor.processDueTodos()).isGreaterThanOrEqualTo(1);
        processor.processDueTodos();

        SalesOrder done = reload("SO-AC-3");
        assertThat(done.getInvoiceTodoStatus()).isEqualTo(SalesOrder.INVOICE_TODO_DONE);
        assertThat(done.getInvoiceTodoAttempts()).isEqualTo(1);
        verify(invoiceService, times(1)).requestInvoiceFromOrder(eq(factory), eq("SO-AC-3"), isNull(), isNull(),
                eq(InvoiceServiceImpl.AUTOMATIC_INVOICE_REMARK));
    }

    @Test
    void rolledBackCompletionLeavesNoTodoAndNoInvoice() {
        String factory = "F-AC-4";
        createOrder("SO-AC-4", factory, tenant(factory), SalesOrderStatus.PARTIAL_DELIVERED);

        tx().executeWithoutResult(s -> {
            SalesOrder order = salesOrderRepository.findById("SO-AC-4").orElseThrow();
            order.setStatus(SalesOrderStatus.COMPLETED);
            salesOrderRepository.saveAndFlush(order);
            publisher.publishEvent(new SalesOrderCompletedEvent(this, factory, "SO-AC-4"));
            s.setRollbackOnly();
        });

        SalesOrder after = reload("SO-AC-4");
        assertThat(after.getStatus()).isEqualTo(SalesOrderStatus.PARTIAL_DELIVERED);
        assertThat(after.getInvoiceTodoStatus()).isNull();
        verify(invoiceService, never()).requestInvoiceFromOrder(anyString(), eq("SO-AC-4"), isNull(), isNull(),
                anyString());
    }

    /** 发货事务的形状: 同一个事务里把订单置为 COMPLETED 并发布完成事件, 然后提交。 */
    private void completeAndPublish(String id, String factory) {
        tx().executeWithoutResult(s -> {
            SalesOrder order = salesOrderRepository.findById(id).orElseThrow();
            order.setStatus(SalesOrderStatus.COMPLETED);
            salesOrderRepository.save(order);
            publisher.publishEvent(new SalesOrderCompletedEvent(this, factory, id));
        });
    }

    private SalesOrder reload(String id) {
        return tx().execute(s -> {
            SalesOrder order = salesOrderRepository.findById(id).orElseThrow();
            entityManager.detach(order);
            return order;
        });
    }

    private String createOrder(String id, String factory, Long creator, SalesOrderStatus status) {
        tx().executeWithoutResult(s -> {
            SalesOrder order = new SalesOrder();
            order.setId(id);
            order.setFactoryId(factory);
            order.setOrderNumber(id);
            order.setCustomerId("C-" + factory);
            order.setOrderDate(LocalDate.of(2026, 10, 3));
            order.setTotalAmount(BigDecimal.ZERO);
            order.setStatus(status);
            order.setCreatedBy(creator);
            salesOrderRepository.save(order);
        });
        return id;
    }

    private Long tenant(String factoryId) {
        return tx().execute(s -> {
            Factory factory = new Factory();
            factory.setId(factoryId);
            factory.setName(factoryId);
            factory.setType(FactoryType.FACTORY);
            factory.setLevel(0);
            factory.setIsActive(true);
            factory.setManuallyVerified(false);
            factory.setAiWeeklyQuota(20);
            entityManager.persist(factory);

            User user = new User();
            user.setFactoryId(factoryId);
            user.setUsername("after-commit-" + factoryId);
            user.setPasswordHash("test-only");
            user.setIsActive(true);
            entityManager.persist(user);
            entityManager.flush();

            Customer customer = new Customer();
            customer.setId("C-" + factoryId);
            customer.setFactoryId(factoryId);
            customer.setCode("C-" + factoryId);
            customer.setCustomerCode("C-" + factoryId);
            customer.setName("Customer " + factoryId);
            customer.setIsActive(true);
            customer.setCreatedBy(user.getId());
            entityManager.persist(customer);
            return user.getId();
        });
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
