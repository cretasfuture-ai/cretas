package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArApAdjustmentClosingLockTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private EntityManager entityManager;
    @Mock private Query query;
    @Mock private AccountingPeriodService accountingPeriodService;

    private ArApServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "accountingPeriodService", accountingPeriodService);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of("TX-1"));
    }

    @Test
    void recordAdjustment_closedPeriodRejectedBeforePendingWrite() {
        doThrow(new BusinessException(409, "期间已结账"))
                .when(accountingPeriodService).assertOpen("F006", 2026, 10);

        assertThatThrownBy(() -> service.recordAdjustment("F006", CounterpartyType.CUSTOMER,
                "C-1", new BigDecimal("-10"), 11L, "test"))
                .isInstanceOf(BusinessException.class);

        verify(accountingPeriodService).assertOpen("F006", 2026, 10);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void approveAdjustment_closedTransactionPeriodRejectedAfterRowRefresh() {
        ArApTransaction txn = new ArApTransaction();
        txn.setId("TX-1");
        txn.setFactoryId("F006");
        txn.setCounterpartyType(CounterpartyType.CUSTOMER);
        txn.setCounterpartyId("C-1");
        txn.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);
        txn.setApprovalStatus(ArApApprovalStatus.PENDING);
        txn.setOperatedBy(11L);
        txn.setTransactionDate(LocalDate.of(2026, 10, 18));
        when(transactionRepository.findById("TX-1")).thenReturn(Optional.of(txn));
        doThrow(new BusinessException(409, "期间已结账"))
                .when(accountingPeriodService).assertOpen("F006", 2026, 10);

        assertThatThrownBy(() -> service.approveAdjustment("F006", "TX-1", 22L))
                .isInstanceOf(BusinessException.class);

        verify(entityManager).refresh(txn);
        verify(accountingPeriodService).assertOpen("F006", 2026, 10);
        verify(customerRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }
}
