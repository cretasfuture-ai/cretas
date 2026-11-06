package com.cretas.aims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable server-owned snapshot issued by urgent-insert impact analysis.
 *
 * <p>The snapshot deliberately stores the product output unit separately from
 * the coarse capacity unit.  No client supplied unit is used as a replacement
 * for the product's real planned unit.</p>
 */
@Entity
@Immutable
@Table(name = "insert_slot_previews",
        indexes = {
                @Index(name = "idx_insert_slot_preview_factory_slot", columnList = "factory_id, slot_id"),
                @Index(name = "idx_insert_slot_preview_factory_created", columnList = "factory_id, created_at")
        },
        uniqueConstraints = {
                @jakarta.persistence.UniqueConstraint(
                        name = "uk_insert_slot_preview_candidate",
                        columnNames = {"factory_id", "slot_id", "candidate_number"})
        })
@Where(clause = "deleted_at IS NULL")
@SQLDelete(sql = "UPDATE insert_slot_previews SET deleted_at = NOW() WHERE id = ?")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InsertSlotPreview extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36, updatable = false)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50, updatable = false)
    private String factoryId;

    @Column(name = "slot_id", nullable = false, length = 36, updatable = false)
    private String slotId;

    @Column(name = "product_type_id", nullable = false, length = 191, updatable = false)
    private String productTypeId;

    @Column(name = "preview_quantity", nullable = false, precision = 20, scale = 6, updatable = false)
    private BigDecimal previewQuantity;

    /** PIECE / KG / BATCH: capacity-check unit only. */
    @Column(name = "quantity_unit", nullable = false, length = 20, updatable = false)
    private String quantityUnit;

    /** Exact ProductType.unit snapshot used by ProductionPlan.plannedUnit. */
    @Column(name = "planned_unit", nullable = false, length = 32, updatable = false)
    private String plannedUnit;

    @Column(name = "production_line_id", nullable = false, length = 36, updatable = false)
    private String productionLineId;

    @Column(name = "window_start", nullable = false, updatable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false, updatable = false)
    private LocalDateTime windowEnd;

    @Column(name = "slot_updated_at", updatable = false)
    private LocalDateTime slotUpdatedAt;

    @Column(name = "impact_level", nullable = false, length = 20, updatable = false)
    private String impactLevel;

    @Column(name = "requires_approval", nullable = false, updatable = false)
    private Boolean requiresApproval;

    @Column(name = "affected_plan_count", nullable = false, updatable = false)
    private Integer affectedPlanCount;

    @Column(name = "candidate_number", nullable = false, updatable = false)
    private Integer candidateNumber;

}
