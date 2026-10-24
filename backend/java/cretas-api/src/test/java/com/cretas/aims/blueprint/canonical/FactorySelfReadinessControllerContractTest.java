package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The factory's own readiness entry: who may read it, what it says without a canonical blueprint,
 * and that it only ever reads. The authority is the real one over mocked repositories, so each
 * denial is attributed to the code its own gate produces.
 */
class FactorySelfReadinessControllerContractTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PlatformAdminRepository platformAdmins = mock(PlatformAdminRepository.class);
    private final CanonicalFactoryBlueprintService blueprints = mock(CanonicalFactoryBlueprintService.class);
    private final FactoryReadinessService readiness = mock(FactoryReadinessService.class);
    private final FactorySelfReadinessController controller = new FactorySelfReadinessController(
            blueprints, readiness, new CanonicalBlueprintActorAuthority(users, platformAdmins));

    private static MockHttpServletRequest as(AuthenticatedActorType type, long id, String factoryId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, id, factoryId, role));
        return request;
    }

    private static User member(String role, boolean active) {
        User user = new User();
        user.setId(7L);
        user.setFactoryId("F006");
        user.setRoleCode(role);
        user.setIsActive(active);
        return user;
    }

    @Test
    void sameFactoryActiveSuperAdminReadsTheCurrentBlueprintsReport() {
        when(users.findByIdAndFactoryId(7L, "F006")).thenReturn(Optional.of(member("factory_super_admin", true)));
        BlueprintResponse current = BlueprintResponse.builder().id("bp-1").factoryId("F006").version(3)
                .rowVersion(11L).lifecycleState("PUBLISHED").build();
        when(blueprints.current("F006")).thenReturn(current);
        FactoryReadinessReport report = mock(FactoryReadinessReport.class);
        when(readiness.read("F006", "bp-1")).thenReturn(report);

        var answer = controller.current("F006", as(AuthenticatedActorType.FACTORY_USER, 7L, "F006", "factory_super_admin"))
                .getData();

        assertThat(answer.state()).isEqualTo(FactorySelfReadinessController.READY_TO_READ);
        assertThat(answer.blueprint()).isEqualTo(new FactorySelfReadinessController.BlueprintRef("bp-1", 3, 11L, "PUBLISHED"));
        assertThat(answer.readiness()).isSameAs(report);
        verify(blueprints).current("F006");
        verify(readiness).read("F006", "bp-1");
        verifyNoMoreInteractions(blueprints, readiness);
    }

    @Test
    void withoutACanonicalBlueprintItSaysSoAndNeverAsksForAReport() {
        when(users.findByIdAndFactoryId(7L, "F006")).thenReturn(Optional.of(member("factory_super_admin", true)));
        when(blueprints.current("F006")).thenReturn(null);

        var answer = controller.current("F006", as(AuthenticatedActorType.FACTORY_USER, 7L, "F006", "factory_super_admin"))
                .getData();

        assertThat(answer.state()).isEqualTo(FactorySelfReadinessController.NO_CANONICAL_BLUEPRINT);
        assertThat(answer.blueprint()).isNull();
        assertThat(answer.readiness()).isNull();
        verifyNoInteractions(readiness);
        verify(blueprints).current("F006");
        verifyNoMoreInteractions(blueprints);
    }

    @Test
    void aPlatformTokenIsRefusedBeforeAnyAuthorityOrBlueprintRead() {
        BusinessException denied = catchThrowableOfType(() -> controller.current("F006",
                as(AuthenticatedActorType.PLATFORM_ADMIN, 7L, null, "super_admin")), BusinessException.class);

        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getErrorCode()).isEqualTo("FACTORY_SELF_READINESS_FACTORY_USER_REQUIRED");
        verifyNoInteractions(users, platformAdmins, blueprints, readiness);
    }

    @Test
    void anotherFactorysOrAnInactiveOrDeletedAccountIsNotAMemberOfThisFactory() {
        // findByIdAndFactoryId is scoped to the path factory and the entity hides soft-deleted rows,
        // so another factory's user and a deleted user both come back empty.
        when(users.findByIdAndFactoryId(7L, "F006")).thenReturn(Optional.empty());
        assertDeniedWith("CANONICAL_FACTORY_MEMBER_REQUIRED");

        when(users.findByIdAndFactoryId(7L, "F006")).thenReturn(Optional.of(member("factory_super_admin", false)));
        assertDeniedWith("CANONICAL_FACTORY_MEMBER_REQUIRED");
    }

    @Test
    void anyOtherFactoryRoleIsRefused() {
        for (String role : new String[] {"factory_admin", "operator", "warehouse_manager", "quality_inspector"}) {
            when(users.findByIdAndFactoryId(7L, "F006")).thenReturn(Optional.of(member(role, true)));
            assertDeniedWith("CANONICAL_FACTORY_ROLE_REQUIRED");
        }
    }

    @Test
    void aFailingReportIsReportedAsAFailureNotAsAnEmptyOrReadyAnswer() {
        when(users.findByIdAndFactoryId(7L, "F006")).thenReturn(Optional.of(member("factory_super_admin", true)));
        when(blueprints.current("F006")).thenReturn(BlueprintResponse.builder().id("bp-1").version(1).rowVersion(1L)
                .lifecycleState("DRAFT").build());
        when(readiness.read(anyString(), anyString())).thenThrow(new BusinessException(500, "readiness unavailable"));

        BusinessException failure = catchThrowableOfType(() -> controller.current("F006",
                as(AuthenticatedActorType.FACTORY_USER, 7L, "F006", "factory_super_admin")), BusinessException.class);

        assertThat(failure.getMessage()).isEqualTo("readiness unavailable");
    }

    @Test
    void theEndpointIsAReadOnlyTransactionUnderTheTenantCheckedMobilePrefix() throws Exception {
        Transactional tx = FactorySelfReadinessController.class
                .getMethod("current", String.class, jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(FactorySelfReadinessController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/mobile/{factoryId}/factory-readiness");
    }

    @Test
    void itFollowsTheFactoryAssemblyFeatureGate() {
        // Registered as finished singletons: the collaborators are mocks, not beans to be autowired.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton("blueprints", blueprints);
                    context.getBeanFactory().registerSingleton("readiness", readiness);
                    context.getBeanFactory().registerSingleton("authority",
                            new CanonicalBlueprintActorAuthority(users, platformAdmins));
                })
                .withUserConfiguration(FactorySelfReadinessController.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(FactorySelfReadinessController.class));
        runner.withPropertyValues("cretas.platform.factory-assembly.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FactorySelfReadinessController.class));
        runner.withPropertyValues("cretas.platform.factory-assembly.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(FactorySelfReadinessController.class));
    }

    /**
     * The same reads over real HTTP: the production {@link com.cretas.aims.config.WebMvcConfig}
     * registers the interceptors on their real paths, {@link com.cretas.aims.config.JwtAuthInterceptor}
     * verifies tokens signed by a real {@link com.cretas.aims.utils.JwtUtil} and compares the URL
     * factory with the token's, and {@link com.cretas.aims.exception.GlobalExceptionHandler} writes the
     * response. The demo read-only and @RequireRole interceptors run as themselves; the permission,
     * module, data-scope, process-secret and access-log interceptors are not this endpoint's gate and
     * pass through. Repositories are mocks.
     */
    @Test
    void overRealHttpTheTokenTheTenantAndTheRoleEachDecideWithTheirOwnGate() throws Exception {
        var jwt = new com.cretas.aims.utils.JwtUtil();
        org.springframework.test.util.ReflectionTestUtils.setField(jwt, "secret",
                java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID());
        org.springframework.test.util.ReflectionTestUtils.setField(jwt, "expiration", 60_000L);
        User superAdmin = member("factory_super_admin", true);
        User otherFactoryAdmin = member("factory_super_admin", true);
        otherFactoryAdmin.setId(8L);
        otherFactoryAdmin.setFactoryId("F007");
        User operator = member("operator", true);
        operator.setId(9L);
        User inactive = member("factory_super_admin", false);
        inactive.setId(10L);
        for (User user : List.of(superAdmin, otherFactoryAdmin, operator, inactive)) {
            when(users.findById(user.getId())).thenReturn(Optional.of(user));
            when(users.findByIdAndFactoryId(user.getId(), user.getFactoryId())).thenReturn(Optional.of(user));
        }
        when(blueprints.current("F006")).thenReturn(BlueprintResponse.builder().id("bp-1").factoryId("F006")
                .version(3).rowVersion(11L).lifecycleState("PUBLISHED").build());
        when(readiness.read("F006", "bp-1")).thenReturn(null);
        var blacklist = mock(com.cretas.aims.service.TokenBlacklistService.class);
        var factories = mock(com.cretas.aims.repository.FactoryRepository.class);
        when(factories.findById(anyString())).thenReturn(Optional.empty());

        try (var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new org.springframework.mock.web.MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "feature", java.util.Map.of("cretas.platform.factory-assembly.enabled", "true")));
            context.addBeanFactoryPostProcessor(registry -> {
                registry.registerSingleton("jwtUtil", jwt);
                registry.registerSingleton("tokenBlacklistService", blacklist);
                registry.registerSingleton("webLoginSessionService",
                        mock(com.cretas.aims.service.mobile.WebLoginSessionService.class));
                registry.registerSingleton("factoryRepository", factories);
                registry.registerSingleton("userRepository", users);
                registry.registerSingleton("platformAdminRepository", platformAdmins);
                registry.registerSingleton("blueprints", blueprints);
                registry.registerSingleton("readiness", readiness);
                registry.registerSingleton("objectMapper", new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
                registry.registerSingleton("permissionInterceptor", passThrough(com.cretas.aims.config.PermissionInterceptor.class));
                registry.registerSingleton("moduleEnabledInterceptor", passThrough(com.cretas.aims.config.ModuleEnabledInterceptor.class));
                registry.registerSingleton("dataScopeRouteInterceptor", passThrough(com.cretas.aims.security.DataScopeRouteInterceptor.class));
                registry.registerSingleton("processSecretGuardInterceptor",
                        passThrough(com.cretas.aims.security.ProcessSecretGuardInterceptor.class));
                registry.registerSingleton("platformDataAccessLogInterceptor",
                        passThrough(com.cretas.aims.audit.PlatformDataAccessLogInterceptor.class));
            });
            context.register(WebMvc.class, com.cretas.aims.config.WebMvcConfig.class,
                    com.cretas.aims.config.JwtAuthInterceptor.class, com.cretas.aims.config.DemoReadOnlyInterceptor.class,
                    com.cretas.aims.config.RequireRoleInterceptor.class, com.cretas.aims.exception.GlobalExceptionHandler.class,
                    CanonicalBlueprintActorAuthority.class, FactorySelfReadinessController.class);
            context.refresh();
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
            String endpoint = "/api/mobile/F006/factory-readiness/current";

            // No token: the JWT interceptor answers before anything else (its own message, not the
            // controller's "无法获取已认证操作者").
            mvc.perform(get(endpoint)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("未授权，请先登录"));
            // Same factory, active factory_super_admin: the real report is read for the current blueprint.
            mvc.perform(get(endpoint).header("Authorization", bearer(jwt.generateToken(7L, "F006", "sa", "factory_super_admin"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.state").value(FactorySelfReadinessController.READY_TO_READ))
                    .andExpect(jsonPath("$.data.blueprint.id").value("bp-1"))
                    .andExpect(jsonPath("$.data.blueprint.rowVersion").value(11));
            verify(readiness).read("F006", "bp-1");
            org.mockito.Mockito.clearInvocations(blueprints, readiness);

            // Another factory's token on this factory's path: the mobile tenant check refuses it.
            mvc.perform(get(endpoint).header("Authorization", bearer(jwt.generateToken(8L, "F007", "other", "factory_super_admin"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("无权访问该工厂数据"))
                    .andExpect(jsonPath("$.errorCode").doesNotExist());
            // A platform token passes the tenant check (platform roles may) and is refused by this endpoint.
            mvc.perform(get(endpoint).header("Authorization", bearer(jwt.generatePlatformToken(62L, "platform", "super_admin"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("FACTORY_SELF_READINESS_FACTORY_USER_REQUIRED"));
            // A same-factory account with another role is refused by the blueprint authority.
            mvc.perform(get(endpoint).header("Authorization", bearer(jwt.generateToken(9L, "F006", "op", "operator"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("CANONICAL_FACTORY_ROLE_REQUIRED"));
            // A deactivated account's still-valid token is refused by the interceptor's account check.
            mvc.perform(get(endpoint).header("Authorization", bearer(jwt.generateToken(10L, "F006", "gone", "factory_super_admin"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("账号已被禁用或不存在，请重新登录"));
            verifyNoInteractions(blueprints, readiness);
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class WebMvc { }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static <T extends org.springframework.web.servlet.HandlerInterceptor> T passThrough(Class<T> type) {
        T interceptor = mock(type);
        try {
            when(interceptor.preHandle(any(), any(), any())).thenReturn(true);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return interceptor;
    }

    private void assertDeniedWith(String code) {
        BusinessException denied = catchThrowableOfType(() -> controller.current("F006",
                as(AuthenticatedActorType.FACTORY_USER, 7L, "F006", "factory_super_admin")), BusinessException.class);
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getErrorCode()).isEqualTo(code);
        verify(blueprints, never()).current(any());
        verifyNoInteractions(readiness);
    }
}
