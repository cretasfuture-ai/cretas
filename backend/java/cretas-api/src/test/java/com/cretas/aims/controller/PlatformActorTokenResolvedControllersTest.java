package com.cretas.aims.controller;

import com.cretas.aims.controller.inventory.PurchaseController;
import com.cretas.aims.controller.inventory.SalesController;
import com.cretas.aims.controller.workflow.WorkflowInstanceController;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.PurchaseOrderPdfService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controllers that resolve the caller from the token ({@code getUserFromToken(token).getId()}) and then
 * load {@code users} by that id. For a platform token the id comes from platform_admins, an independent
 * sequence, so it equals some unrelated factory user's id (on a fresh DB, platform admin 1 vs F999's
 * phase2a_test_user 1). Bypass platform roles get full factory access, other platform roles are
 * refused or masked, and the colliding factory user is never consulted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("token-resolved controllers — platform admin is never judged as the factory user with the same id")
class PlatformActorTokenResolvedControllersTest {

    private static final long SHARED_ID = 1L;
    private static final String FACTORY = "F001";
    private static final String AUTH = "Bearer token";
    private static final List<String> NON_BYPASS = List.of("auditor", "operation_admin", "system_admin");

    @Mock private MobileService mobileService;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @Mock private PurchaseService purchaseService;
    @Mock private SalesService salesService;
    @Mock private PurchaseOrderPdfService purchaseOrderPdfService;
    @Mock private ProductionPlanRepository planRepository;

    @InjectMocks private SalesController salesController;
    @InjectMocks private PurchaseController purchaseController;
    @InjectMocks private ProductionAnalyticsController analyticsController;
    @InjectMocks private WorkflowInstanceController workflowController;

    @BeforeEach
    void setUp() {
        // Both token kinds resolve to the same numeric id: that is the collision.
        when(mobileService.getUserFromToken(anyString())).thenReturn(UserDTO.builder().id(SHARED_ID).build());
        PurchaseOrder order = new PurchaseOrder();
        order.setId("PO-1");
        order.setOrderNumber("PO-1");
        when(purchaseService.getPurchaseOrderById(FACTORY, "PO-1")).thenReturn(order);
        when(purchaseOrderPdfService.generatePurchaseOrderPdf(anyString(), anyString(), any(Boolean.class), any(Boolean.class)))
                .thenReturn(new byte[0]);
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PP-1");
        plan.setEstimatedLaborCost(new BigDecimal("100"));
        plan.setEstimatedMaterialCost(new BigDecimal("200"));
        when(planRepository.findByFactoryId(FACTORY)).thenReturn(List.of(plan));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void as(AuthenticatedActorType type, String factoryId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", SHARED_ID);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(type, SHARED_ID, factoryId, role));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** The factory user who happens to own {@link #SHARED_ID} in {@code users}. */
    private void collidingFactoryUser(boolean granted) {
        User user = new User();
        user.setId(SHARED_ID);
        user.setFactoryId("F999");
        user.setRoleCode("operator");
        when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(user));
        when(permissionService.hasPermission(eq(user), anyString())).thenReturn(granted);
    }

    private boolean salesGateOpen() {
        return opens(() -> salesController.listOrdersByStatus(
                FACTORY, SalesOrderStatus.PENDING_FINANCE_REVIEW, 1, 20, AUTH));
    }

    private boolean purchaseGateOpen() {
        return opens(() -> purchaseController.listOrdersByStatus(
                FACTORY, PurchaseOrderStatus.PENDING_FINANCE_REVIEW, 1, 20, AUTH));
    }

    private boolean pdfMasked() {
        clearInvocations(purchaseOrderPdfService);
        purchaseController.downloadOrderPdf(FACTORY, "PO-1", false, AUTH);
        try {
            verify(purchaseOrderPdfService).generatePurchaseOrderPdf(FACTORY, "PO-1", true, false);
            return true;
        } catch (AssertionError notMasked) {
            verify(purchaseOrderPdfService).generatePurchaseOrderPdf(FACTORY, "PO-1", false, false);
            return false;
        }
    }

    private boolean costsVisible() {
        Map<String, Object> row = analyticsController.getBudgetVsActual(FACTORY, null, null, AUTH).getData().get(0);
        return row.get("estimatedMaterialCost") != null;
    }

    private static boolean opens(Runnable call) {
        try {
            call.run();
            return true;
        } catch (BusinessException e) {
            assertEquals(403, e.getCode(), e.getMessage());
            return false;
        }
    }

    @Test
    @DisplayName("bypass platform roles: gates open, prices and costs shown, even when the colliding user has none")
    void bypassPlatformRole_fullFactoryAccess() {
        collidingFactoryUser(false);
        for (String role : AuthenticatedActor.FACTORY_BYPASS_PLATFORM_ROLES) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            assertTrue(salesGateOpen(), role);
            assertTrue(purchaseGateOpen(), role);
            assertFalse(pdfMasked(), role);
            assertTrue(costsVisible(), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("other platform roles: gates refused, prices and costs masked, even when the colliding user has all")
    void nonBypassPlatformRole_refusedOrMasked() {
        collidingFactoryUser(true);
        for (String role : NON_BYPASS) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            assertFalse(salesGateOpen(), role);
            assertFalse(purchaseGateOpen(), role);
            assertTrue(pdfMasked(), role);
            assertFalse(costsVisible(), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("workflow instances: platform admins are refused instead of acting as the colliding user")
    void workflow_platformAdminRefused() {
        collidingFactoryUser(true);
        for (String role : List.of("super_admin", "auditor")) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> workflowController.getPendingForUser(FACTORY, AUTH, null, 1, 20), role);
            assertEquals(403, ex.getCode(), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("negative control: the factory user with that id keeps their own result")
    void negativeControl_factoryUserWithSameIdKeepsOwnResult() {
        as(AuthenticatedActorType.FACTORY_USER, "F999", "operator");

        collidingFactoryUser(false);
        assertFalse(salesGateOpen());
        assertFalse(purchaseGateOpen());
        assertTrue(pdfMasked());
        assertFalse(costsVisible());

        collidingFactoryUser(true);
        assertTrue(salesGateOpen());
        assertTrue(purchaseGateOpen());
        assertFalse(pdfMasked());
        assertTrue(costsVisible());

        // The workflow resolver reaches users for a factory user (it is not refused as a platform admin).
        clearInvocations(userRepository);
        try {
            workflowController.getPendingForUser(FACTORY, AUTH, null, 1, 20);
        } catch (RuntimeException downstream) {
            assertFalse(downstream.getMessage() != null && downstream.getMessage().contains("平台管理员"),
                    downstream.getMessage());
        }
        verify(userRepository).findById(SHARED_ID);
    }
}
