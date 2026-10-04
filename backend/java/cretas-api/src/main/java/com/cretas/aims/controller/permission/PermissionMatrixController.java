package com.cretas.aims.controller.permission;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.permission.PermissionMatrixView;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.permission.PermissionMatrixViewService;
import com.cretas.aims.service.permission.PlatformRolePermissionWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final PlatformRolePermissionWriteService writeService;
    private final UserRepository userRepository;

    @GetMapping("/matrix")
    @Operation(summary = "角色 × 模块 权限矩阵 (L1 权威 + 硬编码兜底 + 分歧列表), 只读")
    @RequirePermission({"system:read", "system:read_write"})
    public ApiResponse<PermissionMatrixView> matrix(@PathVariable String factoryId) {
        return ApiResponse.success(permissionMatrixViewService.build(factoryId));
    }

    /** 写一格的请求体. level ∈ rw/r/w/-; reason 可选 (≤200). */
    public record WriteCellRequest(String level, String reason) {}

    /** 写一格的响应: 写后从 L1 回读的值 (不是请求体) + 审计 id. */
    public record WriteCellResponse(String role, String module, String oldLevel, String level, Long auditId) {}

    /**
     * step-2 (owner 2026-09-06 裁定): 只写 L1 一格, 同事务写审计, 写后缓存全清。
     *
     * <p>门禁 {@code system:read_write}: permission_admin 由 {@code V20261101_37} 提到 rw 后走得到;
     * factory_super_admin / platform_admin 走超管短路。⚠️ L1 是全平台表, 一次写入影响所有工厂 —— 前端明示。
     * 餐饮角色 / 餐饮模块的格 400 (Steve: 餐饮不管)。守卫全在 {@link PlatformRolePermissionWriteService}, 这里不抄。
     */
    @PutMapping("/matrix/{role}/{module}")
    @Operation(summary = "写 L1 一格 (全平台生效), 同事务审计, 写后缓存失效")
    @RequirePermission({"system:read_write"})
    public ApiResponse<WriteCellResponse> writeCell(@PathVariable String factoryId,
                                                    @PathVariable String role,
                                                    @PathVariable String module,
                                                    @RequestBody WriteCellRequest body,
                                                    @RequestAttribute(value = "userId", required = false) Long userId,
                                                    HttpServletRequest request) {
        String level = body == null ? null : body.level();
        String reason = body == null ? null : body.reason();
        // ⚠️ 先按主体类型分支, 再查 users: 平台管理员的 id 来自 platform_admins, 与 users.id 是两条
        // 独立序列 —— 拿它去 users.findById 会把一个不相干的工厂账号记成操作人 (审计 changed_by / 角色)。
        AuthenticatedActor platformActor = AuthenticatedActor.current(request)
                .filter(AuthenticatedActor::isPlatformAdmin)
                .orElse(null);
        PlatformRolePermissionWriteService.WriteResult result;
        if (platformActor != null) {
            if (!platformActor.hasFactoryBypass()) {
                throw new BusinessException(403, "平台角色 " + platformActor.role() + " 无权写入权限矩阵");
            }
            result = writeService.write(platformActor.id(), platformActor.role(),
                    factoryId, role, module, level, reason);
        } else {
            // 操作人从 JwtAuthInterceptor 放进来的 userId 取; 取不到 ⇒ 403, ⛔ 不写 changed_by=NULL
            User actor = userId == null ? null : userRepository.findById(userId).orElse(null);
            if (actor == null) {
                throw new BusinessException(403, "无法确认操作人, 拒绝写入权限");
            }
            result = writeService.write(actor, factoryId, role, module, level, reason);
        }
        return ApiResponse.success(new WriteCellResponse(
                role, module, result.oldLevel(), result.row().getPermissionLevel(), result.auditId()));
    }
}
