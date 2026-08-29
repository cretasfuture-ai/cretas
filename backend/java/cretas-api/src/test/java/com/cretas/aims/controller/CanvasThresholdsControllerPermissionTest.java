package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阈值中心 6 个端点的门禁形态。
 *
 * <h2>它守的是什么</h2>
 * 原白名单是 {@code {"factory_super_admin", "permission_admin", "factory_admin"}}，
 * 餐饮租户的 {@code restaurant_owner} 不在里面 ⇒ <b>餐饮老板配不了任何阈值</b>。
 *
 * <h2>🔴 为什么不改成 {@code @RequirePermission("system:*")}</h2>
 * 那是最初的方案，<b>生产实测后撤回了</b>。按角色查生产账号：
 *
 * <table border="1">
 *   <tr><th>角色</th><th>生产账号</th><th>换成权限码后</th></tr>
 *   <tr><td>{@code permission_admin}</td>
 *       <td><b>{@code lsm001} @ LIUSHANMEN（真实客户），活跃</b></td>
 *       <td>🔴 <b>失去访问</b> —— L1 {@code V20260422_01:394} 给它 {@code system='-'}</td></tr>
 *   <tr><td>{@code restaurant_owner}</td><td>{@code mock_owner} @ MOCK_REST（模拟租户）</td>
 *       <td>✅ 获得访问</td></tr>
 *   <tr><td>{@code dispatcher} / {@code department_admin}</td><td>F006 + LIUSHANMEN 共 3 个</td>
 *       <td>⚠️ 新获得只读</td></tr>
 *   <tr><td>{@code super_admin} / {@code developer} / {@code platform_super_admin}</td>
 *       <td>独立的 {@code platform_admins} 表</td>
 *       <td>🔴 <b>失去访问</b> —— {@code RequireRoleInterceptor:73} 有
 *           {@code PLATFORM_ADMIN_ROLES} 放行，而 {@code PermissionInterceptor} <b>没有</b>，
 *           且它按 {@code userRepository.findById} 解析用户，平台管理员根本不在那张表里</td></tr>
 * </table>
 *
 * ⇒ 那笔交易是「**为了让一个模拟租户的账号能配，代价是真实客户的账号配不了**」，
 * 还顺带给 3 个账号开了原本没有的读权限。<b>不划算。</b>
 *
 * <p>⇒ 保留 {@code @RequireRole}（它自带平台管理员放行），只把餐饮角色加进白名单 ——
 * 刀口最小，且正好解决原问题。
 *
 * <h2>⛔ 这一类只能证明「注解写对了」</h2>
 * 它<b>不能</b>证明拦截器真的按注解放行/拒绝 —— 那要跑真实请求。
 * 之前有一条 {@code restaurantOwnerCanConfigureThresholds} 只量
 * {@code PermissionServiceImpl}，在注解被变异回旧值时<b>依然是绿的</b> ——
 * 它守的不是这道门禁。已删除，不留一个看起来在守而实际不守的东西。
 */
@DisplayName("阈值中心门禁 = 角色白名单(含餐饮角色), 且平台管理员保持放行")
class CanvasThresholdsControllerPermissionTest {

    private static final List<String> READ_ENDPOINTS = List.of("list", "getByKey");
    private static final List<String> WRITE_ENDPOINTS =
            List.of("create", "update", "delete", "resetCategoryToDefault");

    /** 这些角色<b>必须</b>在白名单里；少任何一个都是一次真实回归。 */
    private static final List<String> MUST_BE_ALLOWED =
            List.of("factory_super_admin", "permission_admin", "restaurant_owner");

    private Method endpoint(String name) {
        return Arrays.stream(CanvasThresholdsController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("端点方法不存在: " + name));
    }

    @Test
    @DisplayName("阳性对照: 6 个端点方法都找得到 —— 找不到的话下面每条断言都是恒真式")
    void allSixEndpointsExist() {
        List<String> all = new java.util.ArrayList<>(READ_ENDPOINTS);
        all.addAll(WRITE_ENDPOINTS);
        assertThat(all).hasSize(6);
        all.forEach(name -> assertThat(endpoint(name)).as(name).isNotNull());
    }

    @Test
    @DisplayName("🔴 6 个端点都带 @RequireRole, 且都含 restaurant_owner (原缺陷: 餐饮老板配不了)")
    void allEndpointsAllowRestaurantOwner() {
        List<String> all = new java.util.ArrayList<>(READ_ENDPOINTS);
        all.addAll(WRITE_ENDPOINTS);
        for (String name : all) {
            RequireRole ann = endpoint(name).getAnnotation(RequireRole.class);
            assertThat(ann).as("%s 上没有 @RequireRole ⇒ 这个端点没有门禁", name).isNotNull();
            assertThat(ann.value()).as(name).contains("restaurant_owner");
        }
    }

    @Test
    @DisplayName("🔴 permission_admin 必须留在白名单 —— lsm001 是真实客户的活跃账号")
    void permissionAdminMustNotLoseAccess() {
        List<String> all = new java.util.ArrayList<>(READ_ENDPOINTS);
        all.addAll(WRITE_ENDPOINTS);
        for (String name : all) {
            assertThat(endpoint(name).getAnnotation(RequireRole.class).value())
                    .as("%s: 去掉 permission_admin 会让 LIUSHANMEN 的 lsm001 配不了阈值", name)
                    .containsAll(MUST_BE_ALLOWED);
        }
    }

    @Test
    @DisplayName("⛔ 阴性对照: 白名单不是「谁都放行」—— 明确不该进的角色确实不在")
    void whitelistIsNotEveryone() {
        String[] read = endpoint("list").getAnnotation(RequireRole.class).value();
        String[] write = endpoint("create").getAnnotation(RequireRole.class).value();
        for (String denied : List.of("operator", "viewer", "warehouse_worker",
                "quality_inspector", "restaurant_chef", "unactivated")) {
            assertThat(read).as("读端点不该放行 %s", denied).doesNotContain(denied);
            assertThat(write).as("写端点不该放行 %s", denied).doesNotContain(denied);
        }
    }

    @Test
    @DisplayName("⛔ 阴性对照: 读比写宽 —— 否则「读写分级」只是句口号")
    void readIsWiderThanWrite() {
        List<String> read = List.of(endpoint("list").getAnnotation(RequireRole.class).value());
        List<String> write = List.of(endpoint("create").getAnnotation(RequireRole.class).value());
        assertThat(read).as("写端点的每个角色都该能读").containsAll(write);
        assertThat(read.size())
                .as("读白名单必须严格更宽; 一样宽说明分级没做")
                .isGreaterThan(write.size());
        assertThat(write)
                .as("restaurant_manager 只读不写 —— 改阈值是老板的事")
                .doesNotContain("restaurant_manager");
    }

    @Test
    @DisplayName("⛔ 6 个端点上不再有 @RequirePermission —— 两份定义是 AND, 会静默收紧")
    void noPermissionCodeRemains() {
        List<String> all = new java.util.ArrayList<>(READ_ENDPOINTS);
        all.addAll(WRITE_ENDPOINTS);
        for (String name : all) {
            assertThat(endpoint(name).getAnnotation(RequirePermission.class))
                    .as("%s 同时带两种门禁 ⇒ 实际生效的是交集, 而读的人只会看到其中一个", name)
                    .isNull();
        }
    }

    @Test
    @DisplayName("⛔ factory_admin 已从白名单移除 —— 它根本不是一个真实角色")
    void deadRoleIsGone() {
        assertThat(endpoint("list").getAnnotation(RequireRole.class).value())
                .as("FactoryUserRole 里没有 factory_admin 这个值, 留着它是一条永远不匹配的死条目")
                .doesNotContain("factory_admin");
    }
}
