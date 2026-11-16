package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 待补账记录: 销售发货成功但应收自动挂账真正失败时留下的一行。
 *
 * <p>2026-11-15 Steve 裁决: 发货不回滚, 失败写本表并发告警, 由人补账。
 * 「幂等命中(该发运单已挂账)」不是失败, 不产生本表记录。
 * 表: {@code ar_posting_pending} (V20261115_71)。补账动作与 RESOLVED 置位本期未做。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "ar_posting_pending",
        indexes = {
                @Index(name = "idx_arpp_factory_status", columnList = "factory_id,status,created_at"),
                @Index(name = "idx_arpp_delivery", columnList = "factory_id,delivery_id")
        })
@SQLDelete(sql = "UPDATE ar_posting_pending SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ArPostingPending extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 子发运单 id (与挂账幂等键 SALES_DELIVERY 的 sourceId 同值)。 */
    @Column(name = "delivery_id", nullable = false, length = 191)
    private String deliveryId;

    @Column(name = "delivery_number", length = 100)
    private String deliveryNumber;

    @Column(name = "sales_order_id", length = 191)
    private String salesOrderId;

    @Column(name = "customer_id", length = 191)
    private String customerId;

    /** 应挂金额(实发价税合计); 算不出来时为 null, 不伪造 0。 */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "failure_reason", nullable = false, length = 1000)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ArPostingPendingStatus status = ArPostingPendingStatus.PENDING;

    @Column(name = "operated_by")
    private Long operatedBy;

    /** 销售订单创建人: 财务行范围(只看本人单据)过滤用; 取不到为 null(受限账号看不到)。 */
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    /**
     * 待补账记录是账务审计线索, 只能置为已处理, 不能删除: 禁止物理/软删
     * (软删注解仅为满足软删注解覆盖率棘轮, 实际被本回调拦在前面)。
     */
    @PreRemove
    protected void preventRemove() {
        throw new UnsupportedOperationException("ar_posting_pending 不允许删除, 补账后置为 RESOLVED");
    }
}
