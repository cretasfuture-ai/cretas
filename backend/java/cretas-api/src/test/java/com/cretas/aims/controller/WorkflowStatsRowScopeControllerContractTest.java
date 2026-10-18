package com.cretas.aims.controller;

import com.cretas.aims.dto.workflow.WorkflowStatsDTO;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.security.RowScopeSupported;
import com.cretas.aims.service.workflow.WorkflowStatsRowScopeService;
import com.cretas.aims.service.workflow.WorkflowStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ensures only production/finance workflow stats opt into row-scoped aggregation. */
@ExtendWith(MockitoExtension.class)
class WorkflowStatsRowScopeControllerContractTest {

    @Mock private WorkflowStatsService workflowStatsService;
    @Mock private WorkflowStatsRowScopeService rowScopeService;
    @Mock private BusinessRowScope businessRowScope;
    @InjectMocks private WorkflowStatsController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "workflowStatsRowScopeService", rowScopeService);
        ReflectionTestUtils.setField(controller, "businessRowScope", businessRowScope);
    }

    @Test
    void productionAndFinanceUseUncachedCreatorScopedBranch() {
        when(businessRowScope.creatorIds("F-SCOPE-STATS")).thenReturn(List.of(11L));
        when(rowScopeService.getProductionStats("F-SCOPE-STATS", List.of(11L)))
                .thenReturn(WorkflowStatsDTO.builder().module("production").build());
        when(rowScopeService.getFinanceStats("F-SCOPE-STATS", List.of(11L)))
                .thenReturn(WorkflowStatsDTO.builder().module("finance").build());

        assertThat(controller.getProductionStats("F-SCOPE-STATS").getData().getModule())
                .isEqualTo("production");
        assertThat(controller.getFinanceStats("F-SCOPE-STATS").getData().getModule())
                .isEqualTo("finance");
        verify(workflowStatsService, never()).getProductionStats("F-SCOPE-STATS");
        verify(workflowStatsService, never()).getFinanceStats("F-SCOPE-STATS");
    }

    @Test
    void allScopeRetainsExistingCachedService() {
        when(businessRowScope.creatorIds("F-ALL-STATS")).thenReturn(null);
        WorkflowStatsDTO production = WorkflowStatsDTO.builder().module("production").build();
        WorkflowStatsDTO finance = WorkflowStatsDTO.builder().module("finance").build();
        when(workflowStatsService.getProductionStats("F-ALL-STATS")).thenReturn(production);
        when(workflowStatsService.getFinanceStats("F-ALL-STATS")).thenReturn(finance);

        assertThat(controller.getProductionStats("F-ALL-STATS").getData()).isSameAs(production);
        assertThat(controller.getFinanceStats("F-ALL-STATS").getData()).isSameAs(finance);
        verify(rowScopeService, never()).getProductionStats("F-ALL-STATS", null);
        verify(rowScopeService, never()).getFinanceStats("F-ALL-STATS", null);
    }

    @Test
    void missingScopedComponentRejectsInsteadOfReadingFactoryCache() {
        when(businessRowScope.creatorIds("F1")).thenReturn(List.of(11L));
        ReflectionTestUtils.setField(controller,"workflowStatsRowScopeService",null);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->controller.getProductionStats("F1"))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        error->assertThat(error.getCode()).isEqualTo(503));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->controller.getFinanceStats("F1"))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        error->assertThat(error.getCode()).isEqualTo(503));
        org.mockito.Mockito.verifyNoInteractions(workflowStatsService);
    }

    @Test
    void onlyProductionAndFinanceMethodsAreRowScopeSupported() throws Exception {
        assertThat(WorkflowStatsController.class.getMethod("getProductionStats", String.class)
                .isAnnotationPresent(RowScopeSupported.class)).isTrue();
        assertThat(WorkflowStatsController.class.getMethod("getFinanceStats", String.class)
                .isAnnotationPresent(RowScopeSupported.class)).isTrue();
        assertThat(WorkflowStatsController.class.getMethod("getSalesStats", String.class)
                .isAnnotationPresent(RowScopeSupported.class)).isFalse();
        assertThat(WorkflowStatsController.class.getMethod("getPurchaseStats", String.class)
                .isAnnotationPresent(RowScopeSupported.class)).isFalse();
        assertThat(WorkflowStatsController.class.getMethod("getInventoryStats", String.class)
                .isAnnotationPresent(RowScopeSupported.class)).isFalse();
    }
}
