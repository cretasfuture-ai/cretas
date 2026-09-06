package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刀 D (2026-09-07): 「角色管理 → 查看权限」返回的**列和值都来自三层解析链**,
 * 不来自任何一份写死的清单, 也不来自只读硬编码兜底的 {@code getRolePermissions}。
 *
 * <h3>为什么这些断言不是恒真式</h3>
 * 假的 {@code resolveRoleModuleLevels} 故意返回一个**与旧 DISPLAY_MODULES 不同**的集合:
 * <ul>
 *   <li>含 {@code work_report} / {@code operations} —— 旧清单里<b>没有</b>这两列;</li>
 *   <li>不含 {@code equipment} / {@code sales} —— 旧清单里<b>有</b>这两列;</li>
 *   <li>{@code quality} 给 {@code rw} 而同一个假的 {@code getRolePermissions} 只给 read。</li>
 * </ul>
 * ⇒ 只要有人把实现改回读那份清单或读 {@code getRolePermissions}, 这里必红。
 *
 * <p>改之前的实测 (临时探针, 已删; 表在 PR #715 body 里): 拿 {@code getRolePermissions} 的
 * 结果与仓里的 L1 快照 {@code permissionMatrix.l1Snapshot.json} 逐格比,
 * 30 个角色 × 11 个模块共 294 格里 <b>31 格</b>不一致, 另有 6 个 L1 模块页面上根本没有列。
 * 要复现: 按同样口径比一次 —— ⛔ 别用 {@code FactoryUserRole.fromRoleCode} 做角色映射,
 * 它对未知码静默返回 {@code unactivated}, 会把「枚举里没有这个角色」变成一个真读数。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleControllerPermissionsReadL1ChainTest {

    private static final String FACTORY = "F006";

    @Mock
    private PermissionService permissionService;

    /** 与旧 DISPLAY_MODULES 刻意不同的一组格 —— 它就是「变异能不能被看见」的保证。 */
    private Map<String, String> chainLevels() {
        Map<String, String> levels = new LinkedHashMap<>();
        levels.put("quality", "rw");        // 旧清单也有这列, 但兜底那条路只给 r
        levels.put("production", "r");
        levels.put("work_report", "rw");    // 🔴 旧清单没有这一列
        levels.put("operations", "r");      // 🔴 旧清单没有这一列
        levels.put("dashboard", "-");
        return levels;
    }

    private RoleController controller() {
        when(permissionService.resolveRoleModuleLevels(eq(FACTORY), any())).thenReturn(chainLevels());
        // 兜底那条路故意给一组不同的答案: 实现若改回读它, 下面每条断言都会红
        when(permissionService.getRolePermissions(any()))
                .thenReturn(Set.of("quality:read", "equipment:read", "equipment:write", "sales:read"));
        return new RoleController(permissionService);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(ApiResponse<List<Map<String, Object>>> res) {
        return (List<Map<String, Object>>) res.getData();
    }

    @Test
    @DisplayName("列 = 三层链返回的模块集, ⛔ 不是任何一份写死清单")
    void columnsComeFromTheChain() {
        List<Map<String, Object>> rows = rows(controller().getRolePermissions(FACTORY, "quality_controller"));

        assertThat(rows.stream().map(r -> r.get("module")))
                .as("列必须逐一来自 resolveRoleModuleLevels")
                .containsExactlyInAnyOrder("dashboard", "production", "quality", "work_report", "operations");
        assertThat(rows.stream().map(r -> (String) r.get("module")))
                .as("旧 DISPLAY_MODULES 独有的列不该凭空出现")
                .doesNotContain("equipment", "sales", "hr", "finance");
        verify(permissionService, never()).getRolePermissions(any());
    }

    @Test
    @DisplayName("值 = 链的值, ⛔ 不是硬编码兜底的值")
    void valuesComeFromTheChain() {
        Map<String, Object> quality = rows(controller().getRolePermissions(FACTORY, "quality_controller"))
                .stream().filter(r -> "quality".equals(r.get("module"))).findFirst().orElseThrow();
        assertThat(quality.get("access"))
                .as("链说 rw, 兜底那条路只给 read —— 读到 r 就说明实现回到了兜底")
                .isEqualTo("rw");
    }

    @Test
    @DisplayName("factoryId 透传给链 (L2 工厂覆盖靠它)")
    void factoryIdReachesTheChain() {
        controller().getRolePermissions(FACTORY, "quality_controller");
        verify(permissionService).resolveRoleModuleLevels(eq(FACTORY), eq(FactoryUserRole.quality_controller));
    }

    @Test
    @DisplayName("超管的 \"*\" 通配键不是模块, 不进表格")
    void wildcardKeyIsNotAColumn() {
        Map<String, String> withWildcard = new LinkedHashMap<>(chainLevels());
        withWildcard.put(PermissionServiceImpl.MODULE_LEVEL_WILDCARD, "rw");
        when(permissionService.resolveRoleModuleLevels(eq(FACTORY), any())).thenReturn(withWildcard);

        List<Map<String, Object>> rows =
                rows(new RoleController(permissionService).getRolePermissions(FACTORY, "factory_super_admin"));
        assertThat(rows.stream().map(r -> r.get("module")))
                .doesNotContain(PermissionServiceImpl.MODULE_LEVEL_WILDCARD);
        assertThat(rows).as("过滤通配键不能把整张表滤空").isNotEmpty();
    }

    @Test
    @DisplayName("门禁: 权限明细端点必须有 @RequirePermission, 且与同 controller 的列表端点同码")
    void permissionsEndpointIsGuarded() throws NoSuchMethodException {
        RequirePermission detail = RoleController.class
                .getMethod("getRolePermissions", String.class, String.class)
                .getAnnotation(RequirePermission.class);
        RequirePermission list = RoleController.class
                .getMethod("listRoles", String.class)
                .getAnnotation(RequirePermission.class);

        // 阳性对照: 列表端点本来就有注解 —— 它证明这条断言读得到注解, 不是恒真
        assertThat(list).as("INSTRUMENT DEAD — 连列表端点的注解都读不到").isNotNull();
        assertThat(detail)
                .as("权限明细端点没有鉴权注解 ⇒ PermissionInterceptor 直接放行, "
                        + "任何已登录用户都能读任意角色的权限矩阵")
                .isNotNull();
        assertThat(detail.value())
                .as("两个端点的权限码不许各漂各的 (形态 D′)")
                .containsExactlyInAnyOrder(list.value());
    }

    @Test
    @DisplayName("中文名与列序来自 PermissionModuleCatalog (侧边栏顺序), 未登记的码排最后且原样显示")
    void labelsAndOrderComeFromTheCatalog() {
        Map<String, String> withUnknown = new LinkedHashMap<>(chainLevels());
        withUnknown.put("module_nobody_registered", "r");
        when(permissionService.resolveRoleModuleLevels(eq(FACTORY), any())).thenReturn(withUnknown);

        List<Map<String, Object>> rows =
                rows(new RoleController(permissionService).getRolePermissions(FACTORY, "quality_controller"));

        assertThat(rows.stream().map(r -> (String) r.get("module")).toList())
                .as("侧边栏顺序: 首页 → 生产 → 质量 → …; 未登记的排最后")
                .containsExactly("dashboard", "production", "quality", "work_report", "operations",
                        "module_nobody_registered");
        assertThat(rows.get(0).get("label")).isEqualTo("首页");
        assertThat(rows.get(2).get("label"))
                .as("质量管理 —— 刀 D 前这里写的是「质检管理」, 而侧边栏一直叫「质量管理」")
                .isEqualTo("质量管理");
        assertThat(rows.get(5).get("label"))
                .as("没有中文名的码原样显示, ⛔ 不编造")
                .isEqualTo("module_nobody_registered");
    }
}
