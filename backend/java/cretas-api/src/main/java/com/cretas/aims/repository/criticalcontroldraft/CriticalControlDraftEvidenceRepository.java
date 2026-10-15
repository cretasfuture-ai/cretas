package com.cretas.aims.repository.criticalcontroldraft;

import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraftEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CriticalControlDraftEvidenceRepository extends JpaRepository<CriticalControlDraftEvidence, String> {
    List<CriticalControlDraftEvidence> findByDraftIdAndFactoryIdOrderByItemIdAscAuthorityCodeAsc(
            String draftId, String factoryId);
}
