package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.DriverRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 车辆-司机绑定 (一车 2-3 司机, 白班/晚班/主备)。
 *
 * <p>{@code vehicleId} 引用 {@code vehicles.id}; {@code driverId} 引用
 * {@link LogisticsDriver#getId()}. 两者均以 plain String 列表达 FK (项目惯例)。
 *
 * <p>Maps to table {@code logistics_vehicle_drivers} (V20261028_01)。同车同司机
 * 同角色同班次不重复 (DB {@code uq_lvd_binding}, 部分索引 WHERE deleted_at IS NULL — JPA 侧
 * 约束语义略严, 见 {@link LogisticsOrderBatch} 类注释同类说明)。
 */
@Entity
// 🔴 实体不再声明这张表的业务唯一键 (2026-09-07, FIX-ENTITY-FULL-UNIQUE-VS-PROD-PARTIAL):
// prod 上是 uq_lvd_binding —— `WHERE deleted_at IS NULL` 的**部分**唯一索引; 而 JPA 表达不了部分唯一,
// 实体里的 @UniqueConstraint 只能在 H2 建出**全量**键 ⇒ H2 比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键, 表现为测试红而线上是对的
// (#701 合入后 logistics 三个测试类 4 个 error 就是这么来的, 全部只在 H2 复现)。
// ⇒ 唯一键以 prod 的部分索引为准, 实体侧不再重复声明。
// ⚠️ 代价: H2 里这张表**没有**唯一性保护 —— 与 #712 对 material_product_conversions 的处置一致。
@Table(name = "logistics_vehicle_drivers",
        indexes = {
                @Index(name = "idx_lvd_vehicle", columnList = "vehicle_id"),
                @Index(name = "idx_lvd_driver", columnList = "driver_id")
        })
@SQLDelete(sql = "UPDATE logistics_vehicle_drivers SET deleted_at = NOW() WHERE id = ? AND version = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsVehicleDriver extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (role == null) {
            role = DriverRole.PRIMARY;
        }
        if (priority == null) {
            priority = 0;
        }
        if (active == null) {
            active = true;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** FK → {@code vehicles.id} (plain String, no JPA relation). */
    @Column(name = "vehicle_id", length = 36, nullable = false)
    private String vehicleId;

    /** FK → {@link LogisticsDriver#getId()} (plain String, no JPA relation). */
    @Column(name = "driver_id", length = 36, nullable = false)
    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false)
    @Builder.Default
    private DriverRole role = DriverRole.PRIMARY;

    @Column(name = "shift_start", length = 8)
    private String shiftStart;

    @Column(name = "shift_end", length = 8)
    private String shiftEnd;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
