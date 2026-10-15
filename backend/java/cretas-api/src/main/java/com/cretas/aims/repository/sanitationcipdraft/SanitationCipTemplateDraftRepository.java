package com.cretas.aims.repository.sanitationcipdraft;

import com.cretas.aims.entity.sanitationcipdraft.SanitationCipTemplateDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SanitationCipTemplateDraftRepository
        extends JpaRepository<SanitationCipTemplateDraft, String> {
    Optional<SanitationCipTemplateDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<SanitationCipTemplateDraft> findByIdAndFactoryId(String id, String factoryId);
}
