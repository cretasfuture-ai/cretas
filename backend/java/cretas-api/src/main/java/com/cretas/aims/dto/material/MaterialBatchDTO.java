package com.cretas.aims.dto.material;

import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 原材料批次数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "原材料批次信息")
public class MaterialBatchDTO {

    @Schema(description = "批次ID")
    private String id;

    @Schema(description = "工厂ID")
    private String factoryId;

    @Schema(description = "批次号")
    private String batchNumber;

    @Schema(description = "原材料类型ID")
    private String materialTypeId;

    @Schema(description = "原材料名称")
    private String materialName;

    @Schema(description = "原材料代码")
    private String materialCode;

    @Schema(description = "原材料类别")
    private String materialCategory;

    @Schema(description = "物料大类（原料/辅料/包材）")
    private String materialMainCategory;

    @Schema(description = "存储类型(来自原材料类型): fresh/frozen/dry")
    private String storageType;

    @Schema(description = "供应商ID")
    private String supplierId;

    @Schema(description = "供应商名称")
    private String supplierName;

    @Schema(description = "入库日期")
    private LocalDate receiptDate;

    @Schema(description = "生产日期")
    private LocalDate productionDate;

    @Schema(description = "到期日期")
    private LocalDate expireDate;

    @Schema(description = "入库数量")
    private BigDecimal receiptQuantity;

    @Schema(description = "数量单位")
    private String quantityUnit;

    @Schema(description = "箱数 (粗略统计用, 不参与库存计算; 实际库存以称重 kg 为准)")
    private Integer boxCount;

    @Schema(description = "每单位重量(kg)")
    private BigDecimal weightPerUnit;

    @Schema(description = "入库总重量(kg)")
    private BigDecimal totalWeight;

    @Schema(description = "当前数量")
    private BigDecimal currentQuantity;

    @Schema(description = "已使用数量")
    private BigDecimal usedQuantity;

    @Schema(description = "预留数量")
    private BigDecimal reservedQuantity;

    /**
     * 报工在途、尚未结单的预扣消耗量 (defect A, 2026-09-02 分仓库存查询)。
     *
     * <p>与 {@code currentQuantity}(账面) 是<b>两个不同口径</b>: {@code currentQuantity} =
     * receiptQuantity − usedQuantity − reservedQuantity, 而 {@code reservedQuantity} 目前
     * <b>没有报工写入路径会动它</b> —— 报工在途占用走的是另一条账
     * ({@code material_consumptions.interim_settled_at IS NULL}), 与
     * 报损/调拨/盘点弹窗同一口径 ({@link com.cretas.aims.service.MaterialBatchService
     * #getUnsettledConsumptionByBatchIds})。
     *
     * <p>⛔ 不要用它覆盖 {@code currentQuantity} —— 两个数各自都对, 合并会丢信息量
     * (「账面」vs「已减报工预扣的可投料量」)。前端应<b>并排</b>显示
     * {@code 可用 = max(0, currentQuantity − unsettledConsumption)}。
     *
     * <p>{@code null} = 未查询该批次的预扣量 (仅 {@code WarehouseInventoryController
     * #queryByWarehouse} 填充此字段, 其它端点保持 null; 调用方按 0 处理, 即 可用==账面)。
     */
    @Schema(description = "报工在途未结单预扣量 (可用数量 = 当前数量 − 本字段; 仅分仓库存查询端点填充, 其余端点为 null)")
    private BigDecimal unsettledConsumption;

    @Schema(description = "单位")
    private String unit;

    @PriceSensitive(permission = PriceSensitive.PURCHASE_OR_FINANCE)
    @Schema(description = "入库总价值(元)")
    private BigDecimal totalValue;

    @PriceSensitive(permission = PriceSensitive.PURCHASE_OR_FINANCE)
    @Schema(description = "单价(元/kg)")
    private BigDecimal unitPrice;

    @PriceSensitive(permission = PriceSensitive.PURCHASE_OR_FINANCE)
    @Schema(description = "总价")
    private BigDecimal totalPrice;

    @Schema(description = "状态")
    private MaterialBatchStatus status;

    @Schema(description = "状态显示名称")
    private String statusDisplayName;

    /** D1 双仓流转 (PR #309 A1=A) — 仓库 UUID (factory_warehouses.id). */
    @Schema(description = "仓库ID (UUID, factory_warehouses.id)")
    private String warehouseId;

    /** D1 双仓流转 (PR #309 A1=A) — 仓库 code, 例如 "WH-LOG" / "WH-WKS". */
    @Schema(description = "仓库 code (WH-LOG / WH-WKS)")
    private String warehouseCode;

    /** D1 双仓流转 (PR #309 A1=A) — 仓库名称, 例如 "物流仓" / "鲜棉仓". */
    @Schema(description = "仓库名称 (物流仓 / 鲜棉仓)")
    private String warehouseName;

    @Schema(description = "库存法律归属")
    private InventoryOwnership ownership;

    @Schema(description = "库存归属客户ID（仅客户自有）")
    private String ownerCustomerId;

    /**
     * 来源单据类型。`PRODUCTION_BATCH` = 在制半成品工件 (materialTypeId 是原料血缘,
     * warehouseId=WH-WKS), **不是真实原料库存** —— 调拨侧 FEFO 明确排除它。
     * 前端调拨页要按同一口径过滤, 所以必须透出来 (2026-08-28: 不透出就只能在前端硬猜)。
     */
    private String sourceDocType;

    /**
     * 发起单 ID (P0-17)。同一次收货共享同一个 sourceDocId ——
     * 「已入库批次」按收货单分组的分组键 (2026-09-01)。
     */
    @Schema(description = "发起单ID (同一次收货共享同一个 ID, 按收货单分组用)")
    private String sourceDocId;

    /**
     * 收货单号 (仅 sourceDocType=PURCHASE_RECEIVE 非空)。与 sourceDocId 配套，
     * 是给用户看的人能读的单号 —— 见 {@link com.cretas.aims.entity.MaterialBatch#getSourceDocNumber()}。
     */
    @Schema(description = "收货单号 (仅采购收货批次非空, 分组表头展示用)")
    private String sourceDocNumber;

    @Schema(description = "来源销售订单ID快照")
    private String sourceSalesOrderId;

    @Schema(description = "来源销售订单行ID快照")
    private String sourceSalesOrderItemId;

    @Schema(description = "存储位置")
    private String storageLocation;

    @Schema(description = "质量证书")
    private String qualityCertificate;

    @Schema(description = "备注")
    private String notes;

    /** SP4-A4: 生产厂家编号，用于一物一码标签 */
    @Schema(description = "生产厂家编号 (SP4-A4)")
    private String factoryNumber;

    /** SP4-A4: 产地，用于一物一码标签 */
    @Schema(description = "产地 (SP4-A4)")
    private String originPlace;

    /**
     * 来源个体档案 ID。可空 —— 只有按个体投产的批次才挂。
     * ⛔ 读不到不代表「没有个体」, 只代表这个批次没挂个体; 两者在 UI 上都显示为「未关联」即可,
     * 但⛔ 不要在任何一层把 null 翻译成一个具体的个体。
     */
    @Schema(description = "来源个体档案ID (source_individuals.id, 可空)")
    private String sourceIndividualId;

    /**
     * 合同号 (V20261029_61)。
     *
     * <p>⛔ 2026-08-06: 列加了、搜索也加了、列表列也加了, <b>唯独漏了这一层 DTO</b> ——
     * 于是「按合同号搜得到, 搜出来那一列却显示 -」。同一个功能横跨
     * 实体 / 查询 / DTO / 表格四处, 漏一处就是「查得到但看不见」。</p>
     */
    @Schema(description = "合同号 (来料追溯)")
    private String contractNumber;

    /** 供应商/客户给的批次号 (V20261029_61) —— 与系统生成的 batchNumber 是两件事。 */
    @Schema(description = "供应商/客户批次号 (来料追溯)")
    private String supplierBatchNumber;

    @Schema(description = "创建人ID")
    private Long createdBy;

    @Schema(description = "创建人姓名")
    private String createdByName;

    @Schema(description = "最后使用时间")
    private LocalDateTime lastUsedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "剩余天数")
    private Integer remainingDays;

    @Schema(description = "库存占用率")
    private BigDecimal usageRate;

    /**
     * 是否已消耗完（用完/过期/报废）
     */
    @Schema(description = "是否已消耗完")
    public Boolean getIfRunout() {
        if (status == null) {
            return false;
        }
        return status == MaterialBatchStatus.USED_UP ||
               status == MaterialBatchStatus.EXPIRED ||
               status == MaterialBatchStatus.SCRAPPED;
    }

    // ==================== 前端字段别名 ====================

    /**
     * inboundQuantity 别名（兼容前端）
     * 前端使用 inboundQuantity，后端使用 receiptQuantity
     */
    @JsonProperty("inboundQuantity")
    @Schema(description = "入库数量(前端别名)")
    public BigDecimal getInboundQuantity() {
        return receiptQuantity;
    }

    /**
     * inboundDate 别名（兼容前端）
     * 前端使用 inboundDate，后端使用 receiptDate
     */
    @JsonProperty("inboundDate")
    @Schema(description = "入库日期(前端别名)")
    public LocalDate getInboundDate() {
        return receiptDate;
    }

    /**
     * expiryDate 别名（兼容前端）
     * 前端使用 expiryDate，后端使用 expireDate
     */
    @JsonProperty("expiryDate")
    @Schema(description = "到期日期(前端别名)")
    public LocalDate getExpiryDate() {
        return expireDate;
    }

    /**
     * remainingQuantity 别名（兼容前端）
     * 前端使用 remainingQuantity，后端使用 currentQuantity
     */
    @JsonProperty("remainingQuantity")
    @Schema(description = "剩余数量(前端别名)")
    public BigDecimal getRemainingQuantity() {
        return currentQuantity;
    }
}
