package com.cretas.aims.entity.config;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Audit row for ruling on an UNKNOWN scheduled run. Append-only; one per run. */
@Entity
@Table(name = "scheduler_run_resolutions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SchedulerRunResolution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "run_id", length = 36, nullable = false, updatable = false) private String runId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(name = "from_status", length = 10, nullable = false, updatable = false) private String fromStatus;
    @Column(name = "to_status", length = 10, nullable = false, updatable = false) private String toStatus;
    @Column(name = "note", columnDefinition = "TEXT", nullable = false, updatable = false) private String note;
    @Column(name = "resolved_by", updatable = false) private Long resolvedBy;
    @Column(name = "resolved_by_name", length = 100, updatable = false) private String resolvedByName;
    @Column(name = "resolved_at", nullable = false, updatable = false) private LocalDateTime resolvedAt;
}
