package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.OwnershipType;
import com.cretas.aims.logistics.entity.enums.TemperatureMode;
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
 * 物流车属性 — 与现有 {@code vehicles.id} (see {@link com.cretas.aims.entity.Vehicle})
 * 1:1 关联, 不改 {@code Vehicle.capacity} (kg 载重语义)。物流方数/来源/区域/班次落此表
 * (spec §1, §2 决策 5)。
 *
 * <p>Maps to table {@code logistics_vehicle_profiles} (V20261028_01)。{@code vehicle_id}
 * 唯一 (DB {@code uq_lvp_vehicle}, 部分索引 WHERE deleted_at IS NULL — JPA 侧约束语义略严,
 * 见 {@link LogisticsOrderBatch} 类注释同类说明)。
 */
@Entity
@Table(name = "logistics_vehicle_profiles",
        indexes = {
                @Index(name = "idx_lvp_factory", columnList = "factory_id")
        })
// 🔴 软删批 3-B (2026-09-07)：补 @SQLDelete，并**摘掉实体级的唯一声明**。
// prod 上这张表的业务唯一键是 `… WHERE deleted_at IS NULL` 的**部分**索引（由 现存迁移 创建），
// 而 JPA 表达不了部分唯一 ⇒ 实体里写 @UniqueConstraint 只会在 H2 建出**全量**键，比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键，症状是测试红而线上是对的
// （#701→#717 就是这么炸出 4 个 error 的）⇒ 唯一键以 prod 的部分索引为准。
// ⚠️ 代价：H2 里这张表没有唯一性保护；行为由 opt-in 的真 PG 契约测试守（见 java-gate-pr 那条车道）。
@SQLDelete(sql = "UPDATE logistics_vehicle_profiles SET deleted_at = NOW() WHERE id = ? AND version = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsVehicleProfile extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (capacityCbm == null) {
            capacityCbm = BigDecimal.ZERO;
        }
        if (maxWeightKg == null) {
            maxWeightKg = BigDecimal.ZERO;
        }
        if (source == null) {
            source = OwnershipType.OWNED;
        }
        if (temperatureMode == null) {
            temperatureMode = TemperatureMode.DUAL_TEMP;
        }
        if (active == null) {
            active = true;
        }
    }

    /** FK → {@code vehicles.id} (plain String, no JPA relation). Unique per DB {@code uq_lvp_vehicle}. */
    @Column(name = "vehicle_id", length = 36, nullable = false)
    private String vehicleId;

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    @Column(name = "capacity_cbm", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal capacityCbm = BigDecimal.ZERO;

    @Column(name = "max_weight_kg", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal maxWeightKg = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    @Builder.Default
    private OwnershipType source = OwnershipType.OWNED;

    @Column(name = "body_type", length = 64)
    private String bodyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "temperature_mode", length = 24, nullable = false)
    @Builder.Default
    private TemperatureMode temperatureMode = TemperatureMode.DUAL_TEMP;

    @Column(name = "service_areas", length = 512)
    private String serviceAreas;

    @Column(name = "available_from", length = 8)
    private String availableFrom;

    @Column(name = "available_to", length = 8)
    private String availableTo;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
