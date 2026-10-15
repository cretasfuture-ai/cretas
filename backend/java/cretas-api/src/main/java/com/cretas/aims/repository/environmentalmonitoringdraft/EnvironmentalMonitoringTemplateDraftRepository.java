package com.cretas.aims.repository.environmentalmonitoringdraft;

import com.cretas.aims.entity.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EnvironmentalMonitoringTemplateDraftRepository
        extends JpaRepository<EnvironmentalMonitoringTemplateDraft, String> {
    Optional<EnvironmentalMonitoringTemplateDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<EnvironmentalMonitoringTemplateDraft> findByIdAndFactoryId(
            String id, String factoryId);
}
