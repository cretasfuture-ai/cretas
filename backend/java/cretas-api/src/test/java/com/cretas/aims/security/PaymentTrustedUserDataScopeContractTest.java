package com.cretas.aims.security;

import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.RoleDefinitionRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentTrustedUserDataScopeContractTest {
    @Test void rehydratedScopeChecksFactoryOwnerAndUnknownIdentityWithoutServlet() {
        var resolver=spy(new DataScopeResolver(mock(UserRepository.class),mock(RoleDefinitionRepository.class)));
        var self=DataScopeContext.builder().factoryId("F1").userId(56L).scope(DataScope.SELF).build();
        doReturn(self).when(resolver).resolve(56L);
        var actor=new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","sales_manager");
        resolver.assertActorCanAccess("F1",56L,actor);
        assertDenied(() -> resolver.assertActorCanAccess("F1",66L,actor));
        assertDenied(() -> resolver.assertActorCanAccess("F1",null,actor));
        assertDenied(() -> resolver.assertActorCanAccess("F2",56L,actor));
        assertDenied(() -> resolver.assertActorCanAccess("F1",56L,new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,999L,"F1","sales_manager")));
        doReturn(DataScopeContext.builder().factoryId("F1").userId(56L).scope(DataScope.ALL).build())
                .when(resolver).resolve(56L);
        resolver.assertActorCanAccess("F1",66L,actor);
        assertDenied(() -> resolver.assertActorCanAccess("F2",66L,actor));
    }
    @Test void collidingPlatformIdDoesNotBorrowFactoryScopeAndBypassIsExplicit() {
        var resolver=spy(new DataScopeResolver(mock(UserRepository.class),mock(RoleDefinitionRepository.class)));
        for(String role:java.util.List.of("system_admin","operation_admin","auditor")) {
            var actor=new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN,56L,null,role);
            assertDenied(() -> resolver.assertActorCanAccess("F1",56L,actor));
        }
        for(String role:AuthenticatedActor.FACTORY_BYPASS_PLATFORM_ROLES)
            resolver.assertActorCanAccess("F1",66L,new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN,56L,null,role));
        verify(resolver,never()).resolve(any());
    }
    @Test void typedWorkerScopesRestoreOnFailureAndDoNotLeakToChildThreads() throws Exception {
        var actor=new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","sales_manager");
        assertThat(AuthenticatedActor.fromCurrentExecution()).isEmpty();
        try(var scope=AuthenticatedActor.trustedAsyncScope(actor)) {
            assertThat(AuthenticatedActor.fromCurrentExecution()).contains(actor);
            assertThatThrownBy(() -> {try(var nested=AuthenticatedActor.trustedAsyncScope(null)) {
                assertThat(AuthenticatedActor.fromCurrentExecution()).isEmpty();throw new IllegalStateException();
            }}).isInstanceOf(IllegalStateException.class);
            assertThat(AuthenticatedActor.fromCurrentExecution()).contains(actor);
            var found=new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread child=new Thread(() -> found.set(AuthenticatedActor.fromCurrentExecution().isPresent()));
            child.start();child.join();assertThat(found.get()).isFalse();
        }
        assertThat(AuthenticatedActor.fromCurrentExecution()).isEmpty();
    }
    private void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(403));
    }
}
