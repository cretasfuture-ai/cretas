package com.cretas.aims.entity.enums;

/**
 * 销售订单状态枚举
 * 通用：工厂出货 = 餐饮外卖/堂食
 */
public enum SalesOrderStatus {
    DRAFT("草稿", "销售订单草稿"),
    CONFIRMED("已确认", "客户已确认订单"),
    PENDING_FINANCE_REVIEW("待财务审核", "已提交财务审核，等待审批"),
    FINANCE_APPROVED("财务已批准", "财务审核通过，可触发生产"),
    FINANCE_REJECTED("财务已驳回", "财务审核不通过，需修改后重新提交"),
    PROCESSING("处理中", "拣货/备货中"),
    PARTIAL_DELIVERED("部分发货", "部分商品已发货"),
    COMPLETED("已完成", "全部发货完成"),
    CANCELLED("已取消", "订单已取消"),
    /**
     * 短交结案 (2026-09-04 owner 拍板): 部分发货后, 剩余数量经确认不再补发, 显式结案。
     *
     * <p>与 {@link #COMPLETED} 的区别 —— COMPLETED 是「订单量全部实发」的自然结果
     * (见 SalesServiceImpl#updateOrderDeliveryStatus); SHORT_CLOSED 是「明知还有缺口,
     * 人工确认不再补」的终态, 一旦进入不会再自动流转。终态, 只能从 PARTIAL_DELIVERED
     * 显式关闭进入 (SalesServiceImpl#closeShortShipment), 不接受自动转入。
     */
    SHORT_CLOSED("短交结案", "部分发货后确认剩余数量不再发货，按短交结案");

    private final String displayName;
    private final String description;

    SalesOrderStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
