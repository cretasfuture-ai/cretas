package com.cretas.aims.engine;

import com.cretas.aims.ai.usage.LlmUsageScope;
import com.cretas.aims.audit.RequestAuditSupport;
import com.cretas.aims.entity.config.SchedulerRunResult;
import com.cretas.aims.repository.config.SchedulerRunResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Stores every finished scheduled run (S6-03). An undeterminable run (timeout, outcome unknown) is
 * stored as UNKNOWN, never SUCCESS. Recording is best effort: a failure here is logged and must not
 * affect the scheduler thread or the run itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerRunRecorder {
    static final int MAX_SNAPSHOT_CHARS = 64 * 1024;
    static final int MAX_SUMMARY_CHARS = 300;

    private final SchedulerRunResultRepository repository;

    /**
     * The recorded status for a runner status. The runner's status set is open, so only statuses known
     * to mean "it did not happen" are FAILED; anything unrecognised is UNKNOWN, never success or failure by default.
     */
    static String normalize(String rawStatus) {
        if (rawStatus == null) return SchedulerRunResult.UNKNOWN;
        return switch (rawStatus) {
            case "SUCCESS" -> SchedulerRunResult.SUCCESS;
            case "FAILED", "GATEWAY_DENIED", "GRANT_DENIED", "WRITE_DENIED", "CONFIG_INVALID",
                 "TOOL_NOT_FOUND", "RUN_AS_MISSING" -> SchedulerRunResult.FAILED;
            default -> SchedulerRunResult.UNKNOWN;
        };
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFinished(SchedulerRunFinishedEvent event) {
        try {
            repository.save(toResult(event));
        } catch (Exception e) {
            log.error("Failed to record scheduled run config={} status={} type={} error={}",
                    event.configId(), event.rawStatus(), e.getClass().getSimpleName(),
                    RequestAuditSupport.sanitizeText(e.getMessage(), 300));
        }
    }

    SchedulerRunResult toResult(SchedulerRunFinishedEvent e) {
        SchedulerRunResult.SchedulerRunResultBuilder b = SchedulerRunResult.builder()
                .runId(UUID.randomUUID().toString())
                .configId(e.configId())
                .factoryId(e.factoryId())
                .taskCode(e.taskCode())
                .toolName(e.toolName())
                .configSnapshot(e.configSnapshot() == null ? Map.of() : e.configSnapshot())
                .plannedFireKey(e.plannedFireKey())
                .startedAt(e.startedAt())
                .finishedAt(e.finishedAt())
                .status(normalize(e.rawStatus()))
                .rawStatus(truncate(e.rawStatus() == null ? "UNKNOWN" : e.rawStatus(), 30))
                .errorMsg(e.error());
        if (e.payload() != null && !e.payload().isNull()) {
            if (e.payload().toString().length() <= MAX_SNAPSHOT_CHARS) {
                b.dataSnapshot(e.payload());
            } else {
                b.snapshotTruncated(true);
            }
            b.summary(summarize(e.payload()));
        } else if (e.error() != null) {
            b.summary(truncate(e.error(), MAX_SUMMARY_CHARS));
        }
        LlmUsageScope.Usage u = e.llmUsage() == null ? LlmUsageScope.Usage.none() : e.llmUsage();
        b.tokenUsageState(u.state().name())
                .llmCallCount(u.calls())
                .promptTokens(u.promptTokens())
                .completionTokens(u.completionTokens())
                .totalTokens(u.totalTokens());
        return b.build();
    }

    private String summarize(JsonNode payload) {
        if (payload.isObject()) {
            for (String key : new String[] {"message", "summary"}) {
                if (payload.hasNonNull(key) && payload.get(key).isTextual()) {
                    return truncate(payload.get(key).asText(), MAX_SUMMARY_CHARS);
                }
            }
        }
        return truncate(payload.isTextual() ? payload.asText() : payload.toString(), MAX_SUMMARY_CHARS);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
