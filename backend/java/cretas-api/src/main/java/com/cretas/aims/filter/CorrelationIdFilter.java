package com.cretas.aims.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import com.cretas.aims.audit.RequestAuditSupport;
import com.cretas.aims.audit.activity.ActivityEventRecorder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

/**
 * Correlation ID Filter
 *
 * Jakarta Servlet Filter that runs before all Spring interceptors.
 * Establishes a correlation ID for request tracing across Java and Python services.
 *
 * 1. Reads X-Correlation-ID from request header (propagated from upstream)
 * 2. If absent, generates a new UUID
 * 3. Puts only the correlation ID into SLF4J MDC; verified identity is read
 *    from request attributes after JwtAuthInterceptor completes
 * 4. Adds X-Correlation-ID to response header
 * 5. Clears MDC in finally block to prevent thread pool leaks
 *
 * @author Cretas Team
 * @since 2026-03-28
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {
    @Autowired(required = false)
    private ActivityEventRecorder activityEventRecorder;

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_FACTORY_ID = "factoryId";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        Throwable failure = null;
        long startedAt = System.nanoTime();
        String correlationId = null;
        try {
            // 1. Read or generate correlation ID
            Object retained = request.getAttribute(CORRELATION_ID_HEADER);
            correlationId = RequestAuditSupport.sanitizeCorrelationId(retained instanceof String value
                    ? value : request.getHeader(CORRELATION_ID_HEADER));
            if (correlationId == null) {
                correlationId = UUID.randomUUID().toString();
            }

            // 2. Put correlation ID in MDC
            MDC.put(MDC_CORRELATION_ID, correlationId);
            request.setAttribute(CORRELATION_ID_HEADER, correlationId);

            // 4. Add correlation ID to response header
            response.setHeader(CORRELATION_ID_HEADER, correlationId);

            // 5. Continue the filter chain
            chain.doFilter(request, response);

        } catch (IOException | ServletException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            if (RequestAuditSupport.isMobileApi(request)) {
                int status = failure == null ? response.getStatus() : 500;
                if (activityEventRecorder != null) {
                    try {
                        activityEventRecorder.recordApi(request, status, elapsedMillis(startedAt));
                    } catch (Exception auditFailure) {
                        log.error("activity_event_record_failed type={}", auditFailure.getClass().getSimpleName());
                    }
                }
                log.info("request_audit phase={} method={} route={} status={} businessSuccess={} responseError={} errorCode={} elapsedMs={} correlation={} authenticated={} userId={} username={} factoryId={}",
                        request.isAsyncStarted() ? "async_dispatch" : "response",
                        RequestAuditSupport.safeToken(request.getMethod(), 16),
                        RequestAuditSupport.route(request),
                        status,
                        request.getAttribute(RequestAuditSupport.RESPONSE_SUCCESS_ATTRIBUTE),
                        RequestAuditSupport.safeToken(RequestAuditSupport.responseError(request), 200),
                        RequestAuditSupport.safeToken(RequestAuditSupport.responseErrorCode(request), 100),
                        elapsedMillis(startedAt),
                        RequestAuditSupport.safeToken(correlationId, 64),
                        RequestAuditSupport.authenticated(request),
                        RequestAuditSupport.safeToken(RequestAuditSupport.userId(request), 64),
                        RequestAuditSupport.safeToken(RequestAuditSupport.username(request), 100),
                        RequestAuditSupport.safeToken(RequestAuditSupport.trustedFactoryId(request), 64));
            }
            // 6. Always clear MDC to prevent thread pool contamination
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_FACTORY_ID);
        }
    }
    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
