package com.cretas.aims.service.agentops;

import com.cretas.aims.client.agentops.AgentOpsClient;
import com.cretas.aims.client.agentops.AgentOpsClient.TrustedContext;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.execution.BusinessTypeGate;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Emits one AgentOps trace per real factory-domain agent run (intent -> tool -> outcome).
 *
 * <p>Recording is best-effort and off the request thread: a failed or dropped trace never
 * changes the user's answer, but is never silent either (WARN log + counters).
 * Only FACTORY tenants emit, and only runs that actually dispatched a tool (a step needs a
 * tool name); unrecognised or clarification-only turns have nothing to trace.
 */
@Slf4j
@Component
public class FactoryAgentTraceRecorder {
    private static final Pattern INTENT = Pattern.compile("^[A-Z][A-Z0-9_]{2,95}$");
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Set<String> BLOCKED = Set.of(
            "FORBIDDEN", "NO_PERMISSION", "PERMISSION_DENIED", "TOOL_DISABLED",
            "READ_MODE_WRITE_BLOCKED", "WRITE_CONFIRM_REQUIRED", "PREVIEW_UNSUPPORTED",
            "PENDING_APPROVAL");
    private static final String SOURCE_REF = "tool_dispatch";

    private final AgentOpsClient client;
    private final BusinessTypeGate businessTypeGate;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), runnable -> {
                Thread thread = new Thread(runnable, "factory-agent-trace");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public FactoryAgentTraceRecorder(AgentOpsClient client, BusinessTypeGate businessTypeGate) {
        this.client = client;
        this.businessTypeGate = businessTypeGate;
        executor.allowCoreThreadTimeOut(true);
    }

    /** Called from the execution facade after a run; never throws. */
    public void record(String factoryId, Long userId, String role,
                       IntentExecuteResponse response, long latencyMs) {
        try {
            Map<String, Object> trace = buildTrace(response, latencyMs);
            if (trace == null || factoryId == null || userId == null || role == null) {
                return;
            }
            if (!"FACTORY".equals(businessTypeGate.resolveDomain(factoryId))) {
                return;
            }
            if (!client.isConfigured()) {
                dropped.incrementAndGet();
                log.warn("[factory-trace] AgentOps secret missing, trace dropped factoryId={}", factoryId);
                return;
            }
            TrustedContext context = new TrustedContext(
                    factoryId, String.valueOf(userId), role.toLowerCase(Locale.ROOT),
                    "trace-" + UUID.randomUUID(), "FACTORY");
            executor.execute(() -> send(context, trace));
        } catch (RejectedExecutionException ex) {
            dropped.incrementAndGet();
            log.warn("[factory-trace] queue full, trace dropped factoryId={}", factoryId);
        } catch (RuntimeException ex) {
            failed.incrementAndGet();
            log.warn("[factory-trace] could not prepare trace factoryId={}: {}", factoryId, ex.toString());
        }
    }

    /** Package-visible for tests: null when this response has nothing to trace. */
    static Map<String, Object> buildTrace(IntentExecuteResponse response, long latencyMs) {
        if (response == null || response.getIntentCode() == null
                || !INTENT.matcher(response.getIntentCode()).matches()) {
            return null;
        }
        Object tool = response.getMetadata() == null ? null : response.getMetadata().get("toolName");
        if (!(tool instanceof String toolName) || !IDENT.matcher(toolName).matches()) {
            return null;
        }
        String outcome = outcomeOf(response.getStatus());
        int latency = (int) Math.max(0, Math.min(latencyMs, 3_600_000L));
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("toolName", toolName);
        step.put("latencyMs", latency);
        step.put("outcome", outcome);
        step.put("sourceRef", SOURCE_REF);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("runId", UUID.randomUUID().toString());
        trace.put("intentCode", response.getIntentCode());
        trace.put("outcome", outcome);
        trace.put("latencyMs", latency);
        trace.put("steps", List.of(step));
        return trace;
    }

    /** Unknown statuses stay UNKNOWN; they are never promoted to SUCCESS. */
    static String outcomeOf(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        if ("COMPLETED".equals(normalized) || "SUCCESS".equals(normalized)) {
            return "SUCCESS";
        }
        if ("FAILED".equals(normalized)) {
            return "FAILED";
        }
        return BLOCKED.contains(normalized) ? "BLOCKED" : "UNKNOWN";
    }

    private void send(TrustedContext context, Map<String, Object> trace) {
        try {
            client.recordFactoryTrace(trace, context);
            recorded.incrementAndGet();
        } catch (Exception ex) {
            failed.incrementAndGet();
            log.warn("[factory-trace] upstream rejected trace factoryId={}: {}",
                    context.factoryId(), ex.toString());
        }
    }

    public long recordedCount() { return recorded.get(); }
    public long failedCount() { return failed.get(); }
    public long droppedCount() { return dropped.get(); }

    /** Test hook: wait until queued traces are sent. */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (executor.getQueue().isEmpty() && executor.getActiveCount() == 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
