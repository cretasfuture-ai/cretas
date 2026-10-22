package com.cretas.aims.audit;

import com.cretas.aims.entity.audit.PlatformDataAccessLog;
import com.cretas.aims.security.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Records every request (reads included) that platform staff make against a specific
 * factory's data, so the factory can later see who on the platform side looked.
 *
 * <p><b>Who counts as platform staff</b> — the same principals that pass every factory
 * check in {@code JwtAuthInterceptor}/{@code FactoryAccessValidator}:
 * an explicit platform token ({@link AuthenticatedActor#isPlatformAdmin()}), a token whose
 * factory is {@code PLATFORM}, or a role in {@link AuthenticatedActor#FACTORY_BYPASS_PLATFORM_ROLES}.
 *
 * <p><b>Target factory</b> — the matched route's {@code {factoryId}} path variable
 * ({@code /api/mobile/{factoryId}/**}, {@code /api/platform/label-qc/{factoryId}/**},
 * {@code /api/platform/factory-assembly/factories/{factoryId}/**}, ...), else a
 * {@code ?factoryId=} query parameter (smartbi-config, scheduled-tasks). Requests whose
 * target equals the actor's own token factory, or is {@code PLATFORM}, are not recorded.
 *
 * <p><b>Ordering</b> — registered in {@code WebMvcConfig} immediately after
 * {@code JwtAuthInterceptor} and before every other guard: {@link #preHandle} snapshots the
 * identity JWT just established (so nothing later can rewrite it), and because its preHandle
 * returned true, {@link #afterCompletion} also runs when a later guard rejects with 403.
 *
 * <p>Never fails or blocks the request: the row is built here and persisted on a bounded
 * pool by {@link PlatformDataAccessLogWriter}, which logs any failure at ERROR.
 */
@Slf4j
@Component
public class PlatformDataAccessLogInterceptor implements AsyncHandlerInterceptor {

    static final String PRINCIPAL_ATTRIBUTE = PlatformDataAccessLogInterceptor.class.getName() + ".principal";
    static final String RECORDED_ATTRIBUTE = PlatformDataAccessLogInterceptor.class.getName() + ".recorded";

    private static final Pattern SAFE_FACTORY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SAFE_PARAM_NAME = Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,64}");
    private static final Pattern SAFE_IP = Pattern.compile("[0-9A-Fa-f.:\\[\\]]{1,64}");

    private final PlatformDataAccessLogWriter writer;

    public PlatformDataAccessLogInterceptor(PlatformDataAccessLogWriter writer) {
        this.writer = writer;
    }

    /** Identity as established by JwtAuthInterceptor at the moment this interceptor ran. */
    record PlatformPrincipal(String actorType, Long userId, String username, String role, String tokenFactoryId) {
    }

    record Target(String factoryId, PlatformDataAccessLog.TargetSource source) {
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            if (request.getAttribute(PRINCIPAL_ATTRIBUTE) == null) {
                PlatformPrincipal principal = platformPrincipal(request);
                if (principal != null) request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            }
        } catch (Throwable unexpected) {
            log.error("platform_data_access_log_principal_failed uri={} errorType={}",
                    RequestAuditSupport.route(request), unexpected.getClass().getSimpleName());
        }
        return true;
    }

    /**
     * SSE / Callable handlers: the data is being served from here on and the async
     * re-dispatch is not guaranteed to happen (client disconnect), so record now with the
     * status at hand-off. The re-dispatch's afterCompletion is then deduplicated.
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        record(request, response.getStatus());
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        int status = response.getStatus();
        if (ex != null && status < 400) status = 500;
        record(request, status);
    }

    private void record(HttpServletRequest request, int status) {
        try {
            if (Boolean.TRUE.equals(request.getAttribute(RECORDED_ATTRIBUTE))) return;
            if (!(request.getAttribute(PRINCIPAL_ATTRIBUTE) instanceof PlatformPrincipal principal)) return;
            Target target = target(request);
            if (target == null
                    || "PLATFORM".equalsIgnoreCase(target.factoryId())
                    || target.factoryId().equals(principal.tokenFactoryId())) {
                return;
            }
            request.setAttribute(RECORDED_ATTRIBUTE, Boolean.TRUE);
            writer.submit(PlatformDataAccessLog.builder()
                    .createdAt(LocalDateTime.now())
                    .targetFactoryId(target.factoryId())
                    .targetSource(target.source())
                    .actorType(principal.actorType())
                    .actorUserId(principal.userId())
                    .actorUsername(RequestAuditSupport.sanitizeText(principal.username(), 100))
                    .actorRole(principal.role() == null ? null : RequestAuditSupport.safeToken(principal.role(), 64))
                    .actorFactoryId(principal.tokenFactoryId())
                    .httpMethod(RequestAuditSupport.safeToken(request.getMethod(), 10))
                    .requestPath(RequestAuditSupport.route(request))
                    .queryParamNames(queryParamNames(request.getQueryString()))
                    .responseStatus(status)
                    .clientIp(clientIp(request))
                    .remoteAddr(ip(request.getRemoteAddr()))
                    .correlationId(RequestAuditSupport.sanitizeCorrelationId(MDC.get("correlationId")))
                    .build());
        } catch (Throwable unexpected) {
            log.error("platform_data_access_log_record_failed uri={} status={} errorType={}",
                    RequestAuditSupport.route(request), status, unexpected.getClass().getSimpleName());
        }
    }

    static PlatformPrincipal platformPrincipal(HttpServletRequest request) {
        Long userId = RequestAuditSupport.userId(request);
        if (userId == null) return null;
        Object rawFactory = request.getAttribute("factoryId");
        String tokenFactoryId = rawFactory == null ? null : String.valueOf(rawFactory);
        Object rawRole = request.getAttribute("role");
        String role = rawRole == null ? null : String.valueOf(rawRole);
        AuthenticatedActor actor = AuthenticatedActor.current(request).orElse(null);

        boolean platformSide = (actor != null && actor.isPlatformAdmin())
                || "PLATFORM".equals(tokenFactoryId)
                || (role != null && AuthenticatedActor.FACTORY_BYPASS_PLATFORM_ROLES.contains(role.toLowerCase(Locale.ROOT)));
        if (!platformSide) return null;
        String actorType = actor != null ? actor.type().name() : "UNTYPED";
        return new PlatformPrincipal(actorType, userId, RequestAuditSupport.username(request), role, tokenFactoryId);
    }

    static Target target(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> map && map.get("factoryId") instanceof String pathFactory) {
            return SAFE_FACTORY.matcher(pathFactory).matches()
                    ? new Target(pathFactory, PlatformDataAccessLog.TargetSource.PATH) : null;
        }
        String queryFactory = queryValue(request.getQueryString(), "factoryId");
        if (queryFactory != null && SAFE_FACTORY.matcher(queryFactory).matches()) {
            return new Target(queryFactory, PlatformDataAccessLog.TargetSource.QUERY);
        }
        return null;
    }

    /** Parameter names only, in order of first appearance; values are dropped. */
    static String queryParamNames(String queryString) {
        if (queryString == null || queryString.isBlank()) return null;
        Set<String> names = new LinkedHashSet<>();
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            names.add(name != null && SAFE_PARAM_NAME.matcher(name).matches() ? name : "?");
        }
        if (names.isEmpty()) return null;
        String joined = String.join(",", names);
        return joined.length() > 512 ? joined.substring(0, 512) : joined;
    }

    private static String queryValue(String queryString, String wanted) {
        if (queryString == null) return null;
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (wanted.equals(decode(pair.substring(0, eq)))) return decode(pair.substring(eq + 1));
        }
        return null;
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return ip(realIp.trim());
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return ip((comma > 0 ? forwarded.substring(0, comma) : forwarded).trim());
        }
        return ip(request.getRemoteAddr());
    }

    private static String ip(String candidate) {
        return candidate != null && SAFE_IP.matcher(candidate).matches() ? candidate : null;
    }
}
