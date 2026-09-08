package com.cretas.aims.audit;

import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.service.datacenter.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import lombok.extern.slf4j.Slf4j;

/** Records one bounded operation log for authenticated factory mutation requests. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RequestAuditInterceptor implements HandlerInterceptor {

    private final OperationLogService operationLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(RequestAuditSupport.STARTED_NANOS_ATTRIBUTE, System.nanoTime());
        Object pathVariables = request.getAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        request.setAttribute(RequestAuditSupport.POST_AUTH_ATTRIBUTE,
                pathVariables instanceof java.util.Map<?, ?> variables && variables.containsKey("factoryId"));
        Long userId = RequestAuditSupport.userId(request);
        String factoryId = RequestAuditSupport.trustedFactoryId(request);
        if (userId != null) MDC.put("userId", String.valueOf(userId));
        if (factoryId != null) MDC.put("factoryId", factoryId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        if (!RequestAuditSupport.isMobileApi(request)
                || !(handler instanceof HandlerMethod handlerMethod)
                || !RequestAuditSupport.isMutation(request.getMethod(), RequestAuditSupport.route(request), handlerMethod)
                || RequestAuditSupport.hasExplicitAudit(request)
                || !RequestAuditSupport.authenticated(request)
                || Boolean.TRUE.equals(request.getAttribute(RequestAuditSupport.OPERATION_AUDITED_ATTRIBUTE))) {
            return;
        }
        request.setAttribute(RequestAuditSupport.OPERATION_AUDITED_ATTRIBUTE, Boolean.TRUE);

        boolean success = exception == null && response.getStatus() < 400
                && !Boolean.FALSE.equals(request.getAttribute(RequestAuditSupport.RESPONSE_SUCCESS_ATTRIBUTE));
        if (response.getStatus() >= 400) {
            request.setAttribute(RequestAuditSupport.RESPONSE_SUCCESS_ATTRIBUTE, Boolean.FALSE);
        }
        String route = RequestAuditSupport.route(request);
        String failure = exception == null ? null : RequestAuditSupport.sanitizeText(exception.getMessage(), 1000);
        if (failure == null && !success) failure = RequestAuditSupport.responseError(request);
        if (failure == null && !success) failure = "controller response success=false";
        Object started = request.getAttribute(RequestAuditSupport.STARTED_NANOS_ATTRIBUTE);
        long elapsedMs = started instanceof Long nanos
                ? Math.max(0L, (System.nanoTime() - nanos) / 1_000_000L) : 0L;
        OperationLog operationLog = OperationLog.builder()
                .factoryId(RequestAuditSupport.trustedFactoryId(request))
                .userId(RequestAuditSupport.userId(request))
                .username(RequestAuditSupport.sanitizeText(RequestAuditSupport.username(request), 100))
                .module("FACTORY_API")
                .action(RequestAuditSupport.action(request.getMethod()))
                .entityType(RequestAuditSupport.entityType(route))
                .entityId(RequestAuditSupport.entityId(route))
                .summary(RequestAuditSupport.sanitizeText(request.getMethod() + " " + route
                        + " correlation=" + RequestAuditSupport.safeToken(MDC.get("correlationId"), 64), 1000))
                .ipAddress(RequestAuditSupport.safeToken(request.getRemoteAddr(), 64))
                .userAgent(RequestAuditSupport.safeToken(request.getHeader("User-Agent"), 500))
                .elapsedMs(elapsedMs)
                .success(success)
                .errorMessage(failure)
                .build();
        try {
            operationLogService.record(operationLog);
        } catch (Throwable auditFailure) {
            // Audit failure must never change the already completed business response.
            log.error("request_audit_operation_failed correlation={} errorType={}",
                    RequestAuditSupport.safeToken(MDC.get("correlationId"), 64),
                    auditFailure.getClass().getSimpleName());
        } finally {
            MDC.remove("userId");
            MDC.remove("factoryId");
        }
    }
}
