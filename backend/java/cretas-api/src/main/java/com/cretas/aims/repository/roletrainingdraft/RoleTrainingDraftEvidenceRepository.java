package com.cretas.aims.repository.roletrainingdraft;

import com.cretas.aims.entity.roletrainingdraft.RoleTrainingDraftEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleTrainingDraftEvidenceRepository
        extends JpaRepository<RoleTrainingDraftEvidence, String> {
    List<RoleTrainingDraftEvidence> findByDraftIdAndFactoryIdOrderByAuthorityCode(
            String draftId, String factoryId);
}
