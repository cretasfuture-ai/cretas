package com.cretas.aims.repository.pot;

import com.cretas.aims.entity.pot.ProductionPotCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProductionPotCorrectionRepository extends JpaRepository<ProductionPotCorrection, Long> {

    List<ProductionPotCorrection> findByFactoryIdAndPotIdInOrderByCorrectedAtAscIdAsc(String factoryId, Collection<Long> potIds);
}
