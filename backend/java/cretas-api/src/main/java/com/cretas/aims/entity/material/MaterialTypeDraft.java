package com.cretas.aims.entity.material;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

/**
 * Module-owned, non-active RawMaterialType draft produced from a confirmed Blueprint ChangeSet.
 *
 * <p>The row is deliberately typed and isolated from {@code raw_material_types}. Persisting it
 * cannot allocate a live material code, create stock, publish, or activate master data.</p>
 */
@Entity
@Table(name = "material_type_drafts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_material_type_draft_idempotency",
                columnNames = {"factory_id", "idempotency_key"}))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialTypeDraft extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(name = "blueprint_id", length = 100, nullable = false, updatable = false)
    private String blueprintId;

    @Column(name = "blueprint_version", nullable = false, updatable = false)
    private Long blueprintVersion;

    @Column(name = "source_digest", length = 64, nullable = false, updatable = false)
    private String sourceDigest;

    @Column(name = "target_material_type_id", length = 191)
    private String targetMaterialTypeId;

    @Column(name = "natural_key", length = 255, nullable = false)
    private String naturalKey;

    @Column(name = "desired_name", length = 200, nullable = false)
    private String desiredName;

    @Column(name = "desired_classification_segment_code", length = 20, nullable = false)
    private String desiredClassificationSegmentCode;

    @Column(name = "desired_primary_category_code", length = 20, nullable = false)
    private String desiredPrimaryCategoryCode;

    @Column(name = "desired_category", length = 100, nullable = false)
    private String desiredCategory;

    @Column(name = "desired_unit", length = 20, nullable = false)
    private String desiredUnit;

    @Column(name = "desired_storage_mode", length = 20)
    private String desiredStorageMode;

    @Column(name = "desired_shelf_life_days")
    private Integer desiredShelfLifeDays;

    @Column(name = "desired_is_byproduct", nullable = false)
    private Boolean desiredIsByproduct;

    @Column(name = "desired_packaging_category", nullable = false)
    private Boolean desiredPackagingCategory;

    @Column(name = "source_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String sourceSnapshotDigest;

    @Column(name = "desired_digest", length = 64, nullable = false, updatable = false)
    private String desiredDigest;

    @Column(name = "preview_digest", length = 64, nullable = false, updatable = false)
    private String previewDigest;

    @Column(name = "request_digest", length = 64, nullable = false, updatable = false)
    private String requestDigest;

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

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;
}
