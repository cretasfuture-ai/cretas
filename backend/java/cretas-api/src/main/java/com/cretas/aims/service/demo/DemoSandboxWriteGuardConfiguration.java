package com.cretas.aims.service.demo;

import com.cretas.aims.controller.FormAssistantController;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.utils.SecurityUtils;
import com.cretas.aims.utils.TokenUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Prevents an active sandbox session from silently writing production data. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cretas.demo.sandbox.enabled", havingValue = "true")
public class DemoSandboxWriteGuardConfiguration implements WebMvcConfigurer {

    private final DemoSandboxWriteGuardInterceptor interceptor;

    public DemoSandboxWriteGuardConfiguration(DemoSandboxGatewayService gateway,
                                              MobileService mobileService,
                                              UserRepository userRepository) {
        this.interceptor = new DemoSandboxWriteGuardInterceptor(gateway, mobileService, userRepository);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).order(2);
    }

    static final class DemoSandboxWriteGuardInterceptor implements HandlerInterceptor {
        private final DemoSandboxGatewayService gateway;
        private final MobileService mobileService;
        private final UserRepository userRepository;

        DemoSandboxWriteGuardInterceptor(DemoSandboxGatewayService gateway,
                                         MobileService mobileService,
                                         UserRepository userRepository) {
            this.gateway = gateway;
            this.mobileService = mobileService;
            this.userRepository = userRepository;
        }

        @Override
        public boolean preHandle(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler) {
            if (!isProtectedWrite(request, handler)) return true;
            User actor = currentTrustedActor(request);
            if (actor == null) return true;
            gateway.activeSession(actor).ifPresent(active -> {
                throw new BusinessException(409,
                        "当前账号仍有活动的演示沙盒会话，不能写入生产数据")
                        .withCode("DEMO_SANDBOX_ACTIVE_SESSION")
                        .withHint("请先恢复或结束演示沙盒会话，再继续生产业务操作");
            });
            return true;
        }

        private boolean isProtectedWrite(HttpServletRequest request, Object handler) {
            if (handler instanceof HandlerMethod method
                    && method.getBeanType().getPackageName().equals("com.cretas.aims.controller.demo")) {
                return false;
            }
            String uri = request.getRequestURI();
            String method = request.getMethod();
            if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                    || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))) {
                return false;
            }
            if ("POST".equalsIgnoreCase(method)
                    && handler instanceof HandlerMethod formHandler
                    && formHandler.getBeanType() == FormAssistantController.class
                    && "parseFormInput".equals(formHandler.getMethod().getName())) {
                return false;
            }
            return uri != null && !isCanonicalAuthPath(uri);
        }

        private boolean isCanonicalAuthPath(String uri) {
            String action = null;
            if (uri.startsWith("/api/mobile/auth/")) {
                action = uri.substring("/api/mobile/auth/".length());
            } else {
                java.util.regex.Matcher match = java.util.regex.Pattern
                        .compile("^/api/mobile/[^/]+/auth/([^/]+)$")
                        .matcher(uri);
                if (match.matches()) action = match.group(1);
            }
            // Only authentication establishment/rotation/termination bypasses the production-write fence.
            return "login".equals(action)
                    || "unified-login".equals(action)
                    || "demo-login".equals(action)
                    || "refresh".equals(action)
                    || "logout".equals(action);
        }

        private User currentTrustedActor(HttpServletRequest request) {
            Long requestUserId = SecurityUtils.currentUserId();
            if (requestUserId != null) {
                return requestUserId == DemoSandboxGatewayService.DEMO_ACTOR_ID
                        ? lookupTrustedActor(requestUserId) : null;
            }
            String authorization = request.getHeader("Authorization");
            if (authorization == null || authorization.isBlank()) return null;
            try {
                String token = TokenUtils.extractToken(authorization);
                if (token == null || token.isBlank()) return null;
                com.cretas.aims.dto.user.UserDTO tokenUser = mobileService.getUserFromToken(token);
                if (tokenUser == null || tokenUser.getId() == null
                        || tokenUser.getId() != DemoSandboxGatewayService.DEMO_ACTOR_ID) return null;
                return lookupTrustedActor(tokenUser.getId());
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                return null;
            }
        }

        private User lookupTrustedActor(Long userId) {
            try {
                User actor = userRepository.findById(userId).orElse(null);
                if (actor == null) {
                    throw new BusinessException(401, "未授权 — 用户不存在")
                            .withCode("DEMO_SANDBOX_AUTH_REQUIRED");
                }
                return DemoSandboxGatewayService.FACTORY_ID.equals(actor.getFactoryId()) ? actor : null;
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException(503, "演示沙盒操作人暂时无法核验", e)
                        .withCode("DEMO_SANDBOX_ACTOR_LOOKUP_FAILED");
            }
        }
    }
}
