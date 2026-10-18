package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ArApRefundStatementContractTest {
    @Test void customerStatementUsesOnlyEffectiveRefundsAndKeepsRawAuditHistory() {
        var repo = mock(ArApTransactionRepository.class);
        var service = new ArApServiceImpl(repo,mock(CustomerRepository.class),mock(SupplierRepository.class));
        var start = LocalDate.of(2026,10,1); var end = LocalDate.of(2026,10,31);
        when(repo.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdOrderByTransactionDateDesc(
                eq("F"),eq(CounterpartyType.CUSTOMER),eq("C"),any()))
                .thenReturn(new PageImpl<>(List.of(dated(ArApTransactionType.AR_INVOICE,"100",ArApApprovalStatus.APPROVED,LocalDate.of(2026,9,30)))));
        var history = List.of(
                row(ArApTransactionType.AR_INVOICE,"1000",ArApApprovalStatus.APPROVED),
                row(ArApTransactionType.AR_PAYMENT,"-300",ArApApprovalStatus.APPROVED),
                row(ArApTransactionType.AR_PAYMENT,"50",ArApApprovalStatus.APPROVED),
                row(ArApTransactionType.AR_ADJUSTMENT,"-100",ArApApprovalStatus.APPROVED),
                row(ArApTransactionType.AR_REFUND,"105",ArApApprovalStatus.APPROVED),
                row(ArApTransactionType.AR_REFUND,"999",ArApApprovalStatus.PENDING),
                row(ArApTransactionType.AR_REFUND,"777",ArApApprovalStatus.REJECTED),
                row(ArApTransactionType.AR_ADJUSTMENT,"888",ArApApprovalStatus.PENDING));
        when(repo.findByCounterpartyAndDateRange("F",CounterpartyType.CUSTOMER,"C",start,end)).thenReturn(history);
        var statement = service.getStatement("F",CounterpartyType.CUSTOMER,"C",start,end);
        assertThat((BigDecimal)statement.get("periodDebit")).isEqualByComparingTo("1105");
        assertThat((BigDecimal)statement.get("periodCredit")).isEqualByComparingTo("450");
        assertThat((BigDecimal)statement.get("closingBalance")).isEqualByComparingTo("755");
        assertThat(statement.get("transactions")).isSameAs(history);
        assertThat(statement.get("transactionCount")).isEqualTo(8);
    }

    @Test void lateApprovedBackdatedReversalUsesEffectiveHistoryRatherThanCurrentBalanceSnapshots() {
        var repo = mock(ArApTransactionRepository.class);
        var service = new ArApServiceImpl(repo,mock(CustomerRepository.class),mock(SupplierRepository.class));
        var originalDate = LocalDate.of(2026,10,1);
        var laterDate = LocalDate.of(2026,10,15);
        var invoice = dated(ArApTransactionType.AR_INVOICE,"100",ArApApprovalStatus.APPROVED,originalDate);
        var refund = dated(ArApTransactionType.AR_REFUND,"15",ArApApprovalStatus.APPROVED,originalDate);
        var reversal = dated(ArApTransactionType.AR_ADJUSTMENT,"-15",ArApApprovalStatus.APPROVED,originalDate);
        reversal.setCreatedAt(java.time.LocalDateTime.of(2026,11,1,10,0));
        reversal.setBalanceAfter(new BigDecimal("160")); // Receipt-time snapshot already includes later +60.
        var laterInvoice = dated(ArApTransactionType.AR_INVOICE,"60",ArApApprovalStatus.APPROVED,laterDate);
        var pending = dated(ArApTransactionType.AR_ADJUSTMENT,"-999",ArApApprovalStatus.PENDING,originalDate);
        var rejected = dated(ArApTransactionType.AR_REFUND,"777",ArApApprovalStatus.REJECTED,originalDate);
        when(repo.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdOrderByTransactionDateDesc(
                eq("F"),eq(CounterpartyType.CUSTOMER),eq("C"),any()))
                .thenReturn(new PageImpl<>(List.of(invoice,refund,reversal,laterInvoice,pending,rejected)));
        var start = LocalDate.of(2026,10,10); var end = LocalDate.of(2026,10,31);
        when(repo.findByCounterpartyAndDateRange("F",CounterpartyType.CUSTOMER,"C",start,end)).thenReturn(List.of(laterInvoice));
        var statement = service.getStatement("F",CounterpartyType.CUSTOMER,"C",start,end);
        assertThat((BigDecimal)statement.get("openingBalance")).isEqualByComparingTo("100");
        assertThat((BigDecimal)statement.get("closingBalance")).isEqualByComparingTo("160");
        var after = service.getStatement("F",CounterpartyType.CUSTOMER,"C",LocalDate.of(2026,10,20),end);
        assertThat((BigDecimal)after.get("openingBalance")).isEqualByComparingTo("160");
        assertThat((BigDecimal)after.get("closingBalance")).isEqualByComparingTo("160");
        verify(repo,never()).findOpeningBalance(any(),any(),any(),any());
    }

    @Test void openingReplayContinuesAcrossPagesAndSupplierPaymentsUseSignedAmount() {
        var repo = mock(ArApTransactionRepository.class);
        var service = new ArApServiceImpl(repo,mock(CustomerRepository.class),mock(SupplierRepository.class));
        var start = LocalDate.of(2026,10,10); var end = LocalDate.of(2026,10,31);
        var date = start.minusDays(1);
        var first = new java.util.ArrayList<ArApTransaction>();
        for(int i=0;i<500;i++) first.add(dated(ArApTransactionType.AP_INVOICE,"1",ArApApprovalStatus.APPROVED,date));
        when(repo.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdOrderByTransactionDateDesc(
                eq("F"),eq(CounterpartyType.SUPPLIER),eq("S"),argThat(p -> p != null && p.getPageNumber()==0)))
                .thenReturn(new PageImpl<>(first,PageRequest.of(0,500),504));
        when(repo.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdOrderByTransactionDateDesc(
                eq("F"),eq(CounterpartyType.SUPPLIER),eq("S"),argThat(p -> p != null && p.getPageNumber()==1)))
                .thenReturn(new PageImpl<>(List.of(dated(ArApTransactionType.AP_PAYMENT,"100",ArApApprovalStatus.APPROVED,date),
                        dated(ArApTransactionType.AP_PAYMENT,"-50",ArApApprovalStatus.APPROVED,date),
                        dated(ArApTransactionType.AP_ADJUSTMENT,"800",ArApApprovalStatus.PENDING,date),
                        dated(ArApTransactionType.AP_ADJUSTMENT,"700",ArApApprovalStatus.REJECTED,date)),PageRequest.of(1,500),504));
        var statement = service.getStatement("F",CounterpartyType.SUPPLIER,"S",start,end);
        assertThat((BigDecimal)statement.get("openingBalance")).isEqualByComparingTo("350");
        assertThat((BigDecimal)statement.get("closingBalance")).isEqualByComparingTo("350");
    }

    private ArApTransaction dated(ArApTransactionType type,String amount,ArApApprovalStatus status,LocalDate date) {
        var result = row(type,amount,status);result.setTransactionDate(date);return result;
    }

    private ArApTransaction row(ArApTransactionType type,String amount,ArApApprovalStatus approval) {
        var row = new ArApTransaction();row.setTransactionType(type);row.setAmount(new BigDecimal(amount));row.setApprovalStatus(approval);return row;
    }
}
