package com.cretas.aims.entity.emergencydefensedraft;

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

@Entity
@Table(name = "emergency_defense_plan_draft_items",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_emergency_defense_item_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_emergency_defense_item_parent_ordinal",
                        columnNames = {"draft_id", "factory_id", "ordinal"}),
                @UniqueConstraint(name = "uk_emergency_defense_item_parent_code",
                        columnNames = {"draft_id", "factory_id", "plan_code"})
        }, indexes = @Index(name = "idx_emergency_defense_item_parent",
                columnList = "factory_id,draft_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EmergencyDefensePlanDraftItem extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "plan_code", length = 60, nullable = false) private String planCode;
    @Column(name = "title", length = 200, nullable = false) private String title;
    @Column(name = "scenario_type", length = 40, nullable = false) private String scenarioType;
    @Column(name = "impact_tier", length = 20, nullable = false) private String impactTier;
    @Column(name = "detection_alert_policy_code", length = 60, nullable = false) private String detectionAlertPolicyCode;
    @Column(name = "detection_alert_severity", length = 20, nullable = false) private String detectionAlertSeverity;
    @Column(name = "detection_alert_snapshot_digest", length = 64, nullable = false) private String detectionAlertSnapshotDigest;
    @Column(name = "trace_recall_segment_key", length = 200) private String traceRecallSegmentKey;
    @Column(name = "containment_actions", length = 300, nullable = false) private String containmentActions;
    @Column(name = "responsible_role", length = 40, nullable = false) private String responsibleRole;
    @Column(name = "required_evidence", length = 300, nullable = false) private String requiredEvidence;
    @Column(name = "drill_frequency_days") private Integer drillFrequencyDays;
    @Column(name = "food_safety_impacting", nullable = false) private Boolean foodSafetyImpacting;
    @Column(name = "trace_link_required", nullable = false) private Boolean traceLinkRequired;
    @Column(name = "drill_required", nullable = false) private Boolean drillRequired;
    @Column(name = "notification_tier", length = 30, nullable = false) private String notificationTier;
    @Column(name = "containment_sla_minutes", nullable = false) private Integer containmentSlaMinutes;
    @Column(name = "change_reason", length = 500, nullable = false) private String changeReason;
}
