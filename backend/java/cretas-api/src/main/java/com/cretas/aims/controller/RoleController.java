package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.permission.PermissionModuleCatalog;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 角色管理 Controller
 *
 * 提供角色权限查询接口,支持前端"角色管理 → 查看权限"功能.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-14
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/roles")
@RequiredArgsConstructor
@Tag(name = "角色管理", description = "工厂角色权限查询")
public class RoleController {

    private final PermissionService permissionService;

    // 2026-09-07 刀 D: 这里原本是一份写死 11 条的 DISPLAY_MODULES (code → 中文名), 与
    // web-admin/src/views/system/roles/list.vue 的 allPermissionModules 逐字重复 (形态 D)。
    // 两份都已删除:
    //   · 列 = 三层现算 (permissionService.resolveRoleModuleLevels), ⛔ 不再有第二份清单;
    //   · 中文名 = PermissionModuleCatalog (全仓唯一一份), ⛔ 不在这里再写一遍;
    //   · 列序 = PermissionModuleCatalog.columnOrder (侧边栏顺序)。

    /**
     * 获取某个角色在各功能模块上的权限明细.
     *
     * 返回格式 (前端 list.vue `rolePermissions` 期望的结构):
     * <pre>
     * [
     *   { "module": "dashboard",  "label": "首页",     "access": "rw" },
     *   { "module": "production", "label": "生产管理", "access": "r"  },
     *   ...
     * ]
     * </pre>
     *
     * access 值含义: rw=读写 / r=只读 / w=只写 / -=无权限
     *
     * <p><b>2026-09-07 刀 D 之前这里是错的</b>: 它读
     * {@code permissionService.getRolePermissions(role)} —— 那个方法<b>只读硬编码兜底矩阵</b>,
     * 一格 L1 都不查, 而 L1 才是权威。实测 30 个角色 × 11 个模块的 294 格里 <b>31 格</b>
     * 与 L1 不一致 (例: {@code permission_admin} 11 格全是「页面说 rw / L1 说 -」;
     * {@code quality_controller} 的 production/quality/warehouse 页面说「无权限」而 L1 给了 r/rw/r)。
     * 现在走 {@link PermissionService#resolveRoleModuleLevels} —— 与
     * {@code @RequirePermission} 实际执行的是同一条链。
     *
     * @param factoryId 工厂ID。<b>参与解析</b>: L2 {@code factory_module_configs} 的
     *                  role×module 覆盖按它取 (2026-09-07 前这个参数只是路由占位)。
     * @param roleName  角色代码 (必须与 {@link FactoryUserRole} 枚举一致,如 factory_super_admin)
     */
    /**
     * Issue #817 (2026-05-17): 工厂角色列表 root LIST endpoint.
     *
     * <p>之前 web-admin {@code system/roles/list.vue} 硬编码 14 个角色; 任何 FE
     * 想动态拉角色列表 (e.g. user assignment dropdown) 撞 404. 本 endpoint 暴露
     * {@link FactoryUserRole} 枚举为公开 API.
     *
     * <p>返回结构 (camelCase, 跟 FE list.vue {@code roles} 数组保持一致):
     * <pre>
     * [
     *   { "name": "factory_super_admin", "displayName": "工厂总监",
     *     "description": "拥有工厂所有权限", "level": 0, "department": "all" },
     *   ...
     * ]
     * </pre>
     *
     * <p>过滤掉 {@code unactivated}。{@code production_manager} 2026-08-21 已从
     * dispatcher 拆成独立角色 (生产经理), 不再是别名, 不再排除。
     */
    @GetMapping
    @Operation(summary = "工厂角色列表",
            description = "Issue #817 — 替代前端硬编码角色列表, 支持 user assignment dropdown")
    @RequirePermission({"system:read_write", "system:read"})
    public ApiResponse<List<Map<String, Object>>> listRoles(@PathVariable String factoryId) {
        log.debug("listRoles: factoryId={}", factoryId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (FactoryUserRole role : FactoryUserRole.values()) {
            if (!role.isActive()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", role.name());
            row.put("displayName", role.getDisplayName());
            row.put("description", role.getDescription());
            row.put("level", role.getLevel());
            row.put("department", role.getDepartment());
            result.add(row);
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/{roleName}/permissions")
    @Operation(summary = "查询角色权限", description = "返回角色在各功能模块的读写权限明细")
    public ApiResponse<List<Map<String, Object>>> getRolePermissions(
            @PathVariable String factoryId,
            @PathVariable String roleName) {

        log.debug("getRolePermissions: factoryId={}, roleName={}", factoryId, roleName);

        FactoryUserRole role = FactoryUserRole.fromRoleCode(roleName);

        // 三层解析链 (L2 → L1 → 硬编码兜底) —— 与 @RequirePermission 实际执行的是同一条链。
        // ⛔ 不再走 getRolePermissions(role): 那个方法只读硬编码兜底矩阵, 一格 L1 都不查。
        Map<String, String> levels = permissionService.resolveRoleModuleLevels(factoryId, role);

        List<Map<String, Object>> result = new ArrayList<>();
        levels.entrySet().stream()
                // 超管的 "*" 通配键是给 RN 端判「任何模块都 rw」用的, 它不是一个模块, 不进表格。
                .filter(e -> !PermissionServiceImpl.MODULE_LEVEL_WILDCARD.equals(e.getKey()))
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, String> e) -> PermissionModuleCatalog.columnOrder(e.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("module", e.getKey());
                    row.put("label", PermissionModuleCatalog.label(e.getKey()));
                    row.put("access", e.getValue());
                    result.add(row);
                });

        return ApiResponse.success(result);
    }
}
