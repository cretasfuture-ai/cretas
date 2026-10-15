package com.cretas.aims.entity.supplierreceiving;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

@Entity
@Table(name = "supplier_receiving_policy_draft_items",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_supplier_receiving_item_identity",
                        columnNames = {"draft_id", "factory_id", "supplier_id", "material_ref_type", "material_ref_id"}),
                @UniqueConstraint(name = "uk_supplier_receiving_item_ordinal",
                        columnNames = {"draft_id", "ordinal"})
        })
@org.hibernate.annotations.SQLDelete(sql = "UPDATE supplier_receiving_policy_draft_items SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierReceivingPolicyDraftItem extends BaseEntity {
    @Id @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;
    @Column(name = "ordinal", nullable = false, updatable = false)
    private Integer ordinal;
    @Column(name = "supplier_id", length = 191, nullable = false, updatable = false)
    private String supplierId;
    @Column(name = "material_ref_type", length = 30, nullable = false, updatable = false)
    private String materialRefType;
    @Column(name = "material_ref_id", length = 191, nullable = false, updatable = false)
    private String materialRefId;
    @Column(name = "material_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String materialSnapshotDigest;
    @Column(name = "supplier_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String supplierSnapshotDigest;
    @Column(name = "relationship_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String relationshipSnapshotDigest;
    @Column(name = "acceptance_mode", length = 30, nullable = false, updatable = false)
    private String acceptanceMode;
    @Column(name = "qualification_expiry_policy", length = 40, nullable = false, updatable = false)
    private String qualificationExpiryPolicy;
    @Column(name = "sample_percentage", precision = 7, scale = 4, nullable = false, updatable = false)
    private BigDecimal samplePercentage;
    @Column(name = "min_sample_size", nullable = false, updatable = false)
    private Integer minSampleSize;
    @Column(name = "max_sample_size", nullable = false, updatable = false)
    private Integer maxSampleSize;
    @Column(name = "quarantine_on_failure", nullable = false, updatable = false)
    private Boolean quarantineOnFailure;
    @Column(name = "receiving_evidence_required", nullable = false, updatable = false)
    private Boolean receivingEvidenceRequired;
    @Column(name = "policy_basis", length = 500, nullable = false, updatable = false)
    private String policyBasis;
    @Column(name = "qualification_count", nullable = false, updatable = false)
    private Integer qualificationCount;
}
