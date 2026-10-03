package com.cretas.aims.config;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JWT认证拦截器
 * 自动从Authorization header中提取JWT token，解析userId和username并注入到request attributes中
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-11-20
 */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthInterceptor.class);

    // 平台管理员角色列表，可访问所有工厂
    private static final Set<String> PLATFORM_ADMIN_ROLES = Set.of(
            "super_admin", "platform_admin", "developer"
    );

    // 匹配URL中的factoryId的正则表达式
    private static final Pattern FACTORY_ID_PATTERN = Pattern.compile("/api/mobile/([^/]+)/");

    // 平台API路径前缀
    private static final String PLATFORM_API_PREFIX = "/api/platform";

    // Internal API shared secret (Python→Java calls)
    private static final String INTERNAL_API_SECRET = System.getenv("INTERNAL_API_SECRET") != null
            ? System.getenv("INTERNAL_API_SECRET") : "";

    /**
     * 无工厂前缀但**必须登录**的路径。
     *
     * <p>2026-07-29 发现的洞：本拦截器的 401 检查只在
     * {@link #extractFactoryIdFromUrl} 能从 URL 里解析出 factoryId 时才跑，
     * 而那个方法把 {@code ai} / {@code upload} / {@code workflow} / {@code system} 等
     * 顶层前缀写进了排除名单（它们确实不是 factoryId）。结果这些路径既不做租户校验、
     * 也**不做登录校验**，直接落到方法末尾的 {@code return true}。
     *
     * <p>prod 实测：匿名 {@code POST /api/mobile/ai/chat} 返回真实 LLM 回复 ——
     * 等于对公网开放了一个计费到自家 DashScope 账号的模型出口，
     * 限流还会因为拿不到 userId 回落成 per-IP，换 IP 即绕过。
     *
     * <p>这里先按端点补最要命的两条；{@code /ai/health} 之类仍由
     * {@link #isPublicEndpoint} 放行。彻底的修法是把整个拦截器翻成「默认要求登录 +
     * 白名单放行」，但那要先证明白名单对启动期/激活/分享等匿名流程是完整的，
     * 单独一轮做。
     */
    private static final String[] AUTH_REQUIRED_GLOBAL_PREFIXES = {
            "/api/mobile/ai/",       // 通用 LLM 通道 (chat / chat/stream)
            "/api/mobile/upload",    // 文件上传 (唯一调用方 mobileApiClient 走 apiClient, 自带 token)
    };

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private FactoryRepository factoryRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * 工厂关停闸 (2026-09-15). factories.is_active=false 的工厂, 其已签发的 access token
     * (24h 有效) 也必须被挡, 否则关厂后 App 里已登录的人还能用一整天.
     * 文案与 MobileAuthServiceImpl.FACTORY_CLOSED_MESSAGE 同一句 —— 用户在登录页看到的
     * 和被踢出时看到的是同一句话.
     *
     * <p>每请求查一次库太贵, 状态按 factoryId 缓存 60s: 关厂最多 1 分钟后全员下线.
     */
    static final String FACTORY_CLOSED_MESSAGE = "当前服务已关闭";
    private static final Duration FACTORY_STATUS_TTL = Duration.ofSeconds(60);

    private final Cache<String, Boolean> factoryClosedCache = Caffeine.newBuilder()
            .expireAfterWrite(FACTORY_STATUS_TTL)
            .maximumSize(1_000)
            .build();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS preflight: 浏览器 OPTIONS 请求不携带 Authorization，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // Extract token: try Authorization header first (mobile), then cookie fallback (web)
        String token = extractToken(request);

        String tokenFactoryId = null;
        String tokenRole = null;
        Long userId = null;

        if (token != null) {
            try {
                // 验证token
                if (jwtUtil.validateToken(token)) {
                    // 检查token是否已被撤销（登出后的黑名单检查）
                    if (tokenBlacklistService.isBlacklisted(token)) {
                        log.warn("Token已被撤销（用户已登出）");
                        sendUnauthorizedResponse(response, "Token已失效，请重新登录");
                        return false;
                    }

                    // 提取userId
                    userId = jwtUtil.getUserIdFromToken(token);
                    if (userId != null) {
                        request.setAttribute("userId", userId);
                        log.debug("从JWT提取userId: {}", userId);
                    }

                    // 提取username
                    String username = jwtUtil.getUsernameFromToken(token);
                    if (username != null) {
                        request.setAttribute("username", username);
                        log.debug("从JWT提取username: {}", username);
                    }

                    // 提取factoryId (可选)
                    tokenFactoryId = jwtUtil.getFactoryIdFromToken(token);
                    if (tokenFactoryId != null) {
                        request.setAttribute("factoryId", tokenFactoryId);
                        log.debug("从JWT提取factoryId: {}", tokenFactoryId);
                    }

                    // 提取role (可选)
                    tokenRole = jwtUtil.getRoleFromToken(token);
                    if (tokenRole != null) {
                        request.setAttribute("role", tokenRole);
                        log.debug("从JWT提取role: {}", tokenRole);
                    }
                } else {
                    log.warn("无效的JWT token");
                }
            } catch (Exception e) {
                log.error("解析JWT token失败: {}", e.getMessage());
            }
        } else {
            log.debug("请求未包含Authorization header或有效cookie");
        }

        // 跨工厂权限验证
        String requestUri = request.getRequestURI();

        if (userId != null && tokenFactoryId != null
                && !isPlatformPrincipal(tokenFactoryId, tokenRole)
                && !isPublicEndpoint(requestUri)
                && !requestUri.startsWith("/api/internal/")) {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || !Boolean.TRUE.equals(user.getIsActive())
                    || !tokenFactoryId.equals(user.getFactoryId())) {
                sendUnauthorizedResponse(response, "账号已被禁用或不存在，请重新登录");
                return false;
            }
        }

        // 工厂关停闸: 已登录的工厂用户, 其工厂 is_active=false → 401 踢回登录页.
        // 公开端点 (登录/刷新) 不在这里挡 —— 服务层 rejectIfFactoryClosed 用同一句话拒.
        // 401 而非 403 是有意的: 前端对 401 走「refresh → 失败 → 清凭证 → 登录页」,
        // 403 只会在当前屏弹一条错, 会话仍挂着.
        if (userId != null && tokenFactoryId != null
                && !isPlatformPrincipal(tokenFactoryId, tokenRole)
                && !isPublicEndpoint(requestUri)
                && isFactoryClosed(tokenFactoryId)) {
            log.warn("工厂已关停, 拒绝在线会话: userId={}, factoryId={}, uri={}", userId, tokenFactoryId, requestUri);
            sendUnauthorizedResponse(response, FACTORY_CLOSED_MESSAGE, "该工厂服务已停用, 如有疑问请联系平台管理员");
            return false;
        }

        // Admin API 权限验证 — 仅允许已认证用户(平台管理员或工厂管理员)访问
        if (requestUri.startsWith("/api/admin/")) {
            if (userId == null) {
                log.warn("Admin API 未认证请求: uri={}", requestUri);
                sendUnauthorizedResponse(response, "未授权，请先登录");
                return false;
            }
            log.debug("Admin API 认证通过: userId={}, role={}, uri={}", userId, tokenRole, requestUri);
            return true;
        }

        // 平台API权限验证 - BUG-044修复: 只有平台管理员才能访问/api/platform/**
        if (requestUri.startsWith(PLATFORM_API_PREFIX)) {
            // 首先检查是否有有效的认证信息
            if (userId == null) {
                log.warn("平台API未认证请求: uri={}", requestUri);
                sendUnauthorizedResponse(response, "未授权，请先登录");
                return false;
            }

            // 检查是否有平台管理员角色
            if (tokenRole == null || !PLATFORM_ADMIN_ROLES.contains(tokenRole.toLowerCase())) {
                log.warn("平台API权限不足: userId={}, role={}, uri={}",
                        userId, tokenRole, requestUri);
                sendForbiddenResponse(response, "无权访问平台管理API，需要平台管理员权限");
                return false;
            }

            log.debug("平台API权限验证通过: userId={}, role={}", userId, tokenRole);
            return true;
        }

        // Internal API: validate shared secret (Python→Java calls)
        if (requestUri.startsWith("/api/internal/")) {
            String internalKey = request.getHeader("X-Internal-Key");
            if (INTERNAL_API_SECRET.isEmpty() || !INTERNAL_API_SECRET.equals(internalKey)) {
                log.warn("内部API认证失败: uri={}, key={}", requestUri,
                        internalKey != null ? "provided-but-invalid" : "missing");
                sendForbiddenResponse(response, "内部API认证失败");
                return false;
            }
            log.debug("内部API认证通过: uri={}", requestUri);
            return true;
        }

        // 无工厂前缀但必须登录的路径 (见 AUTH_REQUIRED_GLOBAL_PREFIXES 注释)。
        // 放在 factoryId 校验之前 —— 这些 URI 本来就取不出 factoryId, 走不到下面那段。
        if (requiresGlobalAuth(requestUri) && !isPublicEndpoint(requestUri)) {
            if (userId == null) {
                log.warn("未认证请求 (无工厂前缀端点): uri={}", requestUri);
                sendUnauthorizedResponse(response, "未授权，请先登录");
                return false;
            }
        }

        String urlFactoryId = extractFactoryIdFromUrl(requestUri);

        if (urlFactoryId != null && !isPublicEndpoint(requestUri)) {
            // 首先检查是否有有效的认证信息（401 场景）
            if (userId == null || (tokenFactoryId == null && tokenRole == null)) {
                log.warn("未认证请求: userId={}, tokenFactoryId={}, urlFactoryId={}",
                        userId, tokenFactoryId, urlFactoryId);
                sendUnauthorizedResponse(response, "未授权，请先登录");
                return false;
            }

            // URL中包含factoryId，需要验证权限（403 场景）
            if (!validateFactoryAccess(urlFactoryId, tokenFactoryId, tokenRole)) {
                log.warn("跨工厂访问被拒绝: userId={}, tokenFactoryId={}, urlFactoryId={}, role={}",
                        userId, tokenFactoryId, urlFactoryId, tokenRole);
                sendForbiddenResponse(response, "无权访问该工厂数据");
                return false;
            }
        }

        // 继续处理请求
        return true;
    }

    /**
     * 平台级主体 (PLATFORM 工厂 ID 或平台管理员角色) 不受工厂关停闸约束 —— 关厂是他们操作的.
     */
    private boolean isPlatformPrincipal(String tokenFactoryId, String tokenRole) {
        return "PLATFORM".equals(tokenFactoryId)
                || (tokenRole != null && PLATFORM_ADMIN_ROLES.contains(tokenRole.toLowerCase()));
    }

    /**
     * 工厂是否已关停 (is_active=false). 只认「明确关停」: 工厂行不存在 / isActive 为 null
     * 一律视为未关停 —— 这道闸不把数据缺口翻译成关停 (那是另一类问题, 由别处报).
     * 结果按 factoryId 缓存 {@link #FACTORY_STATUS_TTL}.
     */
    boolean isFactoryClosed(String factoryId) {
        Boolean closed = factoryClosedCache.get(factoryId, id ->
                factoryRepository.findById(id)
                        .map(Factory::getIsActive)
                        .map(active -> Boolean.FALSE.equals(active))
                        .orElse(false));
        return Boolean.TRUE.equals(closed);
    }

    /**
     * 从URL中提取factoryId
     */
    private String extractFactoryIdFromUrl(String uri) {
        Matcher matcher = FACTORY_ID_PATTERN.matcher(uri);
        if (matcher.find()) {
            String factoryId = matcher.group(1);
            // 排除非factoryId的路径部分
            if (!"auth".equals(factoryId) && !"activation".equals(factoryId)
                    && !"health".equals(factoryId) && !"upload".equals(factoryId)
                    && !"voice".equals(factoryId) && !"ai".equals(factoryId)
                    && !"scale-protocols".equals(factoryId) && !"dashboard".equals(factoryId)
                    && !"workflow".equals(factoryId)   // /api/mobile/workflow/* are global endpoints (node-schemas, validate)
                    && !"admin".equals(factoryId)      // /api/mobile/admin/* are cross-factory admin endpoints (cache management etc)
                    && !"smartbi-config".equals(factoryId)  // /api/mobile/smartbi-config/* uses ?factoryId= query param (R21-F4 fix)
                    && !"system".equals(factoryId)  // Issue #836 (2026-05-17): /api/mobile/system/* is global (release-notes/status/info), not factory-scoped
                    && !"scheduled-tasks".equals(factoryId)) {  // Phase 5 Canvas-Cron (2026-05-19): /api/mobile/scheduled-tasks/* is global cron management (per-factory + NULL=global tasks both managed here)
                return factoryId;
            }
        }
        return null;
    }

    /**
     * 该 URI 是否属于「无工厂前缀但必须登录」的一类。
     * 包级可见, 便于 {@code JwtAuthInterceptorGlobalAuthTest} 直接断言路径判定。
     */
    boolean requiresGlobalAuth(String uri) {
        if (uri == null) {
            return false;
        }
        for (String prefix : AUTH_REQUIRED_GLOBAL_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否为公开端点（不需要权限验证）
     */
    boolean isPublicEndpoint(String uri) {
        return uri.equals("/api/mobile/auth/login") ||  // 登录（精确匹配）
               uri.equals("/api/mobile/auth/unified-login") ||  // 统一登录
               uri.equals("/api/mobile/auth/demo-login") ||  // 演示账号免密登录 (路演)
               uri.equals("/api/mobile/auth/register") ||  // 注册
               uri.equals("/api/mobile/auth/refresh") ||  // 刷新token
               uri.equals("/api/mobile/auth/refresh-token") ||  // 刷新token（别名）
               uri.startsWith("/api/mobile/activation/") ||  // 设备激活
               uri.equals("/api/mobile/health") ||  // 健康检查（精确匹配）
               uri.equals("/api/mobile/version/check") ||  // 版本检查（匿名 — App 启动期强制更新判定，登录前可达）
               uri.equals("/api/mobile/voice/health") ||  // 语音模块健康检查
               uri.equals("/api/mobile/ai/health") ||  // AI模块健康检查
               uri.startsWith("/api/mobile/edge/upload") ||  // IoT 边缘设备上传（无 JWT）
               uri.startsWith("/api/mobile/voice/") ||  // 语音识别接口（顶层，无工厂）
               // Issue #789 follow-up to PR #785 / #718 (2026-05-17): /link-survey-company
               // and /field-visibility/recompute have been moved to /api/internal/* which is
               // protected by X-Internal-Key header check at line 155 above. The previous
               // public-endpoint carve-outs allowed any unauthenticated client to trigger
               // schema-level recompute / re-link survey company — that gap is now closed.
               uri.startsWith("/api/public/");  // 公开溯源查询接口（SmartBI分享等）
    }

    /**
     * 验证工厂访问权限
     */
    private boolean validateFactoryAccess(String urlFactoryId, String tokenFactoryId, String tokenRole) {
        // 未登录用户不能访问工厂数据
        if (tokenFactoryId == null && tokenRole == null) {
            return false;
        }

        // 平台管理员可访问所有工厂（大小写不敏感）
        if (tokenRole != null && PLATFORM_ADMIN_ROLES.contains(tokenRole.toLowerCase())) {
            return true;
        }

        // PLATFORM工厂ID表示平台级用户
        if ("PLATFORM".equals(tokenFactoryId)) {
            return true;
        }

        // 普通用户只能访问自己工厂的数据
        return urlFactoryId.equals(tokenFactoryId);
    }

    /**
     * 发送401 Unauthorized响应 (格式与 PermissionInterceptor.sendPermissionDenied 对齐 —
     * severity + actionHint 让前端 axios interceptor ElNotification sticky 处理一致).
     */
    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        sendUnauthorizedResponse(response, message, "会话已过期或未登录, 请重新登录");
    }

    private void sendUnauthorizedResponse(HttpServletResponse response, String message, String actionHint)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> errorResponse = new java.util.LinkedHashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("code", 401);
        errorResponse.put("message", message);
        errorResponse.put("severity", "error");
        errorResponse.put("actionHint", actionHint);
        errorResponse.put("timestamp", java.time.LocalDateTime.now().toString());

        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(errorResponse));
    }

    /**
     * 发送403 Forbidden响应 (格式与 PermissionInterceptor 对齐).
     * 这条路径主要用于跨工厂访问被拒 / 内部API认证失败, actionHint 相应调整.
     */
    private void sendForbiddenResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> errorResponse = new java.util.LinkedHashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("code", 403);
        errorResponse.put("message", message);
        errorResponse.put("severity", "error");
        errorResponse.put("actionHint", "请检查是否访问了错误的工厂, 或切换到有权限的账号重试");
        errorResponse.put("timestamp", java.time.LocalDateTime.now().toString());

        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(errorResponse));
    }

    /**
     * Extract JWT token from the request.
     * Priority: Authorization header (mobile) > cookie (web admin).
     */
    private String extractToken(HttpServletRequest request) {
        // 1. Try Authorization header first (mobile clients)
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }

        // 2. Fallback: try HttpOnly cookie (web admin clients)
        return CookieAuthHelper.extractCookieValue(request, CookieAuthHelper.ACCESS_TOKEN_COOKIE);
    }
}
