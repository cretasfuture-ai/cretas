package com.cretas.aims.entity.purchase;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 核价单 — 供应商报价记录 (P-NUCLEAR-1).
 *
 * <p>1 个 InquiryQuote (核价单) ←→ N 个 InquiryQuoteSupplierPrice (供应商报价).
 * Buyer 可对比所有供应商报价后选定最优.
 *
 * <p>UniqueConstraint(inquiry_quote_id, supplier_id) 保证一个供应商
 * 对一个询价单只能有一条报价 (重复提交则 UPDATE — 由 service 层判断).
 *
 * @author Cretas Team
 * @since 2026-05-17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"inquiryQuote", "supplier"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "inquiry_quote_supplier_prices",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"inquiry_quote_id", "supplier_id"})
        },
        indexes = {
                @Index(name = "idx_iqsp_quote", columnList = "inquiry_quote_id"),
                @Index(name = "idx_iqsp_supplier", columnList = "supplier_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class InquiryQuoteSupplierPrice extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotBlank(message = "核价单 ID 不能为空")
    @Column(name = "inquiry_quote_id", nullable = false, length = 191)
    private String inquiryQuoteId;

    @NotBlank(message = "供应商 ID 不能为空")
    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;

    /** 供应商名称 (Formula 查询, 列表/对比展示用 — 防呆 R2 context) */
    @Formula("(SELECT s.name FROM suppliers s WHERE s.id = supplier_id)")
    private String supplierName;

    @PriceSensitive(permission = PriceSensitive.PURCHASE)
    @NotNull(message = "报价不能为空")
    @Positive(message = "报价必须 > 0")
    @Column(name = "unit_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /**
     * 税率 (百分比, e.g. 13 表示 13%)。
     *
     * <p>NULL = 税率未知。⛔ 不默认 0: 未知税率 ≠ 0%, 比价时这条报价标为不可比。
     */
    @PriceSensitive(permission = PriceSensitive.PURCHASE)
    @Column(name = "tax_rate", precision = 5, scale = 2)
    private BigDecimal taxRate;

    /** 报价币种 (e.g. CNY)。NULL = 未注明, 比价时不可比。 */
    @Column(name = "currency", length = 8)
    private String currency;

    /** 报价的计价单位。NULL = 未注明; 与询价单位不同则不可比 (不做计数↔重量换算)。 */
    @Column(name = "quoted_unit", length = 20)
    private String quotedUnit;

    /** 报价对应的规格。询价单写了规格时, 报价规格必须一致才可比。 */
    @Column(name = "specification", length = 200)
    private String specification;

    /** 本次报价的起订量 (计价单位下)。NULL 时比价回落到供应关系上维护的起订量。 */
    @Column(name = "min_order_quantity", precision = 18, scale = 6)
    private BigDecimal minOrderQuantity;

    /** 价格生效日。生效日之前不能选定; BOM 影响估算以它为准。 */
    @Column(name = "price_effective_date")
    private LocalDate priceEffectiveDate;

    /** 报价有效期截止 */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    /** 报价提交时间 */
    @Column(name = "quoted_at", nullable = false)
    private LocalDateTime quotedAt = LocalDateTime.now();

    /** 预计交货天数 (可选) */
    @Column(name = "delivery_days")
    private Integer deliveryDays;

    @Column(name = "remark", length = 500)
    private String remark;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_quote_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private InquiryQuote inquiryQuote;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private Supplier supplier;
}
