package com.cretas.aims.repository.packagingdraft;

import com.cretas.aims.entity.packagingdraft.PackagingDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PackagingDraftItemRepository extends JpaRepository<PackagingDraftItem, String> {
    List<PackagingDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId, String factoryId);
}
