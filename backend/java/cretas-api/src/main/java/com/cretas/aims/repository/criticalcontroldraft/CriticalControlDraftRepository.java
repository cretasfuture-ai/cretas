package com.cretas.aims.repository.criticalcontroldraft;

import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CriticalControlDraftRepository extends JpaRepository<CriticalControlDraft, String> {
    Optional<CriticalControlDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);
    Optional<CriticalControlDraft> findByIdAndFactoryId(String id, String factoryId);
}
