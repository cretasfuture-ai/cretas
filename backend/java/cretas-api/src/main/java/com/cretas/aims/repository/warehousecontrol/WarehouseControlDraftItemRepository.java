package com.cretas.aims.repository.warehousecontrol;

import com.cretas.aims.entity.warehousecontrol.WarehouseControlDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WarehouseControlDraftItemRepository extends JpaRepository<WarehouseControlDraftItem, String> {
    List<WarehouseControlDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId, String factoryId);
}
