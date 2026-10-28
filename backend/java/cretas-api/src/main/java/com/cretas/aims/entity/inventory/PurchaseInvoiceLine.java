package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * 采购发票行（S5-05/06/07 逐行三单匹配）
 *
 * <p>每行指向它所开的采购单行（必填）和有效收货行（可选）。累计开票量按采购单行 /
 * 收货行汇总这些行得出，超出「有效收货 − 采购退货」即拒绝。
 * {@code quantity} 的单位与采购单行的 {@code unit} 相同。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_invoice_lines",
        indexes = {
                @Index(name = "idx_pil_invoice", columnList = "invoice_id"),
                @Index(name = "idx_pil_factory_po", columnList = "factory_id,purchase_order_id"),
                @Index(name = "idx_pil_po_item", columnList = "purchase_order_item_id")
        }
)
@SQLDelete(sql = "UPDATE purchase_invoice_lines SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class PurchaseInvoiceLine extends BaseEntity {

    public static final String MATCHED = "MATCHED";
    public static final String MATERIAL_MISMATCH = "MATERIAL_MISMATCH";
    public static final String PRICE_MISMATCH = "PRICE_MISMATCH";
    public static final String PRICE_UNKNOWN = "PRICE_UNKNOWN";
    public static final String MATERIAL_UNKNOWN = "MATERIAL_UNKNOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "invoice_id", nullable = false, length = 191)
    private String invoiceId;

    @Column(name = "purchase_order_id", nullable = false, length = 191)
    private String purchaseOrderId;

    @Column(name = "purchase_order_item_id", nullable = false)
    private Long purchaseOrderItemId;

    /** 可选：本行开的是哪一条有效收货行 */
    @Column(name = "receive_item_id")
    private Long receiveItemId;

    /** 发票上写的物料；为空表示发票未单列物料，按采购单行物料核对 */
    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    /** 采购单行单位快照 */
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @PriceSensitive
    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /** 发票行含税金额 */
    @PriceSensitive
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** 按采购单行含税单价 × 本行数量算出的应开金额；采购单行无价格时为空 */
    @PriceSensitive
    @Column(name = "expected_amount", precision = 12, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "match_status", nullable = false, length = 32)
    private String matchStatus;

    @Column(name = "match_note", length = 500)
    private String matchNote;
}
