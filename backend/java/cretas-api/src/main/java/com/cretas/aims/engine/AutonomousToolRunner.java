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
    private final AutonomousGrantIssuer issuer;

    /**
     * @param status persisted status, at most 20 characters
     * @param error  null on success; otherwise the reason, without parameters or payload
     * @param payload the gateway payload on success (may be null)
     * @param replay  true when this run key was already consumed: nothing executed here, the status
     *                is what the first attempt left (or OUTCOME_UNKNOWN while it is still in flight)
     */
    public record Outcome(String status, String error, JsonNode payload, boolean replay) {
        public Outcome(String status, String error, JsonNode payload) {
            this(status, error, payload, false);
        }

        public boolean succeeded() { return "SUCCESS".equals(status); }
    }

    /** A run with no delegated-grant context (event chain, SOP, ad hoc): writes are always refused. */
    public Outcome run(String factoryId, Long runAsUserId, String toolName, Map<String, Object> params,
            ToolExecutionSource source, String correlationId) {
        return run(factoryId, runAsUserId, toolName, params, source, correlationId, null, null);
    }

    /**
     * @param grantBinding what a delegated write must be granted for, as the scheduler computed it from
     *                     the config it fires ({@link SchedulerGrantBinding}); null for reads
     * @param runKey       the run's planned fire identity ("slot:" + planned instant); the same key
     *                     replays, never re-executes; a write without it is refused
     */
    public Outcome run(String factoryId, Long runAsUserId, String toolName, Map<String, Object> params,
            ToolExecutionSource source, String correlationId, AutonomousGrantService.Binding grantBinding,
            String runKey) {
        Optional<ToolExecutor> tool = toolRegistry.getExecutor(toolName);
        if (tool.isEmpty()) {
            return new Outcome("TOOL_NOT_FOUND", "tool not registered: " + toolName, null);
        }
        String refusal = AutonomousToolPolicy.rejection(toolName, tool.get(), params);
        boolean write = AutonomousToolPolicy.isWriteDenial(refusal);
        if (refusal != null && !write) {
            return new Outcome("CONFIG_INVALID", refusal, null);
        }
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        // Delegated writes are cron-only: an event chain runs all steps in one transaction (a later
        // step's rollback would undo an audited write) and has no durable event id to key replays on.
        if (write && (source != ToolExecutionSource.SCHEDULER
                || !boundToThisRun(grantBinding, factoryId, runAsUserId, toolName, tool.get(), safeParams))) {
            return new Outcome(AutonomousToolPolicy.WRITE_DENIED, refusal, null);
        }
        // Only an immutable planned fire identity keys a write: never the actual start time, which
        // two instances (or one late fire) would turn into different keys for the same planned run.
        if (write && (runKey == null || !runKey.startsWith(PLANNED_FIRE_PREFIX))) {
            return new Outcome(AutonomousToolPolicy.WRITE_DENIED,
                    "no planned fire identity for this write run; refused rather than keyed by its start time", null);
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
        Optional<String> idempotencyKey = Optional.empty();
        Optional<ConfirmationProof> proof = Optional.empty();
        AutonomousGrantService.Consumption consumption = null;
        if (write) {
            // Consumption is the authorization commit point: a grant matching this config revision,
            // tool version, parameters and user is used once for this run key, under a row lock.
            Optional<AutonomousGrantService.Consumption> consumed =
                    issuer.consumeForScheduledFire(grantBinding, runKey, LocalDateTime.now());
            if (consumed.isEmpty()) {
                return new Outcome(GRANT_DENIED, "no usable grant for " + toolName + " (scheduler config "
                        + grantBinding.sourceRef() + "): missing, revoked, expired, exhausted, or issued for another"
                        + " config revision, tool version, user or parameters, its grantor is no longer this"
                        + " factory's super admin, or the config was disabled or changed after this fire read it",
                        null);
            }
            if (consumed.get().replay()) {
                // The same slot again (second instance, duplicate fire): never execute a second time,
                // report what the first attempt recorded.
                return replayed(factoryId, runAsUserId, toolName, consumed.get(), runKey);
            }
            // The config was marked RUNNING in the same transaction that consumed the grant.
            consumption = consumed.get();
            IntentPreviewToken token = previewTokens.createBoundToken(new PreviewTokenService.BoundTokenRequest(
                    // Per-run intent code: creating a token cancels the user's pending tokens with the same
                    // code, so a shared code would let two runs of the same user void each other's proof.
                    factoryId, runAsUserId, null,
                    "AUTONOMOUS_TOOL_RUN:" + ToolCommandDigest.persistentSecretFingerprint(consumed.get().idempotencyKey()),
                    "自动任务委托写入", toolName, tool.get().getVersion(), ToolExecutionMode.EXECUTE,
                    "AUTONOMOUS_GRANT", consumed.get().grantId(), "EXECUTE", safeParams, Map.of(), Map.of(),
                    (int) DEADLINE.toSeconds()));
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
        ToolExecutionResult result = executeDetached(command);
        if (consumption != null) {
            issuer.recordOutcome(consumption.grantId(), runKey, recordedOutcomeOf(result.status()),
                    result.auditEventId(), LocalDateTime.now());
        }
        return outcome(result);
    }

    /** What a consumed run's record keeps of the gateway's answer. */
    static String recordedOutcomeOf(ToolExecutionStatus status) {
        return switch (status) {
            case SUCCEEDED, IDEMPOTENT_REPLAY -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case TIMEOUT, OUTCOME_UNKNOWN -> "OUTCOME_UNKNOWN";
            default -> "DENIED";
        };
    }

    /** Persisted status when no usable delegated grant covers a write (VARCHAR(20)). */
    public static final String GRANT_DENIED = "GRANT_DENIED";

    /** Run keys of delegated writes: the scheduler's planned fire instant, never an actual time. */
    static final String PLANNED_FIRE_PREFIX = "slot:";

    /** The binding must describe exactly this run, so a grant can never be applied to another one. */
    private boolean boundToThisRun(AutonomousGrantService.Binding binding, String factoryId, Long runAsUserId,
            String toolName, ToolExecutor tool, Map<String, Object> params) {
        return binding != null
                && ToolExecutionSource.SCHEDULER.name().equals(binding.sourceType())
                && java.util.Objects.equals(binding.factoryId(), factoryId)
                && java.util.Objects.equals(binding.runAsUserId(), runAsUserId)
                && java.util.Objects.equals(binding.toolName(), toolName)
                && java.util.Objects.equals(binding.descriptorVersion(), tool.getVersion())
                && java.util.Objects.equals(binding.parametersDigest(),
                        ToolCommandDigest.parametersHash(objectMapper.valueToTree(params)));
    }

    private Outcome replayed(String factoryId, Long runAsUserId, String toolName,
            AutonomousGrantService.Consumption consumed, String runKey) {
        Optional<String> recorded = issuer.recordedOutcome(consumed.grantId(), runKey);
        if (recorded.isPresent()) {
            String detail = "replay of grant " + consumed.grantId() + " run: recorded " + recorded.get();
            return switch (recorded.get()) {
                case "SUCCEEDED" -> new Outcome("SUCCESS", null, null, true);
                case "FAILED" -> new Outcome("FAILED", detail, null, true);
                case "DENIED" -> new Outcome("GATEWAY_DENIED", detail, null, true);
                default -> new Outcome("OUTCOME_UNKNOWN", detail, null, true);
            };
        }
        Optional<ToolExecutionIdempotencyRecord> record = grants.gatewayRecord(factoryId, runAsUserId, toolName, consumed);
        if (record.isEmpty()) {
            // Consumed but no gateway record: still in flight on another instance, or the first attempt
            // stopped before the gateway. Left for reconciliation, never retried here.
            return new Outcome("OUTCOME_UNKNOWN", "run already consumed grant " + consumed.grantId()
                    + " but no gateway record exists (in flight elsewhere or interrupted); not retried", null, true);
        }
        String detail = "replay of grant " + consumed.grantId() + " run (audit "
                + record.get().getOriginalAuditEventId() + "): " + record.get().getState();
        return switch (record.get().getState()) {
            case SUCCEEDED -> new Outcome("SUCCESS", null, null, true);
            case FAILED -> new Outcome("FAILED", detail, null, true);
            default -> new Outcome("OUTCOME_UNKNOWN", detail, null, true);
        };
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
