package com.cretas.aims.entity.allergenlabeldraft;

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
@Table(name = "allergen_label_draft_declarations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_allergen_label_declaration_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_allergen_label_declaration_parent_ordinal",
                        columnNames = {"draft_id", "factory_id", "ordinal"}),
                @UniqueConstraint(name = "uk_allergen_label_declaration_parent_identity",
                        columnNames = {"draft_id", "factory_id", "subject_kind", "target_id",
                                "allergen"})
        }, indexes = @Index(name = "idx_allergen_label_declaration_parent",
                columnList = "factory_id,draft_id,ordinal"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE allergen_label_draft_declarations SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AllergenLabelDraftDeclaration extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "declaration_code", length = 100, nullable = false) private String declarationCode;
    @Column(name = "subject_kind", length = 20, nullable = false) private String subjectKind;
    @Column(name = "target_id", length = 64, nullable = false) private String targetId;
    @Column(name = "target_snapshot_digest", length = 64, nullable = false) private String targetSnapshotDigest;
    @Column(name = "subject_code", length = 100, nullable = false) private String subjectCode;
    @Column(name = "subject_name", length = 200, nullable = false) private String subjectName;
    @Column(name = "allergen", length = 40, nullable = false) private String allergen;
    @Column(name = "presence_kind", length = 30) private String presenceKind;
    @Column(name = "evidence_source", length = 30) private String evidenceSource;
    @Column(name = "label_statement", length = 30) private String labelStatement;
    @Column(name = "label_version_code", length = 60) private String labelVersionCode;
    @Column(name = "declaration_basis", length = 30) private String declarationBasis;
    @Column(name = "changeover_required", nullable = false) private Boolean changeoverRequired;
    @Column(name = "change_reason", length = 500, nullable = false) private String changeReason;
}
