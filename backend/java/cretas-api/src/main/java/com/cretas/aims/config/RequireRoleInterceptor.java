package com.cretas.aims.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * Round 5 Fix SEC-1: Enforces {@link RequireRole} annotations.
 *
 * Why custom instead of Spring Security @PreAuthorize:
 *   SecurityAutoConfiguration is excluded in all application profiles, so
 *   @PreAuthorize is silently NO-OP. See {@link RequireRole} for details.
 *
 * Flow:
 *   1. JwtAuthInterceptor runs first (order=1), sets request attribute "role".
 *   2. This interceptor runs after (order=3), reads method's @RequireRole.
 *   3. Platform super admins always pass (consistent with JwtAuthInterceptor:37-39).
 *   4. Otherwise user's role must match one of the annotation values (case-insensitive).
 *   5. On mismatch: 403 + JSON error body, returns false to short-circuit.
 */
@Slf4j
@Component
public class RequireRoleInterceptor implements HandlerInterceptor {

    // Kept in sync with JwtAuthInterceptor.PLATFORM_ADMIN_ROLES — platform admins bypass
    // factory-level role checks (same policy as factoryId URL check). Only an explicit platform
    // identity counts; a factory token naming one of these roles matches nothing here.
    private static final Set<String> PLATFORM_ADMIN_ROLES = Set.of(
            "super_admin", "platform_admin", "developer", "platform_super_admin"
    );

    private final ObjectMapper objectMapper;

    public RequireRoleInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequireRole methodAnn = handlerMethod.getMethodAnnotation(RequireRole.class);
        RequireRole classAnn = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        RequireRole ann = methodAnn != null ? methodAnn : classAnn;
        if (ann == null) {
            return true;
        }

        String[] allowed = ann.value();
        if (allowed.length == 0) {
            return true;
        }

        Object roleAttr = request.getAttribute("role");
        String role = roleAttr == null ? null : String.valueOf(roleAttr).trim().toLowerCase(Locale.ROOT);

        if (role == null) {
            log.warn("@RequireRole: missing role attribute, uri={}", request.getRequestURI());
            sendForbidden(response, "未登录或 Token 已失效", "UNAUTHORIZED");
            return false;
        }

        boolean platformIdentity = com.cretas.aims.security.AuthenticatedActor.current(request)
                .filter(com.cretas.aims.security.AuthenticatedActor::isPlatformAdmin).isPresent();
        if (platformIdentity && PLATFORM_ADMIN_ROLES.contains(role)) {
            return true;
        }

        boolean roleIsTrusted = platformIdentity || !com.cretas.aims.security.PlatformRoleBoundary.isPlatformTier(role);
        for (String allowedRole : allowed) {
            if (roleIsTrusted && allowedRole != null && allowedRole.trim().toLowerCase(Locale.ROOT).equals(role)) {
                return true;
            }
        }

        log.warn("@RequireRole: role '{}' not in allowed {} — uri={}",
                role, java.util.Arrays.toString(allowed), request.getRequestURI());
        sendForbidden(response,
                "当前角色无权访问此接口，需要: " + String.join(" / ", allowed),
                "FORBIDDEN");
        return false;
    }

    private void sendForbidden(HttpServletResponse response, String message, String code) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        body.put("code", code);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
