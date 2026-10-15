package com.cretas.aims.repository.formschemadraft;

import com.cretas.aims.entity.formschemadraft.FormSchemaDraftEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FormSchemaDraftEvidenceRepository
        extends JpaRepository<FormSchemaDraftEvidence, String> {
    List<FormSchemaDraftEvidence> findByDraftIdAndFactoryIdOrderByAuthorityCode(
            String draftId, String factoryId);
}
