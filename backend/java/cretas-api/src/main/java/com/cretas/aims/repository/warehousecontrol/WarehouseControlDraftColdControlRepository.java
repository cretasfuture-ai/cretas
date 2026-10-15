package com.cretas.aims.repository.warehousecontrol;

import com.cretas.aims.entity.warehousecontrol.WarehouseControlDraftColdControl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WarehouseControlDraftColdControlRepository
        extends JpaRepository<WarehouseControlDraftColdControl, String> {
    List<WarehouseControlDraftColdControl> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
