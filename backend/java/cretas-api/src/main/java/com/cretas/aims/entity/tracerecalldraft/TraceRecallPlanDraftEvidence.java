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

/** Platform authority evidence only; never another factory's configuration or identity. */
@Entity
@Table(name = "trace_recall_plan_draft_evidence", uniqueConstraints = {
        @UniqueConstraint(name = "uk_trace_recall_evidence_identity",
                columnNames = {"id", "draft_id", "factory_id"}),
        @UniqueConstraint(name = "uk_trace_recall_evidence_code",
                columnNames = {"draft_id", "factory_id", "authority_code"})
}, indexes = @Index(name = "idx_trace_recall_evidence_parent",
        columnList = "factory_id,draft_id"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE trace_recall_plan_draft_evidence SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class TraceRecallPlanDraftEvidence extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(name = "evidence_type", length = 30, nullable = false, updatable = false) private String evidenceType;
    @Column(name = "authority_code", length = 100, nullable = false, updatable = false) private String authorityCode;
    @Column(name = "authority_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String authoritySnapshotDigest;
}
