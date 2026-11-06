package com.cretas.aims.service.scheduling;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Factory-wide mutex for writes which change APS resource facts.
 *
 * <p>The lock is deliberately mandatory: callers must already be in their
 * existing transaction, so the resource read and write remain one unit.</p>
 */
@Service
@RequiredArgsConstructor
public class SchedulingResourceWriteLock {

    private final FactoryRepository factoryRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockFactory(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "工厂不能为空");
        }
        factoryRepository.findByIdForUpdate(factoryId)
                .orElseThrow(() -> new BusinessException(404, "工厂不存在: " + factoryId));
    }
}
