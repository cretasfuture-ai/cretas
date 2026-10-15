package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

/**
 * 原材料产品转换率实体类
 * 定义原材料到产品的转换关系和比率
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "materialType", "productType"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
// ⚠️ (factory_id, material_type_id, product_type_id) 的唯一性**故意不在这里声明**。
//    本实体 2026-09-07 补了 @SQLDelete(软删), 而它的建重查是**派生查询**
//    ConversionServiceImpl:67 existsByFactoryIdAndMaterialTypeIdAndProductTypeId ——
//    派生查询受类级 @Where(deleted_at IS NULL) 过滤, **只看得见活行**。
//    若这里仍声明全量 @UniqueConstraint, 软删一条配置之后:
//      应用层查重说「空着」(活行没有) → 放行 → 数据库全量唯一键拒绝 → 用户看到看不懂的约束冲突。
//    ⇒ 判据是「**让数据库唯一键与应用自己的查重口径一致**」, 而 JPA 表达不了部分唯一,
//      所以唯一性由 V20261101_38 的**部分唯一索引** uq_conversion_factory_material_product
//      (WHERE deleted_at IS NULL) 拥有, 实体不再重复声明一份会漂的。
//    ⛔ 不要把 uniqueConstraints 加回来 —— 加回来 H2 会建出一份 prod 没有的全量唯一键(形态 D),
//      而且「删了再建同名」的测试会在 H2 上假红。
//    对照: RawMaterialType 的 (factory_id, code) **保持全量**, 因为它的查重是
//      findCodeConflictIncludingDeleted(原生, 含已删) —— 同一条判据, 相反的结论。
@Table(name = "material_product_conversions",
       indexes = {
           @Index(name = "idx_conversion_factory", columnList = "factory_id"),
           @Index(name = "idx_conversion_material", columnList = "material_type_id"),
           @Index(name = "idx_conversion_product", columnList = "product_type_id")
       }
)
@SQLDelete(sql = "UPDATE material_product_conversions SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class MaterialProductConversion extends BaseEntity {
    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 原材料类型ID
     */
    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    /**
     * 产品类型ID
     */
    @Column(name = "product_type_id", nullable = false, length = 191)
    private String productTypeId;

    /**
     * 转换率 (1单位原材料可生产的产品数量)
     * 例如: 1公斤面粉可生产2公斤面包，转换率为2.0
     */
    @Column(name = "conversion_rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal conversionRate;

    /**
     * 损耗率 (百分比，0-100)
     * 生产过程中的原材料损耗比例
     */
    @Column(name = "wastage_rate", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal wastageRate = BigDecimal.ZERO;

    /**
     * 标准用量 (生产1单位产品需要的原材料数量)
     * 与转换率互为倒数关系
     */
    @Column(name = "standard_usage", precision = 10, scale = 4)
    private BigDecimal standardUsage;

    /**
     * 最小批量 (最小生产批量要求)
     */
    @Column(name = "min_batch_size", precision = 10, scale = 2)
    private BigDecimal minBatchSize;

    /**
     * 最大批量 (最大生产批量限制)
     */
    @Column(name = "max_batch_size", precision = 10, scale = 2)
    private BigDecimal maxBatchSize;

    /**
     * 是否启用
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 备注说明
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * 创建人ID
     */
    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 更新人ID
     */
    @Column(name = "updated_by")
    private Long updatedBy;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    private RawMaterialType materialType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductType productType;

    /**
     * 计算标准用量（基于转换率）并生成ID
     */
    @PrePersist
    @PreUpdate
    public void prePersistUpdate() {
        // 生成UUID作为ID (仅在新建时)
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
        // 计算标准用量
        if (conversionRate != null && conversionRate.compareTo(BigDecimal.ZERO) > 0) {
            // 标准用量 = 1 / 转换率
            this.standardUsage = BigDecimal.ONE.divide(conversionRate, 4, BigDecimal.ROUND_HALF_UP);
        }
    }

    /**
     * 计算考虑损耗后的实际用量
     */
    public BigDecimal calculateActualUsage(BigDecimal quantity) {
        if (standardUsage == null || quantity == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal baseUsage = standardUsage.multiply(quantity);

        // 加上损耗
        if (wastageRate != null && wastageRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal wastage = baseUsage.multiply(wastageRate).divide(new BigDecimal(100));
            return baseUsage.add(wastage);
        }

        return baseUsage;
    }

    // Typed audit namespace for explicit platform assembly writes.
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @jakarta.persistence.Column(name = "created_actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType createdActorType;

    @jakarta.persistence.Column(name = "created_actor_id")
    private Long createdActorId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @jakarta.persistence.Column(name = "updated_actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType updatedActorType;

    @jakarta.persistence.Column(name = "updated_actor_id")
    private Long updatedActorId;

    public void setCreatedActor(com.cretas.aims.security.ActorRef actor) {
        createdActorType = actor == null ? null : actor.type();
        createdActorId = actor == null ? null : actor.id();
        createdBy = actor == null ? null : actor.factoryUserId();
    }

    public void setUpdatedActor(com.cretas.aims.security.ActorRef actor) {
        updatedActorType = actor == null ? null : actor.type();
        updatedActorId = actor == null ? null : actor.id();
        updatedBy = actor == null ? null : actor.factoryUserId();
    }
}
