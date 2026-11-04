package com.cretas.aims.ai.tool.impl.restaurant;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Compatibility seam for the retired restaurant tiered-intent lane.
 *
 * <p>The old Python delegate is intentionally no longer wired into any
 * execution path. Keeping this no-op bean avoids breaking stale Spring wiring
 * while guaranteeing that a retired route cannot return success or invoke the
 * old client endpoint.</p>
 */
@Component
public class TieredIntentDelegate {

    /** Retained for old request-context readers; it is no longer written. */
    public static final String ATTEMPTED_CONTEXT_KEY = "tieredDelegateAttempted";

    /** Retired route: always fall through to catalog-backed Java execution. */
    public Map<String, Object> tryDelegate(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context,
            String toolName) {
        return null;
    }
}
