package com.cretas.aims.entity.alertpolicydraft;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

@Entity
@Table(name = "alert_policy_draft_items",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_alert_policy_item_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_alert_policy_item_parent_ordinal",
                        columnNames = {"draft_id", "factory_id", "ordinal"}),
                @UniqueConstraint(name = "uk_alert_policy_item_parent_code",
                        columnNames = {"draft_id", "factory_id", "policy_code"})
        }, indexes = @Index(name = "idx_alert_policy_item_parent",
                columnList = "factory_id,draft_id,ordinal"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE alert_policy_draft_items SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AlertPolicyDraftItem extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "policy_code", length = 60, nullable = false) private String policyCode;
    @Column(name = "title", length = 200, nullable = false) private String title;
    @Column(name = "module_id", length = 60, nullable = false) private String moduleId;
    @Column(name = "module_label", length = 120, nullable = false) private String moduleLabel;
    @Column(name = "module_snapshot_digest", length = 64, nullable = false) private String moduleSnapshotDigest;
    @Column(name = "metric", length = 40, nullable = false) private String metric;
    @Column(name = "metric_dimension", length = 30, nullable = false) private String metricDimension;
    @Column(name = "canonical_unit", length = 20, nullable = false) private String canonicalUnit;
    @Column(name = "comparison", length = 30, nullable = false) private String comparison;
    @Column(name = "threshold_value", precision = 18, scale = 6, nullable = false) private BigDecimal thresholdValue;
    @Column(name = "upper_threshold_value", precision = 18, scale = 6) private BigDecimal upperThresholdValue;
    @Column(name = "evaluation_window_minutes", nullable = false) private Integer evaluationWindowMinutes;
    @Column(name = "consecutive_breaches", nullable = false) private Integer consecutiveBreaches;
    @Column(name = "severity", length = 20, nullable = false) private String severity;
    @Column(name = "recipient_roles", length = 200, nullable = false) private String recipientRoles;
    @Column(name = "suppression_window_minutes", nullable = false) private Integer suppressionWindowMinutes;
    @Column(name = "escalation_role", length = 40, nullable = false) private String escalationRole;
    @Column(name = "acknowledge_sla_minutes", nullable = false) private Integer acknowledgeSlaMinutes;
    @Column(name = "auto_escalate_after_minutes", nullable = false) private Integer autoEscalateAfterMinutes;
    @Column(name = "two_person_acknowledge_required", nullable = false) private Boolean twoPersonAcknowledgeRequired;
    @Column(name = "change_reason", length = 500, nullable = false) private String changeReason;
}
