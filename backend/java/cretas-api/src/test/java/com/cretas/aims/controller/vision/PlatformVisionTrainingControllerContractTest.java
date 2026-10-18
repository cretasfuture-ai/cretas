package com.cretas.aims.controller.vision;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.client.PythonSmartBIClient.VisionTrainingAction;
import com.cretas.aims.dto.platform.FactoryDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.FactoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformVisionTrainingControllerContractTest {
    private final PythonSmartBIClient python = mock(PythonSmartBIClient.class);
    private final FactoryService factories = mock(FactoryService.class);
    private final PlatformVisionTrainingController controller = new PlatformVisionTrainingController(python, factories);

    private MockHttpServletRequest actor(AuthenticatedActorType type, String role) {
        var request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, 1L,
                type == AuthenticatedActorType.FACTORY_USER ? "FA" : null, role));
        return request;
    }

    @Test void verifiedPlatformIdentityCanUseAnExistingTargetFactory() throws Exception {
        FactoryDTO factory = new FactoryDTO(); factory.setId("FA");
        when(factories.getFactoryById("FA")).thenReturn(factory);
        var data = new ObjectMapper().readTree("{\"templates\":[]}");
        when(python.callPlatformVisionTraining("FA", 1L, VisionTrainingAction.TEMPLATES, null, null)).thenReturn(data);
        assertThat(controller.templates("FA", actor(AuthenticatedActorType.PLATFORM_ADMIN, "super_admin")).getData()).isEqualTo(data);
        verify(python).callPlatformVisionTraining("FA", 1L, VisionTrainingAction.TEMPLATES, null, null);
    }

    @Test void sameNumericFactoryIdentityAndForgedHeadersCannotBecomePlatformIdentity() {
        assertThatThrownBy(() -> controller.templates("FA", actor(AuthenticatedActorType.FACTORY_USER, "super_admin")))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(403));
        var headersOnly = new MockHttpServletRequest();
        headersOnly.addHeader("X-User-Id", "1"); headersOnly.addHeader("X-Actor-Type", "PLATFORM_ADMIN");
        assertThatThrownBy(() -> controller.templates("FA", headersOnly)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(factories, python);
    }

    @Test void otherPlatformRolesAndAbsentFactoriesCannotReachPython() {
        assertThatThrownBy(() -> controller.templates("FA", actor(AuthenticatedActorType.PLATFORM_ADMIN, "developer")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.templates("MISSING", actor(AuthenticatedActorType.PLATFORM_ADMIN, "super_admin")))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        verifyNoInteractions(python);
    }

    @Test void unsafeProjectAndFactoryIdentifiersAreRejectedBeforeAnyServiceCall() {
        var admin = actor(AuthenticatedActorType.PLATFORM_ADMIN, "super_admin");
        assertThatThrownBy(() -> controller.draft("FA", "../other", admin)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.templates("FA/other", admin)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(factories, python);
    }
}
