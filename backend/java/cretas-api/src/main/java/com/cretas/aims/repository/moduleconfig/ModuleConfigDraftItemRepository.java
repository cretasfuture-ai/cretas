package com.cretas.aims.repository.moduleconfig;

import com.cretas.aims.entity.moduleconfig.ModuleConfigDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModuleConfigDraftItemRepository extends JpaRepository<ModuleConfigDraftItem, String> {
    List<ModuleConfigDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId, String factoryId);
}
