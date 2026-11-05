package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.permission.PlatformRolePermission;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @org.junit.jupiter.api.Test
    void retiredRestaurantModuleIsDeniedForEveryFactoryRoleDespiteHistoricalL1L2Rows() {
        PermissionServiceImpl service = new PermissionServiceImpl();
        ReflectionTestUtils.setField(service, "platformRepo", platformRepo);
        ReflectionTestUtils.setField(service, "factoryConfigRepo", factoryConfigRepo);
        ReflectionTestUtils.setField(service, "employeeModulePermissions", employeeOverrides);
        PlatformRolePermission l1 = new PlatformRolePermission();
        l1.setRoleCode("viewer");
        l1.setModuleCode("restaurant");
        l1.setPermissionLevel("read_write");
        PlatformRolePermission l1Finance = new PlatformRolePermission();
        l1Finance.setRoleCode("viewer");
        l1Finance.setModuleCode("restaurantFinance");
        l1Finance.setPermissionLevel("read_write");
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenReturn(List.of(l1, l1Finance));
        when(platformRepo.findByDeletedAtIsNull()).thenReturn(List.of(l1, l1Finance));

        FactoryModuleConfig l2 = new FactoryModuleConfig();
        l2.setFactoryId("F006");
        l2.setRoleModuleOverride(Map.of(
                "viewer", Map.of("restaurant", "rw"),
                "warehouse_manager", Map.of("restaurant", "rw"),
                "factory_super_admin", Map.of("restaurant", "rw"),
                "platform_admin", Map.of("restaurant", "rw")));
        when(factoryConfigRepo.findByFactoryIdAndConfigVersion(anyString(), anyInt())).thenReturn(List.of(l2));
        when(employeeOverrides.apply(any(User.class), anyMap())).thenAnswer(invocation -> {
            var injected = new java.util.LinkedHashMap<>((Map<String, String>) invocation.getArgument(1));
            injected.put("restaurant", "rw");
            injected.put("restaurantFinance", "rw");
            return injected;
        });
        when(employeeOverrides.apply(any(User.class), org.mockito.ArgumentMatchers.eq("warehouse"), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        for (FactoryUserRole role : List.of(FactoryUserRole.viewer, FactoryUserRole.warehouse_manager,
                FactoryUserRole.factory_super_admin, FactoryUserRole.platform_admin)) {
            User user = new User();
            user.setId((long) role.ordinal() + 1000);
            user.setFactoryId("F006");
            user.setRoleCode(role.name());

            assertThat(service.hasPermission(user, "restaurant:read")).isFalse();
            assertThat(service.hasPermission(user, "restaurant:read_write")).isFalse();
            assertThat(service.resolveModuleLevels(user)).doesNotContainKey("restaurant");
            assertThat(service.resolveModuleLevelsForAuthorization(user)).doesNotContainKey("restaurant");
            assertThat(service.resolveModuleLevels(user)).doesNotContainKey("restaurantFinance");
            if (role != FactoryUserRole.viewer) {
                assertThat(service.hasPermission(user, "warehouse:read")).isTrue();
            }
        }
        assertThat(service.knownModuleAxis()).doesNotContain("restaurant");
    }
}
