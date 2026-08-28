package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住阈值中心的门禁：<b>权限码</b>，不是手写的角色名清单。
 *
 * <h2>它修的缺陷（2026-08-29）</h2>
 * 6 个端点原来挂
 * {@code @RequireRole({"factory_super_admin","permission_admin","factory_admin"})}
 * （写操作只有前两个）。那是权限矩阵的<b>第二份副本</b>，实测已经漂了两处：
 * <ul>
 *   <li>{@code factory_admin} <b>不是</b> {@link FactoryUserRole} 的枚举值 ——
 *       见 {@link #factoryAdminWasNeverARealRole()}。写下那天起就是死条目，
 *       而<b>没有任何东西会报</b>：白名单是字符串比较，拼错只表现为「那个角色进不来」。</li>
 *   <li>餐饮租户老板 {@code restaurant_owner} 不在清单里 ⇒ <b>配不了任何阈值</b>，
 *       尽管 L1 权限表（{@code V20261029_62__restaurant_owner_global_rw.sql}）
 *       明确给了他 {@code system='rw'}。</li>
 * </ul>
 *
 * <h2>⚠️ 这个类量的是两层，别把它们混成一层</h2>
 * <table border="1">
 *   <tr><th>断言组</th><th>量的是</th><th>是不是生产真相</th></tr>
 *   <tr><td>{@link #readEndpointsRequireSystemRead()} 等注解组</td>
 *       <td>端点上<b>挂了哪道闸</b></td>
 *       <td>✅ 是 —— 注解就是接线本身</td></tr>
 *   <tr><td>{@link #whoGetsInUnderTheHardcodedFallbackMatrix()}</td>
 *       <td>{@code PermissionServiceImpl} 里那份 <b>hard-coded fallback 矩阵</b>的判定</td>
 *       <td>⚠️ <b>不完全是</b> —— 生产上 L1 表({@code platform_role_permissions})优先，
 *           单测里没有 DB，走的是 fallback 那一层</td></tr>
 * </table>
 *
 * <p>🔴 具体差异（读迁移得出，<b>不是</b>本测试跑出来的）：L1 里
 * {@code permission_admin} 的 {@code system} 是 {@code '-'}，而 fallback 矩阵给它全模块 rw。
 * 所以下面 {@code permission_admin} 那条断言只证明「fallback 层放行」，
 * ⛔ 不能读成「生产上他进得来」。见类外报告里的权限对照表。
 */
@DisplayName("阈值中心门禁 = 权限码, 不是角色名单")
class CanvasThresholdsControllerPermissionTest {

    /** 读端点：只需要能读 system 模块。 */
    private static final Set<String> READ_METHODS = new TreeSet<>(List.of("list", "getByKey"));
    /** 写端点：必须完整读写。 */
    private static final Set<String> WRITE_METHODS =
            new TreeSet<>(List.of("create", "update", "delete", "resetCategoryToDefault"));

    private static Method endpoint(String name) {
        return Arrays.stream(CanvasThresholdsController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "找不到端点方法 " + name + " —— 方法被改名了, 本类的断言全部失去目标"));
    }

    // ── 阳性对照: 仪器指着东西吗 ─────────────────────────────────────────

    @Test
    @DisplayName("阳性对照: 6 个端点方法都找得到 —— 找不到的话下面每条断言都是恒真式")
    void allSixEndpointsAreFound() {
        Set<String> all = new TreeSet<>(READ_METHODS);
        all.addAll(WRITE_METHODS);
        assertThat(all).hasSize(6);
        all.forEach(name -> assertThat(endpoint(name)).as(name).isNotNull());
    }

    // ── 注解组: 这一层就是接线本身 ───────────────────────────────────────

    @Test
    @DisplayName("读端点 → @RequirePermission(\"system:read\")")
    void readEndpointsRequireSystemRead() {
        for (String name : READ_METHODS) {
            RequirePermission ann = endpoint(name).getAnnotation(RequirePermission.class);
            assertThat(ann).as(name + " 没有权限注解 ⇒ 任何登录用户都进得来").isNotNull();
            assertThat(ann.value()).as(name).containsExactly("system:read");
        }
    }

    @Test
    @DisplayName("🔴 写端点 → @RequirePermission(\"system:read_write\") (⛔ 读的那个码在这里不够)")
    void writeEndpointsRequireSystemReadWrite() {
        for (String name : WRITE_METHODS) {
            RequirePermission ann = endpoint(name).getAnnotation(RequirePermission.class);
            assertThat(ann).as(name + " 没有权限注解 ⇒ 任何登录用户都能改阈值").isNotNull();
            assertThat(ann.value()).as(name).containsExactly("system:read_write");
        }
    }

    @Test
    @DisplayName("⛔ 阴性对照: 读端点的权限码【不等于】写端点的 —— 否则「读写分级」只是句口号")
    void readAndWriteAreNotTheSameCode() {
        String read = endpoint("list").getAnnotation(RequirePermission.class).value()[0];
        String write = endpoint("create").getAnnotation(RequirePermission.class).value()[0];
        assertThat(read).isNotEqualTo(write);

        PermissionServiceImpl svc = new PermissionServiceImpl();
        // 一个只读 system 的角色: 读得进、写不进。这才是「分级」的可观测形态。
        assertThat(svc.hasPermission(user(FactoryUserRole.dispatcher), read)).isTrue();
        assertThat(svc.hasPermission(user(FactoryUserRole.dispatcher), write))
                .as("⛔ 只读角色若能写, 说明两个码在判定上其实是同一个")
                .isFalse();
    }

    @Test
    @DisplayName("⛔ 阴性对照: 6 个端点上都不再有 @RequireRole —— 留着它就还是两份定义 (且是 AND, 会更紧)")
    void noRoleWhitelistRemains() {
        Set<String> all = new TreeSet<>(READ_METHODS);
        all.addAll(WRITE_METHODS);
        for (String name : all) {
            assertThat(endpoint(name).getAnnotation(RequireRole.class))
                    .as(name + " 还挂着角色白名单")
                    .isNull();
        }
        assertThat(CanvasThresholdsController.class.getAnnotation(RequireRole.class))
                .as("类级别也不许有 —— 类级注解同样被 RequireRoleInterceptor 读")
                .isNull();
    }

    @Test
    @DisplayName("⛔ 阴性对照: 不许顺手加 @RequireModule —— 它问的是「工厂启没启用这个模块」, 不是「这个人有没有权限」")
    void noModuleGateWasAdded() {
        Set<String> all = new TreeSet<>(READ_METHODS);
        all.addAll(WRITE_METHODS);
        for (String name : all) {
            assertThat(endpoint(name).getAnnotation(RequireModule.class)).as(name).isNull();
        }
        assertThat(CanvasThresholdsController.class.getAnnotation(RequireModule.class)).isNull();
    }

    @Test
    @DisplayName("🔴 factory_admin 从来就不是一个真实角色 —— 原白名单里那一项是死条目")
    void factoryAdminWasNeverARealRole() {
        Set<String> realRoles = new TreeSet<>();
        for (FactoryUserRole r : FactoryUserRole.values()) {
            realRoles.add(r.name());
        }
        assertThat(realRoles)
                .as("⚠️ 若哪天真加了这个枚举值, 本条会红 —— 那时该重读上面那段结论, 而不是删掉它")
                .doesNotContain("factory_admin");
        assertThat(realRoles).as("阳性对照: 枚举真的读到了").contains("factory_super_admin", "restaurant_owner");
    }

    // ── RBAC 判定组 (⚠️ 量的是 hard-coded fallback 层) ───────────────────

    private static User user(FactoryUserRole role) {
        User u = new User();
        u.setId(1L);
        u.setRoleCode(role.name());
        u.setFactoryId("LIUSHANMEN");
        return u;
    }

    @Test
    @DisplayName("🔴 餐饮老板配得了阈值 (原角色白名单里没有他 ⇒ 全部 403)")
    void restaurantOwnerCanConfigureThresholds() {
        PermissionServiceImpl svc = new PermissionServiceImpl();
        assertThat(svc.hasPermission(user(FactoryUserRole.restaurant_owner), "system:read")).isTrue();
        assertThat(svc.hasPermission(user(FactoryUserRole.restaurant_owner), "system:read_write"))
                .as("L1 (V20261029_62) 给了他 system='rw'; 只有那份手写角色清单在拦他")
                .isTrue();
    }

    @Test
    @DisplayName("⛔ 阴性对照: 一线角色仍然进不来 —— 「让老板进得来」不能顺手变成「谁都进得来」")
    void frontlineRolesAreStillDenied() {
        PermissionServiceImpl svc = new PermissionServiceImpl();
        Map<FactoryUserRole, String> mustBeDenied = new LinkedHashMap<>();
        mustBeDenied.put(FactoryUserRole.operator, "车间操作员");
        mustBeDenied.put(FactoryUserRole.quality_inspector, "质检员");
        mustBeDenied.put(FactoryUserRole.warehouse_worker, "仓库员");
        mustBeDenied.put(FactoryUserRole.viewer, "只读查看者");
        mustBeDenied.put(FactoryUserRole.restaurant_chef, "厨师长");
        mustBeDenied.put(FactoryUserRole.unactivated, "未激活账号");

        mustBeDenied.forEach((role, label) -> {
            assertThat(svc.hasPermission(user(role), "system:read"))
                    .as(label + " 不该读阈值中心").isFalse();
            assertThat(svc.hasPermission(user(role), "system:read_write"))
                    .as("🔴 " + label + " 能改会 409 拦货的闸门阈值 = 换注解换出了一个越权口子")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("原白名单里的角色: 谁留下、谁的判定改由权限矩阵说了算 (⚠️ 这里是 fallback 层)")
    void whoGetsInUnderTheHardcodedFallbackMatrix() {
        PermissionServiceImpl svc = new PermissionServiceImpl();

        // 工厂总监: 原来在白名单里, 现在 hasPermission 对他直接短路 true —— 不受影响。
        assertThat(svc.hasPermission(user(FactoryUserRole.factory_super_admin), "system:read_write")).isTrue();

        // permission_admin: ⚠️ fallback 矩阵给它 superAdminPerms ⇒ 这里 true。
        // ⛔ 生产上 L1 表里它的 system 是 '-' (V20260422_01:394), L1 优先于 fallback
        //    ⇒ 生产上它会被拒。本条只钉住 fallback 这一层, 不要读成生产结论。
        assertThat(svc.hasPermission(user(FactoryUserRole.permission_admin), "system:read_write"))
                .as("fallback 层放行; L1 层拒绝 —— 两层不一致本身是既有事实, 不是本次改动引入的")
                .isTrue();
    }
}
