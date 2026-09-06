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
    //   · 列 = permissionService.knownModuleAxis() 现算 (L1 出现过的 ∪ 硬编码声明的),
    //     与角色无关的稳定轴 ⇒ 每个角色列数一致, ⛔ 不再有第二份清单;
    //   · 值 = permissionService.resolveRoleModuleLevels(factoryId, role) 三层现算;
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
     * 一格 L1 都不查, 而 L1 才是权威。实测 30 个角色 × 11 个模块的 294 格里 <b>30 格</b>
     * 与 L1 不一致 (例: {@code permission_admin} 10 格全是「页面说 rw / L1 说 -」;
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

    /**
     * 🔴 2026-09-07 刀 D 补的门禁: 本端点此前<b>一个鉴权注解都没有</b>
     * ({@code PermissionInterceptor.preHandle}: 「没有注解，放行」), 而同一个 controller 的
     * 角色列表端点有 {@code @RequirePermission}。任何已登录用户都能读任意角色的权限矩阵。
     *
     * <p>为什么在本 PR 里补而不是另开一刀: 刀 D <b>放大了这个口子</b> —— 端点从「11 列陈旧的
     * 硬编码值」变成「17 列 L1 真实值」, 泄露面变大了。⇒ 由本次改动引出的门禁缺口, 在本次补上。
     *
     * <p>权限码与列表端点<b>逐字相同</b>, 也与唯一调用方所在路由的 {@code meta.module = 'system'}
     * ({@code router/index.ts:1439} 「角色权限」页) 一致 ⇒ 走得到这个页面的人都过得了这道闸,
     * ⛔ 不会把原本能用的人挡在外面。
     */
    @GetMapping("/{roleName}/permissions")
    @Operation(summary = "查询角色权限", description = "返回角色在各功能模块的读写权限明细")
    @RequirePermission({"system:read_write", "system:read"})
    public ApiResponse<List<Map<String, Object>>> getRolePermissions(
            @PathVariable String factoryId,
            @PathVariable String roleName) {

        log.debug("getRolePermissions: factoryId={}, roleName={}", factoryId, roleName);

        FactoryUserRole role = FactoryUserRole.fromRoleCode(roleName);

        // 三层解析链 (L2 → L1 → 硬编码兜底) —— 与 @RequirePermission 实际执行的是同一条链。
        // ⛔ 不再走 getRolePermissions(role): 那个方法只读硬编码兜底矩阵, 一格 L1 都不查。
        Map<String, String> levels = permissionService.resolveRoleModuleLevels(factoryId, role);

        // 超管的 "*" 通配键: 它不是一个模块, 不进表格 —— 但它是「没单独列出的模块也是 rw」的意思,
        // 所以要当作轴上其余格的取值。⛔ 漏了它会让超管的表格出现一堆 '-', 而 hasPermission 说 true。
        String wildcard = levels.get(PermissionServiceImpl.MODULE_LEVEL_WILDCARD);
        String absent = wildcard != null ? wildcard : "-";

        List<Map<String, Object>> result = new ArrayList<>();
        permissionService.knownModuleAxis().stream()
                // Steve 2026-09-06「餐饮不管」: 餐饮码不进这张工厂角色表 —— 与刀 D 之前的 11 列一致
                // (那份也没有餐饮列), 所以这不是新增的隐藏, 是保持原状。⛔ 别在这里顺手把它们放出来:
                // 实测轴上有 6 个餐饮码, 放出来会让 29 个角色里 26 个多出「餐饮管理」等列。
                .filter(module -> !module.startsWith("restaurant"))
                .sorted(Comparator
                        .comparingInt(PermissionModuleCatalog::columnOrder)
                        .thenComparing(module -> module))
                .forEach(module -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("module", module);
                    row.put("label", PermissionModuleCatalog.label(module));
                    // 轴上有而该角色三层都没登记 ⇒ 有效值就是 '-' (checkAction(null,…) 恒 false),
                    // 这是可被 hasPermission 证伪的真实读数, ⛔ 不是兜底默认值。
                    row.put("access", levels.getOrDefault(module, absent));
                    result.add(row);
                });

        return ApiResponse.success(result);
    }
}
