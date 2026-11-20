package com.cretas.aims.entity.shortage;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 每日差异核对的一次运行记录 (V20261114_11)。每个工厂每次扫描一条, 包括「没量到」:
 * {@link Status#NOT_MEASURED} 必带原因, ⛔ 不当「没有异常」。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "daily_reconcile_runs")
public class DailyReconcileRun {

    public enum Status { MEASURED, PARTIAL, NOT_MEASURED, FAILED }

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "trigger_source", length = 32, nullable = false)
    private String triggerSource;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Column(name = "not_measured_reason", columnDefinition = "TEXT")
    private String notMeasuredReason;

    @Column(name = "variance_configured", nullable = false)
    private boolean varianceConfigured;

    @Column(name = "balance_configured", nullable = false)
    private boolean balanceConfigured;

    /** FACTORY = 工厂自己配的阈值; DEFAULT = 系统默认阈值; null = 没有阈值。 */
    @Column(name = "threshold_source", length = 16)
    private String thresholdSource;

    @Column(name = "evaluated_count", nullable = false)
    private int evaluatedCount;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "refreshed_count", nullable = false)
    private int refreshedCount;

    @Column(name = "unknown_count", nullable = false)
    private int unknownCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "notified_count", nullable = false)
    private int notifiedCount;

    @Column(name = "notify_failed_count", nullable = false)
    private int notifyFailedCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
