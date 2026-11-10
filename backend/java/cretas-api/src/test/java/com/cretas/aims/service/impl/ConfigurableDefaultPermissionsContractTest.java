package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工厂纪律 B7 / B9 / A17 (Steve 2026-11-10): 「原料类型维护」「报损审批」「信用特批」不再写死岗位,
 * 按权限判断: 工厂在阈值里配了岗位名单就按配置, 没配才落到默认名单; 厂长恒有。
 *
 * <p>阳性对照: 默认角色真的放行; 阴性对照: 其他角色真的被挡; 配置对照: 同一个角色一配置就翻转。
 * 用户路径 (hasPermission) 与角色路径 (roleHasConfiguredPermission) 必须给同一个结论。
 */
@DisplayName("可配置默认岗位的权限码 (原料类型维护 / 报损审批 / 信用特批)")
class ConfigurableDefaultPermissionsContractTest {

    private PermissionServiceImpl service;
    private ThresholdResolverService thresholds;

    @BeforeEach
    void setUp() {
        service = new PermissionServiceImpl();
        thresholds = mock(ThresholdResolverService.class);
        ReflectionTestUtils.setField(service, "thresholdResolver", thresholds);
    }

    private User user(long id, FactoryUserRole role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId("F100");
        u.setRoleCode(role.name());
        return u;
    }

    private void configure(String key, String roles) {
        when(thresholds.getString("F100", key, null)).thenReturn(roles);
    }

    /** 同一权限码上, 用户路径与角色路径必须一致 (审查 #5: 两条路径曾可能给出不同结论)。 */
    private boolean both(FactoryUserRole role, String code) {
        boolean byUser = service.hasPermission(user(1, role), code);
        boolean byRole = service.roleHasConfiguredPermission("F100", role.name(), code);
        assertThat(byRole).as("用户路径与角色路径对 %s × %s 结论不同", role, code).isEqualTo(byUser);
        return byUser;
    }

    @Test
    @DisplayName("原料类型维护: 默认仓储主管 + 厂长可以, 仓管 / 生产主管不行")
    void materialTypeMaintain_defaults() {
        String code = PermissionService.MATERIAL_TYPE_MAINTAIN_PERMISSION;
        assertThat(both(FactoryUserRole.warehouse_manager, code)).isTrue();
        assertThat(both(FactoryUserRole.factory_super_admin, code)).isTrue();
        assertThat(both(FactoryUserRole.warehouse_worker, code)).isFalse();
        assertThat(both(FactoryUserRole.production_manager, code)).isFalse();
    }

    @Test
    @DisplayName("原料类型维护: 工厂把名单配成 production_manager ⇒ 他能了, 仓储主管反而不行; 厂长不受影响")
    void materialTypeMaintain_followsFactoryConfiguration() {
        String code = PermissionService.MATERIAL_TYPE_MAINTAIN_PERMISSION;
        configure(ThresholdKeys.INVENTORY_PERMISSION_MATERIAL_TYPE_MAINTAIN, "production_manager");

        assertThat(both(FactoryUserRole.production_manager, code)).isTrue();
        assertThat(both(FactoryUserRole.warehouse_manager, code)).isFalse();
        assertThat(both(FactoryUserRole.factory_super_admin, code)).isTrue();
    }

    @Test
    @DisplayName("报损审批: 默认只给厂长; 财务经理 / 生产主管默认不行, 配了就行 (大小写 / 空格不敏感)")
    void wastageApprove_defaultsAndConfiguration() {
        String code = PermissionService.WASTAGE_APPROVE_PERMISSION;
        assertThat(both(FactoryUserRole.factory_super_admin, code)).isTrue();
        assertThat(both(FactoryUserRole.finance_manager, code)).isFalse();
        assertThat(both(FactoryUserRole.production_manager, code)).isFalse();

        configure(ThresholdKeys.INVENTORY_PERMISSION_WASTAGE_APPROVE, " Finance_Manager ,x");
        assertThat(both(FactoryUserRole.finance_manager, code)).isTrue();
        assertThat(both(FactoryUserRole.production_manager, code)).isFalse();
    }

    @Test
    @DisplayName("配成空串 = 明确「只有厂长」, 不会退回默认名单")
    void emptyConfigurationMeansOnlyFactoryHead() {
        String code = PermissionService.MATERIAL_TYPE_MAINTAIN_PERMISSION;
        configure(ThresholdKeys.INVENTORY_PERMISSION_MATERIAL_TYPE_MAINTAIN, "");
        assertThat(both(FactoryUserRole.warehouse_manager, code)).isFalse();
        assertThat(both(FactoryUserRole.factory_super_admin, code)).isTrue();
    }

    @Test
    @DisplayName("角色路径: 不认识的角色 / null / 不是可配置的权限码一律 false")
    void roleBasedCheckRejectsUnknownInputs() {
        String code = PermissionService.WASTAGE_APPROVE_PERMISSION;
        assertThat(service.roleHasConfiguredPermission("F100", "FACTORY_SUPER_ADMIN", code)).isTrue();
        assertThat(service.roleHasConfiguredPermission("F100", "not_a_role", code)).isFalse();
        assertThat(service.roleHasConfiguredPermission("F100", null, code)).isFalse();
        assertThat(service.roleHasConfiguredPermission("F100", "factory_super_admin", "sales:read")).isFalse();
    }

    @Test
    @DisplayName("信用特批: 默认只给厂长, 配了销售经理才行")
    void creditOverride_defaultsAndConfiguration() {
        String code = PermissionService.CREDIT_OVERRIDE_PERMISSION;
        assertThat(both(FactoryUserRole.factory_super_admin, code)).isTrue();
        assertThat(both(FactoryUserRole.sales_manager, code)).isFalse();
        configure(ThresholdKeys.CREDIT_PERMISSION_OVERRIDE, "sales_manager");
        assertThat(both(FactoryUserRole.sales_manager, code)).isTrue();
    }
}
