package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-21: production_manager 从 dispatcher 别名拆成独立角色(生产经理)后,
 * 不应再被 {@code listRoles} 特殊剔除 —— 之前的排除注释写着「deprecated
 * production_manager (已 alias 到 dispatcher)」, 现在不成立了。
 *
 * <p>同时锁住新角色 production_clerk 会出现在这个动态角色列表里(web-admin
 * 的 user assignment dropdown 依赖这个端点, Issue #817)。
 */
@ExtendWith(MockitoExtension.class)
class RoleControllerListRolesTest {

    @Mock
    private PermissionService permissionService;

    @Test
    void listRoles_includesProductionManager() {
        RoleController controller = new RoleController(permissionService);
        ApiResponse<List<Map<String, Object>>> response = controller.listRoles("F006");

        List<String> names = response.getData().stream()
                .map(row -> (String) row.get("name"))
                .collect(Collectors.toList());

        assertTrue(names.contains("production_manager"),
                "production_manager must no longer be excluded from listRoles");
    }

    @Test
    void listRoles_includesProductionClerk() {
        RoleController controller = new RoleController(permissionService);
        ApiResponse<List<Map<String, Object>>> response = controller.listRoles("F006");

        List<String> names = response.getData().stream()
                .map(row -> (String) row.get("name"))
                .collect(Collectors.toList());

        assertTrue(names.contains("production_clerk"));
    }

    @Test
    void listRoles_stillExcludesUnactivated() {
        RoleController controller = new RoleController(permissionService);
        ApiResponse<List<Map<String, Object>>> response = controller.listRoles("F006");

        List<String> names = response.getData().stream()
                .map(row -> (String) row.get("name"))
                .collect(Collectors.toList());

        assertFalse(names.contains("unactivated"));
    }

    @Test
    void listRoles_productionManagerDisplayName_isProductionManagerNotDispatch() {
        RoleController controller = new RoleController(permissionService);
        ApiResponse<List<Map<String, Object>>> response = controller.listRoles("F006");

        Map<String, Object> row = response.getData().stream()
                .filter(r -> "production_manager".equals(r.get("name")))
                .findFirst()
                .orElseThrow();

        assertTrue("生产经理".equals(row.get("displayName")),
                "expected 生产经理, got " + row.get("displayName"));
    }
}
