package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.WarehouseTransferMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

@Repository
public interface WarehouseTransferMovementRepository extends JpaRepository<WarehouseTransferMovement, Long> {
    List<WarehouseTransferMovement> findByTransferIdOrderByIdAsc(String transferId);
    List<WarehouseTransferMovement> findByTransferIdAndKindOrderByIdAsc(String transferId, WarehouseTransferMovement.Kind kind);
    List<WarehouseTransferMovement> findByRequestKey(String requestKey);
    List<WarehouseTransferMovement> findByRequestKeyAndFactoryIdAndTransferId(String requestKey, String factoryId,
                                                                                 String transferId);
    @Query(value = "select * from warehouse_transfer_movements where request_key=:requestKey and factory_id=:factoryId and transfer_id=:transferId order by id", nativeQuery = true)
    List<WarehouseTransferMovement> findRequestHistoryIncludingDeleted(@Param("requestKey") String requestKey,
                                                                         @Param("factoryId") String factoryId,
                                                                         @Param("transferId") String transferId);
    @Query(value = "select * from warehouse_transfer_movements where transfer_id=:transferId order by id", nativeQuery = true)
    List<WarehouseTransferMovement> findTransferHistoryIncludingDeleted(@Param("transferId") String transferId);
    Optional<WarehouseTransferMovement> findByRequestKeyAndTransferItemId(String requestKey, Long transferItemId);
}
