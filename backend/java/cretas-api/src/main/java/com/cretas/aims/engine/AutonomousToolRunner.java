package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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
    private final AutonomousGrantService grants;
    private final PreviewTokenService previewTokens;

    /**
     * @param status persisted status, at most 20 characters
     * @param error  null on success; otherwise the reason, without parameters or payload
     * @param payload the gateway payload on success (may be null)
     */
    public record Outcome(String status, String error, JsonNode payload) {
        public boolean succeeded() { return "SUCCESS".equals(status); }
    }

    /** A run with no delegated-grant context (event chain, SOP, ad hoc): writes are always refused. */
    public Outcome run(String factoryId, Long runAsUserId, String toolName, Map<String, Object> params,
            ToolExecutionSource source, String correlationId) {
        return run(factoryId, runAsUserId, toolName, params, source, correlationId, null, null);
    }

    /**
     * @param sourceRef the scheduler config id a delegated grant is bound to
     * @param runKey    identifies this run (fire slot); the same key replays, never re-executes
     */
    public Outcome run(String factoryId, Long runAsUserId, String toolName, Map<String, Object> params,
            ToolExecutionSource source, String correlationId, String sourceRef, String runKey) {
        Optional<ToolExecutor> tool = toolRegistry.getExecutor(toolName);
        if (tool.isEmpty()) {
            return new Outcome("TOOL_NOT_FOUND", "tool not registered: " + toolName, null);
        }
        String refusal = AutonomousToolPolicy.rejection(toolName, tool.get(), params);
        boolean write = AutonomousToolPolicy.isWriteDenial(refusal);
        if (refusal != null && !write) {
            return new Outcome("CONFIG_INVALID", refusal, null);
        }
        // Delegated writes are cron-only for now: an event chain runs all steps in one transaction
        // (a later step's rollback would undo an audited write) and has no durable event id to
        // key replays on, so chain writes stay refused whatever arguments are passed.
        if (write && (source != ToolExecutionSource.SCHEDULER || sourceRef == null || runKey == null)) {
            return new Outcome(AutonomousToolPolicy.WRITE_DENIED, refusal, null);
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
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        Optional<String> idempotencyKey = Optional.empty();
        Optional<ConfirmationProof> proof = Optional.empty();
        if (write) {
            // Delegated write: consume a grant bound to this source/tool/user/parameters, then hand the
            // gateway a one-time server-issued confirmation and a per-run idempotency key.
            LocalDateTime now = LocalDateTime.now();
            String digest = ToolCommandDigest.parametersHash(objectMapper.valueToTree(safeParams));
            Optional<AutonomousGrantService.Consumption> consumed = grants.consume(factoryId, source.name(), sourceRef,
                    toolName, runAsUserId, digest, runKey, now);
            if (consumed.isEmpty()) {
                return new Outcome(GRANT_DENIED, "no usable grant for " + toolName + " (" + source + " " + sourceRef
                        + "): missing, revoked, expired, exhausted or bound to other parameters/user", null);
            }
            if (consumed.get().replay()) {
                // Same run key again (duplicate fire, re-delivered event): never execute a second time,
                // report what the gateway recorded for the first attempt.
                return replayed(factoryId, runAsUserId, toolName, tool.get().getVersion(), consumed.get());
            }
            IntentPreviewToken token = previewTokens.createBoundToken(new PreviewTokenService.BoundTokenRequest(
                    // Per-run intent code: creating a token cancels the user's pending tokens with the same
                    // code, so a shared code would let two runs of the same user void each other's proof.
                    factoryId, runAsUserId, null,
                    "AUTONOMOUS_TOOL_RUN:" + ToolCommandDigest.persistentSecretFingerprint(consumed.get().idempotencyKey()),
                    "自动任务委托写入", toolName,
                    tool.get().getVersion(), ToolExecutionMode.EXECUTE, "AUTONOMOUS_GRANT", consumed.get().grantId(),
                    "EXECUTE", safeParams, Map.of(), Map.of(), (int) DEADLINE.toSeconds()));
            idempotencyKey = Optional.of(consumed.get().idempotencyKey());
            proof = Optional.of(new ConfirmationProof(token.getToken(), token.getCommandDigest(),
                    Instant.now().plus(DEADLINE)));
        }
        ToolExecutionCommand command = new ToolExecutionCommand(
                source.name().toLowerCase() + "-" + UUID.randomUUID(),
                correlationId,
                source.name().toLowerCase() + "-" + UUID.randomUUID(),
                toolName,
                tool.get().getVersion(),
                objectMapper.valueToTree(safeParams),
                principal,
                source,
                ToolExecutionMode.EXECUTE,
                idempotencyKey,
                proof,
                Optional.empty(),
                Instant.now().plus(DEADLINE));
        return outcome(executeDetached(command));
    }

    private Outcome replayed(String factoryId, Long runAsUserId, String toolName, String version,
            AutonomousGrantService.Consumption consumed) {
        Optional<ToolExecutionIdempotencyRecord> record = grants.gatewayRecord(
                factoryId, runAsUserId, toolName, version, consumed.idempotencyKey());
        if (record.isEmpty()) {
            // Consumed but never reached the gateway (crash in between): left for reconciliation, not retried.
            return new Outcome("OUTCOME_UNKNOWN", "run already consumed grant " + consumed.grantId()
                    + " but no gateway record exists; not retried", null);
        }
        String detail = "replay of grant " + consumed.grantId() + " run (audit " + record.get().getOriginalAuditEventId()
                + "): " + record.get().getState();
        return switch (record.get().getState()) {
            case SUCCEEDED -> new Outcome("SUCCESS", null, null);
            case FAILED -> new Outcome("FAILED", detail, null);
            default -> new Outcome("OUTCOME_UNKNOWN", detail, null);
        };
    }

    /** Persisted status when no usable delegated grant covers a write (VARCHAR(20)). */
    public static final String GRANT_DENIED = "GRANT_DENIED";

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
