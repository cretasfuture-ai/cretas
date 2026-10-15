package com.cretas.aims.repository.qualityreleasedraft;

import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraftEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QualityReleaseTemplateDraftEvidenceRepository
        extends JpaRepository<QualityReleaseTemplateDraftEvidence, String> {
    List<QualityReleaseTemplateDraftEvidence> findByDraftIdAndFactoryIdOrderByItemIdAscAuthorityCodeAsc(
            String draftId, String factoryId);
}
