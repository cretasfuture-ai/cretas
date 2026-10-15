package com.cretas.aims.entity.allergenlabeldraft;

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
@Table(name = "allergen_label_draft_changeovers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_allergen_label_changeover_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_allergen_label_changeover_parent_ordinal",
                        columnNames = {"draft_id", "factory_id", "ordinal"}),
                @UniqueConstraint(name = "uk_allergen_label_changeover_parent_identity",
                        columnNames = {"draft_id", "factory_id", "product_code", "route_node_id",
                                "allergen"})
        }, indexes = @Index(name = "idx_allergen_label_changeover_parent",
                columnList = "factory_id,draft_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AllergenLabelDraftChangeover extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "changeover_code", length = 100, nullable = false) private String changeoverCode;
    @Column(name = "product_code", length = 100, nullable = false) private String productCode;
    @Column(name = "route_node_id", length = 100, nullable = false) private String routeNodeId;
    @Column(name = "route_node_label", length = 200, nullable = false) private String routeNodeLabel;
    @Column(name = "route_snapshot_digest", length = 64, nullable = false) private String routeSnapshotDigest;
    @Column(name = "allergen", length = 40, nullable = false) private String allergen;
    @Column(name = "changeover_method", length = 40, nullable = false) private String changeoverMethod;
    @Column(name = "verifications", length = 300, nullable = false) private String verifications;
    @Column(name = "responsible_role", length = 40, nullable = false) private String responsibleRole;
    @Column(name = "required_evidence", length = 200, nullable = false) private String requiredEvidence;
    @Column(name = "hold_until_verified", nullable = false) private Boolean holdUntilVerified;
    @Column(name = "objective_verification", nullable = false) private Boolean objectiveVerification;
    @Column(name = "sanitation_cip_draft_id", length = 36) private String sanitationCipDraftId;
    @Column(name = "sanitation_template_code", length = 100) private String sanitationTemplateCode;
    @Column(name = "sanitation_item_ordinal") private Integer sanitationItemOrdinal;
    @Column(name = "change_reason", length = 500, nullable = false) private String changeReason;
}
