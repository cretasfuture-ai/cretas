package com.cretas.aims.controller.platform;

import com.cretas.aims.dto.platform.PlatformFactoryAccessDTO;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.platform.PlatformFactoryAccessService;
import com.cretas.aims.service.platform.PlatformFactoryControlAuthority;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlatformFactoryAccessContractTest {
    @Test void missingEnabledCannotTurnOffModule() throws Exception {
        PlatformFactoryAccessService service = mock(PlatformFactoryAccessService.class);
        PlatformFactoryAccessController controller = new PlatformFactoryAccessController(service, mock(PlatformFactoryControlAuthority.class));
        for (String json : java.util.List.of("{}", "{\"enabled\":null}")) {
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, PlatformFactoryAccessDTO.ModuleUpdate.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.module("F1", "production_plan", body, mock(HttpServletRequest.class)))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        }
        verifyNoInteractions(service);
    }
    @Test
    void dtoKeepsContractFieldsAndDoesNotExposeCredentials() {
        PlatformFactoryAccessDTO.UserSummary user = new PlatformFactoryAccessDTO.UserSummary(7L, "alice", "Alice", "operator", true, false);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(user).toString();
        assertThat(json).contains("id", "username", "displayName", "roleCode", "active", "protectedRole");
        assertThat(json).doesNotContain("password", "phone", "email");
    }

    @Test
    void controllerDelegatesTenantAndWriteDecisionToTypedAuthority() {
        PlatformFactoryAccessService service = mock(PlatformFactoryAccessService.class);
        PlatformFactoryControlAuthority authority = mock(PlatformFactoryControlAuthority.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        ActorRef actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 9L);
        when(authority.require(request, "F001", true)).thenReturn(actor);
        when(service.updateModule("F001", "analytics", true, actor)).thenReturn(null);
        PlatformFactoryAccessController controller = new PlatformFactoryAccessController(service, authority);
        controller.module("F001", "analytics", new PlatformFactoryAccessDTO.ModuleUpdate(true), request);
        verify(authority).require(request, "F001", true);
        verify(service).updateModule("F001", "analytics", true, actor);
    }
}
