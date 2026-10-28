package com.cretas.aims.repository.pot;

import com.cretas.aims.entity.pot.ProductionPotOutput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProductionPotOutputRepository extends JpaRepository<ProductionPotOutput, Long> {

    List<ProductionPotOutput> findByFactoryIdAndPotIdIn(String factoryId, Collection<Long> potIds);

    List<ProductionPotOutput> findByFactoryIdAndTargetTypeAndTargetId(String factoryId, String targetType, String targetId);
}
