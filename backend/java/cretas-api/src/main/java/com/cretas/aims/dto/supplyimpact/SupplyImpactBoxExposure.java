package com.cretas.aims.dto.supplyimpact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Packaging-snapshot based box-equivalent exposure for one quality impact read.
 * This is an explanation of recorded quantities, not a claim about physically
 * sealed cartons.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplyImpactBoxExposure {

    public static final String SCOPE = "DISCOVERED_LINEAGE";
    public static final String INVENTORY_BASIS = "PHYSICAL_ON_HAND_INCLUDING_RESERVED";
    public static final String QUANTITY_MEANING = "PACKAGING_SNAPSHOT_EQUIVALENT";

    private String scope;
    private String inventoryBasis;
    private String quantityMeaning;
    private BoxTotals stock;
    private BoxTotals shipped;
    @Builder.Default
    private List<Source> stockSources = new ArrayList<>();
    @Builder.Default
    private List<Source> shipmentSources = new ArrayList<>();
    @Builder.Default
    private List<CustomerTotals> customers = new ArrayList<>();
    @Builder.Default
    private List<SupplyImpactDTO.Gap> gaps = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BoxTotals {
        private boolean complete;
        private String totalBoxQuantity;
        private String knownBoxSubtotal;
        private int knownSourceCount;
        private int unknownSourceCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private String sourceId;
        private String finishedGoodsBatchId;
        private String batchNumber;
        private String sourceQuantity;
        private String sourceUnit;
        private String packagingUnit;
        private String packagingBaseUnit;
        private String packagingFactor;
        private String boxQuantity;
        private String reservedQuantity;
        private String deliveryRecordId;
        private String deliveryItemId;
        private String customerId;
        private String customerName;
        @Builder.Default
        private List<String> gaps = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomerTotals {
        private String customerId;
        private String customerName;
        private BoxTotals totals;
    }
}
