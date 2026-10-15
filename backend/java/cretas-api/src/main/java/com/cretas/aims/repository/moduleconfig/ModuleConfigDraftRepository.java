package com.cretas.aims.repository.moduleconfig;

import com.cretas.aims.entity.moduleconfig.ModuleConfigDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ModuleConfigDraftRepository extends JpaRepository<ModuleConfigDraft, String> {
    Optional<ModuleConfigDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);

    Optional<ModuleConfigDraft> findByIdAndFactoryId(String id, String factoryId);
}
