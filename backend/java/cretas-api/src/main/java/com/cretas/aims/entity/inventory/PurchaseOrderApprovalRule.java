package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 采购订单审核规则 — Sprint2-J P-FIN-1.
 *
 * <p>per-factory 可配置阈值, 替代 PurchaseServiceImpl 中硬编码的
 * PRICE_ALERT_THRESHOLD = 10. approveOrder 评估时:
 * <ul>
 *   <li>任一行 priceAlert = true (varianceFromBom/Avg 绝对值 > priceVarianceThreshold) →
 *       触发 PENDING_FINANCE_REVIEW</li>
 *   <li>order.totalAmount > amountThreshold → 触发 PENDING_FINANCE_REVIEW</li>
 * </ul>
 *
 * <p>多条同 factory + enabled=true 时按 createdAt 取最新一条 (allow ops 灰度).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
// 🔴 实体不再声明这张表的业务唯一键 (2026-09-07, FIX-ENTITY-FULL-UNIQUE-VS-PROD-PARTIAL):
// prod 上是 uk_poar_factory_name_active —— `WHERE deleted_at IS NULL` 的**部分**唯一索引; 而 JPA 表达不了部分唯一,
// 实体里的 @UniqueConstraint 只能在 H2 建出**全量**键 ⇒ H2 比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键, 表现为测试红而线上是对的
// (#701 合入后 logistics 三个测试类 4 个 error 就是这么来的, 全部只在 H2 复现)。
// ⇒ 唯一键以 prod 的部分索引为准, 实体侧不再重复声明。
// ⚠️ 代价: H2 里这张表**没有**唯一性保护 —— 与 #712 对 material_product_conversions 的处置一致。
@Table(name = "purchase_order_approval_rules",
        indexes = {
                @Index(name = "idx_poar_factory_enabled", columnList = "factory_id, enabled")
        }
)
@SQLDelete(sql = "UPDATE purchase_order_approval_rules SET deleted_at = NOW() WHERE id = ? AND version = ?")
@Where(clause = "deleted_at IS NULL")
public class PurchaseOrderApprovalRule extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "uuid")
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    /** 价格偏差阈值百分比 (默认 10.00 = 10%). 当前价 vs BOM/移动均 偏差绝对值超过此值 → priceAlert. */
    @Column(name = "price_variance_threshold", nullable = false, precision = 5, scale = 2)
    private BigDecimal priceVarianceThreshold = new BigDecimal("10.00");

    /** 总金额阈值 (null = 不参与判断). 订单 totalAmount > 此值 → 触发财务审核. */
    @Column(name = "amount_threshold", precision = 18, scale = 2)
    private BigDecimal amountThreshold;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * AUD-4 P1 JPA 乐观锁 version (mirror Canvas Phase A pattern). 由 Flyway 默认 0 NOT NULL.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
