package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 2026-08-21: production_clerk(生产文员) 新增角色, 主职是审核报工 —— owner 原话
 * 「蹲在电脑前审核这些报工数据」。本测试锁住 {@link ProcessWorkReportingController}
 * 四个报工审核端点的角色白名单确实加了 production_clerk, 且**只加这四处**
 * (全仓另外 12 处含 workshop_supervisor 的 @RequireRole 是流程引擎/规则配置,
 * 不属于「审核报工/生产数据录入/产品基础数据」三类, 故意不加 —— 见交付报告)。
 */
class ProcessWorkReportingControllerClerkRoleGateTest {

    private static final Set<String> EXPECTED_ROLES = Set.of(
            "factory_super_admin", "permission_admin", "production_manager",
            "production_clerk", "workshop_supervisor");

    @Test
    void pendingApprovals_allowsProductionClerk() {
        assertRoles("getPendingApprovals");
    }

    @Test
    void approve_allowsProductionClerk() {
        assertRoles("approve");
    }

    @Test
    void reject_allowsProductionClerk() {
        assertRoles("reject");
    }

    @Test
    void batchApprove_allowsProductionClerk() {
        assertRoles("batchApprove");
    }

    private void assertRoles(String methodName) {
        Method method = Arrays.stream(ProcessWorkReportingController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        RequireRole role = method.getAnnotation(RequireRole.class);

        assertNotNull(role, methodName + " must declare a role gate");
        assertEquals(EXPECTED_ROLES, Set.of(role.value()));
    }
}
