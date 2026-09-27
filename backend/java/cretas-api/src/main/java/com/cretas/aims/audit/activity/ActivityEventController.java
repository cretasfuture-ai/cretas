package com.cretas.aims.audit.activity;

import com.cretas.aims.audit.RequestAuditSupport;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticated collection and admin-only reading of the activity timeline. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/activity-events")
@RequiredArgsConstructor
public class ActivityEventController {
    private final ActivityEventRecorder recorder;
    private final ActivityEventStore store;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    @PostMapping
    public ResponseEntity<ApiResponse<Integer>> collect(@PathVariable String factoryId,
            @RequestBody(required = false) EventBatch batch, HttpServletRequest request) {
        if (!authorized(factoryId, request)) return ResponseEntity.status(403).body(ApiResponse.error(403, "无权访问该工厂"));
        RequestAuditSupport.markExplicitAudit(request);
        if (batch == null || batch.events() == null || batch.events().size() > 20) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "事件批次无效"));
        }
        if (!allow(factoryId, RequestAuditSupport.userId(request))) {
            return ResponseEntity.status(429).body(ApiResponse.error(429, "上报频率超限"));
        }
        return ResponseEntity.ok(ApiResponse.success(recorder.recordClient(request, batch.events())));
    }

    @GetMapping
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@PathVariable String factoryId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        if (!authorized(factoryId, request)) return ResponseEntity.status(403).body(ApiResponse.error(403, "无权访问该工厂"));
        if (source != null && !Set.of("screen", "click", "input", "block", "api").contains(source)) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "无效事件来源"));
        }
        if (correlationId != null && RequestAuditSupport.sanitizeCorrelationId(correlationId) == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "无效关联 ID"));
        }
        return ResponseEntity.ok(ApiResponse.success(store.find(factoryId, userId, source, correlationId,
                start, end, limit, offset)));
    }

    private static boolean authorized(String factoryId, HttpServletRequest request) {
        return RequestAuditSupport.userId(request) != null
                && factoryId.equals(RequestAuditSupport.trustedFactoryId(request));
    }

    private boolean allow(String factoryId, Long userId) {
        long window = System.currentTimeMillis() / 60_000L;
        if (counters.size() > 10_000) counters.entrySet().removeIf(e -> e.getValue().window < window);
        Counter counter = counters.compute(factoryId + ':' + userId,
                (key, old) -> old == null || old.window != window ? new Counter(window, 1)
                        : new Counter(window, old.count + 1));
        return counter.count <= 12;
    }

    public record EventBatch(List<ActivityEventRecorder.ClientEvent> events) {}
    private record Counter(long window, int count) {}
}
