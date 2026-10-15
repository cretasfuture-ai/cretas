package com.cretas.aims.repository.warehousecontrol;

import com.cretas.aims.entity.warehousecontrol.WarehouseControlDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WarehouseControlDraftRepository extends JpaRepository<WarehouseControlDraft, String> {
    Optional<WarehouseControlDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);

    Optional<WarehouseControlDraft> findByIdAndFactoryId(String id, String factoryId);
}
