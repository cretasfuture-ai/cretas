package com.cretas.aims.security;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * platform_admins.id and users.id are independent sequences, so a platform admin's id equals some
 * unrelated factory user's id (on a fresh DB, platform admin 1 vs F999's phase2a_test_user 1).
 * Price masking must be decided by the actor type, never by {@code users.findById(actor.id())}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PriceFieldResponseAdvice — platform admin is never judged as the factory user with the same id")
class PriceFieldResponseAdvicePlatformActorTest {

    private static final long SHARED_ID = 1L;

    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;

    @InjectMocks private PriceFieldResponseAdvice advice;

    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setup() {
        httpRequest = new MockHttpServletRequest();
        PriceSensitiveContext.clear();
    }

    @AfterEach
    void teardown() {
        PriceSensitiveContext.clear();
    }

    private void as(AuthenticatedActorType type, String factoryId, String role) {
        httpRequest.setAttribute("userId", SHARED_ID);
        httpRequest.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(type, SHARED_ID, factoryId, role));
    }

    /** The factory user who happens to own {@link #SHARED_ID} in {@code users}. */
    private void collidingFactoryUser(boolean canViewPrices) {
        User user = new User();
        user.setId(SHARED_ID);
        lenient().when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasPermission(eq(user), any(String.class))).thenReturn(canViewPrices);
    }

    private static PurchaseOrder order() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId("po-1");
        order.setTotalAmount(new BigDecimal("12345.67"));
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(1L);
        item.setUnitPrice(new BigDecimal("123.4567"));
        order.setItems(List.of(item));
        return order;
    }

    private PurchaseOrder run() {
        PurchaseOrder order = order();
        advice.beforeBodyWrite(order, null, MediaType.APPLICATION_JSON, null,
                new ServletServerHttpRequest(httpRequest), null);
        return order;
    }

    @Test
    @DisplayName("bypass platform roles see prices even when users row with the same id cannot")
    void bypassPlatformRole_seesPrices_notMaskedByCollidingUser() {
        collidingFactoryUser(false);
        for (String role : AuthenticatedActor.FACTORY_BYPASS_PLATFORM_ROLES) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            PurchaseOrder order = run();
            assertEquals(new BigDecimal("12345.67"), order.getTotalAmount(), role);
            assertEquals(new BigDecimal("123.4567"), order.getItems().get(0).getUnitPrice(), role);
            assertFalse(PriceSensitiveContext.shouldHide(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("other platform roles are masked even when users row with the same id may see prices")
    void nonBypassPlatformRole_masked_evenIfCollidingUserMaySeePrices() {
        collidingFactoryUser(true);
        for (String role : List.of("auditor", "operation_admin", "system_admin")) {
            PriceSensitiveContext.clear();
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            PurchaseOrder order = run();
            assertNull(order.getTotalAmount(), role);
            assertNull(order.getItems().get(0).getUnitPrice(), role);
            assertTrue(PriceSensitiveContext.shouldHide(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("negative control: the factory user with that id keeps their own result")
    void negativeControl_factoryUserWithSameIdKeepsOwnResult() {
        as(AuthenticatedActorType.FACTORY_USER, "F999", "operator");

        collidingFactoryUser(false);
        PurchaseOrder masked = run();
        assertNull(masked.getTotalAmount());
        assertNull(masked.getItems().get(0).getUnitPrice());

        PriceSensitiveContext.clear();
        collidingFactoryUser(true);
        PurchaseOrder visible = run();
        assertEquals(new BigDecimal("12345.67"), visible.getTotalAmount());
        assertEquals(new BigDecimal("123.4567"), visible.getItems().get(0).getUnitPrice());
    }

    @Test
    @DisplayName("a raw userId with no typed actor is unauthenticated, not a users lookup")
    void rawUserIdWithoutActor_isNotResolvedThroughUsers() {
        httpRequest.setAttribute("userId", SHARED_ID);
        PurchaseOrder order = run();
        assertEquals(new BigDecimal("12345.67"), order.getTotalAmount());
        verify(userRepository, never()).findById(any());
    }
}
