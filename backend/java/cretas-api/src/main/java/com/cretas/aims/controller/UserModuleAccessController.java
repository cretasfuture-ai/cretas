package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.UserModuleAccess;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.permission.ProductionModuleRegistry;
import com.cretas.aims.service.UserModuleAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas/user-module-access")
@RequiredArgsConstructor
@Tag(name = "User Module Access", description = "L4 per-account module access override")
public class UserModuleAccessController {

    private final UserModuleAccessService userModuleAccessService;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    @GetMapping("/registry")
    @Operation(summary = "List registered production module codes for user access UI")
    @RequirePermission({"system:read", "system:read_write"})
    public ApiResponse<List<ProductionModuleRegistry.ModuleDefinition>> registry() {
        return ApiResponse.success(ProductionModuleRegistry.modules());
    }

    /**
     * 🔴 2026-08-21: 放行「查自己」。
     *
     * 实测: web-admin 首页每次加载都调这个端点问「我能访问哪些模块」, 而销售主管等
     * 没有 system 权限的角色 ⇒ **每打开一个页面就报一次 403**(owner 截图里满屏都是它)。
     * ▎用户查**自己**的权限配置, 不该需要系统管理权限。
     *
     * ⛔ 但**不能直接去掉权限闸**: userId 是路径参数, 端点原本不校验「是不是查自己」——
     *    裸放行等于任何人都能查任意账号的权限配置。
     * ⇒ 改成: 查自己 -> 放行; 查别人 -> 仍要 system:read。
     *
     * ⛔ /registry(列全部模块定义) 与下面的写操作**不放宽**, 它们确实是管理动作。
     */
    @GetMapping("/{userId}")
    @Operation(summary = "Get effective user module access: role default plus user override")
    public ApiResponse<List<UserModuleAccessService.ModuleAccessView>> get(
            @PathVariable String factoryId,
            @PathVariable String userId,
            @RequestAttribute(value = "userId", required = false) Long callerId) {
        boolean self = callerId != null && String.valueOf(callerId).equals(userId);
        if (!self) {
            User caller = callerId == null ? null : userRepository.findById(callerId).orElse(null);
            // ⛔ caller 取不到一律拒绝 —— 「读不到」不等于「有权限」(fail-closed)
            if (caller == null
                    || !permissionService.hasAnyPermission(caller, "system:read", "system:read_write")) {
                throw new BusinessException(403, "只能查看自己的模块权限")
                        .withHint("查看他人的权限配置需要「系统管理」的读取权限，请联系工厂管理员。");
            }
        }
        return ApiResponse.success(userModuleAccessService.listEffectiveAccess(factoryId, userId));
    }

    @PutMapping("/{userId}/{module}")
    @Operation(summary = "Set user-level module access override")
    @RequirePermission({"system:read_write"})
    public ApiResponse<UserModuleAccess> update(
            @PathVariable String factoryId,
            @PathVariable String userId,
            @PathVariable String module,
            @RequestParam UserModuleAccess.AccessType action,
            @RequestParam(required = false) String remark,
            HttpServletRequest request) {
        UserModuleAccess saved = userModuleAccessService.setOverride(
                factoryId,
                userId,
                module,
                action,
                currentUserId(request),
                remark);
        return ApiResponse.success(saved);
    }

    @DeleteMapping("/{userId}/{module}")
    @Operation(summary = "Clear user-level module access override and restore role default")
    @RequirePermission({"system:read_write"})
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable String userId,
            @PathVariable String module) {
        userModuleAccessService.clearOverride(factoryId, userId, module);
        return ApiResponse.success(null);
    }

    private String currentUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId == null ? null : String.valueOf(userId);
    }
}
