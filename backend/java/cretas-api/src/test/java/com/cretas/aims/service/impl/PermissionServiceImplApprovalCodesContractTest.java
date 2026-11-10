package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 「审批人按权限」(纪律排查 B1–B3/B11/B15, Steve 2026-11-10): 五个审批权限码。
 *
 * <ol>
 *   <li><b>默认授权 = 旧名单</b>: 没有任何 L2 / L1 配置时, 对<b>每一个</b>岗位逐个断言 —— 旧名单里的有,
 *       不在旧名单里的没有; 一线岗位 (操作员 / 质检员 / 班组长 …) 显式断言五个码都没有。</li>
 *   <li><b>改授权后按新授权</b>: L2 把撤回审批授给操作员 → 有; 把生产经理改成 {@code -} → 没有; 别的厂不受影响。</li>
 *   <li>改价 / 退款以前没有岗位名单, 门槛是 finance(/sales):read_write ⇒ 默认逐岗位等于旧门槛,
 *       而且<b>连同该厂对 finance 的现有配置</b>一起继承; 显式配置审批码则以审批码为准。</li>
 *   <li>登录快照 ({@code resolveModuleLevels}, 前端据此显示审批按钮) 与 {@code hasPermission} 一致。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionServiceImplApprovalCodesContractTest {

    private static final String F = "F006";
    private static final String OTHER = "F999";

    @Mock
    private PlatformRolePermissionRepository platformRepo;
    @Mock
    private FactoryModuleConfigRepository factoryConfigRepo;

    @InjectMocks
    private PermissionServiceImpl service;

    private final Map<String, Map<String, String>> fakeL1 = new HashMap<>();
    private final Map<String, Map<String, Map<String, String>>> fakeL2 = new HashMap<>();
    private long nextUserId = 5000;

    /** 改动前写死的名单 (controller @RequireRole / 服务层常量), 逐字抄自改动前的代码。 */
    private static final Map<String, Set<String>> OLD_ROLE_LISTS = Map.of(
            // ReportReversalController @RequireRole({"factory_super_admin", "production_manager"})
            // (+ platform_admin: RequireRoleInterceptor 对已认证平台身份总是放行)
            PermissionServiceImpl.APPROVAL_REPORT_REVERSAL,
            Set.of("factory_super_admin", "production_manager", "platform_admin"),
            // FactoryStocktakeServiceImpl.STOCKTAKE_APPROVAL_ROLES
            PermissionServiceImpl.APPROVAL_STOCKTAKE, Set.of("finance_manager", "factory_super_admin", "platform_admin"),
            // SpecialApprovalService.APPROVER_ROLES (+ platform_admin 按已认证平台身份放行)
            PermissionServiceImpl.APPROVAL_QUALITY_DISPOSITION,
            Set.of("quality_manager", "factory_super_admin", "permission_admin", "platform_admin"));

    /** 一线岗位: 五个审批码默认一个都不能有。 */
    private static final List<FactoryUserRole> FRONTLINE = List.of(
            FactoryUserRole.operator, FactoryUserRole.yield_operator, FactoryUserRole.quality_inspector,
            FactoryUserRole.team_leader, FactoryUserRole.group_leader, FactoryUserRole.workshop_supervisor,
            FactoryUserRole.warehouse_worker, FactoryUserRole.production_clerk);

    @BeforeEach
    void wireFakeTables() {
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenAnswer(inv -> {
            String role = inv.getArgument(0);
            List<PlatformRolePermission> rows = new ArrayList<>();
            fakeL1.getOrDefault(role, Collections.emptyMap()).forEach((module, level) -> rows.add(l1Row(role, module, level)));
            return rows;
        });
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(inv -> {
            String level = fakeL1.getOrDefault(inv.<String>getArgument(0), Collections.emptyMap()).get(inv.<String>getArgument(1));
            return level == null ? Optional.empty() : Optional.of(l1Row(inv.getArgument(0), inv.getArgument(1), level));
        });
        when(factoryConfigRepo.findByFactoryIdAndConfigVersion(anyString(), eq(1))).thenAnswer(inv -> {
            Map<String, Map<String, String>> override = fakeL2.get(inv.<String>getArgument(0));
            if (override == null) return Collections.emptyList();
            FactoryModuleConfig cfg = new FactoryModuleConfig();
            cfg.setFactoryId(inv.getArgument(0));
            cfg.setRoleModuleOverride(override);
            return List.of(cfg);
        });
    }

    private static PlatformRolePermission l1Row(String role, String module, String level) {
        PlatformRolePermission row = new PlatformRolePermission();
        row.setRoleCode(role);
        row.setModuleCode(module);
        row.setPermissionLevel(level);
        return row;
    }

    private User user(FactoryUserRole role, String factoryId) {
        User u = new User();
        u.setId(nextUserId++);   // hasPermission 按 userId 缓存, 每次新 id 免得串
        u.setRoleCode(role.name());
        u.setFactoryId(factoryId);
        return u;
    }

    private boolean can(FactoryUserRole role, String factoryId, String approvalModule) {
        return service.hasPermission(user(role, factoryId), approvalModule + ":approve");
    }

    private static List<FactoryUserRole> liveFactoryRoles() {
        List<FactoryUserRole> roles = new ArrayList<>();
        for (FactoryUserRole role : FactoryUserRole.values()) {
            if (role == FactoryUserRole.unactivated) continue;
            roles.add(role);
        }
        return roles;
    }

    @Test
    @DisplayName("默认授权 = 旧名单: 撤回 / 盘点 / 质检, 每个岗位逐个断言 (名单里的有, 名单外的没有)")
    void defaultGrantEqualsTheOldRoleListForEveryRole() {
        int checked = 0;
        for (Map.Entry<String, Set<String>> entry : OLD_ROLE_LISTS.entrySet()) {
            for (FactoryUserRole role : liveFactoryRoles()) {
                boolean expected = entry.getValue().contains(role.name()) && !isRetired(role);
                assertThat(can(role, F, entry.getKey()))
                        .as("%s 对 %s 的默认授权应与旧名单一致", role, entry.getKey())
                        .isEqualTo(expected);
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(3 * 15);   // 阳性对照: 真的逐岗位遍历了
    }

    @Test
    @DisplayName("默认授权 = 旧门槛: 改价 = finance 或 sales 读写, 退款 = finance 读写, 每个岗位逐个断言")
    void priceAndRefundDefaultsFollowTheOldModuleGate() {
        for (FactoryUserRole role : liveFactoryRoles()) {
            User u = user(role, F);
            boolean finance = service.hasPermission(u, "finance:read_write");
            boolean sales = service.hasPermission(user(role, F), "sales:read_write");
            assertThat(can(role, F, PermissionServiceImpl.APPROVAL_PRICE_ADJUSTMENT)).as("改价 %s", role).isEqualTo(finance || sales);
            assertThat(can(role, F, PermissionServiceImpl.APPROVAL_REFUND)).as("退款 %s", role).isEqualTo(finance);
        }
        // 阳性对照: 名单不是空的, 也不是全员
        assertThat(can(FactoryUserRole.finance_manager, F, PermissionServiceImpl.APPROVAL_REFUND)).isTrue();
        assertThat(can(FactoryUserRole.sales_manager, F, PermissionServiceImpl.APPROVAL_PRICE_ADJUSTMENT)).isTrue();
        assertThat(can(FactoryUserRole.sales_manager, F, PermissionServiceImpl.APPROVAL_REFUND)).isFalse();
    }

    @Test
    @DisplayName("一线岗位 (操作员 / 质检员 / 班组长 …) 五个审批码默认一个都没有")
    void frontlineRolesHoldNoApprovalCodeByDefault() {
        for (FactoryUserRole role : FRONTLINE) {
            for (String module : PermissionServiceImpl.APPROVAL_MODULES) {
                assertThat(can(role, F, module)).as("%s 不应默认有 %s", role, module).isFalse();
            }
        }
    }

    @Test
    @DisplayName("改了授权按新授权: L2 授给操作员 → 有; 生产经理改成 - → 没有; 别的厂不变")
    void factoryOverrideTakesEffectAndStaysInItsFactory() {
        fakeL2.put(F, new HashMap<>(Map.of(
                "operator", Map.of(PermissionServiceImpl.APPROVAL_REPORT_REVERSAL, "rw"),
                "production_manager", Map.of(PermissionServiceImpl.APPROVAL_REPORT_REVERSAL, "-"))));

        assertThat(can(FactoryUserRole.operator, F, PermissionServiceImpl.APPROVAL_REPORT_REVERSAL)).isTrue();
        assertThat(can(FactoryUserRole.production_manager, F, PermissionServiceImpl.APPROVAL_REPORT_REVERSAL)).isFalse();
        // 只读 (r) 不够审批
        fakeL2.get(F).put("quality_inspector", Map.of(PermissionServiceImpl.APPROVAL_QUALITY_DISPOSITION, "r"));
        assertThat(can(FactoryUserRole.quality_inspector, F, PermissionServiceImpl.APPROVAL_QUALITY_DISPOSITION)).isFalse();
        // 别的厂仍是默认
        assertThat(can(FactoryUserRole.operator, OTHER, PermissionServiceImpl.APPROVAL_REPORT_REVERSAL)).isFalse();
        assertThat(can(FactoryUserRole.production_manager, OTHER, PermissionServiceImpl.APPROVAL_REPORT_REVERSAL)).isTrue();
    }

    @Test
    @DisplayName("平台默认 (L1) 也能改授权, 工厂覆盖 (L2) 优先")
    void platformDefaultAppliesAndFactoryOverrideWins() {
        fakeL1.put("warehouse_manager", Map.of(PermissionServiceImpl.APPROVAL_STOCKTAKE, "rw"));
        assertThat(can(FactoryUserRole.warehouse_manager, F, PermissionServiceImpl.APPROVAL_STOCKTAKE)).isTrue();
        fakeL2.put(F, Map.of("warehouse_manager", Map.of(PermissionServiceImpl.APPROVAL_STOCKTAKE, "-")));
        assertThat(can(FactoryUserRole.warehouse_manager, F, PermissionServiceImpl.APPROVAL_STOCKTAKE)).isFalse();
    }

    @Test
    @DisplayName("退款默认跟着本厂对 finance 的配置走; 显式配置退款审批码则以审批码为准")
    void refundDefaultInheritsFinanceConfigUnlessTheCodeIsConfigured() {
        fakeL2.put(F, new HashMap<>(Map.of("warehouse_manager", Map.of("finance", "rw"))));
        assertThat(can(FactoryUserRole.warehouse_manager, F, PermissionServiceImpl.APPROVAL_REFUND)).isTrue();

        fakeL2.put(F, new HashMap<>(Map.of(
                "warehouse_manager", Map.of("finance", "rw", PermissionServiceImpl.APPROVAL_REFUND, "-"),
                "finance_manager", Map.of(PermissionServiceImpl.APPROVAL_REFUND, "-"))));
        assertThat(can(FactoryUserRole.warehouse_manager, F, PermissionServiceImpl.APPROVAL_REFUND)).isFalse();
        assertThat(can(FactoryUserRole.finance_manager, F, PermissionServiceImpl.APPROVAL_REFUND)).isFalse();
        // 财务模块本身没被动
        assertThat(service.hasPermission(user(FactoryUserRole.finance_manager, F), "finance:read_write")).isTrue();
    }

    @Test
    @DisplayName("审查 2: 默认授权与前提模块取交集 —— 线上 L1 permission_admin.quality = '-' ⇒ 默认不能批质检 (与改动前一致)")
    void defaultGrantRequiresThePrerequisiteModule() {
        // 2026-11-11 prod 只读读数: platform_role_permissions 里 permission_admin 的 quality / production 都是 '-'
        fakeL1.put("permission_admin", Map.of("quality", "-", "production", "-"));
        assertThat(can(FactoryUserRole.permission_admin, F, PermissionServiceImpl.APPROVAL_QUALITY_DISPOSITION)).isFalse();
        // 工厂把生产经理 production 降成只读 → 默认不能批撤回; 质检经理 quality 降级 → 默认不能批质检
        fakeL2.put(F, new HashMap<>(Map.of(
                "production_manager", Map.of("production", "r"),
                "quality_manager", Map.of("quality", "r"))));
        assertThat(can(FactoryUserRole.production_manager, F, PermissionServiceImpl.APPROVAL_REPORT_REVERSAL)).isFalse();
        assertThat(can(FactoryUserRole.quality_manager, F, PermissionServiceImpl.APPROVAL_QUALITY_DISPOSITION)).isFalse();
        // 阴性对照: 别的厂没降级, 照常能批
        assertThat(can(FactoryUserRole.production_manager, OTHER, PermissionServiceImpl.APPROVAL_REPORT_REVERSAL)).isTrue();
        // 显式配置审批码按显式配置走, 不叠模块前提
        fakeL2.put(F, new HashMap<>(Map.of("production_manager",
                Map.of("production", "r", PermissionServiceImpl.APPROVAL_REPORT_REVERSAL, "rw"))));
        assertThat(can(FactoryUserRole.production_manager, F, PermissionServiceImpl.APPROVAL_REPORT_REVERSAL)).isTrue();
    }

    @Test
    @DisplayName("审查 2: 个人覆盖把 production 降成只读 → 这个人默认不能批撤回; 同岗位其他人不受影响")
    void personalDowngradeOfThePrerequisiteRemovesTheDefaultGrant() {
        com.cretas.aims.service.platform.PlatformEmployeeModulePermissions personal =
                org.mockito.Mockito.mock(com.cretas.aims.service.platform.PlatformEmployeeModulePermissions.class);
        User downgraded = user(FactoryUserRole.production_manager, F);
        when(personal.apply(org.mockito.ArgumentMatchers.any(User.class), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    User who = inv.getArgument(0);
                    String module = inv.getArgument(1);
                    return who.getId().equals(downgraded.getId()) && "production".equals(module) ? "r" : inv.getArgument(2);
                });
        org.springframework.test.util.ReflectionTestUtils.setField(service, "employeeModulePermissions", personal);

        assertThat(service.hasPermission(downgraded, "approval_report_reversal:approve")).isFalse();
        assertThat(can(FactoryUserRole.production_manager, F, PermissionServiceImpl.APPROVAL_REPORT_REVERSAL)).isTrue();
    }

    /** 个人覆盖: userId → (模块 → 级别), 与 PlatformEmployeeModulePermissions 的两种 apply 同口径。 */
    private final Map<Long, Map<String, String>> personalOverrides = new HashMap<>();

    private void wirePersonalOverrides() {
        com.cretas.aims.service.platform.PlatformEmployeeModulePermissions personal =
                org.mockito.Mockito.mock(com.cretas.aims.service.platform.PlatformEmployeeModulePermissions.class);
        when(personal.apply(org.mockito.ArgumentMatchers.any(User.class), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> personalOverrides.getOrDefault(inv.<User>getArgument(0).getId(), Map.of())
                        .getOrDefault(inv.<String>getArgument(1), inv.getArgument(2)));
        when(personal.apply(org.mockito.ArgumentMatchers.any(User.class), org.mockito.ArgumentMatchers.<Map<String, String>>any()))
                .thenAnswer(inv -> {
                    Map<String, String> merged = new java.util.LinkedHashMap<>(inv.<Map<String, String>>getArgument(1));
                    merged.putAll(personalOverrides.getOrDefault(inv.<User>getArgument(0).getId(), Map.of()));
                    return merged;
                });
        org.springframework.test.util.ReflectionTestUtils.setField(service, "employeeModulePermissions", personal);
    }

    @Test
    @DisplayName("复核 1: 个人降级 / 个人升权后, 登录快照、AI 工具闸快照与 hasPermission 对审批码逐格一致")
    void snapshotsFollowPersonalOverridesOfThePrerequisite() {
        wirePersonalOverrides();
        // 平台默认 permission_admin.quality = '-' (同线上)
        fakeL1.put("permission_admin", Map.of("quality", "-"));

        User downgraded = user(FactoryUserRole.production_manager, F);   // 个人把 production 降成只读
        personalOverrides.put(downgraded.getId(), Map.of("production", "r"));
        User upgraded = user(FactoryUserRole.permission_admin, F);       // 个人把 quality 升成读写
        personalOverrides.put(upgraded.getId(), Map.of("quality", "rw"));
        User financeViaPersonal = user(FactoryUserRole.warehouse_manager, F);   // 个人给了 finance 读写 → 退款默认跟着走
        personalOverrides.put(financeViaPersonal.getId(), Map.of("finance", "rw"));
        User plain = user(FactoryUserRole.production_manager, F);         // 对照: 同岗位没有个人覆盖

        for (User u : List.of(downgraded, upgraded, financeViaPersonal, plain)) {
            Map<String, String> login = service.resolveModuleLevels(u);
            Map<String, String> authz = service.resolveModuleLevelsForAuthorization(u);
            for (String module : PermissionServiceImpl.APPROVAL_MODULES) {
                boolean enforced = service.hasPermission(u, module + ":approve");
                assertThat("rw".equals(login.get(module))).as("登录快照 %s/%s", u.getRoleCode(), module).isEqualTo(enforced);
                assertThat("rw".equals(authz.get(module))).as("鉴权快照 %s/%s", u.getRoleCode(), module).isEqualTo(enforced);
            }
        }
        // 读数本身: 降级的不能批撤回, 升权的能批质检, 个人给了 finance 的能批退款, 对照照常能批撤回
        assertThat(service.hasPermission(downgraded, "approval_report_reversal:approve")).isFalse();
        assertThat(service.hasPermission(upgraded, "approval_quality_disposition:approve")).isTrue();
        assertThat(service.hasPermission(financeViaPersonal, "approval_refund:approve")).isTrue();
        assertThat(service.hasPermission(plain, "approval_report_reversal:approve")).isTrue();
    }

    @Test
    @DisplayName("登录快照 (前端据此显示审批按钮) 与 hasPermission 逐格一致")
    void loginSnapshotMatchesHasPermission() {
        fakeL2.put(F, Map.of("operator", Map.of(PermissionServiceImpl.APPROVAL_REPORT_REVERSAL, "rw")));
        for (FactoryUserRole role : liveFactoryRoles()) {
            if (role == FactoryUserRole.factory_super_admin || role == FactoryUserRole.platform_admin || isRetired(role)) {
                continue;   // 超管快照走通配 "*"; 退役岗位整份为空
            }
            User u = user(role, F);
            Map<String, String> snapshot = service.resolveModuleLevels(u);
            for (String module : PermissionServiceImpl.APPROVAL_MODULES) {
                boolean inSnapshot = "rw".equals(snapshot.get(module));
                assertThat(inSnapshot).as("%s.%s 快照与鉴权不一致", role, module)
                        .isEqualTo(service.hasPermission(user(role, F), module + ":approve"));
            }
        }
    }

    /** 退役 (餐饮) 岗位: hasPermission 对它们一律 false, 名单里本来也没有。判据用服务自己的那一份, 不抄第二份。 */
    private static boolean isRetired(FactoryUserRole role) {
        Boolean retired = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                PermissionServiceImpl.class, "isRetiredRestaurantRole", role);
        return Boolean.TRUE.equals(retired);
    }
}
