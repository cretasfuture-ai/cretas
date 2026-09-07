package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 停靠点 — 车次内门店顺序 (人工可调序/跨车次移动)。
 *
 * <p>一订单只属一条有效车次, 由 DB {@code uq_ls_order_single_trip} 保障
 * (spec §3: 防重复分配/漏排)。同车次内顺序号唯一 (DB {@code uq_ls_trip_seq})。两者均为
 * 部分索引 (WHERE deleted_at IS NULL) — JPA 侧约束语义略严, 见 {@link LogisticsOrderBatch}
 * 类注释同类说明。
 *
 * <p>Maps to table {@code logistics_stops} (V20261028_01)。
 */
@Entity
// 🔴 实体不再声明这张表的业务唯一键 (2026-09-07, FIX-ENTITY-FULL-UNIQUE-VS-PROD-PARTIAL):
// prod 上是 uq_ls_order_single_trip / uq_ls_trip_seq —— `WHERE deleted_at IS NULL` 的**部分**唯一索引; 而 JPA 表达不了部分唯一,
// 实体里的 @UniqueConstraint 只能在 H2 建出**全量**键 ⇒ H2 比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键, 表现为测试红而线上是对的
// (#701 合入后 logistics 三个测试类 4 个 error 就是这么来的, 全部只在 H2 复现)。
// ⇒ 唯一键以 prod 的部分索引为准, 实体侧不再重复声明。
// ⚠️ 代价: H2 里这张表**没有**唯一性保护 —— 与 #712 对 material_product_conversions 的处置一致。
@Table(name = "logistics_stops",
        indexes = {
                @Index(name = "idx_ls_trip", columnList = "trip_id")
        })
@SQLDelete(sql = "UPDATE logistics_stops SET deleted_at = NOW() WHERE id = ? AND version = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsStop extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (legDistanceKm == null) {
            legDistanceKm = BigDecimal.ZERO;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** FK → {@link LogisticsTrip#getId()} (plain String, no JPA relation). */
    @Column(name = "trip_id", length = 36, nullable = false)
    private String tripId;

    /** FK → {@link LogisticsDeliveryOrder#getId()} (plain String, no JPA relation). Unique per DB {@code uq_ls_order_single_trip}. */
    @Column(name = "delivery_order_id", length = 36, nullable = false)
    private String deliveryOrderId;

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    /** 与上一停靠点(或车场 DEPOT)间的公里数 — 不伪造, 缺边由 trip 落 NEEDS_ROUTE_DATA。 */
    @Column(name = "leg_distance_km", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal legDistanceKm = BigDecimal.ZERO;

    @Column(name = "arrival_window_start", length = 8)
    private String arrivalWindowStart;

    @Column(name = "arrival_window_end", length = 8)
    private String arrivalWindowEnd;

    /**
     * 停靠点几何 (JSONB). Nullable — Phase 3 排线算法写入。H2 test 下置 null
     * (同项目 jsonb round-trip 既有限制)。
     */
    @Type(JsonBinaryType.class)
    @Column(name = "geometry", columnDefinition = "jsonb")
    private List<Map<String, Object>> geometry;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
