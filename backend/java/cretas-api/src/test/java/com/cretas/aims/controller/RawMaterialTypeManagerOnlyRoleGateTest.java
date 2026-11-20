package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 工厂纪律 B7 (Steve 2026-11-10): 原料类型的维护动作从写死的岗位白名单 ({@code @RequireRole}) 改成按
 * 「原料类型维护」权限判断。默认谁有这个权限 (仓储主管 + 厂长) 由 {@code ConfigurableDefaultPermissionsContractTest}
 * 钉住; 本文件只证明「注解接对了」: 6 个维护端点都带这个权限码, 且没有任何残留的角色白名单。
 */
class RawMaterialTypeManagerOnlyRoleGateTest {

    /**
     * 物料包装规格 controller (MaterialPackagingHierarchyController, 本次不动) 仍用这份角色白名单,
     * MaterialPackagingHierarchyManagerOnlyRoleGateTest 引用它。
     */
    static final Set<String> EXPECTED_ROLES = Set.of("warehouse_manager", "factory_super_admin");

    /** 维护类端点: 新建 / 改 / 删 / 加别名 / 删别名 / 合并。 */
    private static final Set<String> MAINTAIN_METHODS = Set.of(
            "createMaterialType", "updateMaterialType", "deleteMaterialType",
            "addAlias", "removeAlias", "merge");

    private Method method(String name) {
        return Arrays.stream(RawMaterialTypeController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("端点方法不存在: " + name));
    }

    @Test
    @DisplayName("阳性对照: 6 个维护端点都找得到 —— 找不到的话下面每条断言都是恒真式")
    void allMaintainEndpointsExist() {
        MAINTAIN_METHODS.forEach(name -> assertNotNull(method(name), name));
    }

    @Test
    @DisplayName("6 个维护端点都按「原料类型维护」权限判断")
    void maintainEndpointsRequireTheMaintainPermission() {
        for (String name : MAINTAIN_METHODS) {
            RequirePermission ann = method(name).getAnnotation(RequirePermission.class);
            assertNotNull(ann, name + " 上没有 @RequirePermission");
            assertArrayEquals(new String[]{PermissionService.MATERIAL_TYPE_MAINTAIN_PERMISSION}, ann.value(), name);
        }
    }

    @Test
    @DisplayName("⛔ 阴性对照: 整个 controller 不再有写死岗位的 @RequireRole")
    void noHardcodedRoleWhitelistRemains() {
        for (Method m : RawMaterialTypeController.class.getDeclaredMethods()) {
            assertNull(m.getAnnotation(RequireRole.class), m.getName() + " 仍带写死岗位的 @RequireRole");
        }
        assertEquals("material_type:maintain", PermissionService.MATERIAL_TYPE_MAINTAIN_PERMISSION);
    }
}
