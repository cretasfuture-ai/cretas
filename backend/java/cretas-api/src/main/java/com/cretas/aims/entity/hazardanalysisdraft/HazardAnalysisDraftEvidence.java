package com.cretas.aims.entity.hazardanalysisdraft;

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

/** Server-resolved authority evidence; arbitrary customer evidence IDs are not accepted. */
@Entity
@Table(name = "hazard_analysis_draft_evidence", uniqueConstraints = {
        @UniqueConstraint(name = "uk_hazard_analysis_evidence_authority",
                columnNames = {"item_id", "draft_id", "factory_id", "authority_code"})
}, indexes = @Index(name = "idx_hazard_analysis_evidence_parent",
        columnList = "factory_id,draft_id,item_id"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class HazardAnalysisDraftEvidence extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "item_id", length = 36, nullable = false, updatable = false)
    private String itemId;

    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(name = "evidence_type", length = 40, nullable = false, updatable = false)
    private String evidenceType;

    @Column(name = "authority_code", length = 100, nullable = false, updatable = false)
    private String authorityCode;

    @Column(name = "authority_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String authoritySnapshotDigest;
}
