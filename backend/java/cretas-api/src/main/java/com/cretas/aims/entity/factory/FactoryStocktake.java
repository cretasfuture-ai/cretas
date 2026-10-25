package com.cretas.aims.entity.factory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 工厂盘点任务实体 (SP7 §3.2).
 *
 * <p>状态机: INITIATED → COUNTING → PENDING_APPROVAL → APPROVED → APPLIED
 *                                                    ↘ REJECTED → 可重提 PENDING_APPROVAL
 *                                                    ↘ RECOUNT_REQUIRED → 新盘点单
 *
 * <p>红线 §3.4: 盘点生效(apply)必须在财务审批通过后执行，仓管无法绕过直接修改库存。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"items"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "factory_stocktakes",
        indexes = {
                @Index(name = "idx_stocktake_factory_month", columnList = "factory_id,period_month"),
                @Index(name = "idx_stocktake_warehouse",     columnList = "warehouse_id"),
                @Index(name = "idx_stocktake_status",        columnList = "status")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_stocktake_no_factory", columnNames = {"factory_id", "stocktake_no"})
        }
)
@Where(clause = "deleted_at IS NULL")
public class FactoryStocktake extends BaseEntity {

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        INITIATED("已发起 · 未录入"),
        COUNTING("盘点中 · 未提交"),
        PENDING_APPROVAL("待审批"),
        APPROVED("已审批"),
        APPLIED("已生效"),
        REJECTED("已驳回"),
        RECOUNT_REQUIRED("需重新盘点");

        private final String displayLabel;
    }

    public enum InventoryScope {
        RAW_MATERIAL, FINISHED_GOODS
    }

    /**
     * 导入模式（仅批量导入创建的盘点任务有值；逐项 UI 发起的为 null）。
     *
     * <ul>
     *   <li>{@code null} — 逐项录入盘点（历史行为，apply 不过账损益凭证，保持原样）</li>
     *   <li>{@code NORMAL} — 批量导入常规月度盘点：apply 过账 盘盈(借1405/贷6301) / 盘亏(借6602.01/贷1405)</li>
     *   <li>{@code OPENING} — 批量导入期初建账：apply 过账 借1405/贷 期初权益科目（不进营业外收入，避免虚增当期损益）</li>
     * </ul>
     */
    public enum ImportMode {
        NORMAL, OPENING
    }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 盘点单号，工厂内唯一，格式 ST-{YYYYMM}-{SEQ} */
    @Column(name = "stocktake_no", nullable = false, length = 50)
    private String stocktakeNo;

    /** 被盘仓库 ID，FK → factory_warehouses.id */
    @Column(name = "warehouse_id", nullable = false, length = 64)
    private String warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_scope", nullable = false, length = 30)
    private InventoryScope inventoryScope = InventoryScope.RAW_MATERIAL;

    /** 盘点月份，格式 "2026-06" */
    @Column(name = "period_month", nullable = false, length = 7)
    private String periodMonth;

    /** Server-authored inventory snapshot instant. Clients can never backdate it. */
    @Column(name = "inventory_cutoff_at")
    private LocalDateTime inventoryCutoffAt;

    /** First authoritative count save; opening a read-only dialog does not mutate the task. */
    @Column(name = "counting_started_at")
    private LocalDateTime countingStartedAt;

    /** Audit-only transaction review window. It never changes systemQty snapshot semantics. */
    @Column(name = "reconciliation_start_at")
    private LocalDateTime reconciliationStartAt;

    @Column(name = "reconciliation_end_at")
    private LocalDateTime reconciliationEndAt;

    @Column(name = "reconciliation_preset", length = 30)
    private String reconciliationPreset;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.INITIATED;

    @Column(name = "initiated_by", nullable = false)
    private Long initiatedBy;

    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /** 生效时间（库存差异正式写入后设置）*/
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "counted_by")
    private Long countedBy;

    @Column(name = "applied_by")
    private Long appliedBy;

    /** Explicit audit marker when a zero-difference task is approved by its maker. */
    @Column(name = "self_confirmed_zero_difference", nullable = false)
    private boolean selfConfirmedZeroDifference = false;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** SP12 §5.2: 工作流实例 ID，关联 ApprovalWorkflowInstance.id */
    @Column(name = "workflow_instance_id", length = 191)
    private String workflowInstanceId;

    /** 批量导入模式（NORMAL / OPENING）；逐项 UI 发起为 null。决定 apply 时是否/如何过账损益凭证。*/
    @Enumerated(EnumType.STRING)
    @Column(name = "import_mode", length = 20)
    private ImportMode importMode;

    /** Drift recovery audit: the approved evidence remains unchanged; only the lifecycle state moves. */
    @Column(name = "recount_requested_by")
    private Long recountRequestedBy;

    @Column(name = "recount_requested_at")
    private LocalDateTime recountRequestedAt;

    @Column(name = "recount_reason", columnDefinition = "TEXT")
    private String recountReason;

    /** The version supplied for the one-time recovery request, used for safe idempotent retry. */
    @Column(name = "recount_source_version")
    private Long recountSourceVersion;

    /** New counting task created by this recovery request. */
    @Column(name = "recount_stocktake_id", length = 64)
    private String recountStocktakeId;

    /** Approved task from which this fresh counting task was created. */
    @Column(name = "recount_source_stocktake_id", length = 64)
    private String recountSourceStocktakeId;

    @OneToMany(mappedBy = "stocktake", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<FactoryStocktakeItem> items = new ArrayList<>();
}
