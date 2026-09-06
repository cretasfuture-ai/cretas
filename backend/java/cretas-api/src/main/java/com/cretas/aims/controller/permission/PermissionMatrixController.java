package com.cretas.aims.controller.permission;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.permission.PermissionMatrixView;
import com.cretas.aims.service.permission.PermissionMatrixViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「权限矩阵」只读页的后端 (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906 step 1)。
 *
 * <h3>为什么<b>不</b>挂在 {@code PlatformRolePermissionController} 上</h3>
 * 那个控制器的两条路径前缀在 {@code JwtAuthInterceptor} 里各自带着<b>路径级</b>的角色语义:
 * {@code /api/admin/**} = 任何已登录用户都能读; {@code /api/platform/**} = 只有
 * {@code platform_admin}。本端点要的受众是「平台管理员 + 工厂总监 + 权限管理员」——
 * 前者太宽 (整张权限表对所有角色可见), 后者太窄 (工厂总监进不来)。
 * 而且它是<b>工厂无关</b>的, 拿不到 {@code factoryId}, 而本视图要带 L2 表头。
 * ⇒ 新开 {@code /api/mobile/{factoryId}/permissions}, 走 {@code @RequirePermission} 这道
 * 常规闸, 门禁写在注解上而不是藏在路径前缀里。
 *
 * <h3>门禁选 {@code system} 而不是新造一个 {@code permission} 模块</h3>
 * L1 表 (prod, 2026-09-06) 里<b>没有</b> {@code permission} 这个 module_code, 造一个
 * 等于给全仓已有的 7 份互不一致的模块清单再添一份, 且新模块对所有角色都没有 L1 行 ⇒
 * 只有超管短路能进, 与直接写 {@code @RequireRole} 无异。{@code system} 是现存的
 * 「系统管理」模块, {@code UserModuleAccessController} 的同类管理端点用的就是它。
 *
 * <p>🔴 <b>已知且故意不在本 PR 修</b>: prod L1 里 {@code permission_admin × system = '-'}
 * (兜底矩阵说 {@code rw})。也就是说「权限管理员」这个角色<b>今天进不来这个页面</b>,
 * 而它恰好是本页 diff 列表里的第一条 —— 页面会把这件事自己显示出来。
 * 修它要写 L1 (加一条 Flyway), 那是 owner 裁定的 step 2, 本轮只读。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/permissions")
@RequiredArgsConstructor
@Tag(name = "Permission Matrix", description = "L1 权限矩阵只读视图 (无写入端点)")
public class PermissionMatrixController {

    private final PermissionMatrixViewService permissionMatrixViewService;

    @GetMapping("/matrix")
    @Operation(summary = "角色 × 模块 权限矩阵 (L1 权威 + 硬编码兜底 + 分歧列表), 只读")
    @RequirePermission({"system:read", "system:read_write"})
    public ApiResponse<PermissionMatrixView> matrix(@PathVariable String factoryId) {
        return ApiResponse.success(permissionMatrixViewService.build(factoryId));
    }
}
