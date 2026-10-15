package com.cretas.aims.entity.workprocess;

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

import java.math.BigDecimal;

/**
 * Isolated, non-active WorkProcess draft produced from a confirmed Blueprint ChangeSet.
 *
 * <p>This table deliberately has no route order, unit, Workflow or runtime execution columns.</p>
 */
@Entity
@Table(name = "work_process_drafts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_work_process_draft_idempotency",
                columnNames = {"factory_id", "idempotency_key"}))
@org.hibernate.annotations.SQLDelete(sql = "UPDATE work_process_drafts SET deleted_at = NOW(), lock_version = lock_version + 1 WHERE id = ? AND lock_version = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class WorkProcessDraft extends BaseEntity {

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

    @Column(name = "target_work_process_id", length = 50)
    private String targetWorkProcessId;

    @Column(name = "natural_key", length = 255, nullable = false)
    private String naturalKey;

    @Column(name = "desired_process_name", length = 100, nullable = false)
    private String desiredProcessName;

    @Column(name = "desired_process_category", length = 50, nullable = false)
    private String desiredProcessCategory;

    @Column(name = "desired_category_resolution", length = 20, nullable = false)
    private String desiredCategoryResolution;

    @Column(name = "desired_create_category", nullable = false)
    private Boolean desiredCreateCategory;

    @Column(name = "desired_description", length = 500)
    private String desiredDescription;

    @Column(name = "desired_estimated_minutes")
    private Integer desiredEstimatedMinutes;

    @Column(name = "desired_needs_input", nullable = false)
    private Boolean desiredNeedsInput;

    @Column(name = "desired_default_output_material_kind", length = 32, nullable = false)
    private String desiredDefaultOutputMaterialKind;

    @Column(name = "desired_standard_yield_min", precision = 6, scale = 4)
    private BigDecimal desiredStandardYieldMin;

    @Column(name = "desired_standard_yield_max", precision = 6, scale = 4)
    private BigDecimal desiredStandardYieldMax;

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
