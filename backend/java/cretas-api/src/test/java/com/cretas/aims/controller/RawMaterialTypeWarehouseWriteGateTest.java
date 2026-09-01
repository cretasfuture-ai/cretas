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
 * 缺陷 46 (2026-09-02, 刘山门实测) 的后端回归闸。
 *
 * <p>症状: `/warehouse/material-types` 页面菜单条目 module 是 {@code warehouse}
 * (仓储管理), 但页面写门禁 (`v-if="canWrite"`) 原来绑的是 {@code production},
 * 而 {@code warehouse_manager}/{@code warehouse_worker} 两个角色在
 * {@code platform_role_permissions} 里对 {@code production} 分别只有 {@code r}/{@code -}
 * (见 V20260422_01__platform_role_permissions.sql) —— 仓储角色新增/编辑/删除原料类型
 * 一律 403, 只留「供应商」一栏能操作。
 *
 * <p>owner 拍板改**页面**写权限 (前端 canWrite('warehouse')), 因此后端这三个写端点
 * (create/update/delete) 必须同时接受 {@code warehouse:read_write}, 否则前端放行、
 * 后端仍 403 (「看得见点不开」变成「点了 403」, 见交接件)。{@code requireAll=false}
 * (任一即可) 保证 production 侧角色不受影响。
 *
 * <p>{@link #markByproductStaysProductionOnly()} 是阴性对照: markByproduct 本页
 * 不调用, 不该被本次改动波及 —— 若有人以后"顺手"把它也放宽, 这条测试会先红。
 */
class RawMaterialTypeWarehouseWriteGateTest {

    private static final Set<String> CREATE_UPDATE_DELETE_PERMISSIONS =
            Set.of("production:read_write", "warehouse:read_write");

    @Test
    void createUpdateDeleteAcceptWarehouseWriteAsAlternativeToProduction() {
        assertPermissions("createMaterialType", CREATE_UPDATE_DELETE_PERMISSIONS);
        assertPermissions("updateMaterialType", CREATE_UPDATE_DELETE_PERMISSIONS);
        assertPermissions("deleteMaterialType", CREATE_UPDATE_DELETE_PERMISSIONS);
    }

    @Test
    void gateAcceptsAnyOnePermissionNotBoth() {
        assertRequireAllIsFalse("createMaterialType");
        assertRequireAllIsFalse("updateMaterialType");
        assertRequireAllIsFalse("deleteMaterialType");
    }

    /**
     * 阴性对照: markByproduct 不在 list.vue 的调用路径里 (它调 POST/PUT/DELETE
     * {@code /raw-material-types}，不调 {@code PATCH .../byproduct})，不该被这次
     * 「放宽仓储角色」的改动一并放宽。
     */
    @Test
    void markByproductStaysProductionOnly() {
        assertPermissions("markByproduct", Set.of("production:read_write"));
    }

    private void assertPermissions(String methodName, Set<String> expectedPermissions) {
        Method method = Arrays.stream(RawMaterialTypeController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        RequirePermission permission = method.getAnnotation(RequirePermission.class);

        assertNotNull(permission, methodName + " must declare an explicit permission gate");
        assertEquals(expectedPermissions, Set.of(permission.value()));
    }

    private void assertRequireAllIsFalse(String methodName) {
        Method method = Arrays.stream(RawMaterialTypeController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertNotNull(permission);
        assertFalse(permission.requireAll(), methodName + " must accept either module, not require both");
    }
}
