package com.cretas.aims.config;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.service.mobile.WebLoginSessionService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;

/** Adapt an authorized Web session to existing JWT consumers without immortal JWTs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class WebLoginSessionFilter extends OncePerRequestFilter {
    public static final String ORIGINAL_ACCESS_TOKEN = WebLoginSessionFilter.class.getName() + ".originalAccessToken";
    public static final String VERIFIED_SESSION = WebLoginSessionFilter.class.getName() + ".verifiedSession";
    public static final String VERIFIED_BOUND_ACCESS = WebLoginSessionFilter.class.getName() + ".verifiedBoundAccess";
    private final WebLoginSessionService sessions;
    private final CookieAuthHelper cookies;
    private final JwtUtil jwt;
    private final JwtAuthInterceptor auth;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean hasSession = CookieAuthHelper.extractCookieValue(request, CookieAuthHelper.WEB_SESSION_COOKIE) != null;
        String clientType = request.getHeader(CookieAuthHelper.CLIENT_TYPE_HEADER);
        String authorization = request.getHeader("Authorization");
        boolean explicitBearer = authorization != null && authorization.startsWith("Bearer ");
        boolean nativeClient = !cookies.isWebClient(request) && (clientType != null || explicitBearer);
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || nativeClient
                || !(path.startsWith("/api/mobile/") || path.startsWith("/api/platform/"))
                || (auth.isPublicEndpoint(path) && (!hasSession || path.endsWith("/health")
                    || path.startsWith("/api/mobile/activation/") || path.equals("/api/mobile/version/check")
                    || (auth.isWebCredentialExchange(path)
                        && !path.equals("/api/mobile/auth/refresh") && !path.equals("/api/mobile/auth/refresh-token"))));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String secret = CookieAuthHelper.extractCookieValue(request, CookieAuthHelper.WEB_SESSION_COOKIE);
        if (secret == null) {
            chain.doFilter(request, response);
            return;
        }
        String access;
        try {
            access = sessions.resolve(secret);
        } catch (BusinessException denied) {
            cookies.clearAuthCookies(response);
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"code\":401,\"message\":\"登录已失效，请重新登录\"}");
            return;
        } catch (DataAccessException unavailable) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"code\":503,\"message\":\"登录状态暂时无法核验，请稍后重试\"}");
            return;
        }
        String original = request.getHeader("Authorization");
        boolean platform = jwt.isExplicitPlatformToken(access);
        var expiresAt = jwt.getExpirationDateFromToken(access);
        request.setAttribute(VERIFIED_SESSION, MobileDTO.LoginResponse.builder()
                .token(access).actorType(platform ? "PLATFORM_ADMIN" : "FACTORY_USER")
                .userId(jwt.getUserIdFromToken(access)).username(jwt.getUsernameFromToken(access))
                .factoryId(platform ? null : jwt.getFactoryIdFromToken(access))
                .expiresIn(expiresAt == null ? null : Math.max(0L, (expiresAt.getTime() - System.currentTimeMillis()) / 1000))
                .role(jwt.getRoleFromToken(access)).build());
        String originalToken = original != null && original.startsWith("Bearer ")
                ? original.substring(7).trim()
                : CookieAuthHelper.extractCookieValue(request, CookieAuthHelper.ACCESS_TOKEN_COOKIE);
        // A mixed browser/header must never revoke another actor's legacy session.
        if (jwt.validateToken(originalToken)
                && java.util.Objects.equals(jwt.getUserIdFromToken(originalToken), jwt.getUserIdFromToken(access))
                && java.util.Objects.equals(jwt.getFactoryIdFromToken(originalToken), jwt.getFactoryIdFromToken(access))
                && java.util.Objects.equals(jwt.getWebSessionHash(originalToken), jwt.getWebSessionHash(access))
                && jwt.isExplicitPlatformToken(originalToken) == jwt.isExplicitPlatformToken(access)) {
            request.setAttribute(ORIGINAL_ACCESS_TOKEN, originalToken);
        }
        if (!request.getRequestURI().equals("/api/mobile/auth/logout")) cookies.setWebSessionCookie(response, secret);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                return "Authorization".equalsIgnoreCase(name) ? "Bearer " + access : super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                return "Authorization".equalsIgnoreCase(name)
                        ? Collections.enumeration(Collections.singletonList("Bearer " + access)) : super.getHeaders(name);
            }
            @Override public Enumeration<String> getHeaderNames() {
                var names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
                names.add("Authorization");
                return Collections.enumeration(names);
            }
        }, response);
    }
}
