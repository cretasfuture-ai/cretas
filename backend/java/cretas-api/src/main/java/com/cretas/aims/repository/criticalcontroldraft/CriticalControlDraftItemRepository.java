package com.cretas.aims.repository.criticalcontroldraft;

import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CriticalControlDraftItemRepository extends JpaRepository<CriticalControlDraftItem, String> {
    List<CriticalControlDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId, String factoryId);
}
