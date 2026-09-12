package com.cretas.aims.audit.client;

import com.cretas.aims.audit.RequestAuditSupport;
import com.cretas.aims.dto.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Receives bounded browser diagnostics as application logs, never as business audit rows. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/client-errors")
@Slf4j
public class ClientErrorController {
    private final ClientErrorRateLimiter rateLimiter;

    public ClientErrorController() {
        this(new ClientErrorRateLimiter());
    }

    ClientErrorController(ClientErrorRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> report(
            @PathVariable String factoryId,
            @Valid @RequestBody ClientErrorReportRequest report,
            HttpServletRequest request) {
        Long userId = RequestAuditSupport.userId(request);
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "未授权，请先登录"));
        }
        String trustedFactoryId = RequestAuditSupport.trustedFactoryId(request);
        if (trustedFactoryId == null || !factoryId.equals(trustedFactoryId)) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "无权访问该工厂"));
        }

        // This endpoint is telemetry only. Keep it out of OperationLog, which represents
        // verified business mutations and must never treat browser claims as business facts.
        RequestAuditSupport.markExplicitAudit(request);
        if (!rateLimiter.tryAcquire(trustedFactoryId, userId)) {
            return ResponseEntity.status(429).body(ApiResponse.error(429, "客户端错误上报频率超限"));
        }

        log.warn("client_error reportedBy=browser verified=false factoryId={} userId={} eventId={} "
                        + "category={} route={} method={} status={} errorCode={} parentCorrelationId={} "
                        + "source={} file={} line={} column={}",
                trustedFactoryId,
                userId,
                report.eventId(),
                report.category(),
                report.route(),
                report.method(),
                report.status(),
                report.errorCode(),
                report.parentCorrelationId(),
                report.source(),
                report.file(),
                report.line(),
                report.column());
        return ResponseEntity.ok(ApiResponse.success());
    }
}
