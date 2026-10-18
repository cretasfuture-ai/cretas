package com.cretas.aims.controller;

import com.cretas.aims.domain.OrderUsageWhitelists;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionTransitLedgerRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.orchestration.ProductionWorkflowOrchestrator;
import com.cretas.aims.service.production.CustomerSuppliedSettlementCoverageGuard;
import com.cretas.aims.service.yield.InterimSettleReversalRequestService;
import com.cretas.aims.service.yield.InterimSettleService;
import com.cretas.aims.service.yield.ProductionSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Guards the selectable-sales-order production-plan entry point against post-page filtering. */
@ExtendWith(MockitoExtension.class)
class ProductionScopeControllerContractTest {

    @Mock private ProductionPlanService productionPlanService;
    @Mock private MobileService mobileService;
    @Mock private ProductionPlanRepository planRepository;
    @Mock private InterimSettleService interimSettleService;
    @Mock private InterimSettleReversalRequestService interimSettleReversalRequestService;
    @Mock private ProductionWorkflowOrchestrator workflowOrchestrator;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private PriceMaskResolver priceMaskResolver;
    @Mock private ProductionTransitLedgerRepository transitLedgerRepository;
    @Mock private ProductionSummaryService productionSummaryService;
    @Mock private CustomerSuppliedSettlementCoverageGuard customerSuppliedSettlementCoverageGuard;
    @Mock private BusinessRowScope businessRowScope;

    @InjectMocks private ProductionPlanController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "businessRowScope", businessRowScope);
    }

    @Test
    void exportBindsTheRealUiRequestWithoutDates() throws Exception {
        when(productionPlanService.exportProductionPlans("F1", null, null, false))
                .thenReturn(new byte[]{1, 2, 3});
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/mobile/F1/production-plans/export")
                        .header("Authorization", "Bearer test"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .bytes(new byte[]{1, 2, 3}));
        verify(productionPlanService).exportProductionPlans("F1", null, null, false);
    }

    @Test
    void scopedSelectableOrdersPushStatusPredicateBeforeLimit() {
        when(businessRowScope.creatorIds("F-SCOPE-SO")).thenReturn(List.of(11L));
        SalesOrder plannable = new SalesOrder();
        plannable.setId("SO-PLANNABLE");
        plannable.setFactoryId("F-SCOPE-SO");
        plannable.setCreatedBy(11L);
        plannable.setStatus(SalesOrderStatus.PROCESSING);
        when(salesOrderRepository.findByFactoryIdAndCreatedByInAndStatusInOrderByCreatedAtDesc(
                anyString(), anyCollection(), anyCollection(), any(Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(plannable)));

        var response = controller.getSelectableSalesOrders("F-SCOPE-SO");

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getData()).hasSize(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<SalesOrderStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(salesOrderRepository).findByFactoryIdAndCreatedByInAndStatusInOrderByCreatedAtDesc(
                anyString(), anyCollection(), statuses.capture(), any(Pageable.class));
        assertThat(statuses.getValue()).containsExactlyInAnyOrderElementsOf(OrderUsageWhitelists.SO_PLANNABLE);
        verify(salesOrderRepository, never()).findByFactoryIdAndCreatedByInOrderByCreatedAtDesc(
                anyString(), anyCollection(), any(Pageable.class));
    }

    @Test
    void scopedTransitLedgerListUsesVisiblePlanIdsBeforeMapping() {
        when(businessRowScope.creatorIds("F-SCOPE-TRANSIT")).thenReturn(List.of(11L));
        ProductionPlan visiblePlan = new ProductionPlan();
        visiblePlan.setId("PLAN-VISIBLE");
        when(planRepository.findByFactoryIdAndCreatedByIn("F-SCOPE-TRANSIT", List.of(11L)))
                .thenReturn(List.of(visiblePlan));
        when(transitLedgerRepository
                .findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNullOrderByCreatedAtDesc(
                        "F-SCOPE-TRANSIT", List.of("PLAN-VISIBLE")))
                .thenReturn(List.of());

        var response = controller.listTransitLedgers("F-SCOPE-TRANSIT", null);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getData()).isEmpty();
        verify(transitLedgerRepository)
                .findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNullOrderByCreatedAtDesc(
                        "F-SCOPE-TRANSIT", List.of("PLAN-VISIBLE"));
        verify(transitLedgerRepository, never())
                .findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(anyString());
    }
}
