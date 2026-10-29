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
            "PENDING_APPROVAL", "DENIED", "CONFIRMATION_REQUIRED", "APPROVAL_REQUIRED");
    /** Step name for a blocked/failed run that never reached a tool (the intent code still identifies it). */
    static final String NO_TOOL_STEP = "no_tool_dispatched";
    /** A completed run that is bound to no tool (skill / report routes): the intent answered it. */
    static final String INTENT_ROUTE_STEP = "intent_route";
    /** Security-relevant denials that never resolved an intent (e.g. session admission). */
    static final String UNRESOLVED_INTENT = "UNRESOLVED_INTENT";
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
        record(factoryId, userId, role, response, latencyMs, null);
    }

    /**
     * @param fallbackIntentCode the intent the router resolved, for responses that do not carry it
     *        (e.g. skill/report routes streamed straight from the skill result)
     */
    public void record(String factoryId, Long userId, String role,
                       IntentExecuteResponse response, long latencyMs, String fallbackIntentCode) {
        try {
            Map<String, Object> trace = buildTrace(response, latencyMs, fallbackIntentCode);
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
        return buildTrace(response, latencyMs, null);
    }

    /**
     * Final answers that resolved no intent. A clarification turn (NEED_MORE_INFO / NEED_CLARIFICATION) is the
     * system asking, not a run, so it is not traced; an attempted action that ended as a final answer
     * (e.g. 「停用员工账号」 answered COMPLETED with intent=null) is exactly the miss worth seeing.
     */
    private static final Set<String> UNRESOLVED_FINAL = Set.of(
            "COMPLETED", "SUCCESS", "NOT_RECOGNIZED", "FAILED", "NO_MATCH", "FACTORY_PACK_NO_MATCH", "VALIDATION_FAILED");

    static Map<String, Object> buildTrace(IntentExecuteResponse response, long latencyMs, String fallbackIntentCode) {
        if (response == null) {
            return null;
        }
        String outcome = outcomeOf(response.getStatus());
        String intent = response.getIntentCode();
        if (intent == null || !INTENT.matcher(intent).matches()) {
            intent = fallbackIntentCode;
        }
        if (intent == null || !INTENT.matcher(intent).matches()) {
            String status = statusOf(response);
            if (!"BLOCKED".equals(outcome) && !UNRESOLVED_FINAL.contains(status)) {
                return null;
            }
            intent = UNRESOLVED_INTENT; // denied or unanswered attempts are worth a row before an intent resolved
            if ("SUCCESS".equals(outcome)) {
                outcome = "UNKNOWN"; // an answer that resolved to nothing is never counted as a success
            }
            if (toolNameOf(response) == null) {
                return unresolvedTrace(intent, outcome, latencyMs);
            }
        }
        String toolName = toolNameOf(response);
        if (toolName == null) {
            // Skill/report-routed runs (tool_name NULL, e.g. REPORT_INVENTORY) and blocked/failed runs that
            // stopped before a tool was chosen carry no toolName. Dropping them skews traces toward the
            // tool-bound successes, so keep them, named for what actually happened.
            if ("SUCCESS".equals(outcome)) {
                toolName = INTENT_ROUTE_STEP;
            } else if ("BLOCKED".equals(outcome) || "FAILED".equals(outcome)
                    || UNRESOLVED_FINAL.contains(statusOf(response))) {
                toolName = NO_TOOL_STEP;
            } else {
                return null;
            }
        }
        int latency = (int) Math.max(0, Math.min(latencyMs, 3_600_000L));
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("toolName", toolName);
        step.put("latencyMs", latency);
        step.put("outcome", outcome);
        step.put("sourceRef", SOURCE_REF);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("runId", UUID.randomUUID().toString());
        trace.put("intentCode", intent);
        trace.put("outcome", outcome);
        trace.put("latencyMs", latency);
        trace.put("steps", List.of(step));
        return trace;
    }

    private static String statusOf(IntentExecuteResponse response) {
        return response.getStatus() == null ? "" : response.getStatus().toUpperCase(Locale.ROOT);
    }

    private static Map<String, Object> unresolvedTrace(String intent, String outcome, long latencyMs) {
        int latency = (int) Math.max(0, Math.min(latencyMs, 3_600_000L));
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("toolName", NO_TOOL_STEP);
        step.put("latencyMs", latency);
        step.put("outcome", outcome);
        step.put("sourceRef", SOURCE_REF);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("runId", UUID.randomUUID().toString());
        trace.put("intentCode", intent);
        trace.put("outcome", outcome);
        trace.put("latencyMs", latency);
        trace.put("steps", List.of(step));
        return trace;
    }

    private static String toolNameOf(IntentExecuteResponse response) {
        Object tool = response.getMetadata() == null ? null : response.getMetadata().get("toolName");
        if (tool == null && response.getResultData() instanceof Map<?, ?> data) {
            tool = data.get("toolName"); // e.g. FORBIDDEN responses carry it only in resultData
        }
        return tool instanceof String name && IDENT.matcher(name).matches() ? name : null;
    }

    /** Unknown statuses stay UNKNOWN; they are never promoted to SUCCESS. */
    static String outcomeOf(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        if ("COMPLETED".equals(normalized) || "SUCCESS".equals(normalized) || "SUCCEEDED".equals(normalized)) {
            return "SUCCESS";
        }
        if ("FAILED".equals(normalized) || "TIMEOUT".equals(normalized)) {
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
            // getTaskCount counts submitted tasks, getCompletedTaskCount finished ones: equal means nothing
            // is queued or running (queue-empty + active==0 can both be true while a task is being handed over).
            if (executor.getCompletedTaskCount() == executor.getTaskCount()) {
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
