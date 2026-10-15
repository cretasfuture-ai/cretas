package com.cretas.aims.repository.roletrainingdraft;

import com.cretas.aims.entity.roletrainingdraft.RoleTrainingDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleTrainingDraftRepository extends JpaRepository<RoleTrainingDraft, String> {
    Optional<RoleTrainingDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<RoleTrainingDraft> findByIdAndFactoryId(String id, String factoryId);
}
