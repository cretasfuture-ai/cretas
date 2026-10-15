package com.cretas.aims.repository.allergenlabeldraft;

import com.cretas.aims.entity.allergenlabeldraft.AllergenLabelDraftEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AllergenLabelDraftEvidenceRepository
        extends JpaRepository<AllergenLabelDraftEvidence, String> {
    List<AllergenLabelDraftEvidence> findByDraftIdAndFactoryIdOrderByAuthorityCode(
            String draftId, String factoryId);
}
