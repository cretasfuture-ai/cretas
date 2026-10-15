package com.cretas.aims.blueprint.activation;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.enums.Status;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PlatformAssemblyTypedActorContractTest {
    @Test
    void activeSuperAdminMayAuthorizeTargetFactory() {
        FactoryRepository factories = mock(FactoryRepository.class);
        UserRepository users = mock(UserRepository.class);
        PlatformAdminRepository admins = mock(PlatformAdminRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        Factory factory = new Factory();
        factory.setId("F1");
        factory.setIsActive(true);
        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(7L);
        admin.setStatus(Status.active);
        admin.setPlatformRole(PlatformRole.super_admin);
        when(factories.findById("F1")).thenReturn(java.util.Optional.of(factory));
        when(admins.findById(7L)).thenReturn(java.util.Optional.of(admin));
        AssemblyActivationActorResolver resolver = new AssemblyActivationActorResolver(
                factories, users, admins, permissions);

        assertDoesNotThrow(() -> resolver.authorize(
                new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7L),
                "F1", Set.of("factory-blueprint:activation:workflow")));
        verifyNoInteractions(users, permissions);
    }

    @Test
    void inactiveOrDemotedPlatformAdminAndWrongFactoryFailClosed() {
        FactoryRepository factories = mock(FactoryRepository.class);
        UserRepository users = mock(UserRepository.class);
        PlatformAdminRepository admins = mock(PlatformAdminRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        Factory factory = new Factory();
        factory.setId("F1");
        factory.setIsActive(true);
        when(factories.findById("F1")).thenReturn(java.util.Optional.of(factory));
        AssemblyActivationActorResolver resolver = new AssemblyActivationActorResolver(
                factories, users, admins, permissions);

        PlatformAdmin inactive = new PlatformAdmin();
        inactive.setId(7L);
        inactive.setStatus(Status.inactive);
        inactive.setPlatformRole(PlatformRole.super_admin);
        when(admins.findById(7L)).thenReturn(java.util.Optional.of(inactive));
        ActorRef platform = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7L);
        assertThrows(RuntimeException.class, () -> resolver.authorize(platform, "F1", Set.of("workflow:publish")));

        inactive.setStatus(Status.active);
        inactive.setPlatformRole(PlatformRole.auditor);
        assertThrows(RuntimeException.class, () -> resolver.authorize(platform, "F1", Set.of("workflow:publish")));
        assertThrows(RuntimeException.class, () -> resolver.authorize(platform, "F2", Set.of("workflow:publish")));
    }
}
