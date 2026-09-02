package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/** Purchase-package configuration owned by one supplier-material relationship. */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "supplier_material_purchase_specs",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_material_purchase_spec_name",
                columnNames = {"factory_id", "supplier_material_id", "name"}),
        indexes = @Index(name = "idx_supplier_material_purchase_spec",
                columnList = "factory_id,supplier_material_id,active"))
@Where(clause = "deleted_at IS NULL")
public class SupplierMaterialPurchaseSpec extends BaseEntity {
    @Id @Column(length = 64)
    private String id;
    @PrePersist void assignId() { if (id == null) id = UUID.randomUUID().toString(); }
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;
    @Column(name = "supplier_material_id", nullable = false, length = 64)
    private String supplierMaterialId;
    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(name = "purchase_package_unit", nullable = false, length = 20)
    private String purchasePackageUnit;
    @Column(name = "inventory_base_unit", nullable = false, length = 20)
    private String inventoryBaseUnit;
    @Column(name = "conversion_factor", nullable = false, precision = 24, scale = 12)
    private BigDecimal conversionFactor;
    /** 未税报价 —— <b>派生值</b>, 由服务层从 {@link #taxIncludedQuotedPrice} 按物料税率算出。语义未变。 */
    @PriceSensitive @Column(name = "quoted_price", precision = 18, scale = 6)
    private BigDecimal quotedPrice;

    /**
     * 含税报价 —— <b>人工录入的权威值</b> (19②)。
     *
     * <p>🔴 本列为空而 {@link #quotedPrice} 有值 = 存量基准未知, ⛔ 不猜不回填。
     */
    @PriceSensitive @Column(name = "tax_included_quoted_price", precision = 18, scale = 6)
    private BigDecimal taxIncludedQuotedPrice;
    @Column(nullable = false, length = 8)
    private String currency = "CNY";
    // 🔒 缺陷 16 (2026-09-02): min_order_quantity / lead_time_days 从本实体移除 ——
    //    与供应关系(SupplierMaterial)上的同名字段重复, 且零读者(只有自己的 apply()
    //    setter + 前端两个表单输入)。起订量/交期现在只在供应关系层录入一次。
    //    phase 1 (#480) 摘除映射; phase 2 (V20261101_26) 已 DROP COLUMN —— 数据库列不再存在。
    @Column(name = "is_default", nullable = false)
    private Boolean defaultSpec = Boolean.FALSE;
    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;
    @Version @Column(nullable = false)
    private Long version;
}
