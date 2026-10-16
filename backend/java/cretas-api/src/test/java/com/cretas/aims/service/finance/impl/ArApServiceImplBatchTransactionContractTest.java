package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArApServiceImplBatchTransactionContractTest {
    private final ArApTransactionRepository transactions = mock(ArApTransactionRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final CountingTransactionManager transactionManager = new CountingTransactionManager();
    private ArApServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ArApServiceImpl(transactions, customers, suppliers);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
    }

    @Test
    void batchApproveCommitsEachSuccessAndRollsBackOnlyFailedId() {
        ArApTransaction success = pending("ok");
        Supplier supplier = supplier("SUP-1", "10.00");
        when(transactions.findById("ok")).thenReturn(Optional.of(success));
        when(transactions.findById("missing")).thenReturn(Optional.empty());
        when(suppliers.findByIdAndFactoryId("SUP-1", "F-TX")).thenReturn(Optional.of(supplier));
        when(transactions.save(any(ArApTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(suppliers.save(any(Supplier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.batchApproveAdjustments("F-TX", List.of("ok", "missing"), 2L);

        assertThat(result).containsEntry("approvedCount", 1).containsEntry("failedCount", 1);
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isEqualTo(1);
        assertThat(success.getApprovalStatus()).isEqualTo(ArApApprovalStatus.APPROVED);
        assertThat(supplier.getCurrentBalance()).isEqualByComparingTo("15.00");
    }

    @Test
    void batchRejectCommitsEachSuccessAndDoesNotMutateBalance() {
        ArApTransaction success = pending("reject");
        Supplier supplier = supplier("SUP-1", "10.00");
        when(transactions.findById("reject")).thenReturn(Optional.of(success));
        when(transactions.findById("missing")).thenReturn(Optional.empty());
        when(transactions.save(any(ArApTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.batchRejectAdjustments("F-TX", List.of("reject", "missing"), 2L, "不符合条件");

        assertThat(result).containsEntry("rejectedCount", 1).containsEntry("failedCount", 1);
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isEqualTo(1);
        assertThat(success.getApprovalStatus()).isEqualTo(ArApApprovalStatus.REJECTED);
        assertThat(supplier.getCurrentBalance()).isEqualByComparingTo("10.00");
        verifyNoInteractions(suppliers);
    }

    @Test
    void batchConflictFailureUsesSafeRefreshMessageForSpringAndJpaCauses() {
        ArApTransaction springConflict = pending("spring-conflict");
        ArApTransaction jpaConflict = pending("jpa-conflict");
        Supplier supplier = supplier("SUP-1", "10.00");
        when(transactions.findById("spring-conflict")).thenReturn(Optional.of(springConflict));
        when(transactions.findById("jpa-conflict")).thenReturn(Optional.of(jpaConflict));
        when(suppliers.findByIdAndFactoryId("SUP-1", "F-TX")).thenReturn(Optional.of(supplier));
        when(suppliers.save(any(Supplier.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.save(any(ArApTransaction.class))).thenAnswer(invocation -> {
            ArApTransaction transaction = invocation.getArgument(0);
            if ("spring-conflict".equals(transaction.getId())) {
                throw new org.springframework.dao.OptimisticLockingFailureException("Hibernate SQL: update suppliers ...");
            }
            throw new RuntimeException(new jakarta.persistence.OptimisticLockException("SQL update failed"));
        });

        Map<String, Object> result = service.batchApproveAdjustments(
                "F-TX", List.of("spring-conflict", "jpa-conflict"), 2L);

        assertThat(result).containsEntry("approvedCount", 0).containsEntry("failedCount", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> failures = (List<Map<String, String>>) result.get("failures");
        assertThat(failures).extracting(failure -> failure.get("reason"))
                .containsOnly("该调整或余额已被其他操作修改，请刷新后重试");
    }

    private ArApTransaction pending(String id) {
        ArApTransaction transaction = new ArApTransaction();
        transaction.setId(id);
        transaction.setFactoryId("F-TX");
        transaction.setTransactionType(ArApTransactionType.AP_ADJUSTMENT);
        transaction.setCounterpartyType(CounterpartyType.SUPPLIER);
        transaction.setCounterpartyId("SUP-1");
        transaction.setAmount(new BigDecimal("5.00"));
        transaction.setOperatedBy(1L);
        transaction.setApprovalStatus(ArApApprovalStatus.PENDING);
        return transaction;
    }

    private Supplier supplier(String id, String balance) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setFactoryId("F-TX");
        supplier.setCurrentBalance(new BigDecimal(balance));
        return supplier;
    }

    private static final class CountingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() { return new Object(); }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }

        @Override
        protected void doCommit(DefaultTransactionStatus status) { commits++; }

        @Override
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
