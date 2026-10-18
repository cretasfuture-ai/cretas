package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.ArApTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the real repository and executes refund aggregation, opening and legacy sign queries. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ArApRefundRepositoryQueryValidationContractTest {
    @Autowired private ArApTransactionRepository repository;
    private static final String F = "P6-REFUND-QUERY";

    @Test void approvedRefundAddsToReceivablesWhileMixedSignPaymentsAndUnapprovedRowsRemainCorrect() {
        row(F,ArApTransactionType.AR_INVOICE,"1000",ArApApprovalStatus.APPROVED);
        row(F,ArApTransactionType.AR_PAYMENT,"-300",ArApApprovalStatus.APPROVED);
        row(F,ArApTransactionType.AR_PAYMENT,"50",ArApApprovalStatus.APPROVED);
        row(F,ArApTransactionType.AR_ADJUSTMENT,"-100",ArApApprovalStatus.APPROVED);
        assertThat(repository.sumReceivables(F)).isEqualByComparingTo("550");
        row(F,ArApTransactionType.AR_REFUND,"105",ArApApprovalStatus.APPROVED);
        row(F,ArApTransactionType.AR_REFUND,"999",ArApApprovalStatus.PENDING);
        row(F,ArApTransactionType.AR_REFUND,"777",ArApApprovalStatus.REJECTED);
        row(F,ArApTransactionType.AR_ADJUSTMENT,"888",ArApApprovalStatus.PENDING);
        row(F+"-OTHER",ArApTransactionType.AR_REFUND,"444",ArApApprovalStatus.APPROVED);
        var deleted = row(F,ArApTransactionType.AR_REFUND,"333",ArApApprovalStatus.APPROVED);
        deleted.setDeletedAt(LocalDateTime.now()); repository.saveAndFlush(deleted);
        assertThat(repository.sumReceivables(F)).isEqualByComparingTo("655");
        var summaries = repository.sumByCounterparty(F,CounterpartyType.CUSTOMER);
        assertThat(summaries).hasSize(1);
        assertThat((BigDecimal)summaries.get(0)[2]).isEqualByComparingTo("655");
    }

    @Test void openingBalanceUsesApprovedRefundAndNeverLaterUnapprovedOrDeletedSnapshot() {
        var invoice = row(F,ArApTransactionType.AR_INVOICE,"1000",ArApApprovalStatus.APPROVED);
        snapshot(invoice,"1000",LocalDate.of(2026,9,30));
        var refund = row(F,ArApTransactionType.AR_REFUND,"105",ArApApprovalStatus.APPROVED);
        snapshot(refund,"1105",LocalDate.of(2026,10,1));
        var pending = row(F,ArApTransactionType.AR_REFUND,"999",ArApApprovalStatus.PENDING);
        snapshot(pending,"9999",LocalDate.of(2026,10,2));
        var rejected = row(F,ArApTransactionType.AR_ADJUSTMENT,"888",ArApApprovalStatus.REJECTED);
        snapshot(rejected,"8888",LocalDate.of(2026,10,3));
        var deleted = row(F,ArApTransactionType.AR_REFUND,"333",ArApApprovalStatus.APPROVED);
        snapshot(deleted,"7777",LocalDate.of(2026,10,4));
        deleted.setDeletedAt(LocalDateTime.now());repository.saveAndFlush(deleted);
        var foreign = row(F+"-OTHER",ArApTransactionType.AR_REFUND,"444",ArApApprovalStatus.APPROVED);
        snapshot(foreign,"6666",LocalDate.of(2026,10,5));
        assertThat(repository.findOpeningBalance(F,CounterpartyType.CUSTOMER,"CUSTOMER",LocalDate.of(2026,10,6)).get(0))
                .isEqualByComparingTo("1105");
    }

    @Test void supplierAggregationPreservesExistingPaymentAndAdjustmentSemantics() {
        row(F,ArApTransactionType.AP_INVOICE,"500",ArApApprovalStatus.APPROVED);
        row(F,ArApTransactionType.AP_PAYMENT,"-100",ArApApprovalStatus.APPROVED);
        row(F,ArApTransactionType.AP_ADJUSTMENT,"900",ArApApprovalStatus.PENDING);
        assertThat(repository.sumPayables(F)).isEqualByComparingTo("400");
        assertThat((BigDecimal)repository.sumByCounterparty(F,CounterpartyType.SUPPLIER).get(0)[2])
                .isEqualByComparingTo("400");
    }

    @Test void realStatementReplayHandlesLateBackdatedReversalWithoutSnapshotOrTenantLeakage() {
        var originalDate = LocalDate.of(2026,10,1);
        snapshot(row(F,ArApTransactionType.AR_INVOICE,"100",ArApApprovalStatus.APPROVED),"100",originalDate);
        snapshot(row(F,ArApTransactionType.AR_REFUND,"15",ArApApprovalStatus.APPROVED),"115",originalDate);
        snapshot(row(F,ArApTransactionType.AR_INVOICE,"60",ArApApprovalStatus.APPROVED),"175",LocalDate.of(2026,10,15));
        var reversal = row(F,ArApTransactionType.AR_ADJUSTMENT,"-15",ArApApprovalStatus.APPROVED);
        reversal.setCreatedAt(LocalDateTime.of(2026,11,1,10,0));
        snapshot(reversal,"160",originalDate);
        snapshot(row(F,ArApTransactionType.AR_ADJUSTMENT,"-999",ArApApprovalStatus.PENDING),"9999",originalDate);
        snapshot(row(F,ArApTransactionType.AR_REFUND,"777",ArApApprovalStatus.REJECTED),"8888",originalDate);
        var deleted = row(F,ArApTransactionType.AR_REFUND,"555",ArApApprovalStatus.APPROVED);
        deleted.setDeletedAt(LocalDateTime.now()); snapshot(deleted,"7777",originalDate);
        snapshot(row(F+"-OTHER",ArApTransactionType.AR_REFUND,"444",ArApApprovalStatus.APPROVED),"6666",originalDate);
        var service = new com.cretas.aims.service.finance.impl.ArApServiceImpl(repository,
                org.mockito.Mockito.mock(com.cretas.aims.repository.CustomerRepository.class),
                org.mockito.Mockito.mock(com.cretas.aims.repository.SupplierRepository.class));
        var statement = service.getStatement(F,CounterpartyType.CUSTOMER,"CUSTOMER",LocalDate.of(2026,10,10),LocalDate.of(2026,10,31));
        assertThat((BigDecimal)statement.get("openingBalance")).isEqualByComparingTo("100");
        assertThat((BigDecimal)statement.get("periodDebit")).isEqualByComparingTo("60");
        assertThat((BigDecimal)statement.get("closingBalance")).isEqualByComparingTo(repository.sumReceivables(F));
        var after = service.getStatement(F,CounterpartyType.CUSTOMER,"CUSTOMER",LocalDate.of(2026,10,20),LocalDate.of(2026,10,31));
        assertThat((BigDecimal)after.get("openingBalance")).isEqualByComparingTo("160");
        assertThat((BigDecimal)after.get("closingBalance")).isEqualByComparingTo("160");
    }

    private ArApTransaction row(String factory,ArApTransactionType type,String amount,ArApApprovalStatus status) {
        var row = new ArApTransaction(); row.setFactoryId(factory);row.setTransactionNumber("P6-"+UUID.randomUUID());
        row.setTransactionType(type);row.setCounterpartyType(type.isAR()?CounterpartyType.CUSTOMER:CounterpartyType.SUPPLIER);
        row.setCounterpartyId(type.isAR()?"CUSTOMER":"SUPPLIER");row.setCounterpartyName(row.getCounterpartyId());
        row.setAmount(new BigDecimal(amount));row.setBalanceAfter(BigDecimal.ZERO);
        row.setApprovalStatus(status);row.setTransactionDate(LocalDate.of(2026,10,16));row.setOperatedBy(7L);
        return repository.saveAndFlush(row);
    }
    private void snapshot(ArApTransaction row,String balance,LocalDate date) {
        row.setBalanceAfter(new BigDecimal(balance));row.setTransactionDate(date);repository.saveAndFlush(row);
    }
}
