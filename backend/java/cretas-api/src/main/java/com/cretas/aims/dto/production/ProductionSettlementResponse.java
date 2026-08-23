package com.cretas.aims.dto.production;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionSettlementResponse {

    /**
     * 这张计划到底有没有仓库确认单（{@code production_settlements} 行）。
     *
     * <p>🔴 2026-08-19: 查状态的读接口原来对「没有」抛 404
     * 「该生产计划尚未结单, 不能确认入库」。但**没有不总是错误** ——
     * 存货生产（SAFETY_STOCK）走「小结」，成品在小结时就入库了，这张表里本来就没有行。
     *
     * <p>实际代价（客户实报）：生产计划列表给每个 COMPLETED 计划拉一次状态，
     * 列表里一条过往的存货生产计划命中 404 → 前端弹全局红条 →
     * 用户以为自己刚做的操作失败了，换页面回来还弹，筛「已结单」就不弹。
     *
     * <p>⇒ 读接口改成如实回答：{@code settled=false} + 其余字段留空，<b>不是异常</b>。
     * ⚠️ 写路径（确认入库 / 中转挂账 / 停产桥接）<b>照旧 404</b> —— 那里拦住是对的。
     *
     * <p>⚠️ 新增字段，老客户端忽略它即可（向后兼容）。
     */
    private Boolean settled;

    private String settlementId;
    private String productionPlanId;
    private String planNumber;
    private String status;
    private BigDecimal plannedQuantity;
    private BigDecimal actualFinishedQuantity;
    private BigDecimal actualSemiFinishedQuantity;
    private String quantityUnit;
    private String postingStatus;
    private String postingMessage;
    private BigDecimal warehouseReceivedQuantity;
    private BigDecimal warehouseVarianceQuantity;
    private String finishedGoodsBatchId;
    private String transitLedgerId;

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Builder.Default
    private List<String> createdClearingLedgerIds = new ArrayList<>();

    @Builder.Default
    private List<String> createdInventoryTxnIds = new ArrayList<>();

    @Builder.Default
    private List<ProductionOutputLineDTO> outputLines = new ArrayList<>();
}
