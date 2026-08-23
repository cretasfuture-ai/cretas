package com.cretas.aims.controller.inventory;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.BusinessLinkRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("SalesController finance-review list permission contract")
class SalesControllerFinanceReviewListPermissionTest {

    @Mock private SalesService salesService;
    @Mock private MobileService mobileService;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private BusinessLinkRepository businessLinkRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private GrossMarginRedlineService grossMarginRedlineService;
    @Mock private EstimatePriceCheckService estimatePriceCheckService;
    @Mock private SalesPriceAdjustmentService priceAdjustmentService;
    @Mock private ProductPackagingSpecService productPackagingSpecService;

    private SalesController buildController() {
        return new SalesController(
                salesService,
                mobileService,
                permissionService,
                userRepository,
                salesOrderRepository,
                businessLinkRepository,
                productTypeRepository,
                finishedGoodsBatchRepository,
                grossMarginRedlineService,
                estimatePriceCheckService,
                priceAdjustmentService,
                productPackagingSpecService);
    }

    private MockMvc buildMockMvc() {
        SalesController controller = buildController();
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()))
                .build();
    }

    private User user(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setRoleCode(role);
        user.setFactoryId("F006");
        return user;
    }

    private void stubTokenUser(User user, boolean firstGate, boolean financeReviewGate) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(mobileService.getUserFromToken("test-token"))
                .thenReturn(UserDTO.builder().id(user.getId()).factoryId("F006").build());
        when(permissionService.hasAnyPermission(eq(user), any(String[].class))).thenReturn(firstGate);
        when(permissionService.hasPermission(user, PermissionServiceImpl.FINANCE_REVIEW_VIEW_PERMISSION))
                .thenReturn(financeReviewGate);
    }

    @Test
    @DisplayName("finance:read passes the endpoint gate and finance-review role gate")
    void financeReadWithReviewAuthorityCanListPendingFinanceReview() throws Exception {
        User financeManager = user(1551L, "finance_manager");
        stubTokenUser(financeManager, true, true);

        buildMockMvc().perform(get("/api/mobile/F006/sales/orders/by-status")
                        .param("status", SalesOrderStatus.PENDING_FINANCE_REVIEW.name())
                        .param("page", "1")
                        .param("size", "20")
                        .header("Authorization", "Bearer test-token")
                        .requestAttr("userId", financeManager.getId()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("finance:read alone is insufficient when the independent review gate rejects")
    void financeReadWithoutReviewAuthorityStillGets403() throws Exception {
        User outsider = user(1552L, "viewer");
        when(userRepository.findById(outsider.getId())).thenReturn(Optional.of(outsider));
        when(mobileService.getUserFromToken("test-token"))
                .thenReturn(UserDTO.builder().id(outsider.getId()).factoryId("F006").build());
        when(permissionService.hasPermission(outsider, PermissionServiceImpl.FINANCE_REVIEW_VIEW_PERMISSION))
                .thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class, () ->
                buildController().listOrdersByStatus(
                        "F006", SalesOrderStatus.PENDING_FINANCE_REVIEW, 1, 20,
                        "Bearer test-token"));
        assertEquals(403, error.getCode());
    }

    @Test
    @DisplayName("a role with neither sales nor finance read is rejected by the first gate")
    void unrelatedRoleStillGets403() throws Exception {
        User outsider = user(1553L, "quality_inspector");
        when(userRepository.findById(outsider.getId())).thenReturn(Optional.of(outsider));
        when(permissionService.hasAnyPermission(eq(outsider), any(String[].class))).thenReturn(false);

        buildMockMvc().perform(get("/api/mobile/F006/sales/orders/by-status")
                        .param("status", SalesOrderStatus.PENDING_FINANCE_REVIEW.name())
                        .header("Authorization", "Bearer test-token")
                        .requestAttr("userId", outsider.getId()))
                .andExpect(status().isForbidden());
    }
}
