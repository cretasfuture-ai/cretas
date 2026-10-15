package com.cretas.aims.repository.hazardanalysisdraft;

import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraftEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HazardAnalysisDraftEvidenceRepository extends JpaRepository<HazardAnalysisDraftEvidence, String> {
    List<HazardAnalysisDraftEvidence> findByDraftIdAndFactoryIdOrderByItemIdAscAuthorityCodeAsc(
            String draftId, String factoryId);
}
