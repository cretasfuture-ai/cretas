package com.cretas.aims.repository.sanitationcipdraft;

import com.cretas.aims.entity.sanitationcipdraft.SanitationCipTemplateDraftItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SanitationCipTemplateDraftItemRepository
        extends JpaRepository<SanitationCipTemplateDraftItem, String> {
    List<SanitationCipTemplateDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
