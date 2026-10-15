package com.cretas.aims.repository.alertpolicydraft;

import com.cretas.aims.entity.alertpolicydraft.AlertPolicyDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AlertPolicyDraftRepository extends JpaRepository<AlertPolicyDraft, String> {
    Optional<AlertPolicyDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<AlertPolicyDraft> findByIdAndFactoryId(String id, String factoryId);
}
