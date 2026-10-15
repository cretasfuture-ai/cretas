package com.cretas.aims.repository.alertpolicydraft;

import com.cretas.aims.entity.alertpolicydraft.AlertPolicyDraftItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AlertPolicyDraftItemRepository extends JpaRepository<AlertPolicyDraftItem, String> {
    List<AlertPolicyDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
