package com.cretas.aims.repository.inventory;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Table;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.SalesOrder;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.inventory")
@DisplayName("Sales order invoice todo repository real JPA startup gate")
class SalesOrderInvoiceTodoRepositoryQueryValidationTest {

    @Autowired SalesOrderRepository salesOrderRepository;
    @Autowired EntityManager entityManager;

    private Long creatorId;

    @BeforeEach
    void createRequiredTenantAndUser() {
        Factory factory = new Factory();
        factory.setId("F006");
        factory.setName("Invoice todo JPA gate factory");
        entityManager.persist(factory);

        User creator = new User();
        creator.setFactoryId(factory.getId());
        creator.setUsername("invoice-todo-jpa-gate");
        creator.setPasswordHash("not-used");
        creator.setIsActive(true);
        entityManager.persist(creator);
        entityManager.flush();
        creatorId = creator.getId();

        Customer customer = new Customer();
        customer.setId("C-JPA");
        customer.setFactoryId(factory.getId());
        customer.setCode("C-JPA");
        customer.setCustomerCode("C-JPA");
        customer.setName("Invoice todo JPA gate customer");
        customer.setCreatedBy(creatorId);
        customer.setIsActive(true);
        entityManager.persist(customer);
        entityManager.flush();
    }

    @Test
    void todoQueryAndClaimUpdatesBootWithoutScanningHistoricalNulls() throws NoSuchFieldException {
        assertThat(salesOrderRepository.findInvoiceTodoCandidates(
                LocalDateTime.now(), 5, org.springframework.data.domain.PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(salesOrderRepository.findByFactoryIdAndCreatedByInAndStatusInOrderByCreatedAtDesc(
                "F006", java.util.List.of(creatorId), java.util.List.of(SalesOrderStatus.COMPLETED),
                org.springframework.data.domain.PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(salesOrderRepository.findFailedInvoiceTodosInScope(
                "F006", java.util.List.of(creatorId), 5,
                org.springframework.data.domain.PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(salesOrderRepository.claimInvoiceTodo(
                "missing", "F006", LocalDateTime.now(), LocalDateTime.now().plusMinutes(10), 5))
                .isZero();
        assertThat(salesOrderRepository.markInvoiceTodoDone("missing", "F006")).isZero();
        assertThat(salesOrderRepository.markInvoiceTodoFailure(
                "missing", "F006", LocalDateTime.now().plusMinutes(1), "test", 5)).isZero();
        assertThat(salesOrderRepository.findInvoiceTodoAttempts("missing", "F006")).isEmpty();
        assertThat(salesOrderRepository.retryFailedInvoiceTodo("missing", "F006")).isZero();
        assertThat(salesOrderRepository
                .findByFactoryIdAndInvoiceTodoStatusAndInvoiceTodoAttemptsGreaterThanEqualOrderByUpdatedAtDesc(
                        "F006", "FAILED", 5,
                        org.springframework.data.domain.PageRequest.of(0, 20)))
                .isEmpty();
        assertThat(entityManager.getMetamodel().entity(
                com.cretas.aims.entity.inventory.SalesOrder.class)
                .getAttribute("invoiceTodoStatus")).isNotNull();
        assertThat(com.cretas.aims.entity.inventory.SalesOrder.class
                .getDeclaredField("loadedStatus").getAnnotation(JsonIgnore.class)).isNotNull();
        assertThat(com.cretas.aims.entity.finance.InvoiceRecord.class
                .getAnnotation(Table.class).uniqueConstraints()).isEmpty();
    }

    @Test
    void maxOrderNumberIncludesSoftDeletedHistoryThatStillOccupiesUniqueKey() {
        SalesOrder deletedHistory = newOrder("SO-JPA-DELETED-HISTORY", SalesOrderStatus.DRAFT);
        deletedHistory.setOrderNumber("SO-20991231-0007");
        salesOrderRepository.saveAndFlush(deletedHistory);
        entityManager.createNativeQuery("UPDATE sales_orders SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id")
                .setParameter("id", deletedHistory.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(salesOrderRepository.findById(deletedHistory.getId())).isEmpty();
        assertThat(salesOrderRepository.findMaxOrderNumberByPrefix("F006", "SO-20991231-%"))
                .isEqualTo("SO-20991231-0007");
    }

    @Test
    void completionTransitionCreatesTodoAndHistoricalCompletedUpdateDoesNotBackfill() {
        SalesOrder draft = newOrder("SO-JPA-COMPLETION", SalesOrderStatus.DRAFT);
        salesOrderRepository.saveAndFlush(draft);
        entityManager.clear();

        SalesOrder reloadedDraft = salesOrderRepository.findById(draft.getId()).orElseThrow();
        reloadedDraft.setStatus(SalesOrderStatus.COMPLETED);
        salesOrderRepository.saveAndFlush(reloadedDraft);
        entityManager.clear();

        SalesOrder completed = salesOrderRepository.findById(draft.getId()).orElseThrow();
        assertThat(completed.getInvoiceTodoStatus()).isEqualTo(SalesOrder.INVOICE_TODO_PENDING);
        assertThat(completed.getInvoiceTodoAttempts()).isZero();

        SalesOrder historical = newOrder("SO-JPA-HISTORICAL", SalesOrderStatus.COMPLETED);
        salesOrderRepository.saveAndFlush(historical);
        entityManager.createNativeQuery("UPDATE sales_orders SET invoice_todo_status = NULL, invoice_todo_attempts = NULL WHERE id = :id")
                .setParameter("id", historical.getId())
                .executeUpdate();
        entityManager.clear();

        SalesOrder reloadedHistorical = salesOrderRepository.findById(historical.getId()).orElseThrow();
        assertThat(reloadedHistorical.getInvoiceTodoStatus()).isNull();
        reloadedHistorical.setRemark("ordinary historical edit");
        salesOrderRepository.saveAndFlush(reloadedHistorical);
        entityManager.clear();

        assertThat(salesOrderRepository.findById(historical.getId()).orElseThrow().getInvoiceTodoStatus())
                .isNull();
    }

    @Test
    void expiredProcessingLeaseAtMaxAttemptsAtomicallyBecomesFailed() {
        SalesOrder order = newOrder("SO-JPA-LEASE", SalesOrderStatus.COMPLETED);
        order.setInvoiceTodoStatus(SalesOrder.INVOICE_TODO_PROCESSING);
        order.setInvoiceTodoAttempts(5);
        order.setInvoiceTodoNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        salesOrderRepository.saveAndFlush(order);
        entityManager.clear();

        assertThat(salesOrderRepository.markExpiredInvoiceTodosFailed(
                LocalDateTime.now(), 5, "lease expired at max attempts")).isEqualTo(1);
        entityManager.clear();

        SalesOrder failed = salesOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(failed.getInvoiceTodoStatus()).isEqualTo(SalesOrder.INVOICE_TODO_FAILED);
        assertThat(failed.getInvoiceTodoAttempts()).isEqualTo(5);
        assertThat(failed.getInvoiceTodoNextAttemptAt()).isNull();
        assertThat(failed.getInvoiceTodoLastError()).isEqualTo("lease expired at max attempts");
        assertThat(salesOrderRepository.markExpiredInvoiceTodosFailed(
                LocalDateTime.now(), 5, "should not repeat")).isZero();
    }

    private SalesOrder newOrder(String id, SalesOrderStatus status) {
        SalesOrder order = new SalesOrder();
        order.setId(id);
        order.setFactoryId("F006");
        order.setOrderNumber(id);
        order.setCustomerId("C-JPA");
        order.setOrderDate(LocalDate.of(2026, 8, 23));
        order.setTotalAmount(BigDecimal.ZERO);
        order.setStatus(status);
        order.setCreatedBy(creatorId);
        return order;
    }
}
