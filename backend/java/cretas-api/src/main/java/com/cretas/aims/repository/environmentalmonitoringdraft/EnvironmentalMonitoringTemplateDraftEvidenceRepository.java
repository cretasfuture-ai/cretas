package com.cretas.aims.repository.environmentalmonitoringdraft;

import com.cretas.aims.entity.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraftEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EnvironmentalMonitoringTemplateDraftEvidenceRepository
        extends JpaRepository<EnvironmentalMonitoringTemplateDraftEvidence, String> {
    List<EnvironmentalMonitoringTemplateDraftEvidence>
            findByDraftIdAndFactoryIdOrderByAuthorityCode(String draftId, String factoryId);
}
