package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialBatchCostCorrection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialBatchCostCorrectionRepository
        extends JpaRepository<MaterialBatchCostCorrection, String> {
    List<MaterialBatchCostCorrection> findByMaterialBatchIdOrderByCorrectedAtDesc(String materialBatchId);
}
