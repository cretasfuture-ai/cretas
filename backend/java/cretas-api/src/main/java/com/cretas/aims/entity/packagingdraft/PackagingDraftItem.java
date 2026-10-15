package com.cretas.aims.entity.packagingdraft;

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

/** One typed level in a packaging hierarchy owned by {@link PackagingDraft}. */
@Entity
@Table(name = "packaging_draft_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_packaging_item_parent_ordinal",
                columnNames = {"draft_id", "factory_id", "ordinal"}),
        @UniqueConstraint(name = "uk_packaging_item_target_role",
                columnNames = {"draft_id", "factory_id", "target_kind", "target_id", "packaging_role"})
}, indexes = @Index(name = "idx_packaging_item_parent",
        columnList = "factory_id,draft_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PackagingDraftItem extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(name = "target_kind", length = 30, nullable = false, updatable = false)
    private String targetKind;

    @Column(name = "target_id", length = 191, nullable = false, updatable = false)
    private String targetId;

    @Column(name = "target_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String targetSnapshotDigest;

    @Column(name = "packaging_role", length = 30, nullable = false)
    private String packagingRole;

    @Column(name = "spec_name", length = 80, nullable = false)
    private String specName;

    @Column(name = "package_unit", length = 20, nullable = false)
    private String packageUnit;

    @Column(name = "contained_unit", length = 20, nullable = false)
    private String containedUnit;

    @Column(name = "units_per_package", precision = 20, scale = 8, nullable = false)
    private BigDecimal unitsPerPackage;

    @Column(name = "net_content_quantity", precision = 20, scale = 8)
    private BigDecimal netContentQuantity;

    @Column(name = "net_content_unit", length = 20)
    private String netContentUnit;

    @Column(name = "is_default", nullable = false)
    private Boolean defaultSpec;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "label_reference_type", length = 30, nullable = false)
    private String labelReferenceType;

    @Column(name = "label_reference_id", length = 200)
    private String labelReferenceId;

    @Column(name = "label_reference_digest", length = 64)
    private String labelReferenceDigest;

    @Column(name = "change_reason", length = 500, nullable = false)
    private String changeReason;
}
