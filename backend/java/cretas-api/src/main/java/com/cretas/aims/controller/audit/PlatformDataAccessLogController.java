package com.cretas.aims.controller.audit;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.audit.PlatformDataAccessLog;
import com.cretas.aims.repository.audit.PlatformDataAccessLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * A factory's view of what platform staff accessed in it.
 *
 * <p>Tenant isolation: {@code JwtAuthInterceptor} rejects other factories' users on
 * {@code /api/mobile/{factoryId}/**} (403) and the query is keyed by the path factory.
 * Role: {@code @RequireRole} (enforced by {@code RequireRoleInterceptor}; {@code @PreAuthorize}
 * is a no-op in this app) — same audiences as {@code /operation-logs}. Platform staff may read
 * it too, and since this route has a {@code {factoryId}} path variable, that read is itself
 * recorded by {@code PlatformDataAccessLogInterceptor}.
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/platform-access-log")
public class PlatformDataAccessLogController {

    static final int MAX_SIZE = 100;

    private final PlatformDataAccessLogRepository repository;

    public PlatformDataAccessLogController(PlatformDataAccessLogRepository repository) {
        this.repository = repository;
    }

    /**
     * @param page 1-based page number (default 1)
     * @param size page size (default 20, max 100)
     */
    @GetMapping
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<PageResponse<Entry>> list(@PathVariable String factoryId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        Page<PlatformDataAccessLog> rows = repository.findByTargetFactoryIdOrderByCreatedAtDescIdDesc(
                factoryId, PageRequest.of(safePage - 1, safeSize));
        return ApiResponse.success(PageResponse.of(
                rows.getContent().stream().map(Entry::of).toList(), safePage, safeSize, rows.getTotalElements()));
    }

    /** What the factory sees; the socket peer address and correlation id stay internal. */
    public record Entry(Long id, LocalDateTime accessedAt, String actorType, Long actorUserId,
                        String actorUsername, String actorRole, String httpMethod, String requestPath,
                        String queryParamNames, Integer responseStatus, String clientIp) {
        static Entry of(PlatformDataAccessLog row) {
            return new Entry(row.getId(), row.getCreatedAt(), row.getActorType(), row.getActorUserId(),
                    row.getActorUsername(), row.getActorRole(), row.getHttpMethod(), row.getRequestPath(),
                    row.getQueryParamNames(), row.getResponseStatus(), row.getClientIp());
        }
    }
}
