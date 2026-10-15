package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialTypeDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MaterialTypeDraftRepository extends JpaRepository<MaterialTypeDraft, String> {

    Optional<MaterialTypeDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);

    Optional<MaterialTypeDraft> findByIdAndFactoryId(String id, String factoryId);
}
