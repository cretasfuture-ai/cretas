package com.cretas.aims.audit.activity;

import java.time.Instant;
import java.util.Map;

/** An observed action. Client events are claims; API events are server observations. */
public record ActivityEvent(
        String eventId, String factoryId, Long userId, String source, String action, String route,
        Map<String, Object> detail, String correlationId, String clientActionId,
        String client, Instant occurredAt) {
}
