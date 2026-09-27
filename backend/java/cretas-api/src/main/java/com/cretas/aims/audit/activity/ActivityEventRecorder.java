package com.cretas.aims.audit.activity;

import com.cretas.aims.audit.RequestAuditSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Keeps untrusted client claims distinct from server-observed API outcomes. */
@Component
public class ActivityEventRecorder {
    private static final Set<String> SOURCES = Set.of("screen", "click", "input", "block");
    private static final Set<String> DETAIL_KEYS = Set.of("field", "result", "reason", "component",
            "disabled", "count", "length", "status", "entityType", "entityId", "unit", "quantity", "role");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern ROUTE = Pattern.compile("/[A-Za-z0-9_./{}:-]{0,255}");
    private final ActivityEventStore store;
    private final ConcurrentHashMap<String, Long> recentReads = new ConcurrentHashMap<>();

    public ActivityEventRecorder(ActivityEventStore store) {
        this.store = store;
    }

    public int recordClient(HttpServletRequest request, List<ClientEvent> events) {
        String factoryId = RequestAuditSupport.trustedFactoryId(request);
        Long userId = RequestAuditSupport.userId(request);
        if (factoryId == null || userId == null || events == null) return 0;
        int accepted = 0;
        long now = System.currentTimeMillis();
        for (ClientEvent event : events.subList(0, Math.min(events.size(), 100))) {
            if (event == null || token(event.eventId(), 64) == null || event.source() == null
                    || !SOURCES.contains(event.source()) || !safe(event.action(), TOKEN)
                    || !safe(route(event.route()), ROUTE)) continue;
            if (event.occurredAt() == null || event.occurredAt() < now - 86_400_000L
                    || event.occurredAt() > now + 86_400_000L) continue;
            if (store.submit(new ActivityEvent(event.eventId(), factoryId, userId, event.source(), event.action(),
                    route(event.route()), detail(event.detail()), token(event.correlationId(), 64),
                    token(event.clientActionId(), 64), client(request),
                    Instant.ofEpochMilli(event.occurredAt())))) accepted++;
        }
        return accepted;
    }

    public void recordApi(HttpServletRequest request, int status, long elapsedMs) {
        String path = RequestAuditSupport.route(request);
        if (!RequestAuditSupport.isMobileApi(request) || path.contains("/activity-events")
                || path.contains("/client-errors")) return;
        String factoryId = RequestAuditSupport.trustedFactoryId(request);
        Long userId = RequestAuditSupport.userId(request);
        if (factoryId == null || userId == null) return;
        Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = template instanceof String value && safe(value, ROUTE) ? value : "/unmatched";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("method", RequestAuditSupport.safeToken(request.getMethod(), 8));
        detail.put("status", status);
        detail.put("elapsedMs", elapsedMs);
        if (request.isAsyncStarted()) detail.put("asyncStarted", true);
        Object success = request.getAttribute(RequestAuditSupport.RESPONSE_SUCCESS_ATTRIBUTE);
        if (success instanceof Boolean outcome) detail.put("businessSuccess", outcome);
        String errorCode = token(RequestAuditSupport.responseErrorCode(request), 64);
        if (errorCode != null) detail.put("errorCode", errorCode);
        // Background polling is not a separate human operation. Preserve the first result,
        // then collapse identical reads for one minute; changed outcomes remain visible.
        String clientActionId = token(request.getHeader("X-Client-Action-ID"), 64);
        if ("GET".equalsIgnoreCase(request.getMethod()) && clientActionId == null) {
            long now = System.currentTimeMillis();
            String key = factoryId + ':' + userId + ':' + route + ':' + status + ':' + success + ':' + errorCode;
            Long previous = recentReads.putIfAbsent(key, now);
            if (recentReads.size() > 50_000) {
                recentReads.entrySet().removeIf(e -> now - e.getValue() > 60_000L);
                if (recentReads.size() > 50_000) recentReads.clear();
            }
            if (previous != null && now - previous < 60_000L) return;
            recentReads.put(key, now);
        }
        store.submit(new ActivityEvent(UUID.randomUUID().toString(), factoryId, userId, "api", request.getMethod(), route,
                detail, token(String.valueOf(request.getAttribute("X-Correlation-ID")), 64),
                clientActionId, client(request),
                Instant.now()));
    }

    static Map<String, Object> detail(Map<String, Object> raw) {
        if (raw == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (result.size() >= 12) break;
            String key = entry.getKey();
            if (!DETAIL_KEYS.contains(key)) continue;
            Object value = entry.getValue();
            if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                    || value instanceof Double) result.put(key, value);
            else if (value instanceof String string && safe(string, TOKEN)) result.put(key, string);
        }
        return result;
    }

    static String route(String raw) {
        if (raw == null) return "-";
        String noQuery = raw.split("[?#]", 2)[0];
        if (!safe(noQuery, ROUTE)) return "-";
        return noQuery.replaceAll("(?<=/)\\d{4,}(?=/|$)", "{id}");
    }

    private static boolean safe(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches();
    }

    private static String token(String value, int max) {
        return value != null && value.length() <= max && TOKEN.matcher(value).matches() ? value : null;
    }

    private static String client(HttpServletRequest request) {
        String client = request.getHeader("X-Activity-Client");
        return client != null && Set.of("app", "web-admin", "expo-web").contains(client) ? client : "unknown";
    }

    public record ClientEvent(String eventId, String source, String action, String route, Map<String, Object> detail,
            String correlationId, String clientActionId, Long occurredAt) {}
}
