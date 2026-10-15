package com.cretas.aims.entity.environmentalmonitoringdraft;

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
@Table(name = "environmental_monitoring_template_draft_points",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_env_monitoring_point_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_env_monitoring_point_parent_ordinal",
                        columnNames = {"draft_id", "factory_id", "ordinal"}),
                @UniqueConstraint(name = "uk_env_monitoring_point_parent_code",
                        columnNames = {"draft_id", "factory_id", "point_code"})
        }, indexes = @Index(name = "idx_env_monitoring_point_parent",
                columnList = "factory_id,draft_id,ordinal"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE environmental_monitoring_template_draft_points SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EnvironmentalMonitoringTemplateDraftPoint extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "point_code", length = 100, nullable = false) private String pointCode;
    @Column(name = "sanitation_cip_draft_id", length = 36, nullable = false) private String sanitationCipDraftId;
    @Column(name = "sanitation_item_ordinal", nullable = false) private Integer sanitationItemOrdinal;
    @Column(name = "sanitation_template_code", length = 100, nullable = false) private String sanitationTemplateCode;
    @Column(name = "hygienic_zone_draft_id", length = 36, nullable = false) private String hygienicZoneDraftId;
    @Column(name = "zone_code", length = 40, nullable = false) private String zoneCode;
    @Column(name = "zone_name", length = 120, nullable = false) private String zoneName;
    @Column(name = "zone_hygiene_level", length = 30, nullable = false) private String zoneHygieneLevel;
    @Column(name = "product_code", length = 100, nullable = false) private String productCode;
    @Column(name = "route_node_id", length = 100, nullable = false) private String routeNodeId;
    @Column(name = "route_node_label", length = 200, nullable = false) private String routeNodeLabel;
    @Column(name = "target_snapshot_digest", length = 64, nullable = false) private String targetSnapshotDigest;
    @Column(name = "contact_class", length = 40, nullable = false) private String contactClass;
    @Column(name = "sample_medium", length = 30, nullable = false) private String sampleMedium;
    @Column(name = "target_organism", length = 40, nullable = false) private String targetOrganism;
    @Column(name = "sampling_timing", length = 30, nullable = false) private String samplingTiming;
    @Column(name = "sampling_frequency", length = 30, nullable = false) private String samplingFrequency;
    @Column(name = "sample_size_count", nullable = false) private Integer sampleSizeCount;
    @Column(name = "result_unit", length = 30, nullable = false) private String resultUnit;
    @Column(name = "alert_threshold", precision = 12, scale = 4) private BigDecimal alertThreshold;
    @Column(name = "action_threshold", precision = 12, scale = 4) private BigDecimal actionThreshold;
    @Column(name = "result_hold_hours", nullable = false) private Integer resultHoldHours;
    @Column(name = "responsible_role", length = 40, nullable = false) private String responsibleRole;
    @Column(name = "required_evidence", length = 200, nullable = false) private String requiredEvidence;
    @Column(name = "pathogen_target", nullable = false) private Boolean pathogenTarget;
    @Column(name = "response_level", length = 60, nullable = false) private String responseLevel;
    @Column(name = "escalation_role", length = 40, nullable = false) private String escalationRole;
    @Column(name = "vector_sampling_required", nullable = false) private Boolean vectorSamplingRequired;
    @Column(name = "resample_required", nullable = false) private Boolean resampleRequired;
    @Column(name = "change_reason", length = 500, nullable = false) private String changeReason;
}
