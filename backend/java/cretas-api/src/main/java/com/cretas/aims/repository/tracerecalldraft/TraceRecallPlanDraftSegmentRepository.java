package com.cretas.aims.repository.tracerecalldraft;

import com.cretas.aims.entity.tracerecalldraft.TraceRecallPlanDraftSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TraceRecallPlanDraftSegmentRepository
        extends JpaRepository<TraceRecallPlanDraftSegment, String> {
    List<TraceRecallPlanDraftSegment> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
