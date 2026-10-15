package com.cretas.aims.entity.deviationcapadraft;

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

/** One typed trigger and server-derived deviation/CAPA decision rule. */
@Entity
@Table(name = "deviation_capa_template_draft_rules", uniqueConstraints = {
        @UniqueConstraint(name = "uk_deviation_capa_rule_identity",
                columnNames = {"id", "draft_id", "factory_id"}),
        @UniqueConstraint(name = "uk_deviation_capa_rule_ordinal",
                columnNames = {"draft_id", "factory_id", "ordinal"}),
        @UniqueConstraint(name = "uk_deviation_capa_rule_key",
                columnNames = {"draft_id", "factory_id", "rule_key"})
}, indexes = @Index(name = "idx_deviation_capa_rule_parent",
        columnList = "factory_id,draft_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DeviationCapaTemplateDraftRule extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;
    @Column(nullable = false)
    private Integer ordinal;
    @Column(name = "rule_key", length = 64, nullable = false, updatable = false)
    private String ruleKey;

    @Column(name = "critical_control_draft_id", length = 36, nullable = false, updatable = false)
    private String criticalControlDraftId;
    @Column(name = "critical_control_item_id", length = 36, updatable = false)
    private String criticalControlItemId;
    @Column(name = "quality_release_template_draft_id", length = 36,
            nullable = false, updatable = false)
    private String qualityReleaseTemplateDraftId;
    @Column(name = "quality_release_template_item_id", length = 36, updatable = false)
    private String qualityReleaseTemplateItemId;

    @Column(name = "source_type", length = 30, nullable = false, updatable = false)
    private String sourceType;
    @Column(name = "source_item_id", length = 36, nullable = false, updatable = false)
    private String sourceItemId;
    @Column(name = "source_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String sourceSnapshotDigest;
    @Column(name = "trigger_mode", length = 30, nullable = false)
    private String triggerMode;
    @Column(name = "repeat_threshold")
    private Integer repeatThreshold;
    @Column(name = "recurrence_window_days")
    private Integer recurrenceWindowDays;

    @Column(name = "source_severity", length = 20, nullable = false)
    private String sourceSeverity;
    @Column(name = "derived_classification", length = 30, nullable = false)
    private String derivedClassification;
    @Column(name = "immediate_control_action", length = 40, nullable = false)
    private String immediateControlAction;
    @Column(name = "approval_route", length = 50, nullable = false)
    private String approvalRoute;
    @Column(name = "escalation_sla_hours", nullable = false)
    private Integer escalationSlaHours;
    @Column(name = "root_cause_required", nullable = false)
    private Boolean rootCauseRequired;
    @Column(name = "corrective_action_required", nullable = false)
    private Boolean correctiveActionRequired;
    @Column(name = "preventive_action_required", nullable = false)
    private Boolean preventiveActionRequired;
    @Column(name = "effectiveness_review_required", nullable = false)
    private Boolean effectivenessReviewRequired;
    @Column(name = "root_cause_method", length = 30)
    private String rootCauseMethod;
    @Column(name = "effectiveness_review_days")
    private Integer effectivenessReviewDays;
    @Column(name = "correction_template_code", length = 40, nullable = false)
    private String correctionTemplateCode;
    @Column(name = "corrective_action_code", length = 40, nullable = false)
    private String correctiveActionCode;
    @Column(name = "preventive_action_code", length = 40)
    private String preventiveActionCode;
    @Column(name = "effectiveness_metric_code", length = 40)
    private String effectivenessMetricCode;
    @Column(name = "change_reason", length = 500, nullable = false)
    private String changeReason;
}
