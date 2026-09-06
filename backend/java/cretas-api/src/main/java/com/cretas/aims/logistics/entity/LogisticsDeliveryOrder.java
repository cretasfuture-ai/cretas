package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.DeliveryExecutionStatus;
import com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus;
import com.cretas.aims.logistics.entity.enums.LocationStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 门店配送订单 — 一批次内一家门店一行。
 *
 * <p>{@code batchId} 引用 {@link LogisticsOrderBatch#getId()}，按项目 field-naming
 * 惯例以 plain String 列表达 FK (不建 JPA 关系，服务层按需 join)。
 *
 * <p>Maps to table {@code logistics_delivery_orders} (V20261028_01)。同批内
 * {@code store_code} 唯一 (DB {@code uq_ldo_batch_store}, 部分索引 WHERE deleted_at IS
 * NULL — JPA 侧约束语义略严, 见 {@link LogisticsOrderBatch} 类注释同类说明)；跨车次唯一归属由
 * {@link LogisticsStop} 的 {@code uq_ls_order_single_trip} 保障。
 */
@Entity
// 🔴 实体不再声明这张表的业务唯一键 (2026-09-07, FIX-ENTITY-FULL-UNIQUE-VS-PROD-PARTIAL):
// prod 上是 uq_ldo_batch_store —— `WHERE deleted_at IS NULL` 的**部分**唯一索引; 而 JPA 表达不了部分唯一,
// 实体里的 @UniqueConstraint 只能在 H2 建出**全量**键 ⇒ H2 比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键, 表现为测试红而线上是对的
// (#701 合入后 logistics 三个测试类 4 个 error 就是这么来的, 全部只在 H2 复现)。
// ⇒ 唯一键以 prod 的部分索引为准, 实体侧不再重复声明。
// ⚠️ 代价: H2 里这张表**没有**唯一性保护 —— 与 #712 对 material_product_conversions 的处置一致。
@Table(name = "logistics_delivery_orders",
        indexes = {
                @Index(name = "idx_ldo_batch", columnList = "batch_id"),
                @Index(name = "idx_ldo_factory", columnList = "factory_id")
        })
@SQLDelete(sql = "UPDATE logistics_delivery_orders SET deleted_at = NOW() WHERE id = ? AND version = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsDeliveryOrder extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (pieces == null) {
            pieces = 0;
        }
        if (boxes == null) {
            boxes = 0;
        }
        if (weightKg == null) {
            weightKg = BigDecimal.ZERO;
        }
        if (volumeCbm == null) {
            volumeCbm = BigDecimal.ZERO;
        }
        if (locationStatus == null) {
            locationStatus = LocationStatus.UNRESOLVED;
        }
        if (status == null) {
            status = DeliveryOrderStatus.IMPORTED;
        }
        if (deliveryStatus == null) {
            deliveryStatus = DeliveryExecutionStatus.PENDING;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** FK → {@link LogisticsOrderBatch#getId()} (plain String, no JPA relation). */
    @Column(name = "batch_id", length = 36, nullable = false)
    private String batchId;

    @Column(name = "store_code", length = 64, nullable = false)
    private String storeCode;

    @Column(name = "store_name", length = 256, nullable = false)
    private String storeName;

    @Column(name = "address", length = 512)
    private String address;

    @Column(name = "area_code", length = 64)
    private String areaCode;

    @Column(name = "pieces", nullable = false)
    @Builder.Default
    private Integer pieces = 0;

    @Column(name = "boxes", nullable = false)
    @Builder.Default
    private Integer boxes = 0;

    @Column(name = "weight_kg", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal weightKg = BigDecimal.ZERO;

    @Column(name = "volume_cbm", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal volumeCbm = BigDecimal.ZERO;

    @Column(name = "delivery_window_start", length = 8)
    private String deliveryWindowStart;

    @Column(name = "delivery_window_end", length = 8)
    private String deliveryWindowEnd;

    @Column(name = "longitude", precision = 11, scale = 7)
    private BigDecimal longitude;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_status", length = 24, nullable = false)
    @Builder.Default
    private LocationStatus locationStatus = LocationStatus.UNRESOLVED;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    @Builder.Default
    private DeliveryOrderStatus status = DeliveryOrderStatus.IMPORTED;

    // ===== 执行态(排线确认后的送达跟踪, 与规划态 status 独立) =====
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 24, nullable = false)
    @Builder.Default
    private DeliveryExecutionStatus deliveryStatus = DeliveryExecutionStatus.PENDING;

    /** 送达时间(标记已送达时写)。 */
    @Column(name = "delivered_at")
    private java.time.LocalDateTime deliveredAt;

    /** 异常原因(STORE_CLOSED/REJECTED/UNREACHABLE/DAMAGED/OTHER)。 */
    @Column(name = "exception_reason", length = 32)
    private String exceptionReason;

    /** 异常处置(RESCHEDULE 明日再送 / REASSIGN 改派 / RETURN 退回 / CANCEL 取消)。 */
    @Column(name = "exception_disposition", length = 32)
    private String exceptionDisposition;

    /** 异常备注(改派目标车次名 / 其他原因说明等)。 */
    @Column(name = "exception_note", length = 500)
    private String exceptionNote;

    @Column(name = "source_row_number")
    private Integer sourceRowNumber;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
