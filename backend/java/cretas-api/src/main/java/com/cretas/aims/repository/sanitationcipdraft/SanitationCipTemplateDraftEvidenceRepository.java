package com.cretas.aims.repository.sanitationcipdraft;

import com.cretas.aims.entity.sanitationcipdraft.SanitationCipTemplateDraftEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SanitationCipTemplateDraftEvidenceRepository
        extends JpaRepository<SanitationCipTemplateDraftEvidence, String> {
    List<SanitationCipTemplateDraftEvidence> findByDraftIdAndFactoryIdOrderByAuthorityCode(
            String draftId, String factoryId);
}
