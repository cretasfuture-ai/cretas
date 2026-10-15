package com.cretas.aims.entity.blueprintactivation;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * One activation execution of one idempotency key, in one factory.
 *
 * <p>The row is inserted in state {@code CLAIMED} <em>before</em> any module command runs, and the
 * unique constraint on {@code (factory_id, idempotency_key)} is what makes concurrent execution of
 * the same key impossible. A row left in {@code CLAIMED} means an execution started and did not
 * finish — that is a state worth seeing, not one to hide behind a retry.</p>
 */
@Entity
@Table(name = "factory_activation_executions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_factory_activation_execution_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_factory_activation_execution_idempotency",
                        columnNames = {"factory_id", "idempotency_key"})
        }, indexes = @Index(name = "idx_factory_activation_execution_factory_status",
                columnList = "factory_id,status"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryActivationExecution extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false) private String idempotencyKey;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(name = "request_digest", length = 64, nullable = false, updatable = false) private String requestDigest;
    @Column(name = "plan_id", length = 120) private String planId;
    @Column(name = "schema_version", length = 80) private String schemaVersion;
    @Column(name = "status", length = 20, nullable = false) private String status;
    @Column(name = "requested_by") private Long requestedBy;
    @Column(name = "approved_by") private Long approvedBy;
    @Column(name = "executed_by", updatable = false) private Long executedBy;
    @Column(name = "successful_count") private Integer successfulCount;
    @Column(name = "failed_count") private Integer failedCount;
    @Column(name = "blocked_count") private Integer blockedCount;
    @Column(name = "business_write_count") private Integer businessWriteCount;
    @Column(name = "recovery_summary", length = 500) private String recoverySummary;
    @Version @Column(name = "lock_version", nullable = false) private Long lockVersion;

    /** NULL type/id together means historical identity is unknown. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "requested_actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType requestedActorType;

    @Column(name = "requested_actor_id")
    private Long requestedActorId;

    public void setRequestedActor(com.cretas.aims.security.ActorRef actor) {
        java.util.Objects.requireNonNull(actor, "Actor identity is required");
        this.requestedActorType = actor.type();
        this.requestedActorId = actor.id();
        this.requestedBy = actor.factoryUserId();
    }

    /** NULL type/id together means historical identity is unknown. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "approved_actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType approvedActorType;

    @Column(name = "approved_actor_id")
    private Long approvedActorId;

    public void setApprovedActor(com.cretas.aims.security.ActorRef actor) {
        java.util.Objects.requireNonNull(actor, "Actor identity is required");
        this.approvedActorType = actor.type();
        this.approvedActorId = actor.id();
        this.approvedBy = actor.factoryUserId();
    }

    /** NULL type/id together means historical identity is unknown. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "executed_actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType executedActorType;

    @Column(name = "executed_actor_id")
    private Long executedActorId;

    public void setExecutedActor(com.cretas.aims.security.ActorRef actor) {
        java.util.Objects.requireNonNull(actor, "Actor identity is required");
        this.executedActorType = actor.type();
        this.executedActorId = actor.id();
        this.executedBy = actor.factoryUserId();
    }
}
