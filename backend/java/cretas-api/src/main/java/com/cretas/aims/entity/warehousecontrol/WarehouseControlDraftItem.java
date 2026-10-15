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

/** One typed warehouse master-data decision in an isolated DRAFT. */
@Entity
@Table(name = "warehouse_control_draft_items",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_warehouse_control_draft_item_code",
                        columnNames = {"draft_id", "warehouse_code"}),
                @UniqueConstraint(name = "uk_warehouse_control_draft_item_ordinal",
                        columnNames = {"draft_id", "ordinal"})
        })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseControlDraftItem extends BaseEntity {

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

    @Column(name = "target_warehouse_id", length = 64, updatable = false)
    private String targetWarehouseId;

    @Column(name = "warehouse_code", length = 32, nullable = false, updatable = false)
    private String warehouseCode;

    @Column(name = "warehouse_name", length = 100, nullable = false, updatable = false)
    private String warehouseName;

    @Column(name = "warehouse_type", length = 20, nullable = false, updatable = false)
    private String warehouseType;

    @Column(name = "desired_active", nullable = false)
    private Boolean desiredActive;

    @Column(name = "remarks", length = 1000)
    private String remarks;

    @Column(name = "change_reason", length = 500, nullable = false)
    private String changeReason;

    @Column(name = "current_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String currentSnapshotDigest;
}
