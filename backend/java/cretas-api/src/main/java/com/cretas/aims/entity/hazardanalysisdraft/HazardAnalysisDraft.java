package com.cretas.aims.entity.hazardanalysisdraft;

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

/** Blueprint-owned hazard analysis draft; never a live HACCP checkpoint. */
@Entity
@Table(name = "hazard_analysis_drafts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_hazard_analysis_draft_id_factory", columnNames = {"id", "factory_id"}),
        @UniqueConstraint(name = "uk_hazard_analysis_draft_bom_scope",
                columnNames = {"id", "factory_id", "bom_draft_id"}),
        @UniqueConstraint(name = "uk_hazard_analysis_draft_idempotency",
                columnNames = {"factory_id", "idempotency_key"})
}, indexes = @Index(name = "idx_hazard_analysis_draft_factory_status",
        columnList = "factory_id,status"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE hazard_analysis_drafts SET deleted_at = NOW(), lock_version = lock_version + 1 WHERE id = ? AND lock_version = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class HazardAnalysisDraft extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
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

    @Column(name = "route_product_code", length = 100, nullable = false, updatable = false)
    private String routeProductCode;

    @Column(name = "route_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String routeSnapshotDigest;

    @Column(name = "bom_draft_id", length = 36, nullable = false, updatable = false)
    private String bomDraftId;

    @Column(name = "bom_desired_digest", length = 64, nullable = false, updatable = false)
    private String bomDesiredDigest;

    @Column(name = "bom_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String bomSnapshotDigest;

    @Column(name = "capability_catalog_version", length = 64, nullable = false, updatable = false)
    private String capabilityCatalogVersion;

    @Column(name = "capability_catalog_digest", length = 64, nullable = false, updatable = false)
    private String capabilityCatalogDigest;

    @Column(name = "authority_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String authoritySnapshotDigest;

    @Column(name = "risk_algorithm_version", length = 64, nullable = false, updatable = false)
    private String riskAlgorithmVersion;

    @Column(name = "desired_digest", length = 64, nullable = false, updatable = false)
    private String desiredDigest;

    @Column(name = "preview_digest", length = 64, nullable = false, updatable = false)
    private String previewDigest;

    @Column(name = "request_digest", length = 64, nullable = false, updatable = false)
    private String requestDigest;

    @Column(name = "item_count", nullable = false)
    private Integer itemCount;

    @Column(name = "evidence_count", nullable = false)
    private Integer evidenceCount;

    @Column(name = "dependency_basis", length = 100, nullable = false)
    private String dependencyBasis;

    @Column(name = "dependency_closure_complete", nullable = false)
    private Boolean dependencyClosureComplete;

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

    @Column(length = 20, nullable = false)
    private String status;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;
}
