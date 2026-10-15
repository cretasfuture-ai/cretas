package com.cretas.aims.repository.qualityreleasedraft;

import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QualityReleaseTemplateDraftItemRepository
        extends JpaRepository<QualityReleaseTemplateDraftItem, String> {
    List<QualityReleaseTemplateDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
