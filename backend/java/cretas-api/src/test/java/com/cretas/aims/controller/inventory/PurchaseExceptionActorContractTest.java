package com.cretas.aims.controller.inventory;

import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.inventory.PurchaseExceptionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PurchaseExceptionActorContractTest {
    private final PurchaseExceptionService service = mock(PurchaseExceptionService.class);
    private final PurchaseExceptionController controller = new PurchaseExceptionController(service);

    @Test
    void platformActorWithSameNumericIdCannotBecomeFactoryOwner() {
        rejects(new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 7L, null, "platform_admin"), 403);
    }

    @Test
    void factoryActorCannotDecideForAnotherFactory() {
        rejects(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, "OTHER", "factory_super_admin"), 403);
    }

    @Test
    void missingTypedActorCannotFallBackToBareUserIdOrRole() {
        rejects(null, 401);
    }

    @Test
    void factoryOwnerIdentityReachesOwnershipCheck() {
        accepts(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, "F1", "warehouse_worker"));
    }

    @Test
    void factorySupervisorUsesValidatedActorRole() {
        accepts(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 8L, "F1", "procurement_manager"));
    }

    private MockHttpServletRequest request(AuthenticatedActor actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (actor != null) request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        request.setAttribute("userId", 7L);
        request.setAttribute("role", "factory_super_admin");
        return request;
    }

    private void rejects(AuthenticatedActor actor, int status) {
        BusinessException error = assertThrows(BusinessException.class, () -> controller.decideException(
                "F1", "EX-1", "Bearer unused", request(actor), Map.of("decision", "RETURN_OVER")));
        assertEquals(status, error.getCode());
        verifyNoInteractions(service);
    }

    private void accepts(AuthenticatedActor actor) {
        PurchaseException result = new PurchaseException();
        when(service.decideException("EX-1", "F1", ExceptionDecision.RETURN_OVER, null, actor.id(), actor.role()))
                .thenReturn(result);
        assertSame(result, controller.decideException("F1", "EX-1", "Bearer unused", request(actor),
                Map.of("decision", "RETURN_OVER")).getData());
        verify(service).decideException("EX-1", "F1", ExceptionDecision.RETURN_OVER, null, actor.id(), actor.role());
    }
}
