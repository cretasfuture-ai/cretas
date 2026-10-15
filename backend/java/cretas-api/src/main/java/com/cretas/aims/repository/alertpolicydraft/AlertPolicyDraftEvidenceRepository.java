package com.cretas.aims.repository.alertpolicydraft;

import com.cretas.aims.entity.alertpolicydraft.AlertPolicyDraftEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AlertPolicyDraftEvidenceRepository
        extends JpaRepository<AlertPolicyDraftEvidence, String> {
    List<AlertPolicyDraftEvidence> findByDraftIdAndFactoryIdOrderByAuthorityCode(
            String draftId, String factoryId);
}
