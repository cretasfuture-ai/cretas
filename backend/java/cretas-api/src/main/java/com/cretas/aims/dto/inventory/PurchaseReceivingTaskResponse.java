package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.cretas.aims.dto.material.MaterialPackagingSpecDTO;

/**
 * 仓储统一入库页的采购待收货只读投影。
 *
 * <p>它不是另一张“任务表”。任务身份稳定等于采购订单 ID，来源于已经完成审批且尚未收齐的
 * 采购订单；因此历史订单无需桥接即可进入待办池，也不会因为重复打开页面产生业务写入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseReceivingTaskResponse {

    private String taskId;
    private String sourceType;
    private String sourceId;
    private String sourceNumber;
    private String inboundReason;
    private String counterpartyType;
    private String counterpartyId;
    private String counterpartyName;
    private String purchaseOrderId;
    private String orderNumber;
    private String supplierId;
    private String supplierName;
    private String salesOrderId;
    private String salesOrderNo;
    private String customerId;
    private String customerName;
    private LocalDate expectedDeliveryDate;
    private LocalDateTime expectedArrivalAt;
    private String status;
    private String statusLabel;
    private String warehouseId;
    private String warehouseName;
    private String responsibleName;
    private String activeReceiptId;
    private String activeReceiptNumber;
    private int activeReceiptCount;
    private boolean receiptConflict;

    /**
     * 本工厂<b>实际生效</b>的采购超收上限比率（{@code 0.30} = 最多多收 30%）；
     * {@code null} = 不限制超收。
     *
     * <p>⚠️ 这是后端执行侧 {@code PurchaseServiceImpl.resolveOverReceiveLimit} 用的同一个值。
     * ⛔ 前端不要再写死百分比，也不要拿 {@code remainingReceivableQuantity} 当"最多能收多少"
     * —— 那个字段是<b>还差多少收齐</b>，不含超收余量。
     *
     * @see com.cretas.aims.service.inventory.OverReceiveLimit
     */
    private BigDecimal overReceiveLimitRate;

    /** 面向仓管员的措辞：{@code "不限制"} / {@code "30%"}。直接显示，不要自己格式化。 */
    private String overReceiveLimitLabel;

    /** 这个上限从哪来（{@code unconfigured} / {@code property:...} / {@code factory-settings:...}）。排查用。 */
    private String overReceiveLimitSource;

    @Builder.Default
    private List<Item> items = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long purchaseOrderItemId;
        private Long salesOrderItemId;
        private String materialTypeId;
        private String materialName;
        private BigDecimal orderedQuantity;
        private BigDecimal receivedQuantity;
        private BigDecimal activeDraftAllocatedQuantity;
        /** 还差多少<b>收齐</b> = 下单 − 已收 − 草稿占用（不含任何超收余量）。 */
        private BigDecimal remainingReceivableQuantity;
        /**
         * 本次<b>最多还能收多少</b> = 下单×(1+超收上限) − 已收 − 草稿占用。
         *
         * <p>{@code null} = 不限制超收。⛔ 前端读到 null 不许兜底成
         * {@link #remainingReceivableQuantity} —— 那正是"界面把货挡在门口"的写法。
         *
         * <p>⚠️ 它与 {@code remainingReceivableQuantity} 是两个问题的答案：
         * 一个是"还差多少算收齐"，一个是"最多还能往里收多少"。未配置上限时后者没有答案。
         */
        private BigDecimal maxReceivableQuantity;
        private String unit;
        private String specification;
        /**
         * 行级合同号。下单时填了就带到收货界面预填, 仓管不用照着纸质合同重敲。
         * 行级为空时回落到单头 contract_number(框架合同号)。
         */
        private String contractNumber;
        private String materialPackagingSpecId;
        private String inventoryBaseUnit;
        private BigDecimal packageToBaseFactor;
        @Builder.Default
        private List<MaterialPackagingSpecDTO> packagingSpecs = new ArrayList<>();
    }
}
