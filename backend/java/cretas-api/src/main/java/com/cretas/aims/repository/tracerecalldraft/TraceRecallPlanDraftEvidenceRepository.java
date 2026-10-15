package com.cretas.aims.repository.tracerecalldraft;

import com.cretas.aims.entity.tracerecalldraft.TraceRecallPlanDraftEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TraceRecallPlanDraftEvidenceRepository
        extends JpaRepository<TraceRecallPlanDraftEvidence, String> {
    List<TraceRecallPlanDraftEvidence> findByDraftIdAndFactoryIdOrderByAuthorityCode(
            String draftId, String factoryId);
}
