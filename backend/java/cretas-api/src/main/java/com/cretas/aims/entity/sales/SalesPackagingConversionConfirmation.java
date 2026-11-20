package com.cretas.aims.entity.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 规格不一致确认换算的留痕 (Q10, Owner 2026-11-09)。
 *
 * <p>成品批的包装规格与发货行规格不一致时, 操作人在分配批次时确认换算 ({@code packagingConversionConfirmed=true})。
 * 每次确认记一行: 谁 (登录身份)、什么时候、哪张发货行、哪个成品批、从什么规格 (成品批) 确认成什么规格 (发货行)。
 *
 * <p>只增不删: 不继承 BaseEntity (那里的 @SQLDelete 是软删), {@link Immutable} + 生命周期回调拒绝改和删;
 * 数据库触发器拒绝 UPDATE / TRUNCATE (V20261109_10)。唯一会删它的是整厂数据删除 (用户依法要求的删除)。
 */
@Entity
@Immutable
@Table(name = "sales_packaging_conversion_confirmations",
        indexes = {
                @Index(name = "idx_spcc_delivery_item", columnList = "factory_id, delivery_item_id"),
                @Index(name = "idx_spcc_batch", columnList = "factory_id, finished_goods_batch_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class SalesPackagingConversionConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** 发货行所属的发货单 (子发运单) id; 认不出时为 null, 不猜。 */
    @Column(name = "delivery_record_id", length = 191)
    private String deliveryRecordId;

    @Column(name = "delivery_item_id", length = 64, nullable = false)
    private String deliveryItemId;

    @Column(name = "finished_goods_batch_id", length = 191, nullable = false)
    private String finishedGoodsBatchId;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    /** 本次确认按发货行单位分配的数量。 */
    @Column(name = "allocated_qty", precision = 18, scale = 4, nullable = false)
    private BigDecimal allocatedQty;

    @Column(name = "unit", length = 20)
    private String unit;

    /** 从什么规格: 成品批的包装规格。 */
    @Column(name = "from_packaging_unit", length = 20, nullable = false)
    private String fromPackagingUnit;

    @Column(name = "from_packaging_base_unit", length = 20, nullable = false)
    private String fromPackagingBaseUnit;

    @Column(name = "from_packaging_factor", precision = 20, scale = 8, nullable = false)
    private BigDecimal fromPackagingFactor;

    /** 确认成什么规格: 发货行的包装规格 (按它出库)。 */
    @Column(name = "to_packaging_unit", length = 20, nullable = false)
    private String toPackagingUnit;

    @Column(name = "to_packaging_base_unit", length = 20, nullable = false)
    private String toPackagingBaseUnit;

    @Column(name = "to_packaging_factor", precision = 20, scale = 8, nullable = false)
    private BigDecimal toPackagingFactor;

    /** 确认人: 取登录身份 (请求上的 userId), 不取请求体。 */
    @Column(name = "confirmed_by", nullable = false)
    private Long confirmedBy;

    @Column(name = "confirmed_at", nullable = false)
    private LocalDateTime confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void refuseUpdate() {
        throw new UnsupportedOperationException("规格换算确认留痕只增不改");
    }

    @PreRemove
    void refuseRemove() {
        throw new UnsupportedOperationException("规格换算确认留痕只增不删");
    }
}
