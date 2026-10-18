package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowStatsDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.InvoiceStatus;
import com.cretas.aims.entity.enums.PaymentRecordStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate behavior gate for workflow statistics row scope. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(WorkflowStatsRowScopeService.class)
class WorkflowStatsRowScopeServiceContractTest {

    @Autowired EntityManager entityManager;
    @Autowired WorkflowStatsRowScopeService service;

    @Test
    void productionAndFinanceStatsHideRowsOwnedByAnotherCreator() {
        persistFactory("F-WORKFLOW-SCOPE");
        entityManager.flush();
        Long owner=persistUser("workflow-owner");
        Long outside=persistUser("workflow-outside");
        var product=new com.cretas.aims.entity.ProductType();
        product.setId("PRODUCT-WORKFLOW-SCOPE"); product.setFactoryId("F-WORKFLOW-SCOPE");
        product.setCreatedBy(owner); product.setCode("PRODUCT-WORKFLOW-SCOPE"); product.setName("scope product");
        product.setUnit("box"); product.setCategory("FINISHED_PRODUCT");
        product.setProductCategory("FINISHED_PRODUCT"); product.setIsActive(true);
        entityManager.persist(product);
        var customer=new com.cretas.aims.entity.Customer();
        customer.setId("CUSTOMER-WORKFLOW-SCOPE"); customer.setFactoryId("F-WORKFLOW-SCOPE");
        customer.setName("scope customer"); customer.setCode("CUSTOMER-WORKFLOW-SCOPE");
        customer.setCustomerCode("CUSTOMER-WORKFLOW-SCOPE"); customer.setCreatedBy(owner);
        entityManager.persist(customer); entityManager.flush();
        persistPlan("PLAN-OWNER-PENDING", owner, ProductionPlanStatus.PENDING);
        persistPlan("PLAN-OWNER-RUNNING", owner, ProductionPlanStatus.IN_PROGRESS);
        persistPlan("PLAN-OUTSIDE-DONE", outside, ProductionPlanStatus.COMPLETED);

        persistSalesOrder("SO-OWNER", owner);
        persistSalesOrder("SO-OUTSIDE", outside);
        persistInvoice("INV-OWNER", "SO-OWNER", InvoiceStatus.REQUESTED);
        persistInvoice("INV-OUTSIDE", "SO-OUTSIDE", InvoiceStatus.REQUESTED);
        persistPayment("PAY-OWNER", "SO-OWNER", PaymentRecordStatus.PENDING);
        persistPayment("PAY-OUTSIDE", "SO-OUTSIDE", PaymentRecordStatus.VERIFIED);
        entityManager.flush();
        entityManager.clear();

        WorkflowStatsDTO production = service.getProductionStats("F-WORKFLOW-SCOPE", List.of(owner));
        WorkflowStatsDTO finance = service.getFinanceStats("F-WORKFLOW-SCOPE", List.of(owner));

        assertThat(production.getNodes()).extracting("count").containsExactly(1L, 1L, 0L);
        assertThat(finance.getNodes()).extracting("count").containsExactly(1L, 1L, 0L);
        assertThat(service.getProductionStats("F-WORKFLOW-SCOPE", List.of()).getNodes())
                .extracting("count").containsExactly(0L, 0L, 0L);
    }

    private void persistFactory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName("workflow scope factory");
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
    }

    private Long persistUser(String username) {
        var user=new com.cretas.aims.entity.User();
        user.setFactoryId("F-WORKFLOW-SCOPE"); user.setUsername(username);
        user.setPasswordHash("test-only"); user.setIsActive(true);
        entityManager.persist(user); entityManager.flush();
        return user.getId();
    }

    private void persistPlan(String id, Long creator, ProductionPlanStatus status) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId("F-WORKFLOW-SCOPE");
        plan.setPlanNumber(id);
        plan.setProductTypeId("PRODUCT-WORKFLOW-SCOPE");
        plan.setPlannedQuantity(BigDecimal.ONE);
        plan.setPlannedUnit("box");
        plan.setPlannedDate(LocalDate.of(2026, 10, 18));
        plan.setStatus(status);
        plan.setCreatedBy(creator);
        plan.setSourceOrderIds(null);
        plan.setWorkflowOutputUnitsByProduct(null);
        plan.setSelectedBomRecipeIdsByProduct(null);
        plan.setSelectedBomVersionsByProduct(null);
        entityManager.persist(plan);
    }

    private void persistSalesOrder(String id, Long creator) {
        SalesOrder order = new SalesOrder();
        order.setId(id);
        order.setFactoryId("F-WORKFLOW-SCOPE");
        order.setOrderNumber(id);
        order.setCustomerId("CUSTOMER-WORKFLOW-SCOPE");
        order.setOrderDate(LocalDate.of(2026, 10, 18));
        order.setCreatedBy(creator);
        order.setVersion(0L);
        entityManager.persist(order);
    }

    private void persistInvoice(String id, String salesOrderId, InvoiceStatus status) {
        InvoiceRecord invoice = new InvoiceRecord();
        invoice.setId(id);
        invoice.setFactoryId("F-WORKFLOW-SCOPE");
        invoice.setInvoiceNumber(id);
        invoice.setSalesOrderId(salesOrderId);
        invoice.setAmount(BigDecimal.TEN);
        invoice.setTotalAmount(BigDecimal.TEN);
        invoice.setStatus(status);
        entityManager.persist(invoice);
    }

    private void persistPayment(String id, String salesOrderId, PaymentRecordStatus status) {
        PaymentRecord payment = new PaymentRecord();
        payment.setId(id);
        payment.setFactoryId("F-WORKFLOW-SCOPE");
        payment.setPaymentNumber(id);
        payment.setSalesOrderId(salesOrderId);
        payment.setAmount(BigDecimal.TEN);
        payment.setStatus(status);
        entityManager.persist(payment);
    }
}
