package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One finished run of a scheduled config (S6-03). {@code status} is SUCCESS / FAILED / UNKNOWN; an
 * undeterminable run is UNKNOWN and only {@code resolution} (a person's ruling, audited in
 * {@link SchedulerRunResolution}) can turn it into a success or failure.
 */
@Entity
@Table(name = "scheduler_run_results")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SchedulerRunResult {
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "run_id", length = 36, nullable = false, updatable = false) private String runId;
    @Column(name = "config_id", nullable = false) private Long configId;
    @Column(name = "factory_id", length = 50, nullable = false) private String factoryId;
    @Column(name = "task_code", length = 64, nullable = false) private String taskCode;
    @Column(name = "tool_name", length = 100) private String toolName;
    @Type(JsonBinaryType.class)
    @Column(name = "config_snapshot", columnDefinition = "jsonb", nullable = false)
    @Builder.Default private Map<String, Object> configSnapshot = Map.of();
    @Column(name = "planned_fire_key", length = 100) private String plannedFireKey;
    @Column(name = "started_at", nullable = false) private LocalDateTime startedAt;
    @Column(name = "finished_at", nullable = false) private LocalDateTime finishedAt;
    @Column(name = "status", length = 10, nullable = false) private String status;
    @Column(name = "raw_status", length = 30, nullable = false) private String rawStatus;
    @Column(name = "error_msg", columnDefinition = "TEXT") private String errorMsg;
    @Column(name = "summary", columnDefinition = "TEXT") private String summary;
    @Type(JsonBinaryType.class)
    @Column(name = "data_snapshot", columnDefinition = "jsonb") private Object dataSnapshot;
    @Column(name = "snapshot_truncated", nullable = false) @Builder.Default private Boolean snapshotTruncated = false;
    @Column(name = "resolution", length = 10) private String resolution;
    @Column(name = "resolved_by") private Long resolvedBy;
    @Column(name = "resolved_at") private LocalDateTime resolvedAt;
    @Column(name = "resolution_note", columnDefinition = "TEXT") private String resolutionNote;
    @Column(name = "prompt_tokens") private Integer promptTokens;
    @Column(name = "completion_tokens") private Integer completionTokens;
    @Column(name = "total_tokens") private Integer totalTokens;
    @Column(name = "llm_call_count", nullable = false) @Builder.Default private Integer llmCallCount = 0;
    @Column(name = "token_usage_state", length = 10, nullable = false) @Builder.Default private String tokenUsageState = "NONE";
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;

    /** What the run counts as: the person's ruling when there is one, otherwise the recorded status. */
    public String effectiveStatus() {
        return resolution != null ? resolution : status;
    }
}
