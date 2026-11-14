package com.cretas.aims.entity.outsource;

import com.cretas.aims.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 委外单 (按委外厂开)。一张单可含多个任务行, 每行可挂回某个工单的某道工序 (工序委外) 或不挂 (整单委外)。
 *
 * <p>设计见 {@code docs/specs/outsource-closed-loop-design.md}。状态只由 {@code OutsourceService} 推进;
 * 结单要求该单在委外厂的结存为 0 (退料或登记损耗对平, ⛔ 不静默吞差额)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Entity
@Table(name = "outsource_orders",
        uniqueConstraints = @UniqueConstraint(name = "uk_outsource_orders_no", columnNames = {"factory_id", "order_no"}))
@Where(clause = "deleted_at IS NULL")
public class OutsourceOrder extends BaseEntity {

    public enum Status { DRAFT, RELEASED, CLOSED, CANCELLED }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    /** 委外厂 = 供应商主数据 (应付按 Supplier 记)。 */
    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;

    /** 该委外厂专属 OUTSOURCE 仓, 下达时解析/创建。 */
    @Column(name = "outsource_warehouse_id", length = 64)
    private String outsourceWarehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(name = "expected_return_date")
    private LocalDate expectedReturnDate;

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "released_by")
    private Long releasedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
