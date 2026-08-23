package com.cretas.aims.entity.enums;

/**
 * 采购入库单状态枚举
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
public enum PurchaseReceiveStatus {
    /** 草稿 */
    DRAFT("草稿", "入库单草稿"),
    /** 待质检 */
    PENDING_QC("待质检", "等待质量检验"),
    /** 已确认入库 */
    CONFIRMED("已确认", "已确认入库，库存已增加"),
    /** 已退回 */
    REJECTED("已退回", "质检不合格，退回供应商"),
    /**
     * 已作废 —— M-15 (2026-08-20): 仓储主动作废一张建错/不再需要的草稿。
     * 与 REJECTED（质检不合格退回供应商）是两件不同的事，不能复用：CANCELLED 从未生成
     * 库存批次、未占用采购单已收数量，纯粹释放该草稿此前预留的「待收」余量，使采购单
     * 可以重新创建收货单（分批收货）。只允许从 DRAFT 转入，见 PurchaseServiceImpl#cancelReceive。
     */
    CANCELLED("已作废", "仓储主动作废，未生成入库批次");

    private final String displayName;
    private final String description;

    PurchaseReceiveStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
