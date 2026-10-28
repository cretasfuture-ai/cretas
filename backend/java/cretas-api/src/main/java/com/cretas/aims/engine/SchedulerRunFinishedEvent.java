package com.cretas.aims.engine;

import com.cretas.aims.ai.usage.LlmUsageScope;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Published once when a scheduled fire has finished (result callback): what ran, what came out, and
 * what it cost. The scheduler only publishes it; {@link SchedulerRunRecorder} stores it.
 *
 * @param rawStatus the runner's status (SUCCESS, FAILED, TIMEOUT, OUTCOME_UNKNOWN, GATEWAY_DENIED ...)
 * @param payload   the tool's result on success; null otherwise
 */
public record SchedulerRunFinishedEvent(
        Long configId,
        String factoryId,
        String taskCode,
        String toolName,
        Map<String, Object> configSnapshot,
        String plannedFireKey,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String rawStatus,
        String error,
        JsonNode payload,
        LlmUsageScope.Usage llmUsage) {
}
