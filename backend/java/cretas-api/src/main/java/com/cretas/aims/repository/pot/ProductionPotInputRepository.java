package com.cretas.aims.repository.pot;

import com.cretas.aims.entity.pot.ProductionPotInput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProductionPotInputRepository extends JpaRepository<ProductionPotInput, Long> {

    List<ProductionPotInput> findByFactoryIdAndPotIdIn(String factoryId, Collection<Long> potIds);

    List<ProductionPotInput> findByFactoryIdAndSourceTypeAndSourceId(String factoryId, String sourceType, String sourceId);
}
