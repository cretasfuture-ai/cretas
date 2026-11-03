package com.cretas.aims.entity.lineage;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 血缘边补写台账的一行（{@code lineage_edge_outbox}，迁移 V20261103_150）。
 *
 * <p>一行 = 一条「应当存在」的血缘边。业务事务里登记成 {@link #STATUS_PENDING}，
 * 边真的落库（或确认已在库里）后结成 {@link #STATUS_DONE}；写失败留 PENDING 等补写器重试，
 * 连续失败到上限转 {@link #STATUS_DEAD} —— DEAD 仍然计入积压，⛔ 不是「放弃了就不算」。
 *
 * <p>不继承 {@code BaseEntity}：台账行不软删，DONE 行留作审计。
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "lineage_edge_outbox")
public class LineageEdgeOutboxEntry {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_DEAD = "DEAD";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "edge_type", nullable = false, length = 30)
    private String edgeType;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "source_id", nullable = false, length = 191)
    private String sourceId;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 191)
    private String targetId;

    @Column(name = "quantity_used", precision = 15, scale = 4)
    private BigDecimal quantityUsed;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "event_time")
    private LocalDateTime eventTime;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "ref_key", length = 255)
    private String refKey;

    @Type(JsonBinaryType.class)
    @Column(name = "meta", columnDefinition = "jsonb")
    private Map<String, Object> meta = new HashMap<>();

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** 结掉时 writer 给的结论（INSERTED / ALREADY_EXISTS）。 */
    @Column(name = "resolved_outcome", length = 20)
    private String resolvedOutcome;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "done_at")
    private LocalDateTime doneAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
