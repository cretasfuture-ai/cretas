package com.cretas.aims.repository.workprocess;

import com.cretas.aims.entity.workprocess.WorkProcessDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkProcessDraftRepository extends JpaRepository<WorkProcessDraft, String> {

    Optional<WorkProcessDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);

    Optional<WorkProcessDraft> findByIdAndFactoryId(String id, String factoryId);
}
