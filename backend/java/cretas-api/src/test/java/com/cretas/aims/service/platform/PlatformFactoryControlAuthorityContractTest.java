package com.cretas.aims.service.platform;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.enums.Status;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformFactoryControlAuthorityContractTest {
    @Test void onlyRealActivePlatformSuperAdminMayWriteAndActorNamespaceCannotCollide() {
        FactoryRepository factories = mock(FactoryRepository.class);
        PlatformAdminRepository admins = mock(PlatformAdminRepository.class);
        when(factories.findById("F1")).thenReturn(Optional.of(new Factory()));
        PlatformAdmin admin = PlatformAdmin.builder().id(7L).platformRole(PlatformRole.super_admin).status(Status.active).build();
        when(admins.findById(7L)).thenReturn(Optional.of(admin));
        PlatformFactoryControlAuthority authority = new PlatformFactoryControlAuthority(factories, admins, mock(FactoryFeatureMenuWriteLock.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 7L, "PLATFORM", "super_admin"));
        assertThat(authority.require(request, "F1", true)).isEqualTo(new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7L));
        request.setAttribute(PlatformFactoryViewReadPolicy.REQUEST_ATTRIBUTE, true);
        assertThatThrownBy(() -> authority.require(request, "F1", false)).isInstanceOf(BusinessException.class);
        request.removeAttribute(PlatformFactoryViewReadPolicy.REQUEST_ATTRIBUTE);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, "F1", "factory_super_admin"));
        assertThatThrownBy(() -> authority.require(request, "F1", true)).isInstanceOf(BusinessException.class);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 7L, "PLATFORM", "developer"));
        assertThatThrownBy(() -> authority.require(request, "F1", true)).isInstanceOf(BusinessException.class);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 7L, "PLATFORM", "super_admin"));
        admin.setPlatformRole(PlatformRole.auditor);
        assertThatThrownBy(() -> authority.require(request, "F1", true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> authority.require(request, "F1", false)).isInstanceOf(BusinessException.class);
        admin.setStatus(Status.inactive);
        assertThatThrownBy(() -> authority.require(request, "F1", false)).isInstanceOf(BusinessException.class);
    }
}
