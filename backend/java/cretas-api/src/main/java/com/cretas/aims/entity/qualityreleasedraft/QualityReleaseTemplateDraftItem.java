package com.cretas.aims.entity.qualityreleasedraft;

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

/** One typed inspection specification inside a quality release template draft. */
@Entity
@Table(name = "quality_release_template_draft_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_quality_release_item_identity",
                columnNames = {"id", "draft_id", "factory_id"}),
        @UniqueConstraint(name = "uk_quality_release_item_ordinal",
                columnNames = {"draft_id", "factory_id", "ordinal"}),
        @UniqueConstraint(name = "uk_quality_release_item_key",
                columnNames = {"draft_id", "factory_id", "spec_key"})
}, indexes = @Index(name = "idx_quality_release_item_parent",
        columnList = "factory_id,draft_id,ordinal"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE quality_release_template_draft_items SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class QualityReleaseTemplateDraftItem extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;
    @Column(nullable = false)
    private Integer ordinal;
    @Column(name = "spec_key", length = 64, nullable = false, updatable = false)
    private String specKey;

    @Column(name = "critical_control_draft_id", length = 36, nullable = false, updatable = false)
    private String criticalControlDraftId;
    @Column(name = "critical_control_item_id", length = 36, updatable = false)
    private String criticalControlItemId;
    @Column(name = "bom_draft_id", length = 36, nullable = false, updatable = false)
    private String bomDraftId;
    @Column(name = "bom_item_id", length = 36, updatable = false)
    private String bomItemId;

    @Column(name = "subject_type", length = 30, nullable = false, updatable = false)
    private String subjectType;
    @Column(name = "subject_ref_type", length = 30, nullable = false, updatable = false)
    private String subjectRefType;
    @Column(name = "subject_ref_id", length = 191, nullable = false, updatable = false)
    private String subjectRefId;
    @Column(name = "subject_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String subjectSnapshotDigest;
    @Column(name = "route_node_id", length = 128, updatable = false)
    private String routeNodeId;
    @Column(name = "route_node_label", length = 200, updatable = false)
    private String routeNodeLabel;

    @Column(name = "item_code", length = 64, nullable = false)
    private String itemCode;
    @Column(name = "item_name", length = 200, nullable = false)
    private String itemName;
    @Column(name = "category", length = 30, nullable = false)
    private String category;
    @Column(name = "value_type", length = 20, nullable = false)
    private String valueType;
    @Column(name = "criterion_kind", length = 30, nullable = false)
    private String criterionKind;
    @Column(name = "lower_value", precision = 19, scale = 6)
    private BigDecimal lowerValue;
    @Column(name = "upper_value", precision = 19, scale = 6)
    private BigDecimal upperValue;
    @Column(name = "expected_text", length = 500)
    private String expectedText;
    @Column(name = "expected_boolean")
    private Boolean expectedBoolean;

    @Column(name = "unit_code", length = 64)
    private String unitCode;
    @Column(name = "unit_dimension", length = 30)
    private String unitDimension;
    @Column(name = "unit_base_code", length = 64)
    private String unitBaseCode;
    @Column(name = "unit_factor_to_base", precision = 30, scale = 12)
    private BigDecimal unitFactorToBase;
    @Column(name = "unit_display_name", length = 100)
    private String unitDisplayName;
    @Column(name = "unit_display_scale")
    private Integer unitDisplayScale;
    @Column(name = "unit_conversion_family", length = 100)
    private String unitConversionFamily;
    @Column(name = "unit_snapshot_digest", length = 64)
    private String unitSnapshotDigest;
    @Column(precision = 19, scale = 6)
    private BigDecimal tolerance;

    @Column(name = "standard_reference", length = 300, nullable = false)
    private String standardReference;
    @Column(name = "inspection_method", length = 1000, nullable = false)
    private String inspectionMethod;
    @Column(name = "sampling_strategy", length = 30, nullable = false)
    private String samplingStrategy;
    @Column(name = "sampling_ratio", precision = 7, scale = 4)
    private BigDecimal samplingRatio;
    @Column(name = "minimum_sample_size", nullable = false)
    private Integer minimumSampleSize;
    @Column(name = "aql_level", precision = 7, scale = 4)
    private BigDecimal aqlLevel;
    @Column(length = 20, nullable = false)
    private String severity;
    @Column(name = "release_effect", length = 40, nullable = false)
    private String releaseEffect;
    @Column(name = "is_required", nullable = false)
    private Boolean required;
    @Column(name = "photo_required_on_failure", nullable = false)
    private Boolean photoRequiredOnFailure;
    @Column(name = "note_required_on_failure", nullable = false)
    private Boolean noteRequiredOnFailure;
    @Column(name = "responsible_role", length = 40, nullable = false)
    private String responsibleRole;
    @Column(name = "record_retention_days", nullable = false)
    private Integer recordRetentionDays;
    @Column(name = "change_reason", length = 500, nullable = false)
    private String changeReason;
}
