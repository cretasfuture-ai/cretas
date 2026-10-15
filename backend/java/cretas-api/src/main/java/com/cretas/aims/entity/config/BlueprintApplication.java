package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 蓝图应用记录实体
 * 记录蓝图被应用到工厂的历史
 */
@Entity
@Table(name = "blueprint_applications")
@Data
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BlueprintApplication extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * 蓝图ID
     */
    @Column(name = "blueprint_id", nullable = false, length = 36)
    private String blueprintId;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "blueprint_version")
    private Integer blueprintVersion;

    @Column(name = "operation_type", length = 30)
    private String operationType;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    /**
     * 应用人用户ID
     */
    @Column(name = "applied_by")
    private Long appliedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "applied_actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType appliedActorType;
    @Column(name = "applied_actor_id")
    private Long appliedActorId;

    public void setAppliedActor(com.cretas.aims.security.ActorRef actor) {
        appliedActorType = actor == null ? null : actor.type();
        appliedActorId = actor == null ? null : actor.id();
        appliedBy = actor == null ? null : actor.factoryUserId();
    }

    /**
     * 应用时间
     */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /**
     * 状态: PENDING, IN_PROGRESS, COMPLETED, FAILED
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ApplicationStatus status = ApplicationStatus.COMPLETED;

    /**
     * 应用结果摘要
     */
    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "operation_result", columnDefinition = "JSONB")
    private String operationResult;

    @Column(name = "module_results", columnDefinition = "JSONB")
    private String moduleResults;

    @Column(name = "recovery_plan", columnDefinition = "JSONB")
    private String recoveryPlan;

    @Column(name = "business_write_count", nullable = false)
    private Integer businessWriteCount = 0;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (appliedAt == null) {
            appliedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ApplicationStatus.PENDING;
        }
        if (businessWriteCount == null) {
            businessWriteCount = 0;
        }
    }

    /**
     * 应用状态枚举
     */
    public enum ApplicationStatus {
        PENDING,      // 待处理
        IN_PROGRESS,  // 进行中
        COMPLETED,    // 已完成
        FAILED,       // 失败
        PARTIAL_FAILED,
        ROLLED_BACK
    }
}
