package com.cretas.aims.repository.deviationcapadraft;

import com.cretas.aims.entity.deviationcapadraft.DeviationCapaTemplateDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeviationCapaTemplateDraftRepository
        extends JpaRepository<DeviationCapaTemplateDraft, String> {
    Optional<DeviationCapaTemplateDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<DeviationCapaTemplateDraft> findByIdAndFactoryId(String id, String factoryId);
}
