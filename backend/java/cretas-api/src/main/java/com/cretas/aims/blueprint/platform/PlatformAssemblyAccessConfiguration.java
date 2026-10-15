package com.cretas.aims.blueprint.platform;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.utils.JwtUtil;
import com.cretas.aims.utils.TokenUtils;
import com.cretas.aims.utils.CookieAuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Additional boundary for assembly only; the existing factory authentication chain is untouched. */
@Configuration
public class PlatformAssemblyAccessConfiguration implements WebMvcConfigurer {
    private final JwtUtil jwt;
    private final PlatformAdminRepository admins;
    private final boolean enabled;

    public PlatformAssemblyAccessConfiguration(JwtUtil jwt, PlatformAdminRepository admins,
            @Value("${cretas.platform.factory-assembly.enabled:false}") boolean enabled) {
        this.jwt = jwt;
        this.admins = admins;
        this.enabled = enabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
                authorize(request);
                if (!enabled && !request.getRequestURI().equals("/api/platform/factory-assembly/capabilities")) {
                    throw new BusinessException(404, "平台建厂能力未启用");
                }
                return true;
            }
        }).addPathPatterns("/api/platform/factory-assembly/**",
                "/api/platform/factories/*/ai-initialize/**").order(2);
    }

    void authorize(HttpServletRequest request) {
        // The issuer explicitly marks platform login/refresh. Never infer a namespace from
        // the role, numeric ID, factory sentinel alone, or a client-supplied request attribute.
        String authorization = request.getHeader("Authorization");
        String token = authorization == null ? CookieAuthHelper.extractCookieValue(request, CookieAuthHelper.ACCESS_TOKEN_COOKIE)
                : TokenUtils.extractToken(authorization);
        if (token == null || !jwt.validateToken(token)) throw new BusinessException(401, "请先登录平台账号");
        if (!jwt.isExplicitPlatformToken(token)) {
            throw new BusinessException(403, "仅平台管理员可以使用建厂能力");
        }
        Long id = jwt.getUserIdFromToken(token);
        String claimedFactory = jwt.getFactoryIdFromToken(token);
        if (id == null || id <= 0 || (claimedFactory != null && !claimedFactory.isBlank()
                && !"PLATFORM".equalsIgnoreCase(claimedFactory))) {
            throw new BusinessException(403, "平台建厂必须使用全局平台身份");
        }
        var admin = admins.findById(id).filter(value -> value.isActive())
                .orElseThrow(() -> new BusinessException(403, "平台账号不存在或已停用"));
        if (admin.getPlatformRole() != PlatformRole.super_admin) {
            throw new BusinessException(403, "当前平台角色没有建厂权限");
        }
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, id, null,
                        admin.getPlatformRole().name()));
    }

    /** Available while disabled, so UI does not guess server configuration from a build flag. */
    @RestController
    public static class CapabilitiesController {
        private final boolean enabled;

        public CapabilitiesController(@Value("${cretas.platform.factory-assembly.enabled:false}") boolean enabled) {
            this.enabled = enabled;
        }

        public record Capabilities(boolean enabled) { }

        @GetMapping("/api/platform/factory-assembly/capabilities")
        public ApiResponse<Capabilities> capabilities() {
            return ApiResponse.success(new Capabilities(enabled));
        }
    }
}
