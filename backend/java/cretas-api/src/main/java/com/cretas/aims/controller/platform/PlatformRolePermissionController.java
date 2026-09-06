package com.cretas.aims.controller.platform;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.permission.PlatformRolePermissionWriteService;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Layer 1 (Platform global default) permission matrix management API.
 *
 * See: docs/superpowers/specs/2026-04-18-permission-matrix-ai-driven-design.md §5.1.4
 */
/**
 * L1 API:
 * - GET /api/admin/role-permissions — any authenticated factory admin can read (to build matrix view)
 * - PUT /api/platform/role-permissions/{role}/{module} — platform_admin only (global edits)
 *
 * Split paths because JwtAuthInterceptor:
 *   /api/admin/** → any authenticated user
 *   /api/platform/** → only platform_admin role
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Platform Role Permissions", description = "L1 global permission matrix management")
public class PlatformRolePermissionController {

    private static final Set<String> ALLOWED_MODULES = Set.of(
        "dashboard","production","warehouse","quality","procurement","sales",
        "hr","equipment","finance","system","analytics","scheduling",
        "work_report","inventory","report","rd","restaurant","production_report_correction",
        // 2026-09-07 清单收敛刀 B (owner: 以 L1 模块码为准): prod L1 有 5 行 operations, 写白名单却没有它 ⇒
        // step-2 页面上 operations 列写不进去 (400 无效模块)。⚠️ 与 FactoryRoleModuleOverrideController 那份逐字相同, 闸钉着。
        "operations",
        // 2026-08-05 餐饮四部门驾驶舱细分权限。语义见 permission.ts:552:
        //   最终 = min(restaurant 上限, 该部门声明值 ?? 上限)
        // 大小写敏感, 必须与前端 ModulePermissions 的键逐字一致。
        "restaurantOps","restaurantMarketing","restaurantHr","restaurantFinance");
    private static final Set<String> ALLOWED_LEVELS = Set.of("rw","r","w","-");

    public static void assertModuleAllowed(String module) {
        if (!ALLOWED_MODULES.contains(module)) {
            throw new IllegalArgumentException("无效模块: " + module + ". 允许: " + ALLOWED_MODULES);
        }
    }

    private final PlatformRolePermissionRepository repo;
    private final PermissionService permissionService;
    private final MobileService mobileService;
    /** step-2: L1 的唯一写入口 (校验 / 守卫 / 审计 / 缓存失效都在它里面). */
    private final PlatformRolePermissionWriteService writeService;
    private final UserRepository userRepository;

    @GetMapping("/api/admin/role-permissions")
    @Operation(summary = "获取全平台 role × module 权限矩阵 (L1) — 所有认证用户可读")
    public ApiResponse<List<PlatformRolePermission>> getAll() {
        return ApiResponse.success(repo.findByDeletedAtIsNull());
    }

    @PutMapping("/api/platform/role-permissions/{role}/{module}")
    @Operation(summary = "更新 L1 权限级别 (platform_admin only via path interceptor)")
    @RequirePermission({"system:read_write"})
    public ApiResponse<PlatformRolePermission> update(
            @PathVariable String role,
            @PathVariable String module,
            @RequestParam String level,
            @RequestHeader("Authorization") String authorization) {

        // step-2 (2026-09-06): 校验 / 防锁死守卫 / L1 写 / 同事务审计 / 缓存失效 全部委托给唯一的写服务,
        // 这里不再保留第二份守卫 (形态 D)。行为差异: 以前 token 取不到 userId 就写 updated_by=NULL,
        // 现在拒绝写 (审计行 changed_by NOT NULL) —— 这是有意的, 见 PlatformRolePermissionWriteService。
        User actor = null;
        try {
            Long userId = mobileService.getUserFromToken(TokenUtils.extractToken(authorization)).getId();
            actor = userId == null ? null : userRepository.findById(userId).orElse(null);
        } catch (Exception e) {
            log.warn("无法从 token 获取操作人, 拒绝写 L1: {}", e.getMessage());
        }
        if (actor == null) {
            throw new BusinessException(403, "无法确认操作人, 拒绝写入权限");
        }
        PlatformRolePermissionWriteService.WriteResult result =
                writeService.write(actor, null, role, module, level, null);
        return ApiResponse.success(result.row());
    }

    private void validateModule(String module) {
        assertModuleAllowed(module);
    }

    private void validateLevel(String level) {
        if (!ALLOWED_LEVELS.contains(level)) {
            throw new IllegalArgumentException("无效权限级别: " + level + ". 允许: rw/r/w/-");
        }
    }
}
