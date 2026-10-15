package com.cretas.aims.repository.workflow;

import com.cretas.aims.entity.workflow.FactoryWorkflowDraftMaterializationLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactoryWorkflowDraftMaterializationLedgerRepository
        extends JpaRepository<FactoryWorkflowDraftMaterializationLedger, String> {

    Optional<FactoryWorkflowDraftMaterializationLedger> findByFactoryIdAndIdempotencyKey(
            String factoryId,
            String idempotencyKey);

    /** Completed materializations of one product, newest first (the Workflow entry's written listing). */
    List<FactoryWorkflowDraftMaterializationLedger> findByFactoryIdAndProductTypeIdAndCompletionStateOrderByCreatedAtDesc(
            String factoryId,
            String productTypeId,
            String completionState);
}
