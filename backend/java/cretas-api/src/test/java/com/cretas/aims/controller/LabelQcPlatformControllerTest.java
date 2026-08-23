package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.labelqc.LabelQcDtos.BoundingBox;
import com.cretas.aims.dto.labelqc.LabelQcDtos.ReviewPlatformCandidateRequest;
import com.cretas.aims.entity.enums.LabelQcLabel;
import com.cretas.aims.service.LabelQcService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LabelQcPlatformControllerTest {

    @Mock
    private LabelQcService labelQcService;

    @InjectMocks
    private LabelQcPlatformController controller;

    @Test
    void platformControllerUsesPlatformPathAndRoleGateWithoutFactoryModulePermissionGate() {
        RequestMapping mapping = LabelQcPlatformController.class.getAnnotation(RequestMapping.class);
        RequireRole role = LabelQcPlatformController.class.getAnnotation(RequireRole.class);

        assertNotNull(mapping);
        assertEquals(Set.of("/api/platform/label-qc/{factoryId}"), Set.of(mapping.value()));
        assertNotNull(role);
        assertEquals(
                Set.of("platform_admin", "super_admin", "developer", "platform_super_admin"),
                Set.of(role.value()));
        assertNull(LabelQcPlatformController.class.getAnnotation(RequireModule.class));
        assertFalse(Set.of(LabelQcPlatformController.class.getDeclaredMethods()).stream()
                .anyMatch(method -> method.getAnnotation(RequirePermission.class) != null));
    }

    @Test
    void listDelegatesToFactoryScopedPlatformCandidateQuery() {
        controller.listCandidates("LIUSHANMEN", false, 2, 50);

        verify(labelQcService).listPlatformCandidates("LIUSHANMEN", false, 2, 50);
    }

    @Test
    void reviewUsesAuthenticatedPlatformUserId() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute("userId", 42L);
        ReviewPlatformCandidateRequest review = new ReviewPlatformCandidateRequest(
                0L,
                LabelQcLabel.NO_DEFECT,
                new BoundingBox(0.1, 0.2, 0.3, 0.4),
                "confirmed hard negative");

        controller.reviewCandidate("LIUSHANMEN", "candidate-1", review, servletRequest);

        verify(labelQcService).reviewPlatformCandidate(
                "LIUSHANMEN", "candidate-1", 42L, review);
    }
}
