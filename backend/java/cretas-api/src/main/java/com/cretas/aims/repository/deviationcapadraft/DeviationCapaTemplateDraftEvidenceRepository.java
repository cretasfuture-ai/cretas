package com.cretas.aims.repository.deviationcapadraft;

import com.cretas.aims.entity.deviationcapadraft.DeviationCapaTemplateDraftEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviationCapaTemplateDraftEvidenceRepository
        extends JpaRepository<DeviationCapaTemplateDraftEvidence, String> {
    List<DeviationCapaTemplateDraftEvidence> findByDraftIdAndFactoryIdOrderByRuleIdAscAuthorityCodeAsc(
            String draftId, String factoryId);
}
