package com.cretas.aims.service.platform;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.auth.UserMenuPermission;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.auth.UserMenuPermissionRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.auth.impl.UserMenuPermissionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlatformEmployeeModulePermissionsContractTest {
    private final UserMenuPermissionRepository repository = mock(UserMenuPermissionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PlatformRolePermissionRepository global = mock(PlatformRolePermissionRepository.class);
    private final PlatformFactoryControlAuthority authority = mock(PlatformFactoryControlAuthority.class);
    private final PlatformEmployeeModulePermissions personal = new PlatformEmployeeModulePermissions(repository,
        users, global, authority);

    private User user(FactoryUserRole role) {
        User value = new User(); value.setId(7L); value.setFactoryId("F1"); value.setRoleCode(role.name()); value.setIsActive(true); return value;
    }
    private UserMenuPermission row(String module, String action, boolean grant) {
        UserMenuPermission row = new UserMenuPermission(); row.setFactoryId("F1"); row.setUserId("7");
        row.setMenuCode(PlatformEmployeeModulePermissions.PREFIX + module + "." + action);
        row.setGrantedBy("PLATFORM_ADMIN:77");
        row.setGrantType(grant ? UserMenuPermission.GrantType.GRANT : UserMenuPermission.GrantType.REVOKE); return row;
    }
    private PermissionServiceImpl runtime() {
        PermissionServiceImpl runtime = new PermissionServiceImpl();
        ReflectionTestUtils.setField(runtime, "employeeModulePermissions", personal);
        ReflectionTestUtils.setField(runtime, "platformRepo", global);
        ReflectionTestUtils.setField(runtime, "factoryConfigRepo", mock(FactoryModuleConfigRepository.class));
        return runtime;
    }
    @Test void explicitGrantDenyAndRestoreAgreeWithAuthorizationAndCurrentSnapshot() {
        User user = user(FactoryUserRole.viewer); PermissionServiceImpl runtime = runtime();
        when(repository.findByFactoryIdAndUserId("F1", "7")).thenReturn(List.of(row("production", "read", true), row("production", "write", true)));
        assertThat(runtime.hasPermission(user, "production:write")).isTrue();
        assertThat(runtime.resolveModuleLevelsForAuthorization(user)).containsEntry("production", "rw");
        when(repository.findByFactoryIdAndUserId("F1", "7")).thenReturn(List.of(row("production", "read", false), row("production", "write", false)));
        runtime.invalidateCache();
        assertThat(runtime.hasPermission(user, "production:read")).isFalse();
        assertThat(runtime.resolveModuleLevels(user)).containsEntry("production", "-");
        when(repository.findByFactoryIdAndUserId("F1", "7")).thenReturn(List.of()); runtime.invalidateCache();
        assertThat(runtime.hasPermission(user, "production:write")).isFalse();
        assertThat(runtime.resolveModuleLevelsForAuthorization(user).get("production")).isNotEqualTo("rw");
        verify(repository, atLeastOnce()).findByFactoryIdAndUserId("F1", "7");
        verify(repository, never()).findByFactoryIdAndUserId(eq("F2"), anyString());
    }
    @Test void historicalMenuGrantsNeverBecomeBusinessGrantsAndSpecialPermissionsStayRestricted() {
        UserMenuPermission old = row("production", "write", true); old.setMenuCode("production:write");
        when(repository.findByFactoryIdAndUserId("F1", "7")).thenReturn(List.of(old));
        assertThat(personal.overrides(user(FactoryUserRole.viewer))).isEmpty();
        assertThat(runtime().hasPermission(user(FactoryUserRole.viewer), PermissionServiceImpl.YIELD_STANDARD_WRITE_PERMISSION)).isFalse();
        assertThat(runtime().hasPermission(user(FactoryUserRole.unactivated), "production:write")).isFalse();
        assertThat(runtime().hasPermission(user(FactoryUserRole.factory_super_admin), "production:write")).isTrue();
    }
    @Test void incompleteOrUnavailableEmployeeConfigurationFailsClosed() {
        when(repository.findByFactoryIdAndUserId("F1", "7")).thenReturn(List.of(row("production", "write", true)));
        assertThatThrownBy(() -> runtime().hasPermission(user(FactoryUserRole.viewer), "production:write")).isInstanceOf(BusinessException.class);
        when(repository.findByFactoryIdAndUserId("F1", "7")).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> personal.overrides(user(FactoryUserRole.viewer))).isInstanceOf(BusinessException.class);
    }
    @Test void oldMenuWriterCannotForgeOrClearPlatformBusinessGrants() {
        UserMenuPermissionServiceImpl old = new UserMenuPermissionServiceImpl(repository, runtime());
        String code = PlatformEmployeeModulePermissions.PREFIX + "production.write";
        assertThatThrownBy(() -> old.grantMenu("F1", "7", code, "7", "")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> old.revokeMenu("F1", "7", code, "7", "")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> old.clearUserOverride("F1", "7", code)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }
    @Test void untrustedReservedRowsCannotBecomeBusinessGrants() {
        UserMenuPermission read = row("production", "read", true), write = row("production", "write", true);
        read.setGrantedBy("factory_super_admin");
        when(repository.findByFactoryIdAndUserId("F1", "7")).thenReturn(List.of(read, write));
        assertThatThrownBy(() -> personal.overrides(user(FactoryUserRole.viewer))).isInstanceOf(BusinessException.class);
    }
    @Test void historicalRestaurantRowsAreFilteredAndCannotBeRecreated() {
        when(repository.findByFactoryIdAndUserId("F1", "7")).thenReturn(List.of(
                row("restaurant", "read", true), row("restaurant", "write", true),
                row("restaurantFinance", "read", true), row("restaurantFinance", "write", true)));
        assertThat(personal.overrides(user(FactoryUserRole.viewer))).isEmpty();

        User target = user(FactoryUserRole.viewer);
        when(users.findByIdAndFactoryId(7L, "F1")).thenReturn(Optional.of(target));
        assertThatThrownBy(() -> personal.replace("F1", 7L, "restaurantFinance", "rw",
                new com.cretas.aims.security.ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 77L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("餐饮模块已退役");
        verify(repository, never()).save(any());
        verify(repository, never()).deleteByFactoryIdAndUserIdAndMenuCode(anyString(), anyString(), anyString());
    }

    @Test void retiredModuleCannotSurviveEitherApplyOverloadOrBlockFactoryViaAnObsoleteIssuer() {
        var old = row("restaurant", "read", true);
        old.setGrantedBy("old-business-admin");
        when(repository.findByFactoryIdAndUserId("F1", "7")).thenReturn(List.of(old));
        assertThat(personal.apply(user(FactoryUserRole.viewer), Map.of("production", "r", "restaurant", "rw")))
                .containsExactlyEntriesOf(Map.of("production", "r"));
        assertThat(personal.apply(user(FactoryUserRole.factory_super_admin),
                Map.of("production", "rw", "restaurantFinance", "rw")))
                .containsExactlyEntriesOf(Map.of("production", "rw"));
        assertThat(personal.apply(user(FactoryUserRole.factory_super_admin), "restaurantFinance", "rw"))
                .isEqualTo("-");
    }
}
