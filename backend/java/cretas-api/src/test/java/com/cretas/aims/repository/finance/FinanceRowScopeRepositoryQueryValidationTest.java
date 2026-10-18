package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
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
        var invoice=tx("refund-invoice",11L,new BigDecimal("100.00"),null,null);
        var refund=tx("refund",11L,new BigDecimal("15.00"),null,null);
        refund.setTransactionType(ArApTransactionType.AR_REFUND);
        var reversal=tx("refund-reversal",11L,new BigDecimal("-15.00"),null,null);
        reversal.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);
        var pending=tx("refund-pending",11L,new BigDecimal("90.00"),null,null);
        pending.setTransactionType(ArApTransactionType.AR_REFUND);
        pending.setApprovalStatus(ArApApprovalStatus.PENDING);
        var other=tx("refund-other",22L,new BigDecimal("500.00"),null,null);
        other.setTransactionType(ArApTransactionType.AR_REFUND);
        repository.saveAllAndFlush(List.of(invoice,refund,reversal,pending,other));
        assertThat(repository.sumReceivablesScoped("ROW-SCOPE-FINANCE",List.of(11L))).isEqualByComparingTo("100.00");
        assertThat(repository.findScopedOpeningBalance("ROW-SCOPE-FINANCE","CUSTOMER","customer-1",LocalDate.now().plusDays(1),List.of(11L)))
                .containsExactly(new BigDecimal("100.00"));
        var totals=repository.sumByCounterpartyScoped("ROW-SCOPE-FINANCE","CUSTOMER",List.of(11L));
        assertThat(totals).hasSize(1);
        assertThat((BigDecimal)totals.get(0)[2]).isEqualByComparingTo("100.00");
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
