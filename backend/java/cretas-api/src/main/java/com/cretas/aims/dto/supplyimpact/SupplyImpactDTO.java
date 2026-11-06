package com.cretas.aims.dto.supplyimpact;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 供应商 / 原料品种出发的影响分析 (断供 / 质量 / 涨价) 的返回体。
 * 所有"查不到 / 没填 / 算不出"都进 {@code gaps} 或字段上的 reason, 不猜、不补 0。
 */
public final class SupplyImpactDTO {

    private SupplyImpactDTO() {
    }

    @Data
    @Builder
    public static class Subject {
        /** SUPPLIER / MATERIAL */
        private String kind;
        private String id;
        private String name;
    }

    @Data
    @Builder
    public static class MaterialRef {
        private String materialTypeId;
        private String name;
        private String unit;
        /** 其它在供的供应商名 (排除本次停供对象); 空表示没有登记替代供应商。 */
        private List<String> otherSuppliers;
    }

    @Data
    @Builder
    public static class RecipeDependency {
        private String recipeId;
        private String productTypeId;
        private String productName;
        private Integer version;
        private String materialTypeId;
        private String materialName;
        private Boolean optional;
        /** 同选择组里还能用的其它料 (名称)。 */
        private List<String> groupAlternatives;
        /** 配方登记的替代料。 */
        private List<String> registeredSubstitutes;
        /** 有任何替代路径 (选择组其它料 / 登记替代料 / 可选料)。 */
        private Boolean hasAlternative;
    }

    @Data
    @Builder
    public static class PlanImpact {
        private String planId;
        private String planNumber;
        private String productTypeId;
        private String productName;
        private String status;
        private LocalDate plannedDate;
        private BigDecimal plannedQuantity;
        private String plannedUnit;
        private String materialTypeId;
        private String materialName;
        /** 标准用量 (单位 = quantityUnit), 算不出为 null 并给 quantityReason。 */
        private BigDecimal requiredQuantity;
        private String quantityUnit;
        private String quantityReason;
        private List<String> sourceOrderIds;
    }

    @Data
    @Builder
    public static class StockCover {
        private String materialTypeId;
        private String materialName;
        private String unit;
        private BigDecimal onHand;
        private BigDecimal inTransitFromSubject;
        private BigDecimal inTransitFromOthers;
        private BigDecimal horizonDemand;
        /** 在库能撑几天; 窗口内没有可量化用量时为 null。 */
        private BigDecimal coverDays;
        private String note;
    }

    @Data
    @Builder
    public static class OrderImpact {
        private String orderId;
        private String orderNumber;
        private String customerId;
        private String customerName;
        private String status;
        private LocalDate requiredDeliveryDate;
        private String productName;
        private BigDecimal undeliveredQuantity;
        private String unit;
        /** PLAN(通过计划关联) / PRODUCT(同产品未发订单, 尚无计划) */
        private String linkedBy;
    }

    @Data
    @Builder
    public static class Gap {
        private String code;
        private String message;
    }

    @Data
    @Builder
    public static class DisruptionView {
        private Subject subject;
        private int horizonDays;
        private List<MaterialRef> materials;
        private List<RecipeDependency> recipes;
        private List<PlanImpact> plans;
        private List<StockCover> stock;
        private List<OrderImpact> orders;
        @Builder.Default
        private List<Gap> gaps = new ArrayList<>();
    }

    @Data
    @Builder
    public static class QualityBatchRef {
        private String batchId;
        private String batchNumber;
        private String materialName;
        private String supplierName;
        private LocalDate receiptDate;
        private BigDecimal receiptQuantity;
        private BigDecimal remainingQuantity;
        private String unit;
    }

    @Data
    @Builder
    public static class DownstreamNode {
        private String nodeType;
        private String nodeId;
        private String batchNumber;
        private String name;
        private Double quantity;
        private String unit;
    }

    @Data
    @Builder
    public static class CustomerExposure {
        private String customerName;
        private int shipmentCount;
        /** 按单位分别累计, 单位不同不硬加。 */
        private List<String> quantities;
    }

    @Data
    @Builder
    public static class QualityView {
        private Subject subject;
        private LocalDate from;
        private LocalDate to;
        private List<QualityBatchRef> sourceBatches;
        private List<DownstreamNode> productionBatches;
        private List<DownstreamNode> finishedBatches;
        private List<DownstreamNode> shipments;
        private List<CustomerExposure> customers;
        private List<OrderImpact> unshippedOrders;
        /**
         * Read-only packaging-snapshot exposure.  Null quantities are deliberate:
         * a lineage or snapshot gap must never be rendered as zero.
         */
        private SupplyImpactBoxExposure boxExposure;
        @Builder.Default
        private List<Gap> gaps = new ArrayList<>();
    }

    @Data
    @Builder
    public static class ProductCostChange {
        private String productTypeId;
        private String productName;
        private String planNumber;
        private BigDecimal materialQuantityPerPlanUnit;
        private String quantityUnit;
        private BigDecimal unitCostBefore;
        private BigDecimal unitCostDelta;
        private String plannedUnit;
        private BigDecimal planCostDelta;
        private String reason;
    }

    @Data
    @Builder
    public static class PriceView {
        private Subject subject;
        private BigDecimal percent;
        private BigDecimal currentUnitPrice;
        private List<RecipeDependency> recipes;
        private List<ProductCostChange> products;
        private List<OrderImpact> orders;
        @Builder.Default
        private List<Gap> gaps = new ArrayList<>();
    }
}
