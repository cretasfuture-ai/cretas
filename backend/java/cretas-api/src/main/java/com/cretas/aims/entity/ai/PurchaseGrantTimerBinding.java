package com.cretas.aims.entity.ai;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;

/** Immutable one-shot purchase delegation. Execution state is durable, never a retry instruction. */
@Entity
@Table(name = "purchase_grant_timer_bindings", uniqueConstraints = {
        @UniqueConstraint(name = "uk_purchase_timer_grant", columnNames = "grant_id"),
        @UniqueConstraint(name = "uk_purchase_timer_task", columnNames = "scheduled_task_id")})
@Getter @Setter
public class PurchaseGrantTimerBinding {
    @Id private UUID id;
    @Column(name="factory_id", nullable=false, updatable=false, length=50) private String factoryId;
    @Column(name="grant_id", nullable=false, updatable=false, length=36) private String grantId;
    @Column(name="user_id", nullable=false, updatable=false) private Long userId;
    @Column(name="scheduled_task_id", nullable=false, updatable=false) private UUID scheduledTaskId;
    @Column(name="expected_grant_version", nullable=false, updatable=false) private Long expectedGrantVersion;
    @Column(name="input_digest", nullable=false, updatable=false, length=128) private String inputDigest;
    @Column(name="scope_digest", nullable=false, updatable=false, length=64) private String scopeDigest;
    @Column(name="input_snapshot", nullable=false, updatable=false, columnDefinition="text") private String inputSnapshot;
    @Column(name="command_digest", nullable=false, updatable=false, length=128) private String commandDigest;
    @Column(name="execute_at", nullable=false, updatable=false) private LocalDateTime executeAt;
    @Column(name="expires_at", nullable=false, updatable=false) private LocalDateTime expiresAt;
    @Column(name="cron_expression", nullable=false, updatable=false, length=100) private String cronExpression;
    @Column(name="task_revision", nullable=false, updatable=false) private Long taskRevision;
    @Column(nullable=false, length=20) private String state;
    @Column(name="result_purchase_order_id", length=191) private String resultPurchaseOrderId;
    @Column(name="failure_reason", length=500) private String failureReason;
    @Column(name="created_at", nullable=false, updatable=false) private LocalDateTime createdAt;
    @Column(name="updated_at", nullable=false) private LocalDateTime updatedAt;
    @Version @Column(nullable=false) private Long version;
    @PrePersist void create() { if (id==null) id=UUID.randomUUID(); createdAt=LocalDateTime.now(); updatedAt=createdAt; }
    @PreUpdate void update() { updatedAt=LocalDateTime.now(); }
}
