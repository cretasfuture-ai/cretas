package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.DistanceEdgeSource;
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
 * 距离边 — 公里数不伪造 (spec §1/§2 决策 6): {@code DEPOT->store} / {@code store->store}
 * 有向边, 缺边时排线算法必须让计划/车次落 {@code NEEDS_ROUTE_DATA}, 禁止直线/伪造 km。
 *
 * <p>Demo 租户脱敏数据落 {@code source=DEMO_FIXTURE}; 客户真实维护矩阵落
 * {@code CUSTOMER_MAINTAINED}; 未来地图供应商落 {@code MAP_PROVIDER}。
 *
 * <p>Maps to table {@code logistics_distance_edges} (V20261028_01)。同厂内有向边唯一
 * (DB {@code uq_lde_edge}, 部分索引 WHERE deleted_at IS NULL — JPA 侧约束语义略严, 见
 * {@link LogisticsOrderBatch} 类注释同类说明)。
 */
@Entity
@Table(name = "logistics_distance_edges",
        indexes = {
                @Index(name = "idx_lde_factory", columnList = "factory_id")
        })
// 🔴 软删批 3-B (2026-09-07)：补 @SQLDelete，并**摘掉实体级的唯一声明**。
// prod 上这张表的业务唯一键是 `… WHERE deleted_at IS NULL` 的**部分**索引（由 现存迁移 创建），
// 而 JPA 表达不了部分唯一 ⇒ 实体里写 @UniqueConstraint 只会在 H2 建出**全量**键，比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键，症状是测试红而线上是对的
// （#701→#717 就是这么炸出 4 个 error 的）⇒ 唯一键以 prod 的部分索引为准。
// ⚠️ 代价：H2 里这张表没有唯一性保护；行为由 opt-in 的真 PG 契约测试守（见 java-gate-pr 那条车道）。
@SQLDelete(sql = "UPDATE logistics_distance_edges SET deleted_at = NOW() WHERE id = ? AND version = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsDistanceEdge extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (source == null) {
            source = DistanceEdgeSource.DEMO_FIXTURE;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** DEPOT (车场) 或门店 point key — 有向边起点. */
    @Column(name = "from_point_id", length = 64, nullable = false)
    private String fromPointId;

    /** DEPOT (车场) 或门店 point key — 有向边终点. */
    @Column(name = "to_point_id", length = 64, nullable = false)
    private String toPointId;

    @Column(name = "distance_km", nullable = false, precision = 12, scale = 2)
    private BigDecimal distanceKm;

    /** 路径几何 (JSONB, e.g. polyline). Nullable. H2 test 下置 null (同项目既有限制)。 */
    @Type(JsonBinaryType.class)
    @Column(name = "geometry", columnDefinition = "jsonb")
    private List<Map<String, Object>> geometry;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 24, nullable = false)
    @Builder.Default
    private DistanceEdgeSource source = DistanceEdgeSource.DEMO_FIXTURE;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
