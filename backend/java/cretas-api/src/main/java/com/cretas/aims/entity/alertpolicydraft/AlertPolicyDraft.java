package com.cretas.aims.entity.alertpolicydraft;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
@Table(name = "alert_policy_drafts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_alert_policy_draft_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_alert_policy_draft_module_pin",
                        columnNames = {"id", "factory_id", "module_config_draft_id"}),
                @UniqueConstraint(name = "uk_alert_policy_draft_idempotency",
                        columnNames = {"factory_id", "idempotency_key"})
        }, indexes = @Index(name = "idx_alert_policy_draft_factory_status",
                columnList = "factory_id,status"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE alert_policy_drafts SET deleted_at = NOW(), lock_version = lock_version + 1 WHERE id = ? AND lock_version = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AlertPolicyDraft extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(name = "blueprint_id", length = 100, nullable = false, updatable = false) private String blueprintId;
    @Column(name = "blueprint_version", nullable = false, updatable = false) private Long blueprintVersion;
    @Column(name = "source_digest", length = 64, nullable = false, updatable = false) private String sourceDigest;
    @Column(name = "source_snapshot_digest", length = 64, nullable = false, updatable = false) private String sourceSnapshotDigest;
    @Column(name = "module_config_draft_id", length = 36, nullable = false, updatable = false) private String moduleConfigDraftId;
    @Column(name = "module_config_desired_digest", length = 64, nullable = false, updatable = false) private String moduleConfigDesiredDigest;
    @Column(name = "module_snapshot_digest", length = 64, nullable = false, updatable = false) private String moduleSnapshotDigest;
    @Column(name = "capability_catalog_version", length = 80, nullable = false, updatable = false) private String capabilityCatalogVersion;
    @Column(name = "capability_catalog_digest", length = 64, nullable = false, updatable = false) private String capabilityCatalogDigest;
    @Column(name = "authority_snapshot_digest", length = 64, nullable = false, updatable = false) private String authoritySnapshotDigest;
    @Column(name = "desired_digest", length = 64, nullable = false, updatable = false) private String desiredDigest;
    @Column(name = "preview_digest", length = 64, nullable = false, updatable = false) private String previewDigest;
    @Column(name = "request_digest", length = 64, nullable = false, updatable = false) private String requestDigest;
    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false) private String idempotencyKey;
    @Column(name = "operator_id", updatable = false) private Long operatorId;

    /** Typed draft identity; NULL pair preserves historical rows whose actor namespace is unknown. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @jakarta.persistence.Column(name = "actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType actorType;

    @jakarta.persistence.Column(name = "actor_id")
    private Long actorId;
    @Column(name = "policy_count", nullable = false) private Integer policyCount;
    @Column(name = "evidence_count", nullable = false) private Integer evidenceCount;
    @Column(name = "dependency_basis", length = 100, nullable = false) private String dependencyBasis;
    @Column(name = "algorithm_version", length = 80, nullable = false) private String algorithmVersion;
    @Column(name = "dependency_closure_complete", nullable = false) private Boolean dependencyClosureComplete;
    @Column(name = "status", length = 20, nullable = false) private String status;
    @Version @Column(name = "lock_version", nullable = false) private Long lockVersion;
}
