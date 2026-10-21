package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.SupplierService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SupplierPriceComparisonContractTest {
    @Mock private SupplierService supplierService;
    @InjectMocks private SupplierPriceComparisonTool tool;

    @AfterEach
    void clearActorRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void supplierLookupDoesNotClaimPriceComparisonOrSortWithoutQuotes() throws Exception {
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        String result;
        try (ActorRequest ignored = actor(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, 42L, "F-SUP", "procurement_manager"))) {
            result = tool.execute(ToolCall.of("call-1", "supplier_price_comparison", "{}"),
                    Map.of("factoryId", "F-SUP", "userId", 42L));
        }

        assertThat(result).contains("\"success\":false")
                .contains("供应商价格比较暂不可用")
                .contains("未接入真实报价")
                .contains("SUPPLIER_PRICE_COMPARISON_UNAVAILABLE")
                .contains("NO_QUOTE_SERVICE")
                .contains("supplier_price_comparison");
        verifyNoInteractions(supplierService);
    }

    @Test
    void directToolRejectsPlatformActorWithFactoryUserIdCollision() throws Exception {
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        String result;
        try (ActorRequest ignored = actor(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, 42L, null, "platform_admin"))) {
            result = tool.execute(ToolCall.of("call-platform", "supplier_price_comparison", "{}"),
                    context("F-SUP", 42L, "factory_super_admin"));
        }
        assertForbidden(result);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"platform-scoped-collision", "foreign-factory", "different-id", "missing-actor", "client-spoof"})
    void directToolRejectsForeignMismatchedOrSpoofedActor(String mutation) throws Exception {
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        AuthenticatedActor actor = switch (mutation) {
            case "platform-scoped-collision" -> new AuthenticatedActor(
                    AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F-SUP", "platform_admin");
            case "foreign-factory" -> new AuthenticatedActor(
                    AuthenticatedActorType.FACTORY_USER, 42L, "OTHER", "procurement_manager");
            case "different-id" -> new AuthenticatedActor(
                    AuthenticatedActorType.FACTORY_USER, 99L, "F-SUP", "procurement_manager");
            default -> null;
        };
        String result;
        try (ActorRequest ignored = actor(actor)) {
            result = tool.execute(ToolCall.of("call-negative", "supplier_price_comparison", "{}"),
                    context("F-SUP", 42L, "factory_super_admin"));
        }
        assertForbidden(result);
    }

    private Map<String, Object> context(String factoryId, long userId, String role) {
        Map<String, Object> context = new HashMap<>();
        context.put("factoryId", factoryId);
        context.put("userId", userId);
        context.put("userRole", role);
        return context;
    }

    private void assertForbidden(String result) {
        assertThat(result).contains("\"success\":false", "FORBIDDEN")
                .doesNotContain("SUPPLIER_PRICE_COMPARISON_UNAVAILABLE", "NO_QUOTE_SERVICE");
        verifyNoInteractions(supplierService);
    }

    private ActorRequest actor(AuthenticatedActor actor) {
        RequestAttributes previous = RequestContextHolder.getRequestAttributes();
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (actor != null) {
            request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return new ActorRequest(previous);
    }

    private static final class ActorRequest implements AutoCloseable {
        private final RequestAttributes previous;
        private ActorRequest(RequestAttributes previous) { this.previous = previous; }
        @Override public void close() {
            if (previous == null) RequestContextHolder.resetRequestAttributes();
            else RequestContextHolder.setRequestAttributes(previous);
        }
    }

    @Test
    void readCapabilityStillRequiresTheExistingProcurementOrFinancePermission() {
        assertThat(tool.getAccessMode()).isEqualTo(com.cretas.aims.ai.tool.ToolExecutor.AccessMode.READ);
        assertThat(tool.requiresPermission()).isTrue();
        assertThat(tool.getRequiredPermissions()).containsExactlyInAnyOrder("procurement:read_write", "finance:read_write");
    }
}
