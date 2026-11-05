package com.cretas.aims.service.material;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.PlatformFactoryViewReadPolicy;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Contract for the merge path's production and process-secret write gate. */
@ExtendWith(MockitoExtension.class)
class MaterialMergeWorkflowWriteGuardContractTest {

    private static final String FACTORY = "F-MERGE-GUARD";
    private static final long ACTOR_ID = 7101L;

    @Mock private ProductProcessWorkflowRepository workflowRepository;
    @Mock private PlatformAdminRepository platformAdminRepository;
    @Mock private UserRepository userRepository;
    @Mock private PermissionService permissionService;
    @Mock private FactoryConfigService factoryConfigService;
    @Mock private UserModuleAccessService userModuleAccessService;
    @Mock private ProcessSecretPolicyRepository policyRepository;
    @Mock private ProcessSecretGrantRepository grantRepository;
    @Mock private ProcessSecretAuditRecorder auditRecorder;
    @Mock private BomRecipeRepository recipeRepository;

    private MaterialMergeWorkflowWriteGuard guard;

    @BeforeEach
    void setUp() {
        ProcessSecretAccessService access = new ProcessSecretAccessService(
                policyRepository, grantRepository, auditRecorder);
        guard = new MaterialMergeWorkflowWriteGuard(
                workflowRepository, platformAdminRepository, userRepository, permissionService,
                factoryConfigService, userModuleAccessService,
                new ProcessSecretRecipeGuard(recipeRepository, access));
    }

    @Test
    void warehouseOnlyCallerIsDeniedEvenWhenWarehouseWouldAllowMaterialMerge() {
        userOnly("warehouse_manager");

        assertProductionDenied("warehouse_manager");
        verifyNoInteractions(permissionService, userModuleAccessService);
    }

    @Test
    void productionPermissionWithoutNormalWorkflowRoleIsDenied() {
        userOnly("operator");

        assertProductionDenied("operator");
        verifyNoInteractions(permissionService, userModuleAccessService);
    }

    @Test
    void normalRoleWithProductionWritePermissionIsAllowedForUnprotectedDraft() {
        allow("workshop_supervisor");
        when(policyRepository.findByFactoryIdOrderByProductTypeIdAsc(FACTORY)).thenReturn(List.of());
        when(workflowRepository.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(Optional.of(workflow(1L, "OWNER", "[{\"data\":{\"skuId\":\"PLAIN\"}}]")));

        withActor("workshop_supervisor", () -> guard.require(FACTORY, plan(1L)));
    }

    @Test
    void normalRoleWithoutProductionPermissionIsDenied() {
        userOnly("workshop_supervisor");
        when(permissionService.hasPermission(any(User.class), eq("production:read_write"))).thenReturn(false);

        assertProductionDenied("workshop_supervisor");
        verify(permissionService).hasPermission(any(User.class), eq("production:read_write"));
        verifyNoInteractions(userModuleAccessService);
    }

    @Test
    void disabledProductionModuleRejectsBeforeFactoryAuthority() {
        when(factoryConfigService.isModuleEnabled(FACTORY, "production_plan")).thenReturn(false);

        assertDenied("factory_super_admin", plan(1L));
        verifyNoInteractions(userRepository, permissionService, userModuleAccessService,
                platformAdminRepository, workflowRepository);
    }

    @Test
    void disabledProductionModuleRejectsBeforePlatformBypass() {
        when(factoryConfigService.isModuleEnabled(FACTORY, "production_plan")).thenReturn(false);

        assertDeniedPlatform("super_admin", plan(1L));
        verifyNoInteractions(userRepository, permissionService, userModuleAccessService,
                platformAdminRepository, workflowRepository);
    }

    @Test
    void explicitAccountModuleDenyRejectsAnOtherwiseValidWorkflowWriter() {
        moduleEnabled();
        User user = user("workshop_supervisor");
        when(userRepository.findByIdAndFactoryId(ACTOR_ID, FACTORY)).thenReturn(Optional.of(user));
        when(permissionService.hasPermission(user, "production:read_write")).thenReturn(true);
        when(userModuleAccessService.canAccessModule(user, "production_plan")).thenReturn(false);

        assertDenied("workshop_supervisor", plan(1L));
        verifyNoInteractions(workflowRepository);
    }

    @Test
    void platformBypassStillRequiresActiveMatchingPlatformAdmin() {
        moduleEnabled();
        when(platformAdminRepository.findById(ACTOR_ID))
                .thenReturn(Optional.of(platformAdmin(PlatformRole.super_admin, true)));
        when(workflowRepository.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(Optional.of(workflow(1L, "OWNER", "[]")));

        withPlatformActor("super_admin", () -> guard.require(FACTORY, plan(1L)));
    }

    @Test
    void inactiveOrChangedPlatformAdminCannotUseFactoryBypass() {
        moduleEnabled();
        when(platformAdminRepository.findById(ACTOR_ID))
                .thenReturn(Optional.of(platformAdmin(PlatformRole.super_admin, false)));
        assertDeniedPlatform("super_admin", plan(1L));

        when(platformAdminRepository.findById(ACTOR_ID))
                .thenReturn(Optional.of(platformAdmin(PlatformRole.system_admin, true)));
        assertDeniedPlatform("super_admin", plan(1L));
    }

    @Test
    void collidingFactoryUserIdCannotSatisfyPlatformIdentity() {
        moduleEnabled();
        when(platformAdminRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());
        assertDeniedPlatform("super_admin", plan(1L));
    }

    @Test
    void platformFactoryReadViewCannotWriteEvenForAnActiveAdmin() {
        moduleEnabled();
        AuthenticatedActor actor = new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, ACTOR_ID, null, "super_admin");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        request.setAttribute(PlatformFactoryViewReadPolicy.REQUEST_ATTRIBUTE, true);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThatThrownBy(() -> guard.require(FACTORY, plan(1L)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(403));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void protectedGraphOutputRequiresEditAuthorization() {
        allow("factory_super_admin");
        protectedProduct("OUTPUT");
        when(workflowRepository.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(Optional.of(workflow(1L, "OWNER", "[{\"data\":{\"skuId\":\"OUTPUT\"}}]")));

        assertDenied("factory_super_admin", plan(1L));
        verify(auditRecorder).record(eq(FACTORY), eq("OUTPUT"), any(AuthenticatedActor.class),
                eq(ProcessSecretAction.EDIT), eq("DENY"), anyString(), anyString());
    }

    @Test
    void parseFailureUsesAllProtectedFailClosed() {
        allow("factory_super_admin");
        protectedProduct("OUTPUT");
        when(workflowRepository.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(Optional.of(workflow(1L, "OWNER", "not-json")));

        assertDenied("factory_super_admin", plan(1L));
        verify(auditRecorder).record(eq(FACTORY), eq("OUTPUT"), any(AuthenticatedActor.class),
                eq(ProcessSecretAction.EDIT), eq("DENY"), anyString(), anyString());
    }

    @Test
    void auditFailureDeniesEvenWhenProtectedOutputGrantAllowsEdit() {
        allow("factory_super_admin");
        protectedProduct("OUTPUT");
        ProcessSecretGrant grant = ProcessSecretGrant.builder()
                .factoryId(FACTORY).productTypeId("OUTPUT")
                .granteeType(ProcessSecretGranteeType.FACTORY_USER).granteeId(ACTOR_ID)
                .canEdit(true).expiresAt(LocalDateTime.now().plusHours(1))
                .purpose("merge contract").grantedBy(900L).build();
        when(grantRepository.findActive(eq(FACTORY), eq("OUTPUT"),
                eq(ProcessSecretGranteeType.FACTORY_USER), eq(ACTOR_ID), any(LocalDateTime.class)))
                .thenReturn(List.of(grant));
        doThrow(new IllegalStateException("audit unavailable")).when(auditRecorder).record(
                eq(FACTORY), eq("OUTPUT"), any(AuthenticatedActor.class), eq(ProcessSecretAction.EDIT),
                eq("ALLOW"), anyString(), anyString());
        when(workflowRepository.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(Optional.of(workflow(1L, "OWNER", "[{\"data\":{\"skuId\":\"OUTPUT\"}}]")));

        assertThatThrownBy(() -> withActor("factory_super_admin", () -> guard.require(FACTORY, plan(1L))))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(403))
                .hasMessageContaining("审计");
    }

    @Test
    void oneUnauthorizedGraphRejectsWholeMixedPlan() {
        allow("factory_super_admin");
        protectedProduct("OUTPUT");
        when(workflowRepository.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(Optional.of(workflow(1L, "OWNER", "[{\"data\":{\"skuId\":\"PLAIN\"}}]")));
        when(workflowRepository.findByIdAndFactoryId(2L, FACTORY))
                .thenReturn(Optional.of(workflow(2L, "OWNER", "[{\"data\":{\"skuId\":\"OUTPUT\"}}]")));

        assertDenied("factory_super_admin", plan(1L, 2L));
        verify(workflowRepository).findByIdAndFactoryId(1L, FACTORY);
        verify(workflowRepository).findByIdAndFactoryId(2L, FACTORY);
    }

    @Test
    void missingTrustedActorIsDenied() {
        assertThatThrownBy(() -> guard.require(FACTORY, plan(1L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(403));
        verifyNoInteractions(workflowRepository, platformAdminRepository, userRepository, permissionService,
                userModuleAccessService, recipeRepository, policyRepository, grantRepository, auditRecorder);
    }

    private void assertDenied(String role, MaterialMergeReferenceReplacer.Plan plan) {
        assertThatThrownBy(() -> withActor(role, () -> guard.require(FACTORY, plan)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(403));
    }

    private void assertProductionDenied(String role) {
        assertThatThrownBy(() -> withActor(role, () -> guard.require(FACTORY, plan(1L))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前账号没有工艺草稿的生产读写权限")
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(403));
    }

    private void assertDeniedPlatform(String role, MaterialMergeReferenceReplacer.Plan plan) {
        assertThatThrownBy(() -> withPlatformActor(role, () -> guard.require(FACTORY, plan)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(403));
    }

    private void allow(String role) {
        moduleEnabled();
        User user = user(role);
        when(userRepository.findByIdAndFactoryId(ACTOR_ID, FACTORY)).thenReturn(Optional.of(user));
        when(permissionService.hasPermission(user, "production:read_write")).thenReturn(true);
        when(userModuleAccessService.canAccessModule(user, "production_plan")).thenReturn(true);
    }

    private void userOnly(String role) {
        moduleEnabled();
        User user = user(role);
        when(userRepository.findByIdAndFactoryId(ACTOR_ID, FACTORY)).thenReturn(Optional.of(user));
    }

    private void moduleEnabled() {
        when(factoryConfigService.isModuleEnabled(FACTORY, "production_plan")).thenReturn(true);
    }

    private User user(String role) {
        User user = new User();
        user.setId(ACTOR_ID);
        user.setFactoryId(FACTORY);
        user.setIsActive(true);
        user.setRoleCode(role);
        return user;
    }

    private PlatformAdmin platformAdmin(PlatformRole role, boolean active) {
        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(ACTOR_ID);
        admin.setPlatformRole(role);
        admin.setStatus(active ? com.cretas.aims.entity.enums.Status.active
                : com.cretas.aims.entity.enums.Status.inactive);
        return admin;
    }

    private void protectedProduct(String productId) {
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder()
                .factoryId(FACTORY).productTypeId(productId)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED)
                .ownerUserId(9999L).createdBy(9999L).build();
        when(policyRepository.findByFactoryIdAndProductTypeId(FACTORY, "OWNER"))
                .thenReturn(Optional.empty());
        when(policyRepository.findByFactoryIdAndProductTypeId(FACTORY, productId))
                .thenReturn(Optional.of(policy));
        when(policyRepository.findByFactoryIdOrderByProductTypeIdAsc(FACTORY))
                .thenReturn(List.of(policy));
    }

    private MaterialMergeReferenceReplacer.Plan plan(Long... workflowIds) {
        Map<Long, String> workflows = new LinkedHashMap<>();
        for (Long id : workflowIds) workflows.put(id, "workflow-" + id);
        return new MaterialMergeReferenceReplacer.Plan(
                workflows, Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of());
    }

    private ProductProcessWorkflow workflow(long id, String owner, String nodesJson) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(id);
        workflow.setFactoryId(FACTORY);
        workflow.setProductTypeId(owner);
        workflow.setStatus(ProductProcessWorkflow.Status.DRAFT);
        workflow.setNodesJson(nodesJson);
        return workflow;
    }

    private void withActor(String role, Runnable action) {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, ACTOR_ID, FACTORY, role))) {
            action.run();
        }
    }

    private void withPlatformActor(String role, Runnable action) {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, ACTOR_ID, null, role))) {
            action.run();
        }
    }
}
