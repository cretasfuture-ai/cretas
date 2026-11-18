package com.cretas.aims.ai.tool.gateway;

/** Fixed, non-sensitive result vocabulary persisted by the gateway ledger. */
public enum GatewayResultCode {
    PRINCIPAL_DENIED,
    POLICY_DENIED,
    DEADLINE_EXPIRED,
    PREVIEW_UNSUPPORTED,
    CONFIRMATION_REQUIRED,
    IDEMPOTENCY_REQUIRED,
    IDEMPOTENT_REPLAY,
    IDEMPOTENCY_CONFLICT,
    IN_FLIGHT_OR_IN_DOUBT,
    CONFIRMATION_REJECTED,
    CONFIRMATION_CLAIM_UNCERTAIN,
    TOOL_SUCCEEDED,
    TOOL_EXECUTION_FAILED,
    TOOL_NEEDS_INFO,
    TOOL_OUTCOME_UNCERTAIN,
    CONFIRMATION_RESOLUTION_UNCERTAIN,
    PERSISTENCE_FINALIZATION_UNCERTAIN,
    TOOL_PREVIEW_SUCCEEDED,
    TOOL_PREVIEW_FAILED,
    /** S6-04: a delegated scheduled write refused before the gateway (no usable grant). */
    GRANT_DENIED,
    /** S6-04: a write refused by the autonomous policy (not a schedule, unbound or unstated outcome). */
    AUTONOMOUS_WRITE_DENIED,
    /** S6-04: delegated writes switched off (deployment, factory never switched on, or switched off). */
    WRITES_SUSPENDED,
    /**
     * Pre-execution business-state check failed (object changed after preview, precondition no longer
     * holds, state unreadable or not comparable). The Tool was not invoked; nothing was written.
     */
    PREVIEW_STALE,
    /**
     * Pre-execution business-state check could not compare the object's state (the token recorded no
     * prior state and the Tool cannot supply one). Not retryable by re-previewing; nothing was written.
     */
    CONFIRM_NOT_SUPPORTED
}
