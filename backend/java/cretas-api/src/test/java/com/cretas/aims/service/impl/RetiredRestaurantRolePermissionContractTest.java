package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.platform.PlatformEmployeeModulePermissions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RetiredRestaurantRolePermissionContractTest {

    @Mock PlatformRolePermissionRepository platformRepo;
    @Mock FactoryModuleConfigRepository factoryConfigRepo;
    @Mock PlatformEmployeeModulePermissions employeeOverrides;

    @ParameterizedTest
    @EnumSource(value = FactoryUserRole.class, names = "restaurant_.*", mode = EnumSource.Mode.MATCH_ALL)
    void retiredRestaurantRolesCannotUseHistoricalL1L2OrEmployeeGrants(FactoryUserRole role) {
        PermissionServiceImpl service = new PermissionServiceImpl();
        ReflectionTestUtils.setField(service, "platformRepo", platformRepo);
        ReflectionTestUtils.setField(service, "factoryConfigRepo", factoryConfigRepo);
        ReflectionTestUtils.setField(service, "employeeModulePermissions", employeeOverrides);

        User user = new User();
        user.setId(99L);
        user.setFactoryId("F006");
        user.setRoleCode(role.name());

        assertThat(service.hasPermission(user, "warehouse:read_write")).isFalse();
        assertThat(service.hasConfiguredPermission(user, "warehouse:read_write", Set.of(role.name()))).isFalse();
        assertThat(service.resolveModuleLevels(user)).isEmpty();
        assertThat(service.resolveModuleLevelsForAuthorization(user)).isEmpty();
        verifyNoInteractions(platformRepo, factoryConfigRepo, employeeOverrides);
    }
}
