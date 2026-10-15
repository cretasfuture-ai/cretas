package com.cretas.aims.repository.emergencydefensedraft;

import com.cretas.aims.entity.emergencydefensedraft.EmergencyDefensePlanDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmergencyDefensePlanDraftRepository
        extends JpaRepository<EmergencyDefensePlanDraft, String> {
    Optional<EmergencyDefensePlanDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<EmergencyDefensePlanDraft> findByIdAndFactoryId(String id, String factoryId);
}
