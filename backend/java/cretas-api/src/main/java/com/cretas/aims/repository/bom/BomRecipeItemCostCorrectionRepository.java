package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomRecipeItemCostCorrection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BomRecipeItemCostCorrectionRepository
        extends JpaRepository<BomRecipeItemCostCorrection, String> {
    List<BomRecipeItemCostCorrection> findByBomRecipeItemIdOrderByCorrectedAtDesc(Long bomRecipeItemId);
}
