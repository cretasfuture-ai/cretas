package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

/**
 * 超温告警把某个批次转了质检的留痕 —— 食品 DAG 缺口 15。
 *
 * <p>待检状态本身沿用缺口 11 (#1223): 原料 / 在制批次 {@code INSPECTING}, 成品 {@code PENDING_INSPECTION}。
 * 本表只记「哪条告警、转之前什么状态、暴露多久、谁转的」, 不是新的状态机。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "cold_chain_excursion_holds")
@Where(clause = "deleted_at IS NULL")
@SQLDelete(sql = "UPDATE cold_chain_excursion_holds SET deleted_at = NOW() WHERE id = ?")
public class ColdChainExcursionHold extends BaseEntity {

    public static final class TargetType {
        public static final String RAW_MATERIAL_BATCH = "RAW_MATERIAL_BATCH";
        public static final String WIP_MATERIAL_BATCH = "WIP_MATERIAL_BATCH";
        public static final String FINISHED_GOODS = "FINISHED_GOODS";

        private TargetType() {}
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 191)
    private String targetId;

    @Column(name = "batch_number", length = 64)
    private String batchNumber;

    @Column(name = "warehouse_id", length = 64)
    private String warehouseId;

    /** 暴露时长 (分钟); 在库时间不确定的批次为 null. */
    @Column(name = "exposure_minutes")
    private Integer exposureMinutes;

    @Column(name = "previous_status", nullable = false, length = 32)
    private String previousStatus;

    @Column(name = "held_status", nullable = false, length = 32)
    private String heldStatus;

    @Column(name = "held_by", nullable = false)
    private Long heldBy;

    @Column(name = "held_by_name", length = 100)
    private String heldByName;
}
