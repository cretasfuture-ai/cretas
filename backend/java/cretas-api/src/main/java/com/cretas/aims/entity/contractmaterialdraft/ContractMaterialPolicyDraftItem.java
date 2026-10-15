package com.cretas.aims.entity.contractmaterialdraft;

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
@Table(name = "contract_material_policy_draft_items",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_contract_material_item_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_contract_material_item_parent_ordinal",
                        columnNames = {"draft_id", "factory_id", "ordinal"}),
                @UniqueConstraint(name = "uk_contract_material_item_parent_identity",
                        columnNames = {"draft_id", "factory_id", "customer_scope_code",
                                "supplier_id", "material_ref_type", "material_ref_id"})
        }, indexes = @Index(name = "idx_contract_material_item_parent",
                columnList = "factory_id,draft_id,ordinal"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE contract_material_policy_draft_items SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ContractMaterialPolicyDraftItem extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "policy_code", length = 100, nullable = false) private String policyCode;
    @Column(name = "customer_scope_code", length = 60, nullable = false) private String customerScopeCode;
    @Column(name = "supplier_receiving_draft_id", length = 36, nullable = false) private String supplierReceivingDraftId;
    @Column(name = "supplier_id", length = 191, nullable = false) private String supplierId;
    @Column(name = "material_ref_type", length = 30, nullable = false) private String materialRefType;
    @Column(name = "material_ref_id", length = 191, nullable = false) private String materialRefId;
    @Column(name = "supplier_receiving_item_ordinal", nullable = false) private Integer supplierReceivingItemOrdinal;
    @Column(name = "material_snapshot_digest", length = 64, nullable = false) private String materialSnapshotDigest;
    @Column(name = "supplier_snapshot_digest", length = 64, nullable = false) private String supplierSnapshotDigest;
    @Column(name = "acceptance_mode", length = 30, nullable = false) private String acceptanceMode;
    @Column(name = "ownership_type", length = 40, nullable = false) private String ownershipType;
    @Column(name = "segregation_requirement", length = 40, nullable = false) private String segregationRequirement;
    @Column(name = "issuance_policy", length = 40, nullable = false) private String issuancePolicy;
    @Column(name = "return_policy", length = 40, nullable = false) private String returnPolicy;
    @Column(name = "settlement_policy", length = 40, nullable = false) private String settlementPolicy;
    @Column(name = "residual_tolerance_percent", precision = 7, scale = 4, nullable = false) private BigDecimal residualTolerancePercent;
    @Column(name = "retention_days", nullable = false) private Integer retentionDays;
    @Column(name = "responsible_role", length = 40, nullable = false) private String responsibleRole;
    @Column(name = "required_evidence", length = 200, nullable = false) private String requiredEvidence;
    @Column(name = "accounting_treatment", length = 40, nullable = false) private String accountingTreatment;
    @Column(name = "ap_posting_allowed", nullable = false) private Boolean apPostingAllowed;
    @Column(name = "inventory_valuation_included", nullable = false) private Boolean inventoryValuationIncluded;
    @Column(name = "purchase_order_allowed", nullable = false) private Boolean purchaseOrderAllowed;
    @Column(name = "cross_customer_issue_allowed", nullable = false) private Boolean crossCustomerIssueAllowed;
    @Column(name = "return_on_completion_required", nullable = false) private Boolean returnOnCompletionRequired;
    @Column(name = "change_reason", length = 500, nullable = false) private String changeReason;
}
