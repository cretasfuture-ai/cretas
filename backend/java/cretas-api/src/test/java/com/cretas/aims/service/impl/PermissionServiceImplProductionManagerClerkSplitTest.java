package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-21 owner 实测: {@code production_manager} 长期是 {@code dispatcher}
 * 的别名 ({@code PERMISSION_MATRIX.put(production_manager, dispatcherPerms)}),
 * 导致生产经理拿到调度岗的全模块只读(含 hr/finance/system), web-admin 侧边栏
 * 采购/销售/财务/生产/仓储/质量/数据分析/系统管理/智能调度/人事/设备/AI 全都有,
 * "跟一个 admin 也没什么区别"。
 *
 * <p>本测试锁住三件事(hardcoded fallback matrix, 与
 * {@link PermissionServiceImplWarehouseWorkerReceiveTest} 同一测试策略—— 不
 * mock DB layer, 测的是 L2/L1 都缺失时的兜底路径, 也是 fresh test profile 的
 * 实际路径):
 * <ol>
 *   <li>production_manager 不再拥有 hr/finance/system 任何权限 (回归本次 bug 的核心)</li>
 *   <li>production_manager 的 sales/analytics/rd 从 dispatcher 的 rw 降级为 r</li>
 *   <li>新角色 production_clerk 只有 owner 拍板的那 7 个模块, 其余一律无权限</li>
 *   <li>dispatcher 本身分毫未变 (Apr 18 2026 bug #45 的 sales:rw 回归防线)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceImplProductionManagerClerkSplitTest {

    @InjectMocks
    private PermissionServiceImpl permissionService;

    @BeforeEach
    void successfulEmptyPermissionRepositories() {
        PermissionRepositoryFixtures.installSuccessfulEmpty(permissionService);
    }

    private User makeUser(String roleCode) {
        User u = new User();
        u.setId(1552L);
        u.setRoleCode(roleCode);
        u.setFactoryId("F006");
        return u;
    }

    // ==================== production_manager: 砍掉的三项 ====================

    @Test
    @DisplayName("production_manager no longer has ANY hr permission (core bug regression)")
    void productionManager_hasNoHrPermission() {
        User u = makeUser("production_manager");
        assertFalse(permissionService.hasPermission(u, "hr:read"));
        assertFalse(permissionService.hasPermission(u, "hr:read_write"));
    }

    @Test
    @DisplayName("production_manager no longer has ANY finance permission")
    void productionManager_hasNoFinancePermission() {
        User u = makeUser("production_manager");
        assertFalse(permissionService.hasPermission(u, "finance:read"));
        assertFalse(permissionService.hasPermission(u, "finance:read_write"));
    }

    @Test
    @DisplayName("production_manager no longer has ANY system permission")
    void productionManager_hasNoSystemPermission() {
        User u = makeUser("production_manager");
        assertFalse(permissionService.hasPermission(u, "system:read"));
        assertFalse(permissionService.hasPermission(u, "system:read_write"));
    }

    // ==================== production_manager: 降级的三项 ====================

    @Test
    @DisplayName("production_manager sales is read-only (was read_write on dispatcher alias)")
    void productionManager_salesIsReadOnly() {
        User u = makeUser("production_manager");
        assertTrue(permissionService.hasPermission(u, "sales:read"));
        assertFalse(permissionService.hasPermission(u, "sales:read_write"));
    }

    @Test
    @DisplayName("production_manager analytics is read-only (was read_write on dispatcher alias)")
    void productionManager_analyticsIsReadOnly() {
        User u = makeUser("production_manager");
        assertTrue(permissionService.hasPermission(u, "analytics:read"));
        assertFalse(permissionService.hasPermission(u, "analytics:read_write"));
    }

    @Test
    @DisplayName("production_manager rd is read-only (was read_write on dispatcher alias)")
    void productionManager_rdIsReadOnly() {
        User u = makeUser("production_manager");
        assertTrue(permissionService.hasPermission(u, "rd:read"));
        assertFalse(permissionService.hasPermission(u, "rd:read_write"));
    }

    // ==================== production_manager: 保留的核心权限 ====================

    @Test
    @DisplayName("production_manager keeps production/work_report/scheduling/dashboard read_write")
    void productionManager_keepsCoreReadWrite() {
        User u = makeUser("production_manager");
        assertTrue(permissionService.hasPermission(u, "production:read_write"));
        assertTrue(permissionService.hasPermission(u, "work_report:read_write"));
        assertTrue(permissionService.hasPermission(u, "scheduling:read_write"));
        assertTrue(permissionService.hasPermission(u, "dashboard:read_write"));
    }

    @Test
    @DisplayName("production_manager keeps read on quality/equipment/procurement/warehouse/inventory/report")
    void productionManager_keepsSupportingReads() {
        User u = makeUser("production_manager");
        assertTrue(permissionService.hasPermission(u, "quality:read"));
        assertTrue(permissionService.hasPermission(u, "equipment:read"));
        assertTrue(permissionService.hasPermission(u, "procurement:read"));
        assertTrue(permissionService.hasPermission(u, "warehouse:read"));
        assertTrue(permissionService.hasPermission(u, "inventory:read"));
        assertTrue(permissionService.hasPermission(u, "report:read"));
    }

    // ==================== production_clerk: 拥有的 7 个模块 ====================

    @Test
    @DisplayName("production_clerk has work_report + production read_write (approve reports, enter data)")
    void productionClerk_hasReportAndProductionReadWrite() {
        User u = makeUser("production_clerk");
        assertTrue(permissionService.hasPermission(u, "work_report:read_write"));
        assertTrue(permissionService.hasPermission(u, "production:read_write"));
    }

    @Test
    @DisplayName("production_clerk has read on dashboard/report/analytics/inventory/warehouse")
    void productionClerk_hasReadOnBasicModules() {
        User u = makeUser("production_clerk");
        assertTrue(permissionService.hasPermission(u, "dashboard:read"));
        assertTrue(permissionService.hasPermission(u, "report:read"));
        assertTrue(permissionService.hasPermission(u, "analytics:read"));
        assertTrue(permissionService.hasPermission(u, "inventory:read"));
        assertTrue(permissionService.hasPermission(u, "warehouse:read"));
    }

    @Test
    @DisplayName("production_clerk analytics is read-only, not read_write")
    void productionClerk_analyticsIsReadOnly() {
        User u = makeUser("production_clerk");
        assertFalse(permissionService.hasPermission(u, "analytics:read_write"));
    }

    // ==================== production_clerk: 没有的模块 (不是过度授权) ====================

    @Test
    @DisplayName("production_clerk has no quality/procurement/sales/scheduling/rd permission at all")
    void productionClerk_hasNoUnlistedModulePermission() {
        User u = makeUser("production_clerk");
        for (String module : new String[]{"quality", "procurement", "sales", "scheduling", "rd"}) {
            assertFalse(permissionService.hasPermission(u, module + ":read"),
                    "production_clerk must not read " + module);
            assertFalse(permissionService.hasPermission(u, module + ":read_write"),
                    "production_clerk must not write " + module);
        }
    }

    @Test
    @DisplayName("production_clerk has no hr/finance/system permission (same bug class as production_manager)")
    void productionClerk_hasNoHrFinanceSystemPermission() {
        User u = makeUser("production_clerk");
        for (String module : new String[]{"hr", "finance", "system"}) {
            assertFalse(permissionService.hasPermission(u, module + ":read"),
                    "production_clerk must not read " + module);
        }
    }

    @Test
    @DisplayName("production_clerk can create product types (production:read_write satisfies the OR gate)")
    void productionClerk_canCreateProductTypes() {
        // ProductTypeController.createProductType gate is
        // @RequirePermission({"production:read_write", "rd:read_write"}), requireAll=false.
        // production_clerk qualifies via production:read_write alone, no extra module needed.
        User u = makeUser("production_clerk");
        assertTrue(permissionService.hasAnyPermission(u, "production:read_write", "rd:read_write"));
    }

    // ==================== dispatcher: 分毫未变 (回归防线) ====================

    @Test
    @DisplayName("dispatcher keeps sales:read_write (Apr 18 2026 bug #45) — split must not touch it")
    void dispatcher_stillHasSalesReadWrite() {
        User u = makeUser("dispatcher");
        assertTrue(permissionService.hasPermission(u, "sales:read_write"));
    }

    @Test
    @DisplayName("dispatcher keeps hr/finance/system read (unlike the now-split production_manager)")
    void dispatcher_stillHasHrFinanceSystemRead() {
        User u = makeUser("dispatcher");
        assertTrue(permissionService.hasPermission(u, "hr:read"));
        assertTrue(permissionService.hasPermission(u, "finance:read"));
        assertTrue(permissionService.hasPermission(u, "system:read"));
    }

    @Test
    @DisplayName("dispatcher keeps analytics/rd read_write (unlike the now-downgraded production_manager)")
    void dispatcher_stillHasAnalyticsRdReadWrite() {
        User u = makeUser("dispatcher");
        assertTrue(permissionService.hasPermission(u, "analytics:read_write"));
        assertTrue(permissionService.hasPermission(u, "rd:read_write"));
    }
}
