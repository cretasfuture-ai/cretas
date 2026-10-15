package com.cretas.aims.repository.blueprintactivation;

import com.cretas.aims.entity.blueprintactivation.FactoryActivationExecutionModule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FactoryActivationExecutionModuleRepository
        extends JpaRepository<FactoryActivationExecutionModule, String> {
    List<FactoryActivationExecutionModule> findByExecutionIdAndFactoryIdOrderByOrdinal(
            String executionId, String factoryId);
}
