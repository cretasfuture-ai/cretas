package com.cretas.aims.entity.enums;

/**
 * 请购单状态枚举 (Sprint 5 Track D — P-REQUISITION-1).
 *
 * <p>State machine:
 * <pre>
 *   DRAFT ──submit──> PENDING_APPROVAL ──approve──> APPROVED ──convertToPO──> CONVERTED_TO_PO
 *     ^                      │
 *     │                      └──reject──> REJECTED
 *     └────────reopen(仅请购人)───────────────┘
 * </pre>
 * <p>2026-11-15 Steve 裁决: REJECTED 不再是终态, 请购人可重开回 DRAFT 改后重提, 每一轮审批都留痕
 * (见 PurchaseRequisitionApprovalRecord)。CONVERTED_TO_PO 仍是终态。
 *
 * <p>Round 12 §B.2 X2 — HJ 采购模块"请购单"对齐.
 *
 * @since 2026-05-19
 */
public enum PurchaseRequisitionStatus {
    /** 草稿 — requester 仍在编辑, 未提审 */
    DRAFT("草稿", "请购单草稿, 未提交审批"),
    /** 待审批 — submit 之后, 等采购经理审 */
    PENDING_APPROVAL("待审批", "已提交, 等待采购经理审批"),
    /** 已审批 — 审批通过, 等转 PO (或人工触发 convertToPO) */
    APPROVED("已审批", "审批通过, 等待转采购订单"),
    /** 已转 PO — 已自动 / 手动转为采购订单, 终态 */
    CONVERTED_TO_PO("已转PO", "请购单已转为采购订单"),
    /** 已驳回 — 审批不通过; 请购人可重开回 DRAFT 改后重提 (历史多轮保留) */
    REJECTED("已驳回", "审批未通过");

    private final String displayName;
    private final String description;

    PurchaseRequisitionStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    /**
     * State transition guard. 仅返回某状态下允许的下一组状态.
     */
    public boolean canTransitionTo(PurchaseRequisitionStatus target) {
        if (target == null) return false;
        switch (this) {
            case DRAFT:
                return target == PENDING_APPROVAL;
            case PENDING_APPROVAL:
                return target == APPROVED || target == REJECTED;
            case APPROVED:
                return target == CONVERTED_TO_PO;
            case REJECTED:
                return target == DRAFT;
            case CONVERTED_TO_PO:
            default:
                return false;
        }
    }
}
