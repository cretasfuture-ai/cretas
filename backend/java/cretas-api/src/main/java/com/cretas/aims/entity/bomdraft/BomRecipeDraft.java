package com.cretas.aims.entity.bomdraft;

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

/** Blueprint-owned BOM draft. This row is not a live {@code BomRecipe} version. */
@Entity
@Table(name = "bom_recipe_drafts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_bom_recipe_draft_id_factory", columnNames = {"id", "factory_id"}),
        @UniqueConstraint(name = "uk_bom_recipe_draft_idempotency",
                columnNames = {"factory_id", "idempotency_key"})
})
@org.hibernate.annotations.SQLDelete(sql = "UPDATE bom_recipe_drafts SET deleted_at = NOW(), lock_version = lock_version + 1 WHERE id = ? AND lock_version = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BomRecipeDraft extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(name = "blueprint_id", length = 100, nullable = false, updatable = false)
    private String blueprintId;

    @Column(name = "blueprint_version", nullable = false, updatable = false)
    private Long blueprintVersion;

    @Column(name = "source_digest", length = 64, nullable = false, updatable = false)
    private String sourceDigest;

    @Column(name = "source_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String sourceSnapshotDigest;

    @Column(name = "desired_digest", length = 64, nullable = false, updatable = false)
    private String desiredDigest;

    @Column(name = "preview_digest", length = 64, nullable = false, updatable = false)
    private String previewDigest;

    @Column(name = "request_digest", length = 64, nullable = false, updatable = false)
    private String requestDigest;

    @Column(name = "target_product_ref_type", length = 30, nullable = false, updatable = false)
    private String targetProductRefType;

    @Column(name = "target_product_ref_id", length = 191, nullable = false, updatable = false)
    private String targetProductRefId;

    @Column(name = "target_product_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String targetProductSnapshotDigest;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private Long workflowId;

    @Column(name = "workflow_revision_id", nullable = false, updatable = false)
    private Long workflowRevisionId;

    @Column(name = "workflow_revision_hash", length = 64, nullable = false, updatable = false)
    private String workflowRevisionHash;

    @Column(name = "workflow_definition_version", nullable = false, updatable = false)
    private Integer workflowDefinitionVersion;

    @Column(name = "workflow_schema_version", nullable = false, updatable = false)
    private Integer workflowSchemaVersion;

    @Column(name = "workflow_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String workflowSnapshotDigest;

    @Column(name = "packaging_dependency_type", length = 30, nullable = false, updatable = false)
    private String packagingDependencyType;

    @Column(name = "packaging_draft_id", length = 36, updatable = false)
    private String packagingDraftId;

    @Column(name = "packaging_draft_digest", length = 64, updatable = false)
    private String packagingDraftDigest;

    @Column(name = "output_quantity", precision = 20, scale = 8, nullable = false)
    private BigDecimal outputQuantity;

    @Column(name = "output_unit", length = 20, nullable = false)
    private String outputUnit;

    @Column(name = "net_content_quantity", precision = 20, scale = 8)
    private BigDecimal netContentQuantity;

    @Column(name = "net_content_unit", length = 20)
    private String netContentUnit;

    @Column(name = "item_count", nullable = false)
    private Integer itemCount;

    @Column(name = "substitute_count", nullable = false)
    private Integer substituteCount;

    @Column(name = "dependency_basis", length = 120, nullable = false)
    private String dependencyBasis;

    @Column(name = "dependency_closure_complete", nullable = false)
    private Boolean dependencyClosureComplete;

    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "operator_id", updatable = false)
    private Long operatorId;

    @Column(length = 20, nullable = false)
    private String status;

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
