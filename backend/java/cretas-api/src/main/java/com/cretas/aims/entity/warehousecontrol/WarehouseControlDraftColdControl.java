package com.cretas.aims.entity.warehousecontrol;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/** Typed cold-chain monitoring control, not a reading, alert or equipment command. */
@Entity
@Table(name = "warehouse_control_draft_cold_controls",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_warehouse_control_draft_cold_code",
                        columnNames = {"draft_id", "equipment_code"}),
                @UniqueConstraint(name = "uk_warehouse_control_draft_cold_ordinal",
                        columnNames = {"draft_id", "ordinal"})
        })
@org.hibernate.annotations.SQLDelete(sql = "UPDATE warehouse_control_draft_cold_controls SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseControlDraftColdControl extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(name = "ordinal", nullable = false, updatable = false)
    private Integer ordinal;

    @Column(name = "operation", length = 10, nullable = false, updatable = false)
    private String operation;

    @Column(name = "target_equipment_id", updatable = false)
    private Long targetEquipmentId;

    @Column(name = "equipment_code", length = 50, nullable = false, updatable = false)
    private String equipmentCode;

    @Column(name = "equipment_name", length = 200, nullable = false, updatable = false)
    private String equipmentName;

    @Column(name = "control_type", length = 30, nullable = false, updatable = false)
    private String controlType;

    @Column(name = "warehouse_code", length = 32, updatable = false)
    private String warehouseCode;

    @Column(name = "target_temp_min_celsius", precision = 7, scale = 2, nullable = false)
    private BigDecimal targetTempMinCelsius;

    @Column(name = "target_temp_max_celsius", precision = 7, scale = 2, nullable = false)
    private BigDecimal targetTempMaxCelsius;

    @Column(name = "tolerance_minutes", nullable = false)
    private Integer toleranceMinutes;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "desired_active", nullable = false)
    private Boolean desiredActive;

    @Column(name = "control_basis", length = 500, nullable = false)
    private String controlBasis;

    @Column(name = "current_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String currentSnapshotDigest;

    @Column(name = "temperature_unit", length = 8, nullable = false, updatable = false)
    private String temperatureUnit;

    @Column(name = "deviation_algorithm", length = 80, nullable = false, updatable = false)
    private String deviationAlgorithm;
}
