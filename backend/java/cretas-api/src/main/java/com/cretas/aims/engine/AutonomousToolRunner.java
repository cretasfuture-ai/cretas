package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.security.DataScopeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Runs one tool for a cron schedule or an event trigger chain through {@link ToolExecutionGateway}
 * as the configured run-as factory user. The gateway re-reads that user's account, factory,
 * business type and permissions on every run and records the audit event; this class never
 * executes a tool directly and never invents an identity.
 *
 * <p>Order: {@link AutonomousToolPolicy} (read-only, no identity params) → a run-as user must be
 * set → the gateway decides (tool policy must list the source, user must still be allowed).</p>
 */
@Component
@RequiredArgsConstructor
public class AutonomousToolRunner {
    static final String ASSERTED_ROLE = "autonomous_run";
    static final Duration DEADLINE = Duration.ofSeconds(60);

    private final ToolExecutionGateway gateway;
    private final AuthenticatedToolPrincipalFactory principals;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    /**
     * @param status persisted status, at most 20 characters
     * @param error  null on success; otherwise the reason, without parameters or payload
     * @param payload the gateway payload on success (may be null)
     */
    public record Outcome(String status, String error, JsonNode payload) {
        public boolean succeeded() { return "SUCCESS".equals(status); }
    }

    public Outcome run(String factoryId, Long runAsUserId, String toolName, Map<String, Object> params,
            ToolExecutionSource source, String correlationId) {
        Optional<ToolExecutor> tool = toolRegistry.getExecutor(toolName);
        if (tool.isEmpty()) {
            return new Outcome("TOOL_NOT_FOUND", "tool not registered: " + toolName, null);
        }
        String refusal = AutonomousToolPolicy.rejection(toolName, tool.get(), params);
        if (refusal != null) {
            return new Outcome(AutonomousToolPolicy.isWriteDenial(refusal)
                    ? AutonomousToolPolicy.WRITE_DENIED : "CONFIG_INVALID", refusal, null);
        }
        if (runAsUserId == null) {
            return new Outcome("RUN_AS_MISSING",
                    "no run-as user: a factory user must enable this config before it runs", null);
        }
        ExecutionPrincipal principal;
        try {
            principal = principals.create(factoryId, runAsUserId, ASSERTED_ROLE);
        } catch (SecurityException unavailableTenant) {
            return new Outcome("GATEWAY_DENIED", "run-as identity rejected: " + unavailableTenant.getMessage(), null);
        }
        ToolExecutionCommand command = new ToolExecutionCommand(
                source.name().toLowerCase() + "-" + UUID.randomUUID(),
                correlationId,
                source.name().toLowerCase() + "-" + UUID.randomUUID(),
                toolName,
                tool.get().getVersion(),
                objectMapper.valueToTree(params == null ? Map.of() : params),
                principal,
                source,
                ToolExecutionMode.EXECUTE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Instant.now().plus(DEADLINE));
        return outcome(executeDetached(command));
    }

    /**
     * Event chains run on the publisher's (often HTTP) thread. Detach that request and its
     * data-scope stack so the gateway binds the run-as user and every tool computes its row scope
     * from that user, not from whoever triggered the event. Both are restored afterwards.
     */
    private ToolExecutionResult executeDetached(ToolExecutionCommand command) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Deque<DataScopeContext> scopes = DataScopeContext.detach();
        RequestContextHolder.resetRequestAttributes();
        try {
            return gateway.execute(command);
        } finally {
            RequestContextHolder.setRequestAttributes(request);
            DataScopeContext.restore(scopes);
        }
    }

    static Outcome outcome(ToolExecutionResult result) {
        ToolExecutionStatus status = result.status();
        String detail = "gateway " + status + " (audit " + result.auditEventId() + ")"
                + (result.message() == null ? "" : ": " + result.message());
        return switch (status) {
            case SUCCEEDED, IDEMPOTENT_REPLAY -> new Outcome("SUCCESS", null, result.payload());
            case FAILED -> new Outcome("FAILED", detail, null);
            case TIMEOUT -> new Outcome("TIMEOUT", detail, null);
            // Outcome unknown: never re-run blindly; the audit id is the lookup key.
            case OUTCOME_UNKNOWN -> new Outcome("OUTCOME_UNKNOWN", detail, null);
            default -> new Outcome("GATEWAY_DENIED", detail, null);
        };
    }
}
