package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.enums.PaymentSourceType;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies DB filtering/counting and fail-closed handling for source-less AR/AP rows. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = ArApTransactionRepository.class)
class FinanceRowScopeRepositoryQueryValidationTest {

    @Autowired private ArApTransactionRepository repository;
    @Autowired private EntityManager em;
    private Long refundOwnerId;

    @Test
    void scopedPageAndAggregatesExcludeOtherOwnersAndDamagedSources() {
        repository.saveAndFlush(tx("self", 11L, new BigDecimal("100.00"), null, null));
        repository.saveAndFlush(tx("other", 22L, new BigDecimal("200.00"), null, null));
        repository.saveAndFlush(tx("damaged", 11L, new BigDecimal("999.00"), "UNKNOWN_SOURCE", "x"));

        var page = repository.findScopedTransactions("ROW-SCOPE-FINANCE", null, null,
                List.of(11L), PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(ArApTransaction::getId).containsExactly("self");
        assertThat(repository.sumReceivablesScoped("ROW-SCOPE-FINANCE", List.of(11L)))
                .isEqualByComparingTo("100.00");
        assertThat(repository.findScopedOpeningBalance("ROW-SCOPE-FINANCE", "CUSTOMER", "customer-1",
                LocalDate.now().plusDays(1), List.of(11L)))
                .containsExactly(new BigDecimal("100.00"));
    }

    @Test
    void scopedRefundAndReversalUseApprovedSignedAmountsAcrossAllAggregates() {
        persistRefundLineage();
        var invoice=tx("refund-invoice",refundOwnerId,new BigDecimal("100.00"),null,null);
        var refund=tx("refund",refundOwnerId,new BigDecimal("15.00"),null,null);
        refund.setTransactionType(ArApTransactionType.AR_REFUND);
        refund.setSourceType("RETURN_REFUND");
        refund.setSourceId("refund-request");
        refund.setSalesOrderId("refund-sales-order");
        var reversal=tx("refund-reversal",refundOwnerId,new BigDecimal("-15.00"),null,null);
        reversal.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);
        reversal.setSourceType("RETURN_REFUND_REVERSAL");
        reversal.setSourceId("refund-request");
        reversal.setSalesOrderId("refund-sales-order");
        var pending=tx("refund-pending",refundOwnerId,new BigDecimal("90.00"),null,null);
        pending.setTransactionType(ArApTransactionType.AR_REFUND);
        pending.setApprovalStatus(ArApApprovalStatus.PENDING);
        var other=tx("refund-other",22L,new BigDecimal("500.00"),null,null);
        other.setFactoryId("ROW-SCOPE-OTHER");
        other.setTransactionType(ArApTransactionType.AR_REFUND);
        repository.saveAllAndFlush(List.of(invoice,refund,reversal,pending,other));
        var scoped = repository.findScopedTransactions("ROW-SCOPE-FINANCE", "CUSTOMER", "customer-1",
                List.of(refundOwnerId), PageRequest.of(0, 10));
        assertThat(scoped.getContent()).extracting(ArApTransaction::getId)
                .containsExactlyInAnyOrder("refund-invoice", "refund", "refund-reversal", "refund-pending");
        assertThat(repository.sumReceivables("ROW-SCOPE-FINANCE")).isEqualByComparingTo("100.00");
        assertThat(repository.sumReceivablesScoped("ROW-SCOPE-FINANCE",List.of(refundOwnerId))).isEqualByComparingTo("100.00");
        assertThat(repository.findScopedOpeningBalance("ROW-SCOPE-FINANCE","CUSTOMER","customer-1",LocalDate.now().plusDays(1),List.of(refundOwnerId)))
                .containsExactly(new BigDecimal("100.00"));
        var totals=repository.sumByCounterpartyScoped("ROW-SCOPE-FINANCE","CUSTOMER",List.of(refundOwnerId));
        assertThat(totals).hasSize(1);
        assertThat((BigDecimal)totals.get(0)[2]).isEqualByComparingTo("100.00");
    }

    private void persistRefundLineage() {
        var factory = new com.cretas.aims.entity.Factory();
        factory.setId("ROW-SCOPE-FINANCE"); factory.setName("Refund scope factory"); em.persist(factory);
        var user = new com.cretas.aims.entity.User();
        user.setFactoryId("ROW-SCOPE-FINANCE"); user.setUsername("refund-scope-user");
        user.setPasswordHash("unused"); user.setRoleCode("factory_super_admin");
        em.persist(user);
        em.flush();
        refundOwnerId = user.getId();
        var customer = new com.cretas.aims.entity.Customer();
        customer.setId("customer-1"); customer.setFactoryId("ROW-SCOPE-FINANCE");
        customer.setCode("customer-1"); customer.setCustomerCode("customer-1"); customer.setName("Refund customer");
        customer.setCreatedBy(refundOwnerId); em.persist(customer);
        var so = new SalesOrder();
        so.setId("refund-sales-order"); so.setFactoryId("ROW-SCOPE-FINANCE");
        so.setOrderNumber("refund-sales-order"); so.setCustomerId("customer-1");
        so.setOrderDate(LocalDate.now()); so.setCreatedBy(refundOwnerId);
        em.persist(so);
        var ro = new ReturnOrder();
        ro.setId("refund-return-order"); ro.setFactoryId("ROW-SCOPE-FINANCE");
        ro.setReturnNumber("refund-return-order"); ro.setReturnType(ReturnType.SALES_RETURN);
        ro.setStatus(ReturnOrderStatus.COMPLETED); ro.setWithGoods(Boolean.TRUE); ro.setCounterpartyId("customer-1");
        ro.setSourceOrderId(so.getId()); ro.setReturnDate(LocalDate.now());
        ro.setCreatedBy(refundOwnerId); em.persist(ro);
        var request = new PaymentRequest();
        request.setId("refund-request"); request.setFactoryId("ROW-SCOPE-FINANCE");
        request.setRequestNumber("refund-request"); request.setSourceType(PaymentSourceType.SALES);
        request.setSalesOrderId(so.getId()); request.setReturnOrderId(ro.getId());
        request.setCustomerId("customer-1"); request.setStatus(PaymentRequestStatus.PAID);
        request.setAmount(new BigDecimal("15.00")); request.setCreatedBy(refundOwnerId);
        em.persist(request);
        em.flush();
        request.setStatus(PaymentRequestStatus.CANCELLED);
        em.flush();
    }

    private ArApTransaction tx(String id, Long operator, BigDecimal amount,
                               String sourceType, String sourceId) {
        ArApTransaction transaction = new ArApTransaction();
        transaction.setId(id);
        transaction.setFactoryId("ROW-SCOPE-FINANCE");
        transaction.setTransactionNumber("T-" + id);
        transaction.setTransactionType(ArApTransactionType.AR_INVOICE);
        transaction.setCounterpartyType(CounterpartyType.CUSTOMER);
        transaction.setCounterpartyId("customer-1");
        transaction.setCounterpartyName("customer-1");
        transaction.setAmount(amount);
        transaction.setBalanceAfter(amount);
        transaction.setTransactionDate(LocalDate.now());
        transaction.setOperatedBy(operator);
        transaction.setSourceType(sourceType);
        transaction.setSourceId(sourceId);
        transaction.setApprovalStatus(ArApApprovalStatus.APPROVED);
        return transaction;
    }
}
