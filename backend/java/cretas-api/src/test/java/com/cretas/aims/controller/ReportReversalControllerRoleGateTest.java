package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报工撤回审批的权限闸。
 *
 * <p>纪律排查 B1 (Steve 2026-11-10「审批人按权限」): 原来是 {@code @RequireRole({"factory_super_admin",
 * "production_manager"})} 写死岗位名; 现在是专门的审批权限码 {@code approval_report_reversal:approve},
 * 默认授权仍是这两个岗位 (见 {@code PermissionServiceImplApprovalCodesContractTest}), 各厂可改。
 * ⛔ 不能再叠一层岗位名单 —— 叠着它, 工厂把审批授给别的岗位也批不了。
 */
class ReportReversalControllerRoleGateTest {

    @Test
    void approveAndRejectRequireTheReversalApprovalPermission() {
        for (String methodName : Set.of("approveReversal", "rejectReversal")) {
            Method method = Arrays.stream(ReportReversalController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            RequirePermission gate = method.getAnnotation(RequirePermission.class);

            assertThat(gate).as("%s must have a real permission gate", methodName).isNotNull();
            assertThat(gate.value()).as("%s uses the dedicated approval code", methodName)
                    .containsExactly("approval_report_reversal:approve");
            assertThat(method.getAnnotation(RequireRole.class))
                    .as("%s must not keep a hard-coded role list on top", methodName).isNull();
        }
    }
}
