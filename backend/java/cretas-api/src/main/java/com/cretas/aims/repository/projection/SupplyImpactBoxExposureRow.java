package com.cretas.aims.repository.projection;

import java.math.BigDecimal;

/** One row of the factory-scoped FG plus qualified shipment-edge projection. */
public interface SupplyImpactBoxExposureRow {
    String getFactoryId();
    String getFinishedGoodsBatchId();
    String getBatchNumber();
    BigDecimal getProducedQuantity();
    BigDecimal getShippedQuantity();
    BigDecimal getReservedQuantity();
    String getFinishedUnit();
    String getFinishedPackagingUnit();
    String getFinishedPackagingBaseUnit();
    BigDecimal getFinishedPackagingFactor();
    String getFinishedStatus();
    String getEdgeId();
    String getShipmentId();
    BigDecimal getEdgeQuantity();
    String getEdgeUnit();
    String getDeliveryRecordId();
    String getDeliveryItemId();
    BigDecimal getDeliveredQuantity();
    String getDeliveryUnit();
    String getDeliveryPackagingUnit();
    String getDeliveryPackagingBaseUnit();
    BigDecimal getDeliveryPackagingFactor();
    String getCustomerId();
    String getCustomerName();
}
