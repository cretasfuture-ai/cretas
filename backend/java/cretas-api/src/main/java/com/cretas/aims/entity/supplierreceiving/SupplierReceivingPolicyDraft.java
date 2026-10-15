package com.cretas.aims.entity.supplierreceiving;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

@Entity
@Table(name = "supplier_receiving_policy_drafts",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_receiving_draft_idempotency",
                columnNames = {"factory_id", "idempotency_key"}))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierReceivingPolicyDraft extends BaseEntity {
    @Id @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;
    @Column(name = "blueprint_id", length = 100, nullable = false, updatable = false)
    private String blueprintId;
    @Column(name = "blueprint_version", nullable = false, updatable = false)
    private Long blueprintVersion;
    @Column(name = "source_digest", length = 64, nullable = false, updatable = false)
    private String sourceDigest;
    @Column(name = "source_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String sourceSnapshotDigest;
    @Column(name = "desired_digest", length = 64, nullable = false, updatable = false)
    private String desiredDigest;
    @Column(name = "preview_digest", length = 64, nullable = false, updatable = false)
    private String previewDigest;
    @Column(name = "request_digest", length = 64, nullable = false, updatable = false)
    private String requestDigest;
    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;
    @Column(name = "operator_id", updatable = false)
    private Long operatorId;

    /** Typed draft identity; NULL pair preserves historical rows whose actor namespace is unknown. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @jakarta.persistence.Column(name = "actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType actorType;

    @jakarta.persistence.Column(name = "actor_id")
    private Long actorId;
    @Column(name = "status", length = 20, nullable = false)
    private String status;
    @Column(name = "dependency_basis", length = 200, nullable = false, updatable = false)
    private String dependencyBasis;
    @Column(name = "dependency_closure_complete", nullable = false, updatable = false)
    private Boolean dependencyClosureComplete;
    @Column(name = "policy_count", nullable = false, updatable = false)
    private Integer policyCount;
    @Column(name = "qualification_count", nullable = false, updatable = false)
    private Integer qualificationCount;
    @Version @Column(name = "lock_version", nullable = false)
    private Long lockVersion;
}
