package com.cretas.aims.repository.bomdraft;

import com.cretas.aims.entity.bomdraft.BomRecipeDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BomRecipeDraftItemRepository extends JpaRepository<BomRecipeDraftItem, String> {
    List<BomRecipeDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId, String factoryId);
}
