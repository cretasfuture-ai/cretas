package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.AvailabilityResourceType;
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
 * 按日可用性覆盖 — 标记某司机/车辆在指定日期不可用 (请假/维修) 或当天班次有临时调整
 * (相对 {@link LogisticsDriver#getAvailableFrom()}/{@link LogisticsVehicleProfile#getAvailableFrom()}
 * 等固定班次的例外)。
 *
 * <p><b>诚实默认</b>: 某资源在某天没有覆盖记录 = 当天按其固定资料可用 (行为与本表引入前完全一致)。
 * 只有存在覆盖记录才会改变 {@link com.cretas.aims.logistics.service.routing.LogisticsRoutingService}
 * 排线时喂给算法的车辆/司机列表 (排除不可用 / 覆盖班次)。
 *
 * <p>Maps to table {@code logistics_daily_availability} (V20261028_57)。同厂+同资源类型+同资源+
 * 同日期只允许一条覆盖记录 (DB {@code uq_lda_resource_date}, 部分索引 WHERE deleted_at IS NULL —
 * JPA 侧约束语义略严, 见 {@link LogisticsOrderBatch} 类注释同类说明)。
 *
 * <p>软删除: {@code deleted_at IS NULL} via @Where + @SQLDelete (per {@code BaseEntity} R68 note,
 * @SQLDelete on @MappedSuperclass 不可靠传播, 需在每个子类显式声明, 否则 {@code repository.delete()}
 * 会做物理硬删除, 跟 @Where 过滤 + 部分唯一索引的软删除语义不一致)。
 */
@Entity
// 🔴 实体不再声明这张表的业务唯一键 (2026-09-07, FIX-ENTITY-FULL-UNIQUE-VS-PROD-PARTIAL):
// prod 上是 uq_lda_resource_date —— `WHERE deleted_at IS NULL` 的**部分**唯一索引; 而 JPA 表达不了部分唯一,
// 实体里的 @UniqueConstraint 只能在 H2 建出**全量**键 ⇒ H2 比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键, 表现为测试红而线上是对的
// (#701 合入后 logistics 三个测试类 4 个 error 就是这么来的, 全部只在 H2 复现)。
// ⇒ 唯一键以 prod 的部分索引为准, 实体侧不再重复声明。
// ⚠️ 代价: H2 里这张表**没有**唯一性保护 —— 与 #712 对 material_product_conversions 的处置一致。
@Table(name = "logistics_daily_availability",
        indexes = {
                @Index(name = "idx_lda_factory_date", columnList = "factory_id, avail_date")
        })
@SQLDelete(sql = "UPDATE logistics_daily_availability SET deleted_at = NOW() WHERE id = ? AND version = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsDailyAvailability extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (available == null) {
            available = true;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", length = 16, nullable = false)
    private AvailabilityResourceType resourceType;

    /**
     * FK → {@link LogisticsDriver#getId()} (resourceType=DRIVER) 或 {@code vehicles.id}
     * (resourceType=VEHICLE)。plain String, no JPA relation (项目惯例)。
     */
    @Column(name = "resource_id", length = 36, nullable = false)
    private String resourceId;

    @Column(name = "avail_date", nullable = false)
    private LocalDate availDate;

    /** false = 当天整体不可用 (请假/维修), 该资源当天从排线算法输入中排除。 */
    @Column(name = "available", nullable = false)
    @Builder.Default
    private Boolean available = Boolean.TRUE;

    /** 非空 = 覆盖当天班次 (优先于 {@code LogisticsDriver}/{@code LogisticsVehicleProfile} 的固定班次)。 */
    @Column(name = "shift_start", length = 8)
    private String shiftStart;

    @Column(name = "shift_end", length = 8)
    private String shiftEnd;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
