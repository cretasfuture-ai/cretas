package com.cretas.aims.service.platform;

import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.*;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.FactoryService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformFactoryAccessRoleContractTest {
    @Test void legacyWriterCanonicalizesSameCellWithoutLeavingAStaleGrant() {
        FactoryModuleConfig other = row("production", Map.of("operator", Map.of("production", "rw", "warehouse", "r")));
        FactoryModuleConfig sentinel = row("__permissions__", Map.of("viewer", Map.of("production", "r")));
        FactoryModuleConfigRepository repo = mock(FactoryModuleConfigRepository.class);
        when(repo.findByFactoryIdAndConfigVersion("F1", 1)).thenReturn(List.of(other, sentinel));
        PermissionServiceImpl runtime = new PermissionServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(runtime, "factoryConfigRepo", repo);
        org.springframework.test.util.ReflectionTestUtils.setField(runtime, "platformRepo", mock(com.cretas.aims.repository.permission.PlatformRolePermissionRepository.class));
        com.cretas.aims.controller.FactoryRoleModuleOverrideController controller = new com.cretas.aims.controller.FactoryRoleModuleOverrideController(repo, runtime);
        controller.update("F1", "operator", "production", "-");
        assertThat(other.getRoleModuleOverride().get("operator")).doesNotContainKey("production").containsEntry("warehouse", "r");
        assertThat(sentinel.getRoleModuleOverride().get("viewer")).containsEntry("production", "r");
        com.cretas.aims.entity.User user = new com.cretas.aims.entity.User(); user.setId(5L); user.setFactoryId("F1"); user.setRoleCode("operator");
        assertThat(runtime.hasPermission(user, "production:write")).isFalse();
        assertThat(runtime.resolveModuleLevelsForAuthorization(user)).containsEntry("production", "-");
    }
    @Test
    void roleUpdateCanonicalizesOnlySelectedCellAndPreservesOtherRows() {
        FactoryModuleConfig sentinel = row("__permissions__", Map.of("operator", Map.of("analytics", "r", "sales", "rw"), "manager", Map.of("analytics", "rw")));
        FactoryModuleConfig other = row("production", Map.of("operator", Map.of("analytics", "rw", "production", "r")));
        FactoryModuleConfigRepository repo = mock(FactoryModuleConfigRepository.class);
        when(repo.findByFactoryIdAndConfigVersion("F1", 1)).thenReturn(new ArrayList<>(List.of(sentinel, other)));
        PlatformFactoryAccessService service = service(repo, mock(PermissionService.class));
        ActorRef actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 8L);

        service.updateRole("F1", "operator", "analytics", null, actor);
        assertThat(other.getRoleModuleOverride().get("operator")).doesNotContainKey("analytics").containsEntry("production", "r");
        assertThat(sentinel.getRoleModuleOverride().get("operator")).doesNotContainKey("analytics").containsEntry("sales", "rw");
        assertThat(sentinel.getRoleModuleOverride().get("manager")).containsEntry("analytics", "rw");
    }

    @Test
    void protectedFactorySuperAdminRoleIsRejected() {
        PlatformFactoryAccessService service = service(mock(FactoryModuleConfigRepository.class), mock(PermissionService.class));
        assertThatThrownBy(() -> service.updateRole("F1", FactoryUserRole.factory_super_admin.name(), "analytics", "r", new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 8L)))
                .hasMessageContaining("无效角色模块权限");
    }

    @Test
    void invalidationRunsImmediatelyAndAfterCommit() {
        PermissionServiceImpl permission = spy(new PermissionServiceImpl());
        PlatformFactoryAccessService service = service(mock(FactoryModuleConfigRepository.class), permission);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.updateRole("F1", "operator", "analytics", "r", new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 8L));
            verify(permission, atLeastOnce()).invalidateCache();
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(permission, atLeast(2)).invalidateCache();
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    private static PlatformFactoryAccessService service(FactoryModuleConfigRepository repo, PermissionService permission) {
        FactoryService factories = mock(FactoryService.class); FactoryConfigService config = mock(FactoryConfigService.class);
        FactoryConfigurationRepository versions = mock(FactoryConfigurationRepository.class); PlatformFactoryControlAuthority authority = mock(PlatformFactoryControlAuthority.class);
        PlatformEmployeeModulePermissions employees = mock(PlatformEmployeeModulePermissions.class); UserRepository users = mock(UserRepository.class);
        UserModuleAccessService legacy = mock(UserModuleAccessService.class); ConfigChangeLogRepository audit = mock(ConfigChangeLogRepository.class);
        PlatformFactoryAccessService service = spy(new PlatformFactoryAccessService(factories, config, versions, permission, authority, employees, users, repo, legacy, audit));
        doReturn(null).when(service).access(anyString(), any());
        return service;
    }

    private static FactoryModuleConfig row(String code, Map<String, Map<String, String>> overrides) {
        return FactoryModuleConfig.builder().factoryId("F1").moduleCode(code).configVersion(1).enabled(true)
                .fieldConfig(Map.of("keep", true)).roleModuleOverride(new HashMap<>(overrides)).build();
    }
}
