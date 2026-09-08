package com.cretas.aims.audit;

import com.cretas.aims.dto.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;

import java.util.regex.Pattern;

/** Shared, bounded and non-secret request audit helpers. */
public final class RequestAuditSupport {

    public static final String RESPONSE_SUCCESS_ATTRIBUTE = RequestAuditSupport.class.getName() + ".responseSuccess";
    public static final String RESPONSE_ERROR_ATTRIBUTE = RequestAuditSupport.class.getName() + ".responseError";
    public static final String RESPONSE_ERROR_CODE_ATTRIBUTE = RequestAuditSupport.class.getName() + ".responseErrorCode";
    public static final String EXPLICIT_AUDIT_ATTRIBUTE = RequestAuditSupport.class.getName() + ".explicitAudit";
    public static final String OPERATION_AUDITED_ATTRIBUTE = RequestAuditSupport.class.getName() + ".operationAudited";
    public static final String STARTED_NANOS_ATTRIBUTE = RequestAuditSupport.class.getName() + ".startedNanos";
    public static final String POST_AUTH_ATTRIBUTE = RequestAuditSupport.class.getName() + ".postAuth";

    private static final Pattern SAFE_CORRELATION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Pattern SAFE_FACTORY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\r\\n\\t]+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(authorization|token|access[_-]?token|refresh[_-]?token|password|passwd|secret|api[_-]?key)[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|(?:Bearer\\s+)?[^\\s,;}]+)");

    private RequestAuditSupport() {
    }

    public static boolean isMobileApi(HttpServletRequest request) {
        if (request == null) return false;
        String route = route(request);
        return "/api/mobile".equals(route) || route.startsWith("/api/mobile/");
    }

    public static String sanitizeCorrelationId(String candidate) {
        if (candidate == null || !SAFE_CORRELATION.matcher(candidate).matches()) {
            return null;
        }
        return candidate;
    }

    public static String route(HttpServletRequest request) {
        if (request == null) return "-";
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) return "-";
        int queryStart = uri.indexOf('?');
        return safeToken(queryStart < 0 ? uri : uri.substring(0, queryStart), 512);
    }

    public static String trustedFactoryId(HttpServletRequest request) {
        if (request == null) return null;
        Object raw = request.getAttribute("factoryId");
        String factoryId = raw == null ? null : String.valueOf(raw);
        if (factoryId != null && !SAFE_FACTORY.matcher(factoryId).matches()) return null;

        String route = route(request);
        String prefix = "/api/mobile/";
        if (!route.startsWith(prefix)) return null;
        int end = route.indexOf('/', prefix.length());
        String pathFactory = end < 0 ? route.substring(prefix.length()) : route.substring(prefix.length(), end);
        if (!SAFE_FACTORY.matcher(pathFactory).matches()) return null;
        if (pathFactory.equals(factoryId)) return factoryId;
        // This marker is set only by our order-100 interceptor after JWT/RBAC.
        String role = String.valueOf(request.getAttribute("role")).toLowerCase(java.util.Locale.ROOT);
        if (Boolean.TRUE.equals(request.getAttribute(POST_AUTH_ATTRIBUTE)) && userId(request) != null
                && ("PLATFORM".equals(factoryId)
                || java.util.Set.of("platform_admin", "super_admin", "developer").contains(role))) {
            return pathFactory;
        }
        return null;
    }

    public static Long userId(HttpServletRequest request) {
        if (request == null) return null;
        Object raw = request.getAttribute("userId");
        if (raw instanceof Number number) return number.longValue();
        if (raw instanceof String string) {
            try {
                return Long.valueOf(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static String username(HttpServletRequest request) {
        if (request == null) return null;
        Object raw = request.getAttribute("username");
        return raw == null ? null : String.valueOf(raw);
    }

    public static boolean authenticated(HttpServletRequest request) {
        return userId(request) != null && trustedFactoryId(request) != null;
    }

    public static void markExplicitAudit(HttpServletRequest request) {
        if (request != null) request.setAttribute(EXPLICIT_AUDIT_ATTRIBUTE, Boolean.TRUE);
    }

    public static boolean hasExplicitAudit(HttpServletRequest request) {
        return request != null && Boolean.TRUE.equals(request.getAttribute(EXPLICIT_AUDIT_ATTRIBUTE));
    }

    public static void captureResponseOutcome(HttpServletRequest request, Object body) {
        Object payload = body instanceof ResponseEntity<?> responseEntity ? responseEntity.getBody() : body;
        Boolean outcome = responseOutcome(body);
        if (request != null && outcome != null) {
            request.setAttribute(RESPONSE_SUCCESS_ATTRIBUTE, outcome);
            if (Boolean.FALSE.equals(outcome) && payload instanceof ApiResponse<?> response) {
                request.setAttribute(RESPONSE_ERROR_ATTRIBUTE, sanitizeText(response.getMessage(), 1000));
                request.setAttribute(RESPONSE_ERROR_CODE_ATTRIBUTE, safeToken(response.getErrorCode(), 100));
            }
        }
    }

    public static Boolean responseOutcome(Object result) {
        if (result instanceof ResponseEntity<?> responseEntity) {
            if (responseEntity.getStatusCode().isError()) return false;
            return responseOutcome(responseEntity.getBody());
        }
        if (result instanceof ApiResponse<?> apiResponse) {
            return apiResponse.getSuccess();
        }
        return null;
    }

    public static String responseError(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(RESPONSE_ERROR_ATTRIBUTE);
        return value == null ? null : String.valueOf(value);
    }

    public static String responseErrorCode(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(RESPONSE_ERROR_CODE_ATTRIBUTE);
        return value == null ? null : String.valueOf(value);
    }

    public static boolean isMutation(String method, String route, HandlerMethod handler) {
        if (method == null) return false;
        if ("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)) return true;
        // POST is retained as an auditable request with action OTHER. The generic
        // layer cannot prove whether a domain POST mutates state, so it never
        // labels one CREATE; query-only POSTs remain distinguishable by route.
        return "POST".equalsIgnoreCase(method);
    }

    public static String action(String method) {
        // A generic request interceptor cannot infer CREATE/UPDATE for approve,
        // transfer, publish, and other domain commands from HTTP verb alone.
        if ("POST".equalsIgnoreCase(method)) return "OTHER";
        if ("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) return "UPDATE";
        if ("DELETE".equalsIgnoreCase(method)) return "DELETE";
        return "OTHER";
    }

    public static String safeToken(Object value, int max) {
        if (value == null) return "-";
        String text = sanitizeText(String.valueOf(value), max);
        return text.isBlank() ? "-" : text;
    }

    public static String sanitizeText(String value, int max) {
        if (value == null) return null;
        String clean = CONTROL.matcher(value.replace("\\\"", "\"")).replaceAll(" ");
        clean = SECRET.matcher(clean).replaceAll("$1=[REDACTED]");
        clean = clean.replaceAll("(?i)\\bBearer\\s+\\S+", "Bearer [REDACTED]");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    public static String entityType(String route) {
        String[] parts = routeParts(route);
        return parts.length == 0 ? null : safeToken(parts[0], 200);
    }

    public static String entityId(String route) {
        String[] parts = routeParts(route);
        if (parts.length < 2) return null;
        String candidate = parts[1];
        return candidate.matches("[A-Za-z0-9_-]{1,100}") ? candidate : null;
    }

    private static String[] routeParts(String route) {
        if (route == null) return new String[0];
        String[] raw = route.split("/");
        if (raw.length < 4) return new String[0];
        String[] parts = new String[raw.length - 4];
        System.arraycopy(raw, 4, parts, 0, parts.length);
        return parts;
    }
}
