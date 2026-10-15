package com.cretas.aims.repository.allergenlabeldraft;

import com.cretas.aims.entity.allergenlabeldraft.AllergenLabelDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AllergenLabelDraftRepository extends JpaRepository<AllergenLabelDraft, String> {
    Optional<AllergenLabelDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<AllergenLabelDraft> findByIdAndFactoryId(String id, String factoryId);
}
