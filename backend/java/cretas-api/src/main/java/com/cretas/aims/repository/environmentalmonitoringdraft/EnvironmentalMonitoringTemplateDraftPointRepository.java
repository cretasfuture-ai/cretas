package com.cretas.aims.repository.environmentalmonitoringdraft;

import com.cretas.aims.entity.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraftPoint;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EnvironmentalMonitoringTemplateDraftPointRepository
        extends JpaRepository<EnvironmentalMonitoringTemplateDraftPoint, String> {
    List<EnvironmentalMonitoringTemplateDraftPoint> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
