package com.cretas.aims.service.finance;

import com.cretas.aims.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancePaymentReferenceLockTest {

    @Test
    @DisplayName("normalizes the reference and locks the factory-scoped key in the current transaction")
    void acquireUsesTrimmedFactoryScopedKey() {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(1));

        FinancePaymentReferenceLock lock = new FinancePaymentReferenceLock(entityManager);

        assertEquals("BANK-42", lock.acquire("F006", "  BANK-42  "));
        verify(entityManager).createNativeQuery(
                "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:referenceKey, 0))");
        verify(query).setParameter(eq("referenceKey"), eq("FINANCE_REFERENCE:4:F006:BANK-42"));
        verify(query).getResultList();
    }

    @Test
    @DisplayName("blank references remain unclaimed and do not touch the database")
    void blankReferenceDoesNotLock() {
        EntityManager entityManager = mock(EntityManager.class);
        FinancePaymentReferenceLock lock = new FinancePaymentReferenceLock(entityManager);

        assertEquals("  ", lock.acquire("F006", "  "));
        assertSame(null, lock.acquire("F006", null));
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("nonblank references require a factory namespace")
    void nonblankReferenceRequiresFactory() {
        FinancePaymentReferenceLock lock = new FinancePaymentReferenceLock(mock(EntityManager.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> lock.acquire("  ", "BANK-42"));

        assertEquals("FINANCE_REFERENCE_FACTORY_REQUIRED", error.getErrorCode());
    }

    @Test
    @DisplayName("acquire is mandatory inside the caller transaction")
    void acquireRequiresMandatoryTransaction() throws NoSuchMethodException {
        Method method = FinancePaymentReferenceLock.class.getMethod("acquire", String.class, String.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertEquals(Propagation.MANDATORY, transactional.propagation());
    }
}
