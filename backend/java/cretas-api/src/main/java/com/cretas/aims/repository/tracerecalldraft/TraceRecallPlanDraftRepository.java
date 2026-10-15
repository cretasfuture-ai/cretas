package com.cretas.aims.repository.tracerecalldraft;

import com.cretas.aims.entity.tracerecalldraft.TraceRecallPlanDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TraceRecallPlanDraftRepository extends JpaRepository<TraceRecallPlanDraft, String> {
    Optional<TraceRecallPlanDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<TraceRecallPlanDraft> findByIdAndFactoryId(String id, String factoryId);
}
