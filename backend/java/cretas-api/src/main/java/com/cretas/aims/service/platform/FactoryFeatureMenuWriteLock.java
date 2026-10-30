package com.cretas.aims.service.platform;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Both menu and legacy config writers lock before reading their shared JSON row. */
@Component
public class FactoryFeatureMenuWriteLock {
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String factoryId) {
        if (entityManager.find(Factory.class, factoryId, LockModeType.PESSIMISTIC_WRITE) == null) {
            throw new BusinessException(404, "工厂不存在");
        }
    }
}
