package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 缺陷 46 follow-up (2026-09-04, owner 拍板): 「只让仓储主管改」——
 * {@code warehouse_worker} 通过了 {@link RawMaterialTypeWarehouseWriteGateTest} 钉住的
 * 模块闸 ({@code warehouse:read_write='rw'}, 见
 * V20261024_22__warehouse_worker_read_warehouse_inventory.sql), 但模块权限区分不了
 * 「仓管」和「仓储主管」。本闸钉住新加的第二道闸 —— {@code @RequireRole}。
 *
 * <p>本文件只证明「注解写对了」(与 {@link CanvasThresholdsControllerPermissionTest} 同一类)。
 * 「拦截器真的按注解放行/拒绝」由 {@link RawMaterialTypeRoleGateMvcTest} 跑真实请求钉住。
 */
class RawMaterialTypeManagerOnlyRoleGateTest {

    /** 唯一来源 —— 与前端 materialTypeWriteRoles.ts 的 MATERIAL_TYPE_WRITE_ROLES 逐字节相同。 */
    static final Set<String> EXPECTED_ROLES = Set.of("warehouse_manager", "factory_super_admin");

    private static final Set<String> WRITE_METHODS =
            Set.of("createMaterialType", "updateMaterialType", "deleteMaterialType");

    private Method method(String name) {
        return Arrays.stream(RawMaterialTypeController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("端点方法不存在: " + name));
    }

    @Test
    @DisplayName("阳性对照: 三个写端点方法都找得到 —— 找不到的话下面每条断言都是恒真式")
    void allThreeWriteEndpointsExist() {
        WRITE_METHODS.forEach(name -> assertNotNull(method(name), name));
    }

    @Test
    @DisplayName("create/update/delete 都带 @RequireRole({warehouse_manager, factory_super_admin})")
    void writeEndpointsAreManagerOnly() {
        for (String name : WRITE_METHODS) {
            RequireRole ann = method(name).getAnnotation(RequireRole.class);
            assertNotNull(ann, name + " 上没有 @RequireRole —— 角色闸没有接上");
            assertEquals(EXPECTED_ROLES, Set.of(ann.value()), name);
        }
    }

    @Test
    @DisplayName("⛔ 阴性对照: warehouse_worker / production_manager 不在白名单里")
    void workerAndProductionManagerAreExcluded() {
        for (String name : WRITE_METHODS) {
            Set<String> allowed = Set.of(method(name).getAnnotation(RequireRole.class).value());
            assertEquals(false, allowed.contains("warehouse_worker"),
                    name + ": warehouse_worker 回到名单里就是把缺陷放回来了");
            assertEquals(false, allowed.contains("production_manager"),
                    name + ": owner 的原话是「只让仓储主管改」, 生产侧角色不该被放行");
        }
    }

    @Test
    @DisplayName("⛔ 阴性对照: 不存在的 factory_admin 角色没有被误写进去")
    void deadRoleNotPresent() {
        for (String name : WRITE_METHODS) {
            Set<String> allowed = Set.of(method(name).getAnnotation(RequireRole.class).value());
            assertEquals(false, allowed.contains("factory_admin"),
                    "FactoryUserRole 里没有 factory_admin 这个值 (见 CanvasThresholdsControllerPermissionTest.deadRoleIsGone)");
        }
    }

    @Test
    @DisplayName("markByproduct / 只读端点不该被这次改动波及 (不带 @RequireRole)")
    void readOnlyAndUnrelatedEndpointsUntouched() {
        assertNull(method("markByproduct").getAnnotation(RequireRole.class),
                "markByproduct 不在 list.vue 的调用路径里, 本次改动不该波及它");
        assertNull(method("getMaterialTypeById").getAnnotation(RequireRole.class));
        assertNull(method("getMaterialTypes").getAnnotation(RequireRole.class));
    }

    @Test
    @DisplayName("@RequirePermission (模块闸) 原样保留 —— 角色闸是叠加, 不是替换")
    void modulePermissionGateStaysAdditive() {
        for (String name : WRITE_METHODS) {
            var permission = method(name).getAnnotation(com.cretas.aims.annotation.RequirePermission.class);
            assertNotNull(permission, name + ": 模块闸不该被这次改动移除");
            assertEquals(Set.of("production:read_write", "warehouse:read_write"),
                    Set.of(permission.value()), name);
        }
    }
}
