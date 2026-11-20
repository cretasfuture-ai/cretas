package com.cretas.aims.entity.factory;

import com.cretas.aims.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 工厂物料需求单 (P0-5) — 生产计划与库存调拨之间的"任务单"。
 *
 * <p>与 restaurant.MaterialRequisition 区别: 这是工厂双仓制 (物流仓 → 工厂鲜棉仓),
 * 按 BOM 自动展开多行原/辅/包装材料, 含完整状态机: PENDING → PICKING → TRANSFERRED
 * → ISSUED → IN_USE → CLOSED, 支持报工后退料反向调拨.</p>
 *
 * @since 2026-04-08 (W2-3 — 客户六扇门双仓制核心)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = "items")
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "factory_material_requisitions",
        indexes = {
                @Index(name = "idx_fmr_factory_status", columnList = "factory_id,status"),
                @Index(name = "idx_fmr_plan", columnList = "production_plan_id"),
                @Index(name = "idx_fmr_required_date", columnList = "required_date")
        }
)
@Where(clause = "deleted_at IS NULL")
public class FactoryMaterialRequisition extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "requisition_no", nullable = false, unique = true, length = 40)
    private String requisitionNo;

    @Column(name = "production_plan_id", nullable = false, length = 64)
    private String productionPlanId;

    /** Immutable display snapshots prevent list/detail pages from querying deleted historical plans. */
    @Column(name = "production_plan_number", length = 64)
    private String productionPlanNumber;

    @Column(name = "product_name", length = 191)
    private String productName;

    @Column(name = "source_warehouse_id", length = 64)
    private String sourceWarehouseId;   // 物流仓

    @Column(name = "target_warehouse_id", length = 64)
    private String targetWarehouseId;   // 工厂(鲜棉)仓

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "required_date")
    private LocalDate requiredDate;

    // --- 操作人与时间 ---
    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "picked_by")
    private Long pickedBy;

    @Column(name = "picked_at")
    private LocalDateTime pickedAt;

    @Column(name = "transferred_by")
    private Long transferredBy;

    @Column(name = "transferred_at")
    private LocalDateTime transferredAt;

    @Column(name = "received_by")
    private Long receivedBy;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    /** P0-5: 关联的备料调出 InternalTransfer ID (transferToFactory 时创建). */
    @Column(name = "outbound_transfer_id", length = 191)
    private String outboundTransferId;

    /** P0-5: 关联的退料调入 InternalTransfer ID (close 时如果 returned>0 则创建). */
    @Column(name = "return_transfer_id", length = 191)
    private String returnTransferId;

    @OneToMany(mappedBy = "requisition", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<FactoryMaterialRequisitionItem> items = new ArrayList<>();

    /**
     * 确认领料弹窗「辅料能不能自动算」, <b>不落库</b>, 只由详情接口现算
     * (见 {@code FactoryMaterialRequisitionService#getDetailForPicking}):
     * {@code AVAILABLE} 至少一行辅料带了 {@code dosagePerKgG} / {@code NO_RATIO} 没有可用配比 (含计划或配方取不到) /
     * {@code PROCESS_SECRET} 产品工艺保密且调用者无完整查看权, 配比不输出 /
     * {@code UNAVAILABLE} 读配比时出错 (详情照常返回) —— 除 AVAILABLE 外前端都提示「辅料按实际称重填写」,
     * 不弹错误。其它接口不填 (null, 不输出)。
     */
    @Transient
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String auxiliaryAutoCalc;

    /**
     * 「按计划生成」时调料行这一段的结果, <b>不落库</b>, 只由
     * {@code FactoryMaterialRequisitionService#generateFromPlan} 填:
     * {@code OK} 调料表读到了 (配方没有调料时本来就没有调料行) /
     * {@code UNAVAILABLE} 调料表读取失败 —— 单据照常生成, 但<b>没有追加任何调料行</b>, 要人工补。
     * 其它接口不填 (null, 不输出)。
     */
    @Transient
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String seasoningItemsStatus;

    /** 状态机. */
    public enum Status {
        DRAFT,
        PENDING,       // 已生成待备料
        PICKING,       // 备料中
        TRANSFERRED,   // 已调拨 (物流→工厂)
        ISSUED,        // 已签收 (工厂仓入库)
        IN_USE,        // 生产中
        CLOSED,        // 已退料关单
        CANCELLED
    }
}
