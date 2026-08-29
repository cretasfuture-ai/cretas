package com.cretas.aims.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
/**
 * 安全工具类
 *
 * @author Cretas Team
 * @version 1.0.0
 */
public class SecurityUtils {

    /**
     * 获取当前登录用户 ID —— <b>本项目唯一可用的取法</b>。
     *
     * <p>⛔ 不要用 {@link #getCurrentUserId()}：本项目<b>从不填充</b> Spring 的
     * {@code SecurityContextHolder}（全仓 {@code setAuthentication} 出现 <b>0 次</b>），
     * 鉴权由 {@code JwtAuthInterceptor.preHandle} 完成，它把身份写进
     * <b>request attribute</b>：{@code userId} / {@code username} / {@code factoryId} / {@code role}。
     * 于是那个方法在生产上<b>恒为 null</b>。
     *
     * <p>这不是新发现 —— 仓里已经有四处注释记着同一件事
     * （FactoryBlueprintController / FactoryNetworkController /
     * WarehouseInventoryController / ProductTypeController），
     * 每一处都是<b>就地绕开</b>：各自手抄一份读 attribute 的代码，没有人把它收成一份，
     * 也没有闸拦住下一个人再踩。<b>本方法就是那一份。</b>
     *
     * <p>⚠️ 2026-08-29 实测：{@code PUT /users/me/profile} 用
     * {@code getCurrentUserId()} 上线后返 <b>401 未登录</b>，
     * 而部署的三条判据（{@code DEPLOY_EXIT=0} / {@code RELEASE_FINAL_STATUS} 一次 /
     * {@code BACKEND_HEALTH=pass}）<b>全绿</b> —— 判据只证明「发上去了」，
     * 不证明「跑得起来」。
     *
     * @return 用户 ID；不在请求线程里（如 {@code @Async}）或未登录时返回 {@code null}
     */
    public static Long currentUserId() {
        return toLong(currentRequestAttribute("userId"));
    }

    /**
     * 获取当前登录用户名，来源同 {@link #currentUserId()}（request attribute）。
     */
    public static String currentUsername() {
        Object raw = currentRequestAttribute("username");
        return raw == null ? null : String.valueOf(raw);
    }

    /**
     * 获取 token 里的工厂 ID，来源同 {@link #currentUserId()}（request attribute）。
     */
    public static String currentFactoryId() {
        Object raw = currentRequestAttribute("factoryId");
        return raw instanceof String s ? s : null;
    }

    private static Object currentRequestAttribute(String name) {
        org.springframework.web.context.request.RequestAttributes attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs)) {
            return null;
        }
        return servletAttrs.getRequest().getAttribute(name);
    }

    private static Long toLong(Object raw) {
        if (raw instanceof Long l) {
            return l;
        }
        if (raw instanceof Integer i) {
            return i.longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 获取当前用户ID
     *
     * @return 用户ID
     */
    /** @deprecated 本项目从不填充 SecurityContextHolder，此方法恒返回 null。改用 {@link #currentUserId()}。 */
    @Deprecated
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long) {
            return (Long) principal;
        }
        if (principal instanceof Integer) {
            return ((Integer) principal).longValue();
        }
        // 如果是字符串，尝试解析为长整数
        if (principal instanceof String) {
            try {
                return Long.parseLong((String) principal);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 获取当前用户名
     *
     * @return 用户名
     */
    public static String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }

    /**
     * 检查当前用户是否有指定角色
     *
     * @param role 角色名
     * @return 是否有该角色
     */
    public static boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_" + role) ||
                                auth.getAuthority().equals(role));
    }

    /**
     * 检查当前用户是否有指定权限
     *
     * @param permission 权限名
     * @return 是否有该权限
     */
    public static boolean hasPermission(String permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals(permission));
    }
}
