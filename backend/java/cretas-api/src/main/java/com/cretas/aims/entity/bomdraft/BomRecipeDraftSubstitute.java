package com.cretas.aims.entity.bomdraft;

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

import java.math.BigDecimal;

/** Typed, draft-only substitute candidate. It never selects a runtime material. */
@Entity
@Table(name = "bom_recipe_draft_substitutes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_bom_draft_substitute_priority",
                columnNames = {"item_id", "draft_id", "factory_id", "priority"}),
        @UniqueConstraint(name = "uk_bom_draft_substitute_candidate",
                columnNames = {"item_id", "draft_id", "factory_id", "candidate_ref_type", "candidate_ref_id"})
}, indexes = @Index(name = "idx_bom_draft_substitute_parent",
        columnList = "factory_id,draft_id,item_id,priority"))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE bom_recipe_draft_substitutes SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BomRecipeDraftSubstitute extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "item_id", length = 36, nullable = false, updatable = false)
    private String itemId;

    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(nullable = false)
    private Integer priority;

    @Column(name = "candidate_ref_type", length = 30, nullable = false, updatable = false)
    private String candidateRefType;

    @Column(name = "candidate_ref_id", length = 191, nullable = false, updatable = false)
    private String candidateRefId;

    @Column(name = "candidate_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String candidateSnapshotDigest;

    @Column(name = "conversion_basis", length = 50, nullable = false, updatable = false)
    private String conversionBasis;

    @Column(name = "conversion_ratio", precision = 20, scale = 8, nullable = false)
    private BigDecimal conversionRatio;

    @Column(name = "max_share_percent", precision = 7, scale = 4, nullable = false)
    private BigDecimal maxSharePercent;

    @Column(name = "reason", length = 500, nullable = false)
    private String reason;
}
