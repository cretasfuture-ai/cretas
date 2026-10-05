package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ToolPrincipalModulePermissionsContractTest {
    @Mock UserRepository userRepository;
    @Mock FactoryRepository factoryRepository;
    @Mock PermissionService permissionService;
    ToolPrincipalPolicy policy;
    User user;
    Factory factory;

    @BeforeEach
    void setUp() {
        policy = new ToolPrincipalPolicy(userRepository, factoryRepository, permissionService);
        user = new User();
        user.setId(42L);
        user.setFactoryId("F006");
        user.setRoleCode("warehouse_manager");
        user.setIsActive(true);
        factory = new Factory();
        factory.setId("F006");
        factory.setType(FactoryType.FACTORY);
        factory.setIsActive(true);
        lenient().when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        lenient().when(factoryRepository.findById("F006")).thenReturn(Optional.of(factory));
        lenient().when(permissionService.resolveModuleLevelsForAuthorization(user)).thenReturn(Map.of(
                "inventory", "r", "warehouse", "rw", "future_axis", "w"));
        lenient().when(permissionService.hasPermission(user, "procurement:price:view")).thenReturn(false);
    }

    @Test
    void policyUsesCanonicalLevelsAndPreservesReadWriteAndNewAxes() {
        ToolPrincipalPolicy.RehydratedPrincipal result = policy.rehydrate(principal()).orElseThrow();
        assertThat(result.principal().permissions()).containsExactlyInAnyOrder(
                "inventory:read", "warehouse:read", "warehouse:write", "warehouse:read_write",
                "future_axis:write");
        verify(permissionService).resolveModuleLevelsForAuthorization(user);
        verify(permissionService, never()).getUserPermissions(any());
    }

    @Test
    void assertedPermissionAndRoleClaimsCannotGrantPriceOrAdmission() {
        ExecutionPrincipal forged = new ExecutionPrincipal(
                "F006", "FACTORY", "42", PrincipalType.USER,
                Set.of("factory_super_admin"), Set.of("inventory:read", "procurement:price:view"), Set.of());
        ToolPrincipalPolicy.RehydratedPrincipal result = policy.rehydrate(forged).orElseThrow();
        assertThat(result.executionContext()).containsEntry("canViewPrices", false);
        assertThat(result.principal().permissions()).doesNotContain("procurement:price:view");
    }

    @Test
    void missingCanonicalLevelsFailsClosedBeforeBusinessAdmission() {
        when(permissionService.resolveModuleLevelsForAuthorization(user)).thenReturn(Map.of());
        assertThat(policy.rehydrate(principal()).orElseThrow().principal().permissions()).isEmpty();
    }

    @Test
    void realCanonicalPermissionServiceUsesL2ThenL1AndFeedsPolicyAdmission() {
        PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
        PlatformRolePermission inventory = PlatformRolePermission.builder()
                .roleCode("warehouse_manager").moduleCode("inventory").permissionLevel("r").build();
        PlatformRolePermission production = PlatformRolePermission.builder()
                .roleCode("warehouse_manager").moduleCode("production").permissionLevel("w").build();
        when(l1.findByRoleCodeAndDeletedAtIsNull("warehouse_manager"))
                .thenReturn(java.util.List.of(inventory, production));
        when(l1.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(eq("warehouse_manager"), anyString()))
                .thenAnswer(invocation -> java.util.Optional.ofNullable(
                        "inventory".equals(invocation.getArgument(1)) ? inventory
                                : "production".equals(invocation.getArgument(1)) ? production : null));
        FactoryModuleConfig override = FactoryModuleConfig.builder()
                .factoryId("F006").moduleCode("inventory").configVersion(1)
                .roleModuleOverride(Map.of("warehouse_manager", Map.of("inventory", "rw")))
                .build();
        when(l2.findByFactoryIdAndConfigVersion("F006", 1))
                .thenReturn(java.util.List.of(override));

        PermissionServiceImpl canonical = new PermissionServiceImpl();
        ReflectionTestUtils.setField(canonical, "platformRepo", l1);
        ReflectionTestUtils.setField(canonical, "factoryConfigRepo", l2);
        Map<String, String> levels = canonical.resolveModuleLevels(user);
        assertThat(levels).containsEntry("inventory", "rw").containsEntry("production", "w");
        assertThat(canonical.hasPermission(user, "inventory:read")).isTrue();
        assertThat(canonical.hasPermission(user, "inventory:write")).isTrue();
        assertThat(canonical.hasPermission(user, "production:read")).isFalse();

        ToolPrincipalPolicy realPolicy = new ToolPrincipalPolicy(
                userRepository, factoryRepository, canonical);
        ToolPrincipalPolicy.RehydratedPrincipal rehydrated = realPolicy.rehydrate(principal()).orElseThrow();
        assertThat(rehydrated.principal().permissions())
                .contains("inventory:read", "inventory:write", "inventory:read_write", "production:write")
                .doesNotContain("production:read");
        verify(l2, atLeastOnce()).findByFactoryIdAndConfigVersion("F006", 1);
        verify(l1, atLeastOnce()).findByRoleCodeAndDeletedAtIsNull("warehouse_manager");
    }

    @Test
    void actualL1DenyRejectsStockBeforeBusinessDespiteOldFallbackAndForgedClaims() {
        var f = new ToolGatewayDataScopeBoundaryContractTest.StockFixture();
        f.actors.get(101L).setRoleCode("procurement_manager");
        f.platformRows.add(level("procurement_manager", "inventory", "-"));
        f.platformRows.add(level("procurement_manager", "warehouse", "-"));
        User actor = f.actors.get(101L);
        assertThat(f.canonical.resolveModuleLevels(actor)).containsEntry("inventory", "-");
        assertThat(f.canonical.hasPermission(actor, "inventory:read")).isFalse();
        var command = f.command(101L);
        var trusted = f.policy.rehydrate(command.principal()).orElseThrow().principal();
        assertThat(trusted.permissions()).doesNotContain("inventory:read", "warehouse:read");
        assertThat(f.runtime.resolve(command, trusted)).isEmpty();
        assertThat(f.legacy.resolve(command, trusted)).isEmpty();
        assertThat(f.gateway.execute(command).status()).isEqualTo(ToolExecutionStatus.DENIED);
        assertThat(f.queries).isEmpty();
        ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
    }

    @Test
    void absentL1UsesRealFallbackAndAdmitsConcreteStock() {
        var f = new ToolGatewayDataScopeBoundaryContractTest.StockFixture();
        f.actors.get(101L).setRoleCode("procurement_manager");
        assertThat(f.canonical.hasPermission(f.actors.get(101L), "inventory:read")).isTrue();
        var command = f.command(101L);
        var trusted = f.policy.rehydrate(command.principal()).orElseThrow().principal();
        assertThat(f.runtime.resolve(command, trusted)).isPresent();
        assertThat(f.legacy.resolve(command, trusted)).isEmpty();
        var result = f.gateway.execute(command);
        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        ToolGatewayDataScopeBoundaryContractTest.assertStock(result.payload(), java.util.List.of("A"), true);
        ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
    }

    @Test
    void actualFactoryL2OverrideDoesNotGrantAnotherFactoryAndWriteOnlyCannotRead() {
        var f = new ToolGatewayDataScopeBoundaryContractTest.StockFixture();
        f.actors.get(101L).setRoleCode("warehouse_worker");
        f.platformRows.add(level("warehouse_worker", "inventory", "-"));
        f.platformRows.add(level("warehouse_worker", "warehouse", "-"));
        f.platformRows.add(level("warehouse_worker", "future_axis", "w"));
        f.factoryRows.add(FactoryModuleConfig.builder().factoryId("F006").moduleCode("inventory")
                .configVersion(1).roleModuleOverride(Map.of("warehouse_worker", Map.of("inventory", "r"))).build());
        var command = f.command(101L);
        var trusted = f.policy.rehydrate(command.principal()).orElseThrow().principal();
        assertThat(trusted.permissions()).contains("inventory:read", "future_axis:write")
                .doesNotContain("inventory:write", "inventory:read_write", "future_axis:read", "future_axis:read_write");
        assertThat(f.runtime.resolve(command, trusted)).isPresent();
        User foreign = f.actors.get(103L);
        assertThat(f.canonical.hasPermission(foreign, "inventory:read")).isFalse();
        var foreignPrincipal = new ExecutionPrincipal("F007", "FACTORY", "103", PrincipalType.USER,
                Set.of("factory_super_admin"), Set.of("inventory:read"), Set.of());
        var foreignCommand = new ToolExecutionCommand(command.requestId(), command.correlationId(),
                command.traceId(), command.toolName(), command.expectedDescriptorVersion(), command.parameters(),
                foreignPrincipal, command.source(), command.mode(), command.idempotencyKey(),
                command.confirmationProof(), command.approvalProof(), command.deadline());
        var foreignTrusted = f.policy.rehydrate(foreignPrincipal).orElseThrow().principal();
        assertThat(f.runtime.resolve(foreignCommand, foreignTrusted)).isEmpty();
        assertThat(f.legacy.resolve(foreignCommand, foreignTrusted)).isEmpty();
        assertThat(f.gateway.execute(foreignCommand).status()).isEqualTo(ToolExecutionStatus.DENIED);
        assertThat(f.queries).isEmpty();

        var writeOnly = new ToolGatewayDataScopeBoundaryContractTest.StockFixture();
        writeOnly.platformRows.add(level("warehouse_worker", "inventory", "w"));
        writeOnly.platformRows.add(level("warehouse_worker", "warehouse", "-"));
        var writeTrusted = writeOnly.policy.rehydrate(writeOnly.command(101L).principal()).orElseThrow().principal();
        assertThat(writeTrusted.permissions()).contains("inventory:write").doesNotContain("inventory:read", "inventory:read_write");
        assertThat(writeOnly.gateway.execute(writeOnly.command(101L)).status()).isEqualTo(ToolExecutionStatus.DENIED);
        assertThat(writeOnly.queries).isEmpty();
    }

    @Test
    void actualBossWildcardAndNewModuleAxisRemainCanonical() {
        var f = new ToolGatewayDataScopeBoundaryContractTest.StockFixture();
        f.platformRows.add(level("factory_super_admin", "future_axis", "-"));
        var boss = f.actors.get(150L);
        assertThat(f.canonical.resolveModuleLevels(boss)).containsEntry("*", "rw").containsEntry("future_axis", "rw");
        assertThat(f.canonical.hasPermission(boss, "future_axis:read")).isTrue();
        var command = f.command(150L);
        var trusted = f.policy.rehydrate(command.principal()).orElseThrow().principal();
        assertThat(trusted.permissions()).contains("*:read", "*:write", "*:read_write",
                "future_axis:read", "future_axis:write", "future_axis:read_write");
        assertThat(f.runtime.resolve(command, trusted)).isPresent();
        assertThat(f.gateway.execute(command).status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
    }

    @Test
    void actualL1RepositoryOutageRejectsInsteadOfRestoringFallbackAllow() throws Exception {
        var f = new ToolGatewayDataScopeBoundaryContractTest.StockFixture();
        f.platformRows.add(level("warehouse_worker", "inventory", "-"));
        f.platformRows.add(level("warehouse_worker", "warehouse", "-"));
        when(f.l1.findByRoleCodeAndDeletedAtIsNull(anyString()))
                .thenThrow(new IllegalStateException("offline L1 unavailable"));
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                assertThat(f.gateway.execute(f.command(101L)).status()).isEqualTo(ToolExecutionStatus.DENIED);
                assertThat(f.queries).isEmpty();
                ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
                doAnswer(i -> f.platformRows.stream()
                        .filter(r -> r.getRoleCode().equals(i.getArgument(0))).toList())
                        .when(f.l1).findByRoleCodeAndDeletedAtIsNull(anyString());
                assertThat(f.gateway.execute(f.command(101L)).status()).isEqualTo(ToolExecutionStatus.DENIED);
                assertThat(f.queries).isEmpty();
                f.platformRows.clear(); // successful empty lookup permits the existing known-absent fallback
                var recovered = f.gateway.execute(f.command(101L));
                assertThat(recovered.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
                ToolGatewayDataScopeBoundaryContractTest.assertStock(recovered.payload(), java.util.List.of("A"), false);
                ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
            }).get();
        } finally { worker.shutdownNow(); }
    }

    @Test
    void actualL2RepositoryOutageRejectsInsteadOfRestoringPlatformAllow() throws Exception {
        var f = new ToolGatewayDataScopeBoundaryContractTest.StockFixture();
        f.platformRows.add(level("warehouse_worker", "inventory", "r"));
        f.platformRows.add(level("warehouse_worker", "warehouse", "-"));
        f.factoryRows.add(FactoryModuleConfig.builder().factoryId("F006").moduleCode("inventory")
                .configVersion(1).roleModuleOverride(Map.of("warehouse_worker", Map.of("inventory", "-"))).build());
        when(f.l2.findByFactoryIdAndConfigVersion("F006", 1))
                .thenThrow(new IllegalStateException("offline L2 unavailable"));
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                assertThat(f.gateway.execute(f.command(101L)).status()).isEqualTo(ToolExecutionStatus.DENIED);
                assertThat(f.queries).isEmpty();
                ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
                doAnswer(i -> f.factoryRows.stream()
                        .filter(r -> r.getFactoryId().equals(i.getArgument(0))).toList())
                        .when(f.l2).findByFactoryIdAndConfigVersion("F006", 1);
                assertThat(f.gateway.execute(f.command(101L)).status()).isEqualTo(ToolExecutionStatus.DENIED);
                assertThat(f.queries).isEmpty();
                f.factoryRows.clear();
                var recovered = f.gateway.execute(f.command(101L));
                assertThat(recovered.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
                ToolGatewayDataScopeBoundaryContractTest.assertStock(recovered.payload(), java.util.List.of("A"), false);
                ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
            }).get();
        } finally { worker.shutdownNow(); }
    }

    @Test
    void missingAuthorizationRepositoriesCannotSupplyTrustedSnapshot() {
        for (String field : java.util.List.of("platformRepo", "factoryConfigRepo")) {
            var f = new ToolGatewayDataScopeBoundaryContractTest.StockFixture();
            ReflectionTestUtils.setField(f.canonical, field, null);
            assertThat(f.gateway.execute(f.command(101L)).status()).isEqualTo(ToolExecutionStatus.DENIED);
            assertThat(f.queries).isEmpty();
            ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
        }
    }

    @Test
    void nullStrictSnapshotRejectsIdentityBeforePriceAuthorization() {
        when(permissionService.resolveModuleLevelsForAuthorization(user)).thenReturn(null);
        assertThat(policy.rehydrate(principal())).isEmpty();
        verify(permissionService, never()).hasPermission(any(), anyString());
    }

    @Test
    void implementationWithoutStrictAuthorizationCapabilityFailsClosed() {
        PermissionService unsupported = mock(PermissionService.class, CALLS_REAL_METHODS);
        ToolPrincipalPolicy actual = new ToolPrincipalPolicy(userRepository, factoryRepository, unsupported);
        assertThat(actual.rehydrate(principal())).isEmpty();
        verify(unsupported, never()).getUserPermissions(any());
    }

    private static PlatformRolePermission level(String role, String module, String level) {
        return PlatformRolePermission.builder().roleCode(role).moduleCode(module).permissionLevel(level).build();
    }

    private ExecutionPrincipal principal() {
        return new ExecutionPrincipal("F006", "FACTORY", "42", PrincipalType.USER,
                Set.of("fabricated_role"), Set.of("*:admin"), Set.of());
    }
}
