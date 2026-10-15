package com.cretas.aims.entity.sanitationcipdraft;

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
@Table(name = "sanitation_cip_template_draft_items",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sanitation_cip_item_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_sanitation_cip_item_parent_ordinal",
                        columnNames = {"draft_id", "factory_id", "ordinal"}),
                @UniqueConstraint(name = "uk_sanitation_cip_item_parent_code",
                        columnNames = {"draft_id", "factory_id", "template_code"})
        }, indexes = @Index(name = "idx_sanitation_cip_item_parent",
                columnList = "factory_id,draft_id,ordinal"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE sanitation_cip_template_draft_items SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SanitationCipTemplateDraftItem extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "template_code", length = 100, nullable = false) private String templateCode;
    @Column(name = "product_code", length = 100, nullable = false) private String productCode;
    @Column(name = "route_node_id", length = 100, nullable = false) private String routeNodeId;
    @Column(name = "route_node_label", length = 200, nullable = false) private String routeNodeLabel;
    @Column(name = "target_type", length = 30, nullable = false) private String targetType;
    @Column(name = "equipment_plan_draft_id", length = 36) private String equipmentPlanDraftId;
    @Column(name = "equipment_id") private Long equipmentId;
    @Column(name = "equipment_plan_entry_ordinal") private Integer equipmentPlanEntryOrdinal;
    @Column(name = "target_snapshot_digest", length = 64, nullable = false) private String targetSnapshotDigest;
    @Column(name = "sanitation_method", length = 30, nullable = false) private String sanitationMethod;
    @Column(name = "chemical_type", length = 40, nullable = false) private String chemicalType;
    @Column(name = "concentration_min", precision = 12, scale = 4) private BigDecimal concentrationMin;
    @Column(name = "concentration_target", precision = 12, scale = 4) private BigDecimal concentrationTarget;
    @Column(name = "concentration_max", precision = 12, scale = 4) private BigDecimal concentrationMax;
    @Column(name = "concentration_unit", length = 20) private String concentrationUnit;
    @Column(name = "temperature_min_c", precision = 8, scale = 3, nullable = false) private BigDecimal temperatureMinC;
    @Column(name = "temperature_target_c", precision = 8, scale = 3, nullable = false) private BigDecimal temperatureTargetC;
    @Column(name = "temperature_max_c", precision = 8, scale = 3, nullable = false) private BigDecimal temperatureMaxC;
    @Column(name = "contact_time_seconds", nullable = false) private Integer contactTimeSeconds;
    @Column(name = "flow_rate_liters_per_minute", precision = 12, scale = 4) private BigDecimal flowRateLitersPerMinute;
    @Column(name = "pre_rinse_seconds", nullable = false) private Integer preRinseSeconds;
    @Column(name = "final_rinse_seconds", nullable = false) private Integer finalRinseSeconds;
    @Column(length = 40, nullable = false) private String frequency;
    @Column(name = "verification_methods", length = 300, nullable = false) private String verificationMethods;
    @Column(name = "responsible_role", length = 50, nullable = false) private String responsibleRole;
    @Column(name = "required_evidence", length = 200, nullable = false) private String requiredEvidence;
    @Column(name = "hold_until_verified", nullable = false) private Boolean holdUntilVerified;
    @Column(name = "change_reason", length = 500, nullable = false) private String changeReason;
}
