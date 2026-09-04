package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 缺陷 46 follow-up (2026-09-04) 的后端回归闸 —— 见
 * {@link RawMaterialTypeManagerOnlyRoleGateTest} 的完整背景。
 *
 * <p>list.vue 保存原料类型时会顺带 upsert/delete 该原料的包装换算规格
 * ({@code PUT}/{@code DELETE .../material-packaging/by-material/{id}})，角色白名单
 * 必须与 {@code RawMaterialTypeController} 那三个端点逐字节相同 —— 只加一边会让
 * 「物料主记录保存成功、包装规格却 403」这种更隐蔽的形式复发 (与 2026-09-02 那次
 * 模块闸遗漏同一形状，见 {@link MaterialPackagingHierarchyWarehouseWriteGateTest}）。
 */
class MaterialPackagingHierarchyManagerOnlyRoleGateTest {

    private static final Set<String> EXPECTED_ROLES =
            RawMaterialTypeManagerOnlyRoleGateTest.EXPECTED_ROLES;

    private Method method(String name) {
        return Arrays.stream(MaterialPackagingHierarchyController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("端点方法不存在: " + name));
    }

    @Test
    @DisplayName("upsert/delete 都带 @RequireRole，且与 RawMaterialTypeController 逐字节相同")
    void writeEndpointsMatchRawMaterialTypeController() {
        for (String name : Set.of("upsert", "delete")) {
            RequireRole ann = method(name).getAnnotation(RequireRole.class);
            assertNotNull(ann, name + " 上没有 @RequireRole —— 角色闸没有接上");
            assertEquals(EXPECTED_ROLES, Set.of(ann.value()), name);
        }
    }

    @Test
    @DisplayName("⛔ 阴性对照: warehouse_worker 不在白名单里")
    void workerIsExcluded() {
        for (String name : Set.of("upsert", "delete")) {
            Set<String> allowed = Set.of(method(name).getAnnotation(RequireRole.class).value());
            assertFalse(allowed.contains("warehouse_worker"),
                    name + ": warehouse_worker 回到名单里就是把缺陷放回来了");
        }
    }

    @Test
    @DisplayName("只读端点 (list / getByMaterial) 不该被这次改动波及")
    void readOnlyEndpointsUntouched() {
        assertNull(method("list").getAnnotation(RequireRole.class));
        assertNull(method("getByMaterial").getAnnotation(RequireRole.class));
    }

    @Test
    @DisplayName("@RequirePermission (模块闸) 原样保留")
    void modulePermissionGateStaysAdditive() {
        for (String name : Set.of("upsert", "delete")) {
            var permission = method(name).getAnnotation(com.cretas.aims.annotation.RequirePermission.class);
            assertNotNull(permission, name);
            assertEquals(Set.of("production:read_write", "warehouse:read_write"),
                    Set.of(permission.value()), name);
        }
    }
}
