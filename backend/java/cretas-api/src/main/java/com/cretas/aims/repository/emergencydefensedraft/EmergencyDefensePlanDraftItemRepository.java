package com.cretas.aims.repository.emergencydefensedraft;

import com.cretas.aims.entity.emergencydefensedraft.EmergencyDefensePlanDraftItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmergencyDefensePlanDraftItemRepository
        extends JpaRepository<EmergencyDefensePlanDraftItem, String> {
    List<EmergencyDefensePlanDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
