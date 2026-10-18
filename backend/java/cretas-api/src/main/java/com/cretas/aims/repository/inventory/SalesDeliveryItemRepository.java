package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

@Repository
public interface SalesDeliveryItemRepository extends JpaRepository<SalesDeliveryItem, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM SalesDeliveryItem i WHERE i.id = :id")
    Optional<SalesDeliveryItem> findByIdForUpdate(@Param("id") Long id);

    /** Locks every line of one delivery in deterministic id order before ship/cancel mutations. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM SalesDeliveryItem i WHERE i.deliveryRecordId = :deliveryRecordId ORDER BY i.id")
    List<SalesDeliveryItem> findByDeliveryRecordIdForUpdate(
            @Param("deliveryRecordId") String deliveryRecordId);

    /** Actual deduction quantities are in each batch's native unit; allocation intent is not cost truth. */
    @Query(value = """
        SELECT r.delivery_date, e.quantity_used, b.unit_cost,
               COALESCE(b.product_type_id,i.product_type_id), b.batch_number,
               COALESCE(i.product_name,b.product_name), r.delivery_number,
               i.id, i.delivered_quantity, i.unit, b.unit, p.grams_per_unit,
               i.packaging_unit,i.packaging_base_unit,i.packaging_factor,
               b.packaging_unit,b.packaging_base_unit,b.packaging_factor,e.id,
               (SELECT COUNT(*) FROM sales_delivery_item_batch_allocations a
                WHERE a.factory_id=r.factory_id AND a.delivery_item_id=CAST(i.id AS varchar) AND a.deleted_at IS NULL),e.unit
        FROM sales_delivery_items i JOIN sales_delivery_records r ON r.id=i.delivery_record_id
        LEFT JOIN batch_lineage_edges e ON e.factory_id=r.factory_id AND e.target_id=r.id
            AND e.target_type='SHIPMENT_RECORD' AND e.source_type='FINISHED_BATCH'
            AND e.edge_type='FINISHED_TO_SHIPMENT' AND e.deleted_at IS NULL
            AND jsonb_extract_path_text(e.meta,'shipmentCarrier')='SALES_DELIVERY_RECORD'
            AND jsonb_extract_path_text(e.meta,'origin')='SALES_DELIVERY_SHIPMENT'
            AND jsonb_extract_path_text(e.meta,'deliveryItemId')=CAST(i.id AS varchar)
        LEFT JOIN finished_goods_batches b ON b.id=e.source_id
            AND b.factory_id=r.factory_id AND b.product_type_id=i.product_type_id AND b.deleted_at IS NULL
        LEFT JOIN product_types p ON p.id=i.product_type_id AND p.factory_id=r.factory_id AND p.deleted_at IS NULL
        WHERE r.factory_id=:factoryId AND r.delivery_date BETWEEN :startDate AND :endDate
          AND COALESCE(r.record_role,'LEGACY')<>'MASTER'
          AND r.status IN ('SHIPPED','DELIVERED') AND i.deleted_at IS NULL AND r.deleted_at IS NULL
        ORDER BY r.delivery_date,r.id,i.id,e.id
        """, nativeQuery = true)
    List<Object[]> findShippedCostSources(@Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /** Columns: [date, nativeQty, nativeUnitCost, product, batch, name, delivery, nativeUnit]. */
    default List<Object[]> findShippedMovementsForLedger(String factoryId, LocalDate startDate, LocalDate endDate) {
        return ShipmentCostRows.movements(findShippedCostSources(factoryId, startDate, endDate));
    }

    default Object[] aggregateShippedCogs(String factoryId, LocalDate startDate, LocalDate endDate) {
        return ShipmentCostRows.aggregate(findShippedMovementsForLedger(factoryId, startDate, endDate), false);
    }

    default Object[] aggregateMissingCost(String factoryId, LocalDate startDate, LocalDate endDate) {
        return ShipmentCostRows.aggregate(findShippedMovementsForLedger(factoryId, startDate, endDate), true);
    }
}
