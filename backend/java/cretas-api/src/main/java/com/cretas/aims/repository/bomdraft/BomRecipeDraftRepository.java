package com.cretas.aims.repository.bomdraft;

import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BomRecipeDraftRepository extends JpaRepository<BomRecipeDraft, String> {
    Optional<BomRecipeDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);
    Optional<BomRecipeDraft> findByIdAndFactoryId(String id, String factoryId);
}
