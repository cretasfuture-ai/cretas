package com.cretas.aims.repository.hygieniczone;

import com.cretas.aims.entity.hygieniczone.HygienicZoneDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HygienicZoneDraftRepository extends JpaRepository<HygienicZoneDraft, String> {
    Optional<HygienicZoneDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);
    Optional<HygienicZoneDraft> findByIdAndFactoryId(String id, String factoryId);
}
