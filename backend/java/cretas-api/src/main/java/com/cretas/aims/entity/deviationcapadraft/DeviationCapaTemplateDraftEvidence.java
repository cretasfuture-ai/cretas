package com.cretas.aims.entity.deviationcapadraft;

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

/** Platform catalog authority pinned to one deviation/CAPA draft rule. */
@Entity
@Table(name = "deviation_capa_template_draft_evidence", uniqueConstraints = {
        @UniqueConstraint(name = "uk_deviation_capa_evidence_identity",
                columnNames = {"id", "rule_id", "draft_id", "factory_id"}),
        @UniqueConstraint(name = "uk_deviation_capa_evidence_code",
                columnNames = {"rule_id", "draft_id", "factory_id", "authority_code"})
}, indexes = @Index(name = "idx_deviation_capa_evidence_parent",
        columnList = "factory_id,draft_id,rule_id"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE deviation_capa_template_draft_evidence SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DeviationCapaTemplateDraftEvidence extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "rule_id", length = 36, nullable = false, updatable = false)
    private String ruleId;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;
    @Column(name = "evidence_type", length = 30, nullable = false, updatable = false)
    private String evidenceType;
    @Column(name = "authority_code", length = 100, nullable = false, updatable = false)
    private String authorityCode;
    @Column(name = "authority_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String authoritySnapshotDigest;
}
