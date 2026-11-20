package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialBatchRelease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialBatchReleaseRepository extends JpaRepository<MaterialBatchRelease, String> {

    List<MaterialBatchRelease> findByFactoryIdAndMaterialBatchIdOrderByReleasedAtDesc(String factoryId,
                                                                                      String materialBatchId);
}
