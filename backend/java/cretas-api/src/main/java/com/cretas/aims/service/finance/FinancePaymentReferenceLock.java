package com.cretas.aims.service.finance;

import com.cretas.aims.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes payment-reference uniqueness checks across all finance write paths
 * that share the factory-wide payment reference namespace.
 */
@Component
public class FinancePaymentReferenceLock {

    private final EntityManager entityManager;

    public FinancePaymentReferenceLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String acquire(String factoryId, String reference) {
        if (reference == null || reference.isBlank()) {
            return reference;
        }
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId不能为空")
                    .withCode("FINANCE_REFERENCE_FACTORY_REQUIRED");
        }

        String normalizedReference = reference.trim();
        String referenceKey = "FINANCE_REFERENCE:" + factoryId.length()
                + ":" + factoryId + ":" + normalizedReference;
        entityManager.createNativeQuery(
                        "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:referenceKey, 0))")
                .setParameter("referenceKey", referenceKey)
                .getResultList();
        return normalizedReference;
    }
}
