package com.cretas.aims.entity.criticalcontroldraft;

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

import java.math.BigDecimal;

/** One typed CCP/OPRP/PRP decision and its complete criterion/plan. */
@Entity
@Table(name = "critical_control_draft_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_critical_control_item_identity",
                columnNames = {"id", "draft_id", "factory_id"}),
        @UniqueConstraint(name = "uk_critical_control_item_ordinal",
                columnNames = {"draft_id", "factory_id", "ordinal"}),
        @UniqueConstraint(name = "uk_critical_control_item_key",
                columnNames = {"draft_id", "factory_id", "control_key"})
}, indexes = @Index(name = "idx_critical_control_item_parent",
        columnList = "factory_id,draft_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CriticalControlDraftItem extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;
    @Column(name = "hazard_draft_id", length = 36, nullable = false, updatable = false)
    private String hazardDraftId;
    @Column(name = "hazard_item_id", length = 36, nullable = false, updatable = false)
    private String hazardItemId;
    @Column(nullable = false)
    private Integer ordinal;
    @Column(name = "control_key", length = 64, nullable = false, updatable = false)
    private String controlKey;

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
    @Column(name = "hazard_key", length = 64, nullable = false, updatable = false)
    private String hazardKey;
    @Column(name = "hazard_type", length = 20, nullable = false, updatable = false)
    private String hazardType;
    @Column(name = "risk_score", nullable = false, updatable = false)
    private Integer riskScore;
    @Column(name = "risk_band", length = 20, nullable = false, updatable = false)
    private String riskBand;
    @Column(name = "is_significant", nullable = false, updatable = false)
    private Boolean significant;

    @Column(name = "control_classification", length = 20, nullable = false)
    private String controlClassification;
    @Column(name = "criterion_type", length = 30, nullable = false)
    private String criterionType;
    @Column(name = "decision_algorithm_version", length = 64, nullable = false)
    private String decisionAlgorithmVersion;
    @Column(name = "control_measure_at_step", nullable = false)
    private Boolean controlMeasureAtStep;
    @Column(name = "step_designed_to_control", nullable = false)
    private Boolean stepDesignedToControl;
    @Column(name = "hazard_may_increase", nullable = false)
    private Boolean hazardMayIncrease;
    @Column(name = "later_step_controls", nullable = false)
    private Boolean laterStepControls;
    @Column(name = "operational_prerequisite_adequate", nullable = false)
    private Boolean operationalPrerequisiteAdequate;

    @Column(name = "parameter_code", length = 64, nullable = false)
    private String parameterCode;
    @Column(name = "parameter_name", length = 200, nullable = false)
    private String parameterName;
    @Column(name = "parameter_type", length = 30, nullable = false)
    private String parameterType;
    @Column(name = "limit_kind", length = 20, nullable = false)
    private String limitKind;
    @Column(name = "lower_limit", precision = 19, scale = 6)
    private BigDecimal lowerLimit;
    @Column(name = "upper_limit", precision = 19, scale = 6)
    private BigDecimal upperLimit;
    @Column(name = "lower_inclusive", nullable = false)
    private Boolean lowerInclusive;
    @Column(name = "upper_inclusive", nullable = false)
    private Boolean upperInclusive;
    @Column(name = "unit_code", length = 64, nullable = false)
    private String unitCode;
    @Column(name = "unit_dimension", length = 30, nullable = false)
    private String unitDimension;
    @Column(name = "unit_base_code", length = 64, nullable = false)
    private String unitBaseCode;
    @Column(name = "unit_factor_to_base", precision = 30, scale = 12)
    private BigDecimal unitFactorToBase;
    @Column(name = "unit_display_name", length = 100, nullable = false)
    private String unitDisplayName;
    @Column(name = "unit_display_scale", nullable = false)
    private Integer unitDisplayScale;
    @Column(name = "unit_conversion_family", length = 100, nullable = false)
    private String unitConversionFamily;
    @Column(name = "unit_snapshot_digest", length = 64, nullable = false)
    private String unitSnapshotDigest;

    @Column(name = "monitoring_method", length = 40, nullable = false)
    private String monitoringMethod;
    @Column(name = "monitoring_frequency_interval", nullable = false)
    private Integer monitoringFrequencyInterval;
    @Column(name = "monitoring_frequency_unit", length = 30, nullable = false)
    private String monitoringFrequencyUnit;
    @Column(name = "responsible_role", length = 40, nullable = false)
    private String responsibleRole;
    @Column(name = "monitoring_procedure", length = 1000, nullable = false)
    private String monitoringProcedure;
    @Column(name = "deviation_disposition", length = 40, nullable = false)
    private String deviationDisposition;
    @Column(name = "corrective_action", length = 1000, nullable = false)
    private String correctiveAction;
    @Column(name = "verification_method", length = 40, nullable = false)
    private String verificationMethod;
    @Column(name = "verification_frequency_interval", nullable = false)
    private Integer verificationFrequencyInterval;
    @Column(name = "verification_frequency_unit", length = 30, nullable = false)
    private String verificationFrequencyUnit;
    @Column(name = "verification_procedure", length = 1000, nullable = false)
    private String verificationProcedure;
    @Column(name = "record_retention_days", nullable = false)
    private Integer recordRetentionDays;
    @Column(name = "change_reason", length = 500, nullable = false)
    private String changeReason;
}
