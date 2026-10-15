package com.cretas.aims.repository.emergencydefensedraft;

import com.cretas.aims.entity.emergencydefensedraft.EmergencyDefensePlanDraftEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmergencyDefensePlanDraftEvidenceRepository
        extends JpaRepository<EmergencyDefensePlanDraftEvidence, String> {
    List<EmergencyDefensePlanDraftEvidence> findByDraftIdAndFactoryIdOrderByAuthorityCode(
            String draftId, String factoryId);
}
