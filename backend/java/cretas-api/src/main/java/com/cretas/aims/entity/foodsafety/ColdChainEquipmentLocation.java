package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;

/**
 * 冷链设备 ↔ 仓库 (factory_warehouses) 的对应关系, 带生效时段 —— 食品 DAG 缺口 15「温度关联批次」。
 *
 * <p>{@code [effectiveFrom, effectiveTo)}; {@code effectiveTo = null} 表示至今仍在这个库。
 * 设备换库 = 关掉当前时段 + 开一条新时段, ⛔ 不改写已关的时段: 历史告警要按<b>当时</b>的库位算受影响批次。
 *
 * <p>粒度到仓库 (批次只有 warehouse_id 是结构化的; storage_location 是自由文本, 拿它匹配会把批次漏掉)。
 * 同仓但不在这台设备里的批次也会被列出, 由质检评估 —— 宁可多列, 不漏列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "cold_chain_equipment_locations")
@Where(clause = "deleted_at IS NULL")
public class ColdChainEquipmentLocation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 关联 {@link ColdChainEquipment#getId()}. */
    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    /** 关联 factory_warehouses.id. */
    @Column(name = "warehouse_id", nullable = false, length = 64)
    private String warehouseId;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    /** null = 至今仍在这个库. */
    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "created_by")
    private Long createdBy;
}
