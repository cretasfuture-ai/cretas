package com.cretas.aims.repository.packagingdraft;

import com.cretas.aims.entity.packagingdraft.PackagingDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PackagingDraftRepository extends JpaRepository<PackagingDraft, String> {
    Optional<PackagingDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);
    Optional<PackagingDraft> findByIdAndFactoryId(String id, String factoryId);
}
