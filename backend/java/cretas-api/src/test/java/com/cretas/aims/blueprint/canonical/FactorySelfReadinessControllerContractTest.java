package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
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

    private void assertDeniedWith(String code) {
        BusinessException denied = catchThrowableOfType(() -> controller.current("F006",
                as(AuthenticatedActorType.FACTORY_USER, 7L, "F006", "factory_super_admin")), BusinessException.class);
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getErrorCode()).isEqualTo(code);
        verify(blueprints, never()).current(any());
        verifyNoInteractions(readiness);
    }
}
