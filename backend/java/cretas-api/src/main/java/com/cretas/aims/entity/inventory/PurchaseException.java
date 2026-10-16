package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 采购异常单（SP6）
 *
 * <p>入库完成后，若实收数量与 PO 数量存在差异（超出超收上限或少收），
 * 系统自动生成本单，采购员在单上做 ACCEPT/RETURN 决策。
 *
 * <p>事务边界：decideException(RETURN_OVER) 调用 ReturnOrderService 加入同一
 * 决策事务，异常单状态、退货单和关联 ID 原子提交或一起回滚。
 *
 * @see com.cretas.aims.entity.enums.ExceptionDecision
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_exceptions",
        indexes = {
                @Index(name = "idx_pe_factory", columnList = "factory_id"),
                @Index(name = "idx_pe_receive", columnList = "receive_record_id"),
                @Index(name = "idx_pe_status", columnList = "status")
        }
)
// 🔴 软删批 3-B (2026-09-07)：补 @SQLDelete，并**摘掉实体级的唯一声明**。
// prod 上这张表的业务唯一键是 `… WHERE deleted_at IS NULL` 的**部分**索引（由 现存迁移 创建），
// 而 JPA 表达不了部分唯一 ⇒ 实体里写 @UniqueConstraint 只会在 H2 建出**全量**键，比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键，症状是测试红而线上是对的
// （#701→#717 就是这么炸出 4 个 error 的）⇒ 唯一键以 prod 的部分索引为准。
// ⚠️ 代价：H2 里这张表没有唯一性保护；行为由 opt-in 的真 PG 契约测试守（见 java-gate-pr 那条车道）。
@SQLDelete(sql = "UPDATE purchase_exceptions SET deleted_at = NOW() WHERE id = ? AND version = ?")
@Where(clause = "deleted_at IS NULL")
public class PurchaseException extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotBlank
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @NotBlank
    @Column(name = "exception_number", nullable = false, length = 50)
    private String exceptionNumber;

    @NotBlank
    @Column(name = "receive_record_id", nullable = false, length = 191)
    private String receiveRecordId;

    @NotBlank
    @Column(name = "purchase_order_id", nullable = false, length = 191)
    private String purchaseOrderId;

    @Column(name = "supplier_id", length = 191)
    private String supplierId;

    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    /**
     * 读时补齐的可读单号 —— 表里只有 {@code purchaseOrderId}（UUID）。
     *
     * <p>2026-08-28 逐屏走查：采购异常屏把那个 UUID 直接当成「采购单」显示
     * （{@code 采购单: 39603589-739e-4094-ac50-ab745e977077}），而同一个 App 的采购单列表
     * 用的是可读的 {@code PO-20260828-0001}。⛔ 前端造不出这个号，只能后端给。
     *
     * <p>⚠️ 不落库：这是快照表，补历史行要写迁移；读时按 PO 查一次即可，
     * 而且<b>存量行也一起修好</b>。
     */
    @Transient
    private String purchaseOrderNumber;

    /** 读时补齐的供应商名 —— 表里只有 {@code supplierId}，前端从来显示不出供应商。 */
    @Transient
    private String supplierName;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false, length = 32)
    private ReceiveExceptionType exceptionType;

    @NotNull
    @Column(name = "po_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal poQuantity;

    @NotNull
    @Column(name = "received_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal receivedQuantity;

    @NotNull
    @Column(name = "exception_qty", nullable = false, precision = 15, scale = 4)
    private BigDecimal exceptionQty;

    @Column(name = "unit", length = 20)
    private String unit;

    /** 采购员决策结果，null = 尚未决策 */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 32)
    private ExceptionDecision decision;

    @Column(name = "decision_by")
    private Long decisionBy;

    @Column(name = "decision_at")
    private LocalDateTime decisionAt;

    @Column(name = "decision_notes", columnDefinition = "TEXT")
    private String decisionNotes;

    /**
     * 异常单状态：PENDING = 待决策，RESOLVED = 已处理
     */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    @NotNull
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /**
     * D-6 责任绑定：该异常单的责任人（源采购单的创建人 userId）。
     * 生成异常单时自动从 PurchaseOrder.createdBy 带入。
     * 决策端点要求本人或主管角色（factory_super_admin / procurement_manager）才可决策。
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /**
     * 责任人用户名（展示用，冗余存储避免联表查询）。
     */
    @Column(name = "owner_name", length = 100)
    private String ownerName;

    /**
     * D-BUG3：RETURN_OVER 决策时生成的采购退货单（ReturnOrder）ID。
     * null = 尚未生成退货单。作为幂等键：RETURN_OVER 重复决策时，
     * 若已有 returnOrderId 则不重复创建退货单。
     */
    @Column(name = "return_order_id", length = 191)
    private String returnOrderId;

    /**
     * HIGH (并发幂等): 乐观锁版本号。
     * 防止两个并发 decideException(RETURN_OVER) 都读到 returnOrderId==null + PENDING
     * 各自创建一张退货单 (重复供应商退货)。服务层先对异常单行加悲观锁，后到者读取到
     * 已处理状态并拒绝，不会创建第二张退货单。
     */
    @Version
    @Column(name = "version")
    private Long version;
}
