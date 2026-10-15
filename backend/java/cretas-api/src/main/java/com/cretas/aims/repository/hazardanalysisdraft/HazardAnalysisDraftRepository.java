package com.cretas.aims.repository.hazardanalysisdraft;

import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface HazardAnalysisDraftRepository extends JpaRepository<HazardAnalysisDraft, String> {
    Optional<HazardAnalysisDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);
    Optional<HazardAnalysisDraft> findByIdAndFactoryId(String id, String factoryId);
}
