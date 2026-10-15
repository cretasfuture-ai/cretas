package com.cretas.aims.repository.hazardanalysisdraft;

import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HazardAnalysisDraftItemRepository extends JpaRepository<HazardAnalysisDraftItem, String> {
    List<HazardAnalysisDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId, String factoryId);
}
