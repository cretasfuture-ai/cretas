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
        /**
         * 内部批次主键。<b>公开（消费者）接口恒为 null</b> —— 它是数据库主键，
         * 其中 PRODUCTION_BATCH 是顺序自增 BIGSERIAL，泄漏出去可被枚举、可推断生产节奏。
         * ⛔ 前端不要拿它当展示文案的兜底，用 batchNumber。
         */
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
        /**
         * 源头个体（这块肉来自哪头牛/鸭/猪）。
         *
         * <p>只有 {@code MATERIAL_BATCH} 节点、且该批次挂了 {@code source_individual_id} 时才有；
         * 其余情况为 {@code null}。⚠️ {@code null} 表示「这批原料没登记个体」，
         * ⛔ 不要在前端兜底成「未知个体」—— 绝大多数原料（调料、包材）本来就没有个体。
         */
        private SourceIndividualBrief sourceIndividual;
    }

    /**
     * 血缘节点上的源头个体摘要 —— <b>脱敏口径由后端决定，前端只负责显示</b>。
     *
     * <h3>公开（消费者）接口给什么</h3>
     * 允许：{@code species} / {@code breed} / {@code originFarm} / {@code dnaVerificationStatus}
     * / {@code tagNumber}（<b>只保留末 4 位，前面打星</b>）。
     *
     * <h3>⛔ 公开接口一个字都不许出的</h3>
     * {@code individualCode}（内部编码）、{@code externalGeneBankId}（外部基因库 ID）、
     * {@code dnaSampleCode}（DNA 样本号）、{@code sireIndividualId} / {@code damIndividualId}
     * （父母系谱）、<b>完整耳标号</b>。
     *
     * <p>⚠️ 这个类<b>刻意不含</b> geneBank / dnaSampleCode / sire / dam 这几个字段 ——
     * 「字段根本不在 DTO 上」比「运行时记得置 null」强：后者漏一次就泄漏一次，
     * 而且漏了没有任何测试会红。内部要看全字段走
     * {@code /api/mobile/{factoryId}/source-individuals/{id}}（带鉴权）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceIndividualBrief {
        /** 内部个体编码；公开接口恒为 null。 */
        private String individualCode;
        /**
         * 耳标号。公开接口只给末 4 位、前面用 {@code *} 补齐（如 {@code ****7788}）；
         * 内部接口给完整值。
         */
        private String tagNumber;
        /** 物种（牛/鸭/猪…）。⚠️ 是字段值，不是模型名 —— 本功能对所有物种通用。 */
        private String species;
        private String breed;
        private String originFarm;
        /** UNSAMPLED / SAMPLED / MATCHED / MISMATCH。 */
        private String dnaVerificationStatus;
        /**
         * 上面的 {@code tagNumber} 是不是被遮过。
         *
         * <p>没有它，前端分不清「****7788」是脱敏结果还是耳标本身就长这样，
         * 也就无法决定能不能拿它去检索。
         */
        private Boolean tagNumberMasked;
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
        /**
         * 上游链上<b>去重后</b>的源头个体数（「本批来自 N 头牛」里的那个 N）。
         *
         * <p>🔴 这个数<b>必须由后端算</b>：公开接口上 {@code individualCode} 是 null、
         * {@code tagNumber} 被遮成末 4 位，前端拿脱敏值去重会把「末 4 位相同的两头牛」
         * 数成一头 —— 那是个只会<b>少报</b>的错，而「来自几头牛」少报一头在溯源语境里
         * 就是一句假话。后端按真实个体 id 去重，前端只负责显示。
         *
         * <p>没有任何带个体的原料时为 0（不是 null）—— 0 是一个确定的读数。
         */
        private Integer sourceIndividualCount;
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
