package com.cretas.aims.dto.traceability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 溯源数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public class TraceabilityDTO {

    /**
     * 完整溯源链路响应
     *
     * <p>{@code lineage} 是<b>加法式</b>新增（2026-09-14）：既有字段一个都没动，
     * 老客户端读到多出来的一个键，行为不变。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FullTraceResponse {
        private ProductionInfo production;
        private List<MaterialInfo> materials;
        private List<ProcessingStageInfo> processingStages;
        private List<QualityInfo> qualityInspections;
        private List<ShipmentInfo> shipments;
        private String traceCode;
        private LocalDateTime queryTime;
        /** 多跳血缘（来自 batch_lineage_closure + edges）；引擎不可用时为 null，⛔ 不是空对象。 */
        private LineageSection lineage;
    }

    // ==================== 多跳血缘（2026-09-14 新增，加法式） ====================

    /**
     * 血缘图上的一个节点。
     *
     * <p><b>{@code resolved} 存在的理由</b>：批次实体可能已被软删/清理，而血缘边是 forensic 的
     * （永不删）。查不到实体时 {@code resolved=false} 且 {@code batchNumber}/{@code name} 留 null，
     * ⛔ 不编一个「未知批次」之类的假名字 —— 那会让「这个批次被删了」和「这个批次叫未知」
     * 在前端长得一模一样。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageNode {
        /** MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH / SHIPMENT_RECORD */
        private String nodeType;
        private String nodeId;
        private String batchNumber;
        /** 物料名 / 产品名 / 客户名，按 nodeType 取。查不到实体时为 null。 */
        private String name;
        /** 仅原料节点有；公开（消费者）接口一律 null —— 脱敏口径沿用 getPublicTrace。 */
        private String supplierName;
        /** 该节点与图上相邻节点之间那条边的数量；深层节点或多条边单位不一致时为 null（诚实 null）。 */
        private Double quantity;
        private String unit;
        private LocalDateTime eventTime;
        /** 批次实体是否查得到。false ⇒ 上面的名称字段全为 null，不是「没名字」而是「查不到」。 */
        private Boolean resolved;
    }

    /** 按跳数分组的一层。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageDepthGroup {
        /** 1 = 直接上/下游，2 = 隔一跳，以此类推（0 是自引用，不出现在这里）。 */
        private Integer depth;
        private List<LineageNode> nodes;
    }

    /**
     * 一个批次的血缘视图。
     *
     * <p>{@code descendants} 只在内部完整溯源里给；公开接口只给 {@code ancestors}
     * （消费者该看到「这块肉从哪来」，不该看到「同一批还发给了哪些客户」）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageSection {
        private String rootType;
        private String rootId;
        /** 上游，按 depth 从近到远。空列表 = 确实没有上游血缘记录（不是 null）。 */
        private List<LineageDepthGroup> ancestors;
        /** 下游；公开接口为 null（不是空列表 —— null 表示「本接口不提供」）。 */
        private List<LineageDepthGroup> descendants;
        /** 本次查询的最大跳数上限。 */
        private Integer maxDepth;
        /** 祖先 + 后代的节点总数，供前端判断「有没有东西可展示」。 */
        private Integer nodeCount;
    }

    /**
     * 召回影响面 —— 从一个批次往下游追，能波及到哪些生产批次 / 成品批次 / 出货单。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImpactResponse {
        private String rootType;
        private String rootId;
        private List<LineageNode> productionBatches;
        private List<LineageNode> finishedBatches;
        /** 出货节点；{@code name} 是客户名（内部接口才给）。 */
        private List<LineageNode> shipments;
        private Integer productionBatchCount;
        private Integer finishedBatchCount;
        private Integer shipmentCount;
        private Integer maxDepth;
        private LocalDateTime queryTime;
    }

    /**
     * 生产批次信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductionInfo {
        private String batchNumber;
        private String productName;
        private String productType;
        private LocalDate productionDate;
        private LocalDateTime completionTime;
        private String supervisorName;
        private String equipmentName;
        private Double quantity;
        private String unit;
        private String qualityStatus;
        private String factoryName;
        private String factoryId;
    }

    /**
     * 原材料溯源信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialInfo {
        private String batchNumber;
        private String materialName;
        private String materialType;
        private String supplierName;
        private String supplierCode;
        private LocalDate receiptDate;
        private LocalDate expireDate;
        private Double quantity;
        private String unit;
        private String storageLocation;
        private String status;
    }

    /**
     * 加工环节溯源信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingStageInfo {
        private String stageType;
        private String stageName;
        private Integer stageOrder;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer durationMinutes;
        private Double inputWeight;
        private Double outputWeight;
        private Double lossRate;
        private Double passRate;
        private Double temperature;
        private String equipmentName;
        private String operatorName;
    }

    /**
     * 质检溯源信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityInfo {
        private String inspectionId;
        private LocalDateTime inspectionDate;
        private String inspectorName;
        private String result;
        private Double passRate;
        private String conclusion;
        private String remarks;
    }

    /**
     * 出货溯源信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShipmentInfo {
        private String shipmentNumber;
        private LocalDate shipmentDate;
        private String customerName;
        private String logisticsCompany;
        private String trackingNumber;
        private String status;
        private Double quantity;
        private String unit;
    }

    /**
     * 公开溯源信息（消费者扫码查询，脱敏）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublicTraceResponse {
        private String productName;
        private String batchNumber;
        private LocalDate productionDate;
        private String factoryName;
        private String qualityStatus;
        private String certificationInfo;
        private List<PublicMaterialInfo> materials;
        private PublicQualityInfo qualityInspection;
        private String traceCode;
        private LocalDateTime queryTime;
        private Boolean isValid;
        private String message;
        /**
         * 多跳来源链（脱敏）：只有 {@code ancestors}，且节点上的 {@code supplierName} 恒为 null。
         * 引擎不可用时为 null。
         */
        private LineageSection lineage;
    }

    /**
     * 公开原材料信息（脱敏）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublicMaterialInfo {
        private String materialType;
        private String origin;
        private LocalDate receiptDate;
    }

    /**
     * 公开质检信息（脱敏）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublicQualityInfo {
        private LocalDateTime inspectionDate;
        private String result;
        private Double passRate;
    }

    /**
     * 基础溯源响应（批次级别）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchTraceResponse {
        private ProductionInfo production;
        private Integer materialCount;
        private Integer inspectionCount;
        private Integer shipmentCount;
        private String qualityStatus;
        private LocalDateTime lastUpdateTime;
    }

    /**
     * 溯源列表项（列表查询用）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceListItem {
        private String batchNumber;
        private String productName;
        private LocalDateTime productionDate;
        private String status;
        private String qualityStatus;
        private Integer completedQuantity;
    }
}
