package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工厂纪律 B7 / B9 / A17 (Steve 2026-11-10): 「原料类型维护」「报损审批」「信用特批」不再写死岗位,
 * 按权限判断: 工厂配了就按配置, 没配才落到默认角色。
 *
 * <p>阳性对照: 默认角色真的放行; 阴性对照: 其他角色真的被挡; 配置对照: 同一个角色一配置就翻转。
 */
@DisplayName("可配置默认角色的权限码 (原料类型维护 / 报损审批 / 信用特批)")
class ConfigurableDefaultPermissionsTest {

    private PermissionServiceImpl service;
    private PlatformRolePermissionRepository platformRepo;

    @BeforeEach
    void setUp() {
        service = new PermissionServiceImpl();
        platformRepo = mock(PlatformRolePermissionRepository.class);
        ReflectionTestUtils.setField(service, "platformRepo", platformRepo);
        ReflectionTestUtils.setField(service, "factoryConfigRepo", mock(FactoryModuleConfigRepository.class));
    }

    private User user(long id, FactoryUserRole role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId("F100");
        u.setRoleCode(role.name());
        return u;
    }

    private void grantL1(FactoryUserRole role, String module, String level) {
        PlatformRolePermission row = PlatformRolePermission.builder()
                .roleCode(role.name()).moduleCode(module).permissionLevel(level).build();
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(role.name(), module))
                .thenReturn(Optional.of(row));
    }

    @Test
    @DisplayName("原料类型维护: 默认仓储主管 + 厂长可以, 仓管 / 生产主管不行")
    void materialTypeMaintain_defaults() {
        String code = PermissionService.MATERIAL_TYPE_MAINTAIN_PERMISSION;
        assertThat(service.hasPermission(user(1, FactoryUserRole.warehouse_manager), code)).isTrue();
        assertThat(service.hasPermission(user(2, FactoryUserRole.factory_super_admin), code)).isTrue();
        assertThat(service.hasPermission(user(3, FactoryUserRole.warehouse_worker), code)).isFalse();
        assertThat(service.hasPermission(user(4, FactoryUserRole.production_manager), code)).isFalse();
    }

    @Test
    @DisplayName("原料类型维护: 工厂把 material_type 模块配给生产主管 ⇒ 他能了; 配给仓储主管为只读 ⇒ 他反而不行")
    void materialTypeMaintain_followsFactoryConfiguration() {
        String code = PermissionService.MATERIAL_TYPE_MAINTAIN_PERMISSION;
        grantL1(FactoryUserRole.production_manager, "material_type", "rw");
        grantL1(FactoryUserRole.warehouse_manager, "material_type", "r");

        assertThat(service.hasPermission(user(4, FactoryUserRole.production_manager), code)).isTrue();
        assertThat(service.hasPermission(user(1, FactoryUserRole.warehouse_manager), code)).isFalse();
        assertThat(service.hasPermission(user(2, FactoryUserRole.factory_super_admin), code)).isTrue();
    }

    @Test
    @DisplayName("报损审批: 默认只给厂长; 财务经理 / 生产主管默认不行, 配了就行")
    void wastageApprove_defaultsAndConfiguration() {
        String code = PermissionService.WASTAGE_APPROVE_PERMISSION;
        assertThat(service.hasPermission(user(2, FactoryUserRole.factory_super_admin), code)).isTrue();
        assertThat(service.hasPermission(user(5, FactoryUserRole.finance_manager), code)).isFalse();
        assertThat(service.hasPermission(user(4, FactoryUserRole.production_manager), code)).isFalse();

        grantL1(FactoryUserRole.finance_manager, "wastage", "rw");
        assertThat(service.hasPermission(user(5, FactoryUserRole.finance_manager), code)).isTrue();
        assertThat(service.hasPermission(user(4, FactoryUserRole.production_manager), code)).isFalse();
    }

    @Test
    @DisplayName("只有角色码的调用方 (报损服务) 与按用户判断结论一致")
    void roleBasedCheckAgreesWithUserBasedCheck() {
        String code = PermissionService.WASTAGE_APPROVE_PERMISSION;
        assertThat(service.roleHasConfiguredPermission("F100", "factory_super_admin", code)).isTrue();
        assertThat(service.roleHasConfiguredPermission("F100", "FACTORY_SUPER_ADMIN", code)).isTrue();
        assertThat(service.roleHasConfiguredPermission("F100", "finance_manager", code)).isFalse();
        assertThat(service.roleHasConfiguredPermission("F100", "operator", code)).isFalse();
        assertThat(service.roleHasConfiguredPermission("F100", "not_a_role", code)).isFalse();
        assertThat(service.roleHasConfiguredPermission("F100", null, code)).isFalse();
        // 不是可配置默认角色的码一律 false, 不会误放行
        assertThat(service.roleHasConfiguredPermission("F100", "factory_super_admin", "sales:read")).isFalse();

        grantL1(FactoryUserRole.finance_manager, "wastage", "rw");
        assertThat(service.roleHasConfiguredPermission("F100", "finance_manager", code)).isTrue();
    }

    @Test
    @DisplayName("信用特批: 默认只给厂长")
    void creditOverride_defaults() {
        String code = PermissionService.CREDIT_OVERRIDE_PERMISSION;
        assertThat(service.hasPermission(user(2, FactoryUserRole.factory_super_admin), code)).isTrue();
        assertThat(service.hasPermission(user(6, FactoryUserRole.sales_manager), code)).isFalse();
    }
}
