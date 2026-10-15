package com.cretas.aims.repository.qualityreleasedraft;

import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QualityReleaseTemplateDraftRepository
        extends JpaRepository<QualityReleaseTemplateDraft, String> {
    Optional<QualityReleaseTemplateDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<QualityReleaseTemplateDraft> findByIdAndFactoryId(String id, String factoryId);
}
