package com.cretas.aims.entity.tracerecalldraft;

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

/** Blueprint-owned preparedness plan; never a live trace query or recall event. */
@Entity
@Table(name = "trace_recall_plan_drafts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_trace_recall_plan_id_factory",
                columnNames = {"id", "factory_id"}),
        @UniqueConstraint(name = "uk_trace_recall_plan_idempotency",
                columnNames = {"factory_id", "idempotency_key"})
}, indexes = @Index(name = "idx_trace_recall_plan_factory_status",
        columnList = "factory_id,status"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TraceRecallPlanDraft extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false)
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

    @Column(name = "deviation_capa_template_draft_id", length = 36, nullable = false, updatable = false)
    private String deviationCapaTemplateDraftId;
    @Column(name = "deviation_capa_desired_digest", length = 64, nullable = false, updatable = false)
    private String deviationCapaDesiredDigest;
    @Column(name = "deviation_capa_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String deviationCapaSnapshotDigest;
    @Column(name = "quality_release_template_draft_id", length = 36, nullable = false, updatable = false)
    private String qualityReleaseTemplateDraftId;
    @Column(name = "quality_release_desired_digest", length = 64, nullable = false, updatable = false)
    private String qualityReleaseDesiredDigest;
    @Column(name = "quality_release_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String qualityReleaseSnapshotDigest;
    @Column(name = "bom_draft_id", length = 36, nullable = false, updatable = false)
    private String bomDraftId;
    @Column(name = "bom_desired_digest", length = 64, nullable = false, updatable = false)
    private String bomDesiredDigest;
    @Column(name = "bom_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String bomSnapshotDigest;
    @Column(name = "warehouse_control_draft_id", length = 36, nullable = false, updatable = false)
    private String warehouseControlDraftId;
    @Column(name = "warehouse_desired_digest", length = 64, nullable = false, updatable = false)
    private String warehouseDesiredDigest;
    @Column(name = "warehouse_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String warehouseSnapshotDigest;
    @Column(name = "route_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String routeSnapshotDigest;

    @Column(name = "capability_catalog_version", length = 64, nullable = false, updatable = false)
    private String capabilityCatalogVersion;
    @Column(name = "capability_catalog_digest", length = 64, nullable = false, updatable = false)
    private String capabilityCatalogDigest;
    @Column(name = "authority_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String authoritySnapshotDigest;
    @Column(name = "plan_algorithm_version", length = 64, nullable = false, updatable = false)
    private String planAlgorithmVersion;

    @Column(name = "trace_direction", length = 20, nullable = false)
    private String traceDirection;
    @Column(name = "max_traversal_depth", nullable = false)
    private Integer maxTraversalDepth;
    @Column(name = "max_traversal_nodes", nullable = false)
    private Integer maxTraversalNodes;
    @Column(name = "trace_completion_minutes", nullable = false)
    private Integer traceCompletionMinutes;
    @Column(name = "mass_balance_tolerance_basis_points", nullable = false)
    private Integer massBalanceToleranceBasisPoints;
    @Column(name = "mock_recall_frequency_days", nullable = false)
    private Integer mockRecallFrequencyDays;
    @Column(name = "evidence_retention_days", nullable = false)
    private Integer evidenceRetentionDays;
    @Column(name = "escalation_route", length = 120, nullable = false)
    private String escalationRoute;
    @Column(name = "policy_change_reason", length = 500, nullable = false)
    private String policyChangeReason;

    @Column(name = "desired_digest", length = 64, nullable = false, updatable = false)
    private String desiredDigest;
    @Column(name = "preview_digest", length = 64, nullable = false, updatable = false)
    private String previewDigest;
    @Column(name = "request_digest", length = 64, nullable = false, updatable = false)
    private String requestDigest;
    @Column(name = "segment_count", nullable = false)
    private Integer segmentCount;
    @Column(name = "evidence_count", nullable = false)
    private Integer evidenceCount;
    @Column(name = "dependency_basis", length = 180, nullable = false)
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
    @Version @Column(name = "lock_version", nullable = false)
    private Long lockVersion;
}
