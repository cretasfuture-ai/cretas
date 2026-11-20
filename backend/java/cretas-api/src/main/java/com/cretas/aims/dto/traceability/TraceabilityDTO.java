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
        /** Exact same-report-group input owner; quantities are shared group inputs, not this SKU's exclusive consumption. */
        private String sharedInputSourceBatchNumber;
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
     * {@code id}（档案主键）、{@code individualCode}（内部编码）、{@code externalGeneBankId}（外部基因库 ID）、
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
        /**
         * 个体档案主键 —— 内部溯源页拿它跳「个体详情」。<b>公开接口恒为 null</b>
         * （与 {@code LineageNode.nodeId} 同一条纪律：主键不出公开侧）。
         * 前端以它是否为 null 判断这一行能不能点，⛔ 不要拿 individualCode 兜底。
         */
        private String id;
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
        /**
         * 这次查询结果完不完整（2026-11-03，食品 DAG 缺口 02）。
         *
         * <p>内部视图必给；公开视图为 null（消费者页不出这一块）。
         */
        private LineageCompleteness completeness;
    }

    /**
     * 血缘查询结果的完整性 —— 「完整」还是「缺哪一段」。
     *
     * <p>{@code status=COMPLETE} 的判据：上游每个叶子都是外购/客供原料批（不是中间工序产出、
     * 不是生产批 / 成品批 / 出货单），没有节点查不到，没有节点卡在跳数上限上还有更上游的边。
     * 任何一条不满足就是 {@code INCOMPLETE}，并在 {@code gaps} 里逐个指出是哪个节点、缺什么。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageCompleteness {
        public static final String COMPLETE = "COMPLETE";
        public static final String INCOMPLETE = "INCOMPLETE";

        /** COMPLETE / INCOMPLETE */
        private String status;
        /** 一句话结论，直接给人看。 */
        private String summary;
        /** 是否在跳数上限处截断（上限处的节点还有更远的边没取回来）。 */
        private Boolean truncated;
        /** 截断发生的跳数（= 本次 maxDepth）；没截断为 null。 */
        private Integer truncatedAtDepth;
        /** 断链 / 查不到 / 截断的节点，逐个列出。完整时为空列表。 */
        private List<LineageGap> gaps;
        /**
         * 链上节点还有多少条边在补写台账里没结掉（PENDING / DEAD）。
         * 非 0 时缺口可能只是「还没补上」；null = 这次没量（台账不可用）。
         */
        private Long pendingEdgeWrites;
    }

    /** 一个缺口：哪个节点、缺什么。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageGap {
        /** 缺上游 */
        public static final String MISSING_UPSTREAM = "MISSING_UPSTREAM";
        /** 节点实体查不到（软删 / 不存在），判不了它的上游 */
        public static final String NODE_UNRESOLVED = "NODE_UNRESOLVED";
        /** 在跳数上限处还有更上游 / 更下游的边 */
        public static final String DEPTH_TRUNCATED = "DEPTH_TRUNCATED";

        private String kind;
        private String nodeType;
        private String nodeId;
        private String batchNumber;
        /** 离查询起点几跳（起点本身为 0）。 */
        private Integer depth;
        /** UPSTREAM / DOWNSTREAM */
        private String direction;
        private String reason;
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
        /**
         * S3-16 按锅收窄: 影响面里有锅次记录的生产批次, 具体是哪几锅、出到哪些批次。
         * 只在<b>能证明</b>时收窄 (见 PotTraceService); 上面三组批次级列表不受影响、一行不减。
         */
        private List<com.cretas.aims.service.pot.PotTraceService.BatchPotScope> potScopes;
        /** 按锅收窄没算成时的原因 (此时 potScopes 为 null; 批次级三组照常有效)。 */
        private String potScopesError;
        /** 下游影响面是否完整（跳数上限截断 / 节点查不到）。见 {@link LineageCompleteness}。 */
        private LineageCompleteness completeness;
        /**
         * 在库箱数 / 已发箱数（按包装快照折算，复用 supply-impact 的折算逻辑）。
         * null = 本次没算成（箱数引擎未接入）；各段 complete=false 时箱数是「无法折算」，
         * ⛔ 前端不要把 null / 未知显示成 0 箱。
         */
        private com.cretas.aims.dto.supplyimpact.SupplyImpactBoxExposure boxExposure;
    }

    /**
     * 生产批次信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductionInfo {
        private Long batchId;
        private String batchNumber;
        private String productName;
        private String productType;
        private LocalDate productionDate;
        private LocalDateTime completionTime;
        private String supervisorName;
        private String equipmentName;
        private Double quantity;
        private Double goodQuantity;
        private Double defectQuantity;
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

        /** 报工自动带出 / BOM 自动扣料 (数量 = 配比 × 投入): 工艺保密时按配比类裁剪 (独立审查 V2.1 M4)。 */
        private boolean automatic;
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
        private String inputUnit;
        private Double outputWeight;
        private String outputUnit;
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
        private Long inspectorId;
        private LocalDateTime inspectionDate;
        private String inspectorName;
        private String result;
        private Double passRate;
        private Double sampleSize;
        private Double passCount;
        private Double failCount;
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
        /** Actual shipment date when evidenced, never the sales plan's delivery date. */
        private LocalDate shipmentDate;
        private LocalDate plannedDeliveryDate;
        private LocalDateTime shippedAt;
        /** LINEAGE_EVENT / LEGACY_DATE_ONLY / UNRECORDED. */
        private String shipmentTimeSource;
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
