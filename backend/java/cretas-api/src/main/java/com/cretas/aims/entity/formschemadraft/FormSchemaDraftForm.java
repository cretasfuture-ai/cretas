package com.cretas.aims.entity.formschemadraft;

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
@Table(name = "form_schema_draft_forms",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_form_schema_form_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_form_schema_form_parent_ordinal",
                        columnNames = {"draft_id", "factory_id", "ordinal"}),
                @UniqueConstraint(name = "uk_form_schema_form_parent_code",
                        columnNames = {"draft_id", "factory_id", "form_code"}),
                @UniqueConstraint(name = "uk_form_schema_form_tenant",
                        columnNames = {"id", "draft_id", "factory_id"})
        }, indexes = @Index(name = "idx_form_schema_form_parent",
                columnList = "factory_id,draft_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FormSchemaDraftForm extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "form_code", length = 60, nullable = false) private String formCode;
    @Column(name = "title", length = 200, nullable = false) private String title;
    @Column(name = "module_id", length = 60, nullable = false) private String moduleId;
    @Column(name = "module_label", length = 120, nullable = false) private String moduleLabel;
    @Column(name = "module_snapshot_digest", length = 64, nullable = false) private String moduleSnapshotDigest;
    @Column(name = "retention_class", length = 40, nullable = false) private String retentionClass;
    @Column(name = "retention_days", nullable = false) private Integer retentionDays;
    @Column(name = "audit_trail_required", nullable = false) private Boolean auditTrailRequired;
    @Column(name = "immutable_after_submit", nullable = false) private Boolean immutableAfterSubmit;
    @Column(name = "responsible_role", length = 40, nullable = false) private String responsibleRole;
    @Column(name = "field_identity_digest", length = 64, nullable = false) private String fieldIdentityDigest;
    @Column(name = "field_count", nullable = false) private Integer fieldCount;
    @Column(name = "change_reason", length = 500, nullable = false) private String changeReason;
}
