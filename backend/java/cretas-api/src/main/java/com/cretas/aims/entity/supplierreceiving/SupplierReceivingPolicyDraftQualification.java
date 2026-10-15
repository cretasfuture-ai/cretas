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

@Entity
@Table(name = "supplier_receiving_policy_draft_qualifications",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_supplier_receiving_qualification",
                        columnNames = {"item_id", "factory_id", "qualification_type"}),
                @UniqueConstraint(name = "uk_supplier_receiving_qualification_ordinal",
                        columnNames = {"item_id", "ordinal"})
        })
@org.hibernate.annotations.SQLDelete(sql = "UPDATE supplier_receiving_policy_draft_qualifications SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierReceivingPolicyDraftQualification extends BaseEntity {
    @Id @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;
    @Column(name = "item_id", length = 36, nullable = false, updatable = false)
    private String itemId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;
    @Column(name = "ordinal", nullable = false, updatable = false)
    private Integer ordinal;
    @Column(name = "qualification_type", length = 40, nullable = false, updatable = false)
    private String qualificationType;
    @Column(name = "current_evidence_digest", length = 64, nullable = false, updatable = false)
    private String currentEvidenceDigest;
}
