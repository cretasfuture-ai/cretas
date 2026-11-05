package com.cretas.aims.entity.enums;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class FactoryUserRoleRetirementContractTest {

    @ParameterizedTest
    @EnumSource(value = FactoryUserRole.class, names = "restaurant_.*", mode = EnumSource.Mode.MATCH_ALL)
    void historicalRestaurantRoleValuesRemainReadableButHaveNoActiveCapabilities(FactoryUserRole role) {
        assertThat(FactoryUserRole.fromRoleCode(role.name())).isEqualTo(role);
        assertThat(role.isRetiredRestaurantRole()).isTrue();
        assertThat(role.getPermissionPrefix()).isEqualTo("none");
        assertThat(role.isActive()).isFalse();
        assertThat(role.isManager()).isFalse();
        assertThat(role.canManage(FactoryUserRole.viewer)).isFalse();
        assertThat(FactoryUserRole.factory_super_admin.canManage(role)).isFalse();
    }
}
