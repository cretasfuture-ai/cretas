package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.FinanceRowScopeQuery;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArApStatementScopeContractTest {

    @Test
    void nativeScopedPagesDoNotAppendEntityPropertySorts() {
        ArApTransactionRepository transactions = mock(ArApTransactionRepository.class);
        FinanceRowScopeQuery scope = mock(FinanceRowScopeQuery.class);
        ArApServiceImpl service = new ArApServiceImpl(transactions,
                mock(CustomerRepository.class), mock(SupplierRepository.class));
        ReflectionTestUtils.setField(service, "financeRowScopeQuery", scope);
        when(scope.creators("F1")).thenReturn(List.of(11L));
        org.mockito.Mockito.doAnswer(call -> {
            org.springframework.data.domain.Pageable page = call.getArgument(4);
            assertThat(page.getSort().isUnsorted()).isTrue();
            assertThat(page.getPageNumber()).isEqualTo(1);
            return org.springframework.data.domain.Page.empty(page);
        }).when(transactions).findScopedTransactions(org.mockito.ArgumentMatchers.eq("F1"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(List.of(11L)), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> {
            org.springframework.data.domain.Pageable page = call.getArgument(7);
            assertThat(page.getSort().isUnsorted()).isTrue();
            return org.springframework.data.domain.Page.empty(page);
        }).when(transactions).findScopedPendingAdjustments(org.mockito.ArgumentMatchers.eq("F1"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(List.of(11L)),
                org.mockito.ArgumentMatchers.any());
        service.getTransactions("F1", null, null, 2, 10);
        service.getPendingAdjustments("F1", 2, 10);
        service.getPendingAdjustments("F1", CounterpartyType.SUPPLIER, null, null, null, null, 2, 10);
    }

    @Test
    void restrictedStatementSumsOwnerHistoryAndHidesRunningBalance() {
        ArApTransactionRepository transactions = mock(ArApTransactionRepository.class);
        FinanceRowScopeQuery scope = mock(FinanceRowScopeQuery.class);
        ArApServiceImpl service = new ArApServiceImpl(transactions,
                mock(CustomerRepository.class), mock(SupplierRepository.class));
        ReflectionTestUtils.setField(service, "financeRowScopeQuery", scope);
        when(scope.creators("F1")).thenReturn(List.of(11L));
        when(transactions.findScopedOpeningBalance("F1", "CUSTOMER", "C1",
                LocalDate.of(2026, 10, 10), List.of(11L)))
                .thenReturn(List.of(new BigDecimal("100.00")));

        ArApTransaction invoice = row("INV", ArApTransactionType.AR_INVOICE,
                new BigDecimal("30.00"), ArApApprovalStatus.APPROVED, "9999.00");
        ArApTransaction payment = row("PAY", ArApTransactionType.AR_PAYMENT,
                new BigDecimal("20.00"), ArApApprovalStatus.APPROVED, "8888.00");
        ArApTransaction pending = row("PENDING", ArApTransactionType.AR_ADJUSTMENT,
                new BigDecimal("50.00"), ArApApprovalStatus.PENDING, "7777.00");
        ArApTransaction approved = row("APPROVED", ArApTransactionType.AR_ADJUSTMENT,
                new BigDecimal("5.00"), ArApApprovalStatus.APPROVED, "6666.00");
        when(transactions.findScopedByCounterpartyAndDateRange("F1", "CUSTOMER", "C1",
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20), List.of(11L)))
                .thenReturn(List.of(invoice, payment, pending, approved));

        Map<String, Object> statement = service.getStatement("F1", CounterpartyType.CUSTOMER, "C1",
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20));

        assertThat(statement).containsEntry("scopeApplied", true)
                .containsEntry("scopeLabel", "当前授权范围")
                .containsEntry("openingBalance", new BigDecimal("100.00"))
                .containsEntry("periodDebit", new BigDecimal("35.00"))
                .containsEntry("periodCredit", new BigDecimal("20.00"))
                .containsEntry("closingBalance", new BigDecimal("115.00"));
        @SuppressWarnings("unchecked")
        List<ArApTransaction> projections = (List<ArApTransaction>) statement.get("transactions");
        assertThat(projections).hasSize(4).allSatisfy(row -> assertThat(row.getBalanceAfter()).isNull());
        assertThat(invoice.getBalanceAfter()).isEqualByComparingTo("9999.00");
    }

    private static ArApTransaction row(String id, ArApTransactionType type, BigDecimal amount,
                                       ArApApprovalStatus approval, String balanceAfter) {
        ArApTransaction row = new ArApTransaction();
        row.setId(id);
        row.setFactoryId("F1");
        row.setCounterpartyType(CounterpartyType.CUSTOMER);
        row.setCounterpartyId("C1");
        row.setTransactionType(type);
        row.setAmount(amount);
        row.setApprovalStatus(approval);
        row.setBalanceAfter(new BigDecimal(balanceAfter));
        return row;
    }
}
