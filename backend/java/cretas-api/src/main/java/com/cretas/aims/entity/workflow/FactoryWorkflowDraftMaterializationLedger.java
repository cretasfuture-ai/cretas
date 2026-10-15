package com.cretas.aims.entity.workflow;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;


/** Durable idempotency/readback ledger for one B24 DRAFT-only operation. */
@Entity
@Table(name = "factory_workflow_draft_materialization_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_factory_workflow_draft_materialization_key",
                columnNames = {"factory_id", "idempotency_key"}))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryWorkflowDraftMaterializationLedger extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "product_type_id", length = 50, nullable = false)
    private String productTypeId;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "request_digest", length = 64, nullable = false)
    private String requestDigest;

    @Column(name = "preview_digest", length = 64, nullable = false)
    private String previewDigest;

    @Column(name = "operation_digest", length = 64, nullable = false)
    private String operationDigest;

    @Column(name = "draft_mode", length = 32, nullable = false)
    private String draftMode;

    @Column(name = "completion_state", length = 20, nullable = false)
    private String completionState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private String resultJson;

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
