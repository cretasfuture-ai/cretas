package com.cretas.aims.config;

import com.cretas.aims.audit.RequestAuditSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses request paths whose raw form and routed form can disagree.
 *
 * <p>Spring routes on the decoded path with {@code ;} parameters removed, while
 * {@link JwtAuthInterceptor}, {@code RequestAuditSupport}, {@code WebLoginSessionFilter} and
 * others judge the raw {@link HttpServletRequest#getRequestURI()}. So {@code /api/%70latform/x}
 * and {@code /api;a=1/mobile/F002/x} reached their controllers with the platform, internal-key,
 * factory-isolation and operation-audit checks all skipped. Rejecting here keeps every raw check
 * truthful, instead of patching each one.
 *
 * <p>No legitimate client sends these: {@code encodeURIComponent} and {@code quote} never encode
 * letters, digits, {@code -}, {@code .} or {@code _}, and no id or name holds a control byte. An
 * encoded separator is refused too; Tomcat rejects it first today, and this holds if that default
 * ever changes. {@code %20}, {@code %25} and UTF-8 bytes still pass.
 *
 * <p>{@code %3B} passes this filter but does not survive every route here: the URI-form
 * {@code proxy_pass} on the admin, web-admin and www vhosts decodes it, so it arrives as a real
 * {@code ;} and is refused. A value holding {@code ;} cannot travel in a path through those vhosts.
 *
 * <p>Order: behind the {@code HIGHEST_PRECEDENCE} filters, which do not judge the path, so a refusal
 * still carries its correlation id; ahead of {@code ClientErrorBodyLimitFilter} and
 * {@link WebLoginSessionFilter}, which do.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class AmbiguousRequestPathFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AmbiguousRequestPathFilter.class);

    static final String REJECTED_BODY =
            "{\"success\":false,\"code\":400,\"data\":null,\"message\":\"请求路径不规范\"}";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAmbiguous(request.getRequestURI())) {
            log.warn("ambiguous_request_path_rejected method={} uri={}",
                    RequestAuditSupport.safeToken(request.getMethod(), 10), RequestAuditSupport.route(request));
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(REJECTED_BODY);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isAmbiguous(String rawUri) {
        if (rawUri == null) {
            return false;
        }
        if (rawUri.indexOf(';') >= 0) {
            return true;
        }
        for (int at = rawUri.indexOf('%'); at >= 0 && at + 2 < rawUri.length(); at = rawUri.indexOf('%', at + 1)) {
            int high = Character.digit(rawUri.charAt(at + 1), 16);
            int low = Character.digit(rawUri.charAt(at + 2), 16);
            if (high >= 0 && low >= 0 && refusedWhenEncoded((char) (high * 16 + low))) {
                return true;
            }
        }
        return false;
    }

    private static boolean refusedWhenEncoded(char decoded) {
        return decoded < 0x20 || decoded == 0x7F
                || (decoded >= 'a' && decoded <= 'z') || (decoded >= 'A' && decoded <= 'Z')
                || (decoded >= '0' && decoded <= '9')
                || decoded == '-' || decoded == '.' || decoded == '_'
                || decoded == '/' || decoded == '\\';
    }
}
