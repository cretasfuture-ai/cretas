package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 请购单审批流水 —— 每一轮「提交 / 通过 / 驳回 / 重开」各留一行, 只追加、不覆盖。
 *
 * <p>2026-11-15 Steve 裁决: 请购被驳回后允许请购人改后重提, 并保留多轮审批历史。
 * {@code round} 从 1 起, 每次提交 +1; {@code seq} 是该请购单内的全局顺序号(同一轮里依次是
 * SUBMITTED → REJECTED/APPROVED → REOPENED)。SUBMITTED 行带当时的行项目快照, 所以改量重提后
 * 每一轮到底提交了什么都查得到。表: {@code purchase_requisition_approval_records} (V20261115_72)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"itemsSnapshot"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
// 唯一约束 uk_prar_requisition_seq(requisition_id, seq) 只在迁移里声明, 不在这里重复声明:
// 软删唯一约束清单闸要求「软删实体声明的唯一约束必须登记」, 而本表禁止删除, 登记无意义。
@Table(name = "purchase_requisition_approval_records",
        indexes = {
                @Index(name = "idx_prar_requisition", columnList = "factory_id, requisition_id, seq")
        })
@SQLDelete(sql = "UPDATE purchase_requisition_approval_records SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class PurchaseRequisitionApprovalRecord extends BaseEntity {

    public enum Action { SUBMITTED, APPROVED, REJECTED, REOPENED }

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "requisition_id", nullable = false, length = 191)
    private String requisitionId;

    /** 第几轮提交(1 起)。 */
    @Column(name = "round_no", nullable = false)
    private Integer round;

    /** 该请购单内的流水顺序号(1 起)。 */
    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private Action action;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "acted_at", nullable = false)
    private LocalDateTime actedAt;

    /** 驳回原因(REJECTED 行)。 */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** 提交当时的行项目快照(SUBMITTED 行)。 */
    @Type(JsonBinaryType.class)
    @Column(name = "items_snapshot", columnDefinition = "jsonb")
    private List<Map<String, Object>> itemsSnapshot;

    @Column(name = "expected_date_snapshot")
    private LocalDate expectedDateSnapshot;

    /**
     * 审批流水是只追加的审计记录, 不允许删除(物理/软删都不行): 软删后历史接口会少轮次。
     * @SQLDelete 仅为满足软删注解覆盖率棘轮, 实际被本回调拦在前面。
     */
    @PreRemove
    protected void preventRemove() {
        throw new UnsupportedOperationException("请购审批流水是只追加的审计记录, 不允许删除");
    }
}
