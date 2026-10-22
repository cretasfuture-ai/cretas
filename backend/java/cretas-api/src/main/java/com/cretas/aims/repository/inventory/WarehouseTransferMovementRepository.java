package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.WarehouseTransferMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public interface WarehouseTransferMovementRepository extends JpaRepository<WarehouseTransferMovement, Long> {
    List<WarehouseTransferMovement> findByTransferIdOrderByIdAsc(String transferId);
    List<WarehouseTransferMovement> findByTransferIdAndKindOrderByIdAsc(String transferId, WarehouseTransferMovement.Kind kind);
    List<WarehouseTransferMovement> findByRequestKey(String requestKey);
    Optional<WarehouseTransferMovement> findByRequestKeyAndTransferItemId(String requestKey, Long transferItemId);
}
