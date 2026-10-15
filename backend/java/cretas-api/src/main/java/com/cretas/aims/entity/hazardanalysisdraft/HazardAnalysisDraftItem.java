package com.cretas.aims.entity.hazardanalysisdraft;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

/** One typed hazard decision bound to an exact Blueprint route node. */
@Entity
@Table(name = "hazard_analysis_draft_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_hazard_analysis_item_identity",
                columnNames = {"id", "draft_id", "factory_id"}),
        @UniqueConstraint(name = "uk_hazard_analysis_item_ordinal",
                columnNames = {"draft_id", "factory_id", "ordinal"}),
        @UniqueConstraint(name = "uk_hazard_analysis_item_key",
                columnNames = {"draft_id", "factory_id", "hazard_key"})
}, indexes = @Index(name = "idx_hazard_analysis_item_parent",
        columnList = "factory_id,draft_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class HazardAnalysisDraftItem extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(name = "hazard_key", length = 64, nullable = false, updatable = false)
    private String hazardKey;

    @Column(name = "route_product_code", length = 100, nullable = false, updatable = false)
    private String routeProductCode;

    @Column(name = "route_node_id", length = 128, nullable = false, updatable = false)
    private String routeNodeId;

    @Column(name = "route_node_label", length = 200, nullable = false, updatable = false)
    private String routeNodeLabel;

    @Column(name = "bom_draft_id", length = 36, nullable = false, updatable = false)
    private String bomDraftId;

    @Column(name = "bom_item_id", length = 36, updatable = false)
    private String bomItemId;

    @Column(name = "material_ref_type", length = 30, nullable = false, updatable = false)
    private String materialRefType;

    @Column(name = "material_ref_id", length = 191, updatable = false)
    private String materialRefId;

    @Column(name = "material_snapshot_digest", length = 64, updatable = false)
    private String materialSnapshotDigest;

    @Column(name = "hazard_type", length = 20, nullable = false)
    private String hazardType;

    @Column(name = "hazard_description", length = 500, nullable = false)
    private String hazardDescription;

    @Column(name = "cause_summary", length = 500, nullable = false)
    private String causeSummary;

    @Column(length = 20, nullable = false)
    private String severity;

    @Column(length = 20, nullable = false)
    private String likelihood;

    @Column(name = "risk_score", nullable = false)
    private Integer riskScore;

    @Column(name = "risk_band", length = 20, nullable = false)
    private String riskBand;

    @Column(name = "is_significant", nullable = false)
    private Boolean significant;

    @Column(name = "control_measure_type", length = 40, nullable = false)
    private String controlMeasureType;

    @Column(name = "preventive_measure", length = 1000, nullable = false)
    private String preventiveMeasure;

    @Column(name = "change_reason", length = 500, nullable = false)
    private String changeReason;
}
