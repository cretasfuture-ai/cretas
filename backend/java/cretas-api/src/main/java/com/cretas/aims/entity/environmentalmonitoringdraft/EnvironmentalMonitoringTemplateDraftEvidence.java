package com.cretas.aims.entity.environmentalmonitoringdraft;

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

@Entity
@Table(name = "environmental_monitoring_template_draft_evidence",
        uniqueConstraints = @UniqueConstraint(name = "uk_env_monitoring_evidence_parent_code",
                columnNames = {"draft_id", "factory_id", "authority_code"}),
        indexes = @Index(name = "idx_env_monitoring_evidence_parent",
                columnList = "factory_id,draft_id"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EnvironmentalMonitoringTemplateDraftEvidence extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(name = "authority_code", length = 80, nullable = false, updatable = false) private String authorityCode;
    @Column(name = "catalog_version", length = 80, nullable = false, updatable = false) private String catalogVersion;
    @Column(name = "catalog_digest", length = 64, nullable = false, updatable = false) private String catalogDigest;
    @Column(name = "authority_snapshot_digest", length = 64, nullable = false, updatable = false) private String authoritySnapshotDigest;
}
