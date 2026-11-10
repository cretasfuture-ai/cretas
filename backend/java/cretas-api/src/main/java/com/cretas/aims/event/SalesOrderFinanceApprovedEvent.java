package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 销售订单财务审核通过事件
 * 当销售订单通过财务审核后触发，用于驱动供应链协同流程（库存检查+生产计划+采购建议）
 * 替代原先的 SalesOrderConfirmedEvent 作为供应链联动的触发点
 *
 * <h3>审批来源 (D-SO-AUTOPLAN-NOAPPROVER, 2026-11-10)</h3>
 *
 * <p>OA 审批流在启动时就命中自动通过条件 ({@code autoApproveCondition}) 时, 没有任何人点「通过」,
 * {@code approvedBy} 如实为 {@code null}。原来事件只带 {@code approvedBy}, 自动生成生产计划因此
 * 拿不到建单人而抛「缺少审批人」, 订单审批通过了、计划却没生成 (prod 2026-11-10 F006 三单)。
 *
 * <p>现在事件另带两项: {@link #approvalSource} (人工 / 系统自动) 与 {@link #triggeredBy}
 * (系统自动审批时, 提交审批、触发这次自动通过的那个用户)。⛔ {@code approvedBy} 仍保持 null,
 * 不把提交人冒充成审批人 —— 那会把「系统自动通过」写成「提交人自己批了自己的单」。
 */
@Getter
public class SalesOrderFinanceApprovedEvent extends ApplicationEvent {

    /** 这次「财审通过」是怎么来的。 */
    public enum ApprovalSource {
        /** 有人审批 (OA 节点上点通过 / 财务直接审批), {@code approvedBy} 是那个人。 */
        MANUAL,
        /** 系统自动通过 (OA 审批流命中自动通过条件 / 当前业务无需审批), 没有人点「通过」。 */
        SYSTEM_AUTO
    }

    private final String factoryId;
    private final String salesOrderId;
    private final Long approvedBy;
    private final LocalDateTime approvedAt;
    private final ApprovalSource approvalSource;
    /**
     * 系统自动审批时, 提交审批、触发这次自动通过的用户 (销售订单审批流的发起人)。
     * 人工审批时为 {@code null} (此时有 {@link #approvedBy})。
     */
    private final Long triggeredBy;
    /** 系统自动审批时的原因说明 (如「销售审批工作流自动通过」), 人工审批时为 {@code null}。 */
    private final String autoApprovalReason;

    /** 人工审批: {@code approvedBy} 是审批人。 */
    public SalesOrderFinanceApprovedEvent(Object source, String factoryId, String salesOrderId, Long approvedBy) {
        this(source, factoryId, salesOrderId, approvedBy, ApprovalSource.MANUAL, null, null);
    }

    private SalesOrderFinanceApprovedEvent(Object source, String factoryId, String salesOrderId, Long approvedBy,
                                           ApprovalSource approvalSource, Long triggeredBy,
                                           String autoApprovalReason) {
        super(source);
        this.factoryId = factoryId;
        this.salesOrderId = salesOrderId;
        this.approvedBy = approvedBy;
        this.approvedAt = LocalDateTime.now();
        this.approvalSource = approvalSource;
        this.triggeredBy = triggeredBy;
        this.autoApprovalReason = autoApprovalReason;
    }

    /**
     * 系统自动审批通过。
     *
     * @param approvedBy  订单上记的财审人 (OA 自动通过时为 {@code null}; 「无需审批直接通过」沿用原口径记提交人)
     * @param triggeredBy 提交审批、触发自动通过的用户
     * @param reason      自动通过的原因说明
     */
    public static SalesOrderFinanceApprovedEvent systemAutoApproved(Object source, String factoryId,
                                                                    String salesOrderId, Long approvedBy,
                                                                    Long triggeredBy, String reason) {
        return new SalesOrderFinanceApprovedEvent(source, factoryId, salesOrderId, approvedBy,
                ApprovalSource.SYSTEM_AUTO, triggeredBy, reason);
    }

    public boolean isSystemAutoApproved() {
        return approvalSource == ApprovalSource.SYSTEM_AUTO;
    }

    @Override
    public String toString() {
        return String.format("SalesOrderFinanceApprovedEvent[factoryId=%s, salesOrderId=%s, approvedBy=%s, "
                        + "approvalSource=%s, triggeredBy=%s, approvedAt=%s]",
                factoryId, salesOrderId, approvedBy, approvalSource, triggeredBy, approvedAt);
    }
}
