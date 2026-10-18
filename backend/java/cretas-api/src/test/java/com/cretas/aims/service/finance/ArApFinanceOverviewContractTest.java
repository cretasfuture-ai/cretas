package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArApFinanceOverviewContractTest {
    private static final String FACTORY = "P8-OVERVIEW-A";
    @Mock private ArApTransactionRepository transactions;
    @Mock private CustomerRepository customers;
    @Mock private SupplierRepository suppliers;

    private ArApTransaction invoice(String amount, String outstanding) {
        ArApTransaction invoice = new ArApTransaction();
        invoice.setFactoryId(FACTORY);
        invoice.setAmount(new BigDecimal(amount));
        invoice.setOutstandingAmount(outstanding == null ? null : new BigDecimal(outstanding));
        return invoice;
    }

    private Map<String, Object> overview(List<ArApTransaction> receivables, List<ArApTransaction> payables) {
        when(transactions.sumReceivables(FACTORY)).thenReturn(new BigDecimal("525.00"));
        when(transactions.sumPayables(FACTORY)).thenReturn(new BigDecimal("1370.00"));
        when(transactions.findOverdueReceivables(eq(FACTORY), any(LocalDate.class))).thenReturn(receivables);
        when(transactions.findOverduePayables(eq(FACTORY), any(LocalDate.class))).thenReturn(payables);
        return new ArApServiceImpl(transactions, customers, suppliers).getFinanceOverview(FACTORY);
    }

    @Test
    void overdueAmountsAndCountsUseRemainingArAndApWithoutChangingNetBalance() {
        Map<String, Object> result = overview(
                List.of(invoice("100", "30"), invoice("100", "0")),
                List.of(invoice("200", "50"), invoice("200", "0")));

        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) result.get("overdueReceivable")));
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) result.get("overduePayable")));
        assertEquals(1, result.get("overdueARCount"));
        assertEquals(1, result.get("overdueAPCount"));
        assertEquals(new BigDecimal("525.00"), result.get("totalReceivable"));
        assertEquals(new BigDecimal("1370.00"), result.get("totalPayable"));
        verify(transactions).findOverdueReceivables(eq(FACTORY), any(LocalDate.class));
        verify(transactions).findOverduePayables(eq(FACTORY), any(LocalDate.class));
        verify(transactions).sumByCounterparty(FACTORY, CounterpartyType.CUSTOMER);
        verify(transactions).sumByCounterparty(FACTORY, CounterpartyType.SUPPLIER);
    }

    @Test
    void unknownLegacyRemainingAmountKeepsOriginalAmountInsteadOfInventingSettlement() {
        Map<String, Object> result = overview(List.of(invoice("100", null)), List.of(invoice("200", null)));
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) result.get("overdueReceivable")));
        assertEquals(0, new BigDecimal("200").compareTo((BigDecimal) result.get("overduePayable")));
        assertEquals(1, result.get("overdueARCount"));
        assertEquals(1, result.get("overdueAPCount"));
    }

    @Test
    void fullySettledInvoicesDoNotRemainOverdue() {
        Map<String, Object> result = overview(List.of(invoice("100", "0")), List.of(invoice("200", "0")));
        assertEquals(BigDecimal.ZERO, result.get("overdueReceivable"));
        assertEquals(BigDecimal.ZERO, result.get("overduePayable"));
        assertEquals(0, result.get("overdueARCount"));
        assertEquals(0, result.get("overdueAPCount"));
    }
}
