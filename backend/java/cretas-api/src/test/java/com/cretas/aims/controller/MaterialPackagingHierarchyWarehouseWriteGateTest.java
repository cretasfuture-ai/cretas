package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 缺陷 46 (2026-09-02) 的后端回归闸 —— 见
 * {@link RawMaterialTypeWarehouseWriteGateTest} 的完整背景。
 *
 * <p>`/warehouse/material-types` 页面保存原料类型时会顺带 upsert/delete 该原料的
 * 包装换算规格 ({@code PUT}/{@code DELETE .../material-packaging/by-material/{id}}),
 * 这两个端点原来同样只认 {@code production:read_write} —— 只放宽
 * {@code RawMaterialTypeController} 而漏这里, 仓储角色保存主记录会成功 (物料主记录是独立
 * 事务, list.vue 把包装规格的请求单独 try/catch, 见
 * packagingFailureNotReportedAsCreateFailure.source.spec.ts), 但包装规格 403,
 * 提示「物料已保存，但包装规格未保存」—— 缺陷会以更隐蔽的「包装换算怎么又没存上」形式复发,
 * 而不是显眼的「新建按钮 403」。
 */
class MaterialPackagingHierarchyWarehouseWriteGateTest {

    private static final Set<String> EXPECTED = Set.of("production:read_write", "warehouse:read_write");

    @Test
    void upsertAndDeleteAcceptWarehouseWriteAsAlternativeToProduction() {
        assertPermissions("upsert", EXPECTED);
        assertPermissions("delete", EXPECTED);
    }

    @Test
    void gateAcceptsAnyOnePermissionNotBoth() {
        assertRequireAllIsFalse("upsert");
        assertRequireAllIsFalse("delete");
    }

    private void assertPermissions(String methodName, Set<String> expectedPermissions) {
        Method method = Arrays.stream(MaterialPackagingHierarchyController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        RequirePermission permission = method.getAnnotation(RequirePermission.class);

        assertNotNull(permission, methodName + " must declare an explicit permission gate");
        assertEquals(expectedPermissions, Set.of(permission.value()));
    }

    private void assertRequireAllIsFalse(String methodName) {
        Method method = Arrays.stream(MaterialPackagingHierarchyController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertNotNull(permission);
        assertFalse(permission.requireAll(), methodName + " must accept either module, not require both");
    }
}
