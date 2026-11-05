package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolPrincipalPolicyTest {

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
        user.setIsActive(true);
        user.setRoleCode("warehouse_manager");
        factory = new Factory();
        factory.setId("F006");
        factory.setIsActive(true);
        factory.setType(FactoryType.FACTORY);
    }

    @Test
    void reloadsIdentityAndPermissionsAndIgnoresCommandClaims() {
        PermissionServiceImpl canonical = new PermissionServiceImpl();
        PermissionRepositoryFixtures.installSuccessfulEmpty(canonical);
        user.setFactoryId("F006");
        user.setRoleCode("factory_super_admin");
        factory.setId("F006");
        factory.setType(FactoryType.FACTORY);
        ToolPrincipalPolicy realPolicy = new ToolPrincipalPolicy(userRepository, factoryRepository, canonical);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(factoryRepository.findById("F006")).thenReturn(Optional.of(factory));

        ExecutionPrincipal asserted = principal(
                "F006", "FACTORY", Set.of("ADMIN"), Set.of("*:admin"));
        ToolPrincipalPolicy.RehydratedPrincipal current = realPolicy.rehydrate(asserted).orElseThrow();

        assertThat(current.principal().tenantId()).isEqualTo("F006");
        assertThat(current.principal().businessType()).isEqualTo("FACTORY");
        assertThat(current.principal().roles()).containsExactly("factory_super_admin");
        assertThat(current.principal().permissions()).noneMatch(permission -> permission.startsWith("restaurant:"));
        assertThat(current.executionContext())
                .containsEntry("factoryId", "F006")
                .containsEntry("businessType", "FACTORY")
                .containsEntry("userId", 42L)
                .doesNotContainKey("username");
    }

    @Test
    void rejectsBusinessTypeTenantActiveAndNonUserMismatches() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(factoryRepository.findById("F006")).thenReturn(Optional.of(factory));

        assertThat(policy.rehydrate(principal(
                "F006", "RESTAURANT", Set.of("role"), Set.of()))).isEmpty();
        assertThat(policy.rehydrate(principal(
                "OTHER", "FACTORY", Set.of("role"), Set.of()))).isEmpty();
        assertThat(policy.rehydrate(new ExecutionPrincipal(
                "F006", "FACTORY", "service-1", PrincipalType.SERVICE,
                Set.of(), Set.of(), Set.of("tool.execute")))).isEmpty();

        user.setIsActive(false);
        assertThat(policy.rehydrate(principal(
                "F006", "FACTORY", Set.of("role"), Set.of()))).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(permissionService);
    }

    @Test
    void derivesCompositeOnlyFromMatchingCurrentReadAndWritePermissions() {
        assertThat(ToolPrincipalPolicy.withReadWriteComposites(Set.of(
                "hr:read", "hr:write", "production:read", "quality:write")))
                .contains("hr:read_write")
                .doesNotContain("production:read_write", "quality:read_write");

        assertThat(ToolPrincipalPolicy.withReadWriteComposites(Set.of(
                "production:read", "production:write")))
                .contains("production:read_write");
    }

    @Test
    void rehydratesCanonicalPriceGrantSeparatelyFromReadWritePermissions() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(factoryRepository.findById("F006")).thenReturn(Optional.of(factory));
        org.mockito.Mockito.lenient().when(permissionService.resolveModuleLevelsForAuthorization(user)).thenReturn(Map.of("inventory", "r"));
        when(permissionService.hasPermission(user, "procurement:price:view")).thenReturn(true);

        ToolPrincipalPolicy.RehydratedPrincipal current = policy.rehydrate(
                principal("F006", "FACTORY", Set.of("fabricated_manager"),
                        Set.of("procurement:price:view"))).orElseThrow();

        assertThat(current.executionContext()).containsEntry("canViewPrices", true);
        assertThat(current.principal().permissions())
                .doesNotContain("procurement:price:view");
    }

    @Test
    void realCanonicalPermissionServiceAllowsBossAndRejectsManagerDespiteClaims() {
        PermissionServiceImpl canonical = new PermissionServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(canonical, "platformRepo",
                org.mockito.Mockito.mock(com.cretas.aims.repository.permission.PlatformRolePermissionRepository.class));
        org.springframework.test.util.ReflectionTestUtils.setField(canonical, "factoryConfigRepo",
                org.mockito.Mockito.mock(com.cretas.aims.repository.config.FactoryModuleConfigRepository.class));
        ToolPrincipalPolicy canonicalPolicy = new ToolPrincipalPolicy(
                userRepository, factoryRepository, canonical);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(factoryRepository.findById("F006")).thenReturn(Optional.of(factory));

        user.setRoleCode("factory_super_admin");
        ToolPrincipalPolicy.RehydratedPrincipal boss = canonicalPolicy.rehydrate(
                principal("F006", "FACTORY", Set.of("warehouse_manager"),
                        Set.of("procurement:price:view"))).orElseThrow();
        assertThat(boss.executionContext()).containsEntry("canViewPrices", true);

        user.setRoleCode("warehouse_manager");
        ToolPrincipalPolicy.RehydratedPrincipal manager = canonicalPolicy.rehydrate(
                principal("F006", "FACTORY", Set.of("factory_super_admin"),
                        Set.of("procurement:price:view"))).orElseThrow();
        assertThat(manager.executionContext()).containsEntry("canViewPrices", false);
        assertThat(manager.principal().permissions())
                .doesNotContain("procurement:price:view");
    }

    @Test
    void realCanonicalPermissionServiceCoversRepresentativeAllowedPriceRoles() {
        PermissionServiceImpl canonical = new PermissionServiceImpl();
        for (String role : Set.of("procurement_manager", "finance_manager", "sales_manager",
                "dispatcher", "production_manager", "permission_admin", "department_admin")) {
            user.setRoleCode(role);
            assertThat(canonical.hasPermission(user, "procurement:price:view"))
                    .as("canonical price grant for %s", role)
                    .isTrue();
        }
        for (String role : Set.of("warehouse_manager", "warehouse_worker", "quality_manager",
                "restaurant_owner", "restaurant_manager", "restaurant_chef", "restaurant_purchaser")) {
            user.setRoleCode(role);
            assertThat(canonical.hasPermission(user, "procurement:price:view"))
                    .as("canonical price denial for %s", role)
                    .isFalse();
        }
    }

    @Test
    void translatesCanonicalModuleLevelsWithoutTrustingAssertedPermissions() {
        assertThat(ToolPrincipalPolicy.moduleLevelPermissions(Map.of(
                "inventory", "rw", "quality", "r", "production", "w", "new_axis", "-")))
                .containsExactlyInAnyOrder(
                        "inventory:read", "inventory:write", "inventory:read_write",
                        "quality:read", "production:write")
                .doesNotContain("new_axis:read", "new_axis:write");
    }

    private static ExecutionPrincipal principal(
            String tenant,
            String businessType,
            Set<String> roles,
            Set<String> permissions) {
        return new ExecutionPrincipal(
                tenant, businessType, "42", PrincipalType.USER, roles, permissions, Set.of());
    }

    /** S5-12: 工具通道的采购价口径与 HTTP 一致 —— 在旧名单里 (price:view) 不等于能看采购价。 */
    @Test
    void purchasePriceVisibilityFollowsConfiguredProcurementReadNotTheRoleList() {
        user.setRoleCode("sales_manager");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(factoryRepository.findById("F006")).thenReturn(Optional.of(factory));
        org.mockito.Mockito.lenient().when(permissionService.resolveModuleLevelsForAuthorization(user))
                .thenReturn(Map.of("sales", "rw"));
        org.mockito.Mockito.lenient().when(permissionService.hasPermission(user, "procurement:price:view"))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(permissionService.hasConfiguredPermission(
                org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(false);

        ToolPrincipalPolicy.RehydratedPrincipal current = policy.rehydrate(
                principal("F006", "FACTORY", Set.of("sales_manager"), Set.of())).orElseThrow();
        assertThat(current.executionContext())
                .containsEntry("canViewPrices", true)
                .containsEntry("canViewPurchasePrices", false)
                .containsEntry("canViewPurchaseOrFinancePrices", false);

        // 配置给了采购读 → 同一个人重新 rehydrate 就能看
        org.mockito.Mockito.lenient().when(permissionService.hasConfiguredPermission(
                org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.eq("procurement:read"),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        assertThat(policy.rehydrate(principal("F006", "FACTORY", Set.of("sales_manager"), Set.of()))
                .orElseThrow().executionContext())
                .containsEntry("canViewPurchasePrices", true)
                .containsEntry("canViewPurchaseOrFinancePrices", true);
    }
}
