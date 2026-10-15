package com.cretas.aims.repository.blueprintactivation;

import com.cretas.aims.entity.blueprintactivation.FactoryActivationExecution;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FactoryActivationExecutionRepository
        extends JpaRepository<FactoryActivationExecution, String> {
    Optional<FactoryActivationExecution> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
}
