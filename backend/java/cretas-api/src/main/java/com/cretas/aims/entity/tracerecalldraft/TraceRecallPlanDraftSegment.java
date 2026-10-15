package com.cretas.aims.entity.tracerecalldraft;

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

/** One deterministic trace-field requirement derived from current-factory draft authority. */
@Entity
@Table(name = "trace_recall_plan_draft_segments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_trace_recall_segment_identity",
                columnNames = {"id", "draft_id", "factory_id"}),
        @UniqueConstraint(name = "uk_trace_recall_segment_ordinal",
                columnNames = {"draft_id", "factory_id", "ordinal"}),
        @UniqueConstraint(name = "uk_trace_recall_segment_key",
                columnNames = {"draft_id", "factory_id", "segment_key"})
}, indexes = @Index(name = "idx_trace_recall_segment_parent",
        columnList = "factory_id,draft_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class TraceRecallPlanDraftSegment extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "segment_key", length = 64, nullable = false, updatable = false)
    private String segmentKey;
    @Column(name = "source_type", length = 30, nullable = false, updatable = false)
    private String sourceType;
    @Column(name = "source_ref_id", length = 191, nullable = false, updatable = false)
    private String sourceRefId;
    @Column(name = "source_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String sourceSnapshotDigest;
    @Column(length = 40, nullable = false) private String stage;
    @Column(name = "trace_direction", length = 20, nullable = false) private String traceDirection;
    @Column(name = "identifier_type", length = 40, nullable = false) private String identifierType;
    @Column(name = "required_link", nullable = false) private Boolean requiredLink;
    @Column(name = "quantity_capture_required", nullable = false) private Boolean quantityCaptureRequired;
    @Column(name = "evidence_required", nullable = false) private Boolean evidenceRequired;
    @Column(name = "completion_sla_minutes", nullable = false) private Integer completionSlaMinutes;
    @Column(name = "evidence_retention_days", nullable = false) private Integer evidenceRetentionDays;
}
