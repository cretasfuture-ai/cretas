package com.cretas.aims.entity.promotion;

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
 * One promotion of a master-data DRAFT row (product / material / process) into its live table.
 *
 * <p>The DRAFT row itself is never touched: its status is pinned to {@code DRAFT} by CHECK and its
 * status / lock version / updated-at feed the digests of downstream drafts. This row is the only
 * trace a promotion leaves besides the live row it created. Table: V20261103_41.</p>
 *
 * <p>Lives in cretas-api with its only writer, so no other deployable app scans an entity whose
 * table it may not have.</p>
 */
@Entity
@Table(name = "master_data_draft_promotions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_master_data_draft_promotion_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_master_data_draft_promotion_idempotency",
                        columnNames = {"factory_id", "idempotency_key"})})
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class MasterDataDraftPromotion extends BaseEntity {

    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_PROMOTED = "PROMOTED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String ACTION_CREATE = "CREATE";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(name = "capability_id", length = 20, nullable = false, updatable = false)
    private String capabilityId;

    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;

    @Column(name = "blueprint_id", length = 100, nullable = false, updatable = false)
    private String blueprintId;

    @Column(name = "blueprint_version", nullable = false, updatable = false)
    private Long blueprintVersion;

    @Column(name = "source_digest", length = 64, nullable = false, updatable = false)
    private String sourceDigest;

    @Column(name = "draft_desired_digest", length = 64, nullable = false, updatable = false)
    private String draftDesiredDigest;

    @Column(name = "draft_lock_version", nullable = false, updatable = false)
    private Long draftLockVersion;

    @Column(name = "action", length = 20, nullable = false, updatable = false)
    private String action;

    @Column(name = "natural_key", length = 255, nullable = false, updatable = false)
    private String naturalKey;

    @Column(name = "live_target_id", length = 191)
    private String liveTargetId;

    @Column(name = "promotion_preview_digest", length = 64, nullable = false, updatable = false)
    private String promotionPreviewDigest;

    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "operator_id", updatable = false)
    private Long operatorId;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "live_readback_digest", length = 64)
    private String liveReadbackDigest;

    @Column(name = "failure_code", length = 80)
    private String failureCode;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    /** NULL type/id together means historical identity is unknown. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType actorType;

    @Column(name = "actor_id")
    private Long actorId;

    public void setActor(com.cretas.aims.security.ActorRef actor) {
        java.util.Objects.requireNonNull(actor, "Actor identity is required");
        this.actorType = actor.type();
        this.actorId = actor.id();
        this.operatorId = actor.factoryUserId();
    }
}
