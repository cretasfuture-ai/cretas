package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "supplier_materials",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_material_identity",
                columnNames = {"factory_id", "supplier_id", "material_type_id"}),
        indexes = {
                @Index(name = "idx_supplier_material_supplier", columnList = "factory_id,supplier_id,active"),
                @Index(name = "idx_supplier_material_material", columnList = "factory_id,material_type_id,active")
        })
@Where(clause = "deleted_at IS NULL")
public class SupplierMaterial extends BaseEntity {
    @Id @Column(name = "id", nullable = false, length = 64)
    private String id;
    @PrePersist void assignId() { if (id == null) id = UUID.randomUUID().toString(); }
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;
    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;
    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;
    @Column(name = "supplier_material_code", length = 100)
    private String supplierMaterialCode;
    /**
     * 未税默认采购价 —— <b>派生值</b>, 由服务层从 {@link #taxIncludedDefaultPurchasePrice}
     * 按物料税率算出。
     *
     * <p>⛔ 语义<b>没有</b>随 19② 改变: 它一直是、现在仍然是未税值。四个下游消费点
     * (PurchaseServiceImpl / MaterialPackagingHierarchyServiceImpl /
     * SupplierMaterialPurchaseSpecServiceImpl / resolveEffectivePrice) 因此一行都不用改 ——
     * 「靠不改而正确」比「四处都记得改」强得多。
     */
    @PriceSensitive
    @Column(name = "default_purchase_price", precision = 18, scale = 6)
    private BigDecimal defaultPurchasePrice;

    /**
     * 含税默认采购价 —— <b>人工录入的权威值</b> (19②, owner 2026-09-01 裁定)。
     *
     * <p>owner 原话: 「不可能一开始就知道未税报价……未税报价是不需要去填写的,
     * 只能是自动计算出来」。⇒ 人只填含税, 未税由系统算。
     *
     * <p>🔴 本列为空而 {@link #defaultPurchasePrice} 有值 = <b>存量基准未知</b>
     * (那个值是含税还是未税, 我们不知道)。⛔ 不猜、不回填, 并把这种行排除出自动带价链 ——
     * 一个基准不明的数悄悄给采购单定价, 正是本次改动要消灭的东西。
     * ⛔ 也不为此加 flag 列: 多一个列就多一个会和它描述的两列漂开的东西。
     */
    @PriceSensitive
    @Column(name = "tax_included_default_purchase_price", precision = 18, scale = 6)
    private BigDecimal taxIncludedDefaultPurchasePrice;
    @Column(name = "currency", nullable = false, length = 8)
    @Builder.Default
    private String currency = "CNY";
    @Column(name = "purchase_unit", nullable = false, length = 20)
    private String purchaseUnit;
    @Column(name = "min_order_quantity", precision = 18, scale = 6)
    private BigDecimal minOrderQuantity;
    @Column(name = "lead_time_days")
    private Integer leadTimeDays;
    @Column(name = "preferred", nullable = false)
    @Builder.Default
    private Boolean preferred = Boolean.FALSE;
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;
    @Version @Column(name = "version", nullable = false)
    private Long version;
}
