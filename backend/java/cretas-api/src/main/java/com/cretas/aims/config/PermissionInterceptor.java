package com.cretas.aims.config;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.permission.PermissionModuleCatalog;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 权限拦截器
 *
 * 处理 @RequirePermission 注解，检查用户权限
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-12-27
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final PermissionService permissionService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // 构造器注入 - 确保依赖按正确顺序创建
    public PermissionInterceptor(PermissionService permissionService,
                                  UserRepository userRepository,
                                  ObjectMapper objectMapper) {
        this.permissionService = permissionService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // 只处理 HandlerMethod (Controller方法)
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;

        // 获取方法或类上的 @RequirePermission 注解
        RequirePermission methodAnnotation = handlerMethod.getMethodAnnotation(RequirePermission.class);
        RequirePermission classAnnotation = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);

        // 优先使用方法注解，如果没有则使用类注解
        RequirePermission annotation = methodAnnotation != null ? methodAnnotation : classAnnotation;

        // 没有注解，放行
        if (annotation == null) {
            return true;
        }

        // 获取当前用户
        User user = getCurrentUser(request);
        if (user == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "用户未登录");
            return false;
        }

        // 检查权限
        String[] requiredPermissions = annotation.value();
        boolean hasPermission;

        if (annotation.requireAll()) {
            hasPermission = permissionService.hasAllPermissions(user, requiredPermissions);
        } else {
            hasPermission = permissionService.hasAnyPermission(user, requiredPermissions);
        }

        if (!hasPermission) {
            sendPermissionDenied(response, user, requiredPermissions, annotation);
            return false;
        }

        return true;
    }

    /**
     * 权限不足响应 — 按 qa-prompt v2.2 Rule 8 四位一体要求构造 rich body:
     *   message:     具体描述哪个角色 + 哪个模块 + 哪个动作 被拒
     *   actionHint:  明确 next action (联系管理员 / 切号)
     *   severity:    "error" 触发前端 ElNotification sticky + closeBtn
     *   hintTarget:  (可选) 前端 pulseHintTarget
     */
    private void sendPermissionDenied(HttpServletResponse response, User user,
                                       String[] requiredPermissions,
                                       RequirePermission annotation) throws Exception {
        String roleCode = user.getRoleEnum() == null ? "unknown" : user.getRoleEnum().name();
        String roleLabel = roleLabel(user.getRoleEnum());

        // 解析所有 required perms. requireAll=false → 任一, true → 全部.
        // 构造人类可读的 moduleLabel list (去重) 供 message/actionHint 使用.
        java.util.List<String> moduleLabels = new java.util.ArrayList<>();
        java.util.List<String> actionLabels = new java.util.ArrayList<>();
        java.util.List<Map<String, String>> permList = new java.util.ArrayList<>();
        String primaryModule = "";
        String primaryAction = "read_write";
        for (int i = 0; i < requiredPermissions.length; i++) {
            String code = requiredPermissions[i];
            String[] parts = code.split(":", 2);
            String m = parts.length > 0 ? parts[0] : "";
            String a = parts.length > 1 ? parts[1] : "read_write";
            if (i == 0) { primaryModule = m; primaryAction = a; }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("module", m);
            entry.put("action", a);
            permList.add(entry);
            String mLabel = moduleLabel(m);
            String aLabel = actionLabel(a);
            if (!moduleLabels.contains(mLabel)) moduleLabels.add(mLabel);
            if (!actionLabels.contains(aLabel)) actionLabels.add(aLabel);
        }

        String joiner = annotation.requireAll() ? " 和 " : " 或 ";
        String moduleDesc = String.join(joiner, moduleLabels);
        String actionDesc = String.join(" / ", actionLabels);

        // 如果 annotation.message() 不是默认文案, 尊重自定义
        String customMsg = annotation.message();
        boolean isDefault = customMsg == null || customMsg.isEmpty()
                || "权限不足，无法访问此资源".equals(customMsg);
        String message;
        if (isDefault) {
            if (moduleLabels.size() > 1) {
                message = String.format("您的角色 [%s] 缺少 %s 模块的 [%s] 权限 (需%s)",
                        roleLabel, moduleDesc, actionDesc,
                        annotation.requireAll() ? "全部" : "任一");
            } else {
                message = String.format("您的角色 [%s] 在 [%s] 模块无 [%s] 权限",
                        roleLabel, moduleDesc, actionDesc);
            }
        } else {
            message = customMsg;
        }

        String actionHint;
        if (moduleLabels.size() > 1) {
            actionHint = "请联系工厂管理员在 Canvas → 模块权限 矩阵为角色 [" + roleLabel
                    + "] 开通 " + moduleDesc + " 的 [" + actionDesc + "] 权限"
                    + (annotation.requireAll() ? " (全部需要)" : " (任一即可)")
                    + ", 或切换到有权限的账号重试";
        } else {
            actionHint = "请联系工厂管理员在 Canvas → 模块权限 矩阵为角色 [" + roleLabel
                    + "] 开通 [" + moduleDesc + "] 的 [" + actionDesc + "] 权限, 或切换到有权限的账号重试";
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "FORBIDDEN");
        body.put("message", message);
        body.put("severity", "error");
        body.put("actionHint", actionHint);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("role", roleCode);
        meta.put("module", primaryModule);
        meta.put("action", primaryAction);
        meta.put("requireAll", annotation.requireAll());
        meta.put("requiredPermissions", permList);
        body.put("meta", meta);

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private String roleLabel(FactoryUserRole role) {
        if (role == null) return "未知角色";
        try {
            // FactoryUserRole 的构造参数第一个就是中文名 (description)
            return role.getDisplayName();
        } catch (Throwable t) {
            return role.name();
        }
    }

    /**
     * 2026-09-07 刀 D: 这里原本是一份 17 条的私有 switch —— 全仓第三份模块中文名表, 且与
     * {@code RoleController.DISPLAY_MODULES} 在 5 个码上已经漂了。措辞逐字搬进
     * {@link PermissionModuleCatalog} (它采用的正是本方法的措辞, 因为 5 处冲突里侧边栏
     * 逐条站在这一边) ⇒ <b>本方法的返回值一个字都没变</b>,
     * {@code PermissionModuleCatalogRefactorIsLosslessTest} 逐码钉住。
     */
    private String moduleLabel(String moduleCode) {
        return PermissionModuleCatalog.label(moduleCode);
    }

    private String actionLabel(String action) {
        switch (action) {
            case "read": return "读取";
            case "write": return "写入";
            case "read_write": return "读写";
            case "create": return "创建";
            case "approve": return "审批";
            default: return action;
        }
    }

    /**
     * 获取当前登录用户
     */
    private User getCurrentUser(HttpServletRequest request) {
        // 从请求属性中获取用户ID (由 JwtAuthInterceptor 设置)
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj == null) {
            return null;
        }

        Long userId;
        if (userIdObj instanceof Long) {
            userId = (Long) userIdObj;
        } else if (userIdObj instanceof Integer) {
            userId = ((Integer) userIdObj).longValue();
        } else if (userIdObj instanceof String) {
            try {
                userId = Long.parseLong((String) userIdObj);
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }

        Optional<User> userOpt = userRepository.findById(userId);
        return userOpt.orElse(null);
    }

    /**
     * 发送错误响应 (401 未登录场景, 格式与 sendPermissionDenied 对齐).
     * 401 场景用户未登录, actionHint 指向"重新登录" (前端 axios interceptor 看到
     * 401 会自动尝试 refresh → 失败才跳 /login, 所以 hint 主要给没有 refresh 的
     * fetch 层代码读).
     */
    private void sendError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("success", false);
        errorBody.put("code", status == HttpServletResponse.SC_UNAUTHORIZED ? "UNAUTHORIZED" : "FORBIDDEN");
        errorBody.put("message", message);
        errorBody.put("severity", "error");
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            errorBody.put("actionHint", "会话已过期, 请重新登录");
        }

        response.getWriter().write(objectMapper.writeValueAsString(errorBody));
    }
}
