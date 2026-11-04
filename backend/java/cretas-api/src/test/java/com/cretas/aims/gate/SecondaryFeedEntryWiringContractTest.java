package com.cretas.aims.gate;

import com.cretas.aims.controller.SemiFinishedInventoryController;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.production.SecondaryPlanFeedService;
import com.cretas.aims.service.production.SettlementSemiStockingService;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-S315-01/02 的入口接线: 规则本身在 {@code SecondaryReworkFeedContractTest} 里验,
 * 这里只验 web 录入、App 投料列表、web 投料下拉、整单撤回真的接上了它。
 */
class SecondaryFeedEntryWiringContractTest {

    private static final String F = "F006";
    private static final String WIP_NO = "CLK-SEMI-1";

    private SecondaryPlanFeedService feed;
    private SecondaryPlanFeedService.Source source;

    @BeforeEach
    void setUp() {
        feed = mock(SecondaryPlanFeedService.class);
        ProductionPlan plan = new ProductionPlan();
        plan.setId("P1");
        source = new SecondaryPlanFeedService.Source(plan, 403L, WIP_NO, "SEMI-1", new BigDecimal("0.6"), "kg");
    }

    @Test
    @DisplayName("web 正式录入: 超量 → 409, 不写工序行")
    void webRowOverPlanIsRejected() {
        BusinessException overPlan = new BusinessException(409, "超量").withCode("SECONDARY_SOURCE_OVER_PLAN");
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        when(planRepo.findByIdAndFactoryId("P1", F)).thenReturn(Optional.of(new ProductionPlan()));
        ProcessSheetRowRepository rowRepo = mock(ProcessSheetRowRepository.class);
        ProcessSheetServiceImpl svc = WorkflowStepUnlockWiringContractTest.construct(ProcessSheetServiceImpl.class,
                Map.of(ProductionPlanRepository.class, planRepo, ProcessSheetRowRepository.class, rowRepo));
        ReflectionTestUtils.setField(svc, "secondaryPlanFeedService", feed);
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setProcessOrder(2);
        req.setProcessCode("REWORK");
        req.setClientRowId("r1");
        req.setOutputQuantity(new BigDecimal("0.5"));
        doThrow(overPlan).when(feed).assertRowWithinPlan(F, "P1", req);

        assertThatThrownBy(() -> svc.saveRow(F, "P1", req, 7L)).isSameAs(overPlan);
        verify(rowRepo, never()).save(any());
    }

    @Test
    @DisplayName("web 录入: 来源被本计划占空不算领空; 别的批次、占用用完了都不算自己的")
    void ownHeldSourceIsNotTreatedAsDepleted() {
        ProcessSheetServiceImpl svc = WorkflowStepUnlockWiringContractTest.construct(ProcessSheetServiceImpl.class, Map.of());
        ReflectionTestUtils.setField(svc, "secondaryPlanFeedService", feed);
        when(feed.forPlanId(F, "P1")).thenReturn(Optional.of(source));
        when(feed.heldQuantity(F, source)).thenReturn(new BigDecimal("0.6"));

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(svc, "isOwnSecondarySource", F, "P1", WIP_NO)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(svc, "isOwnSecondarySource", F, "P1", "OTHER")).isFalse();

        when(feed.heldQuantity(F, source)).thenReturn(BigDecimal.ZERO);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(svc, "isOwnSecondarySource", F, "P1", WIP_NO)).isFalse();
    }

    @Test
    @DisplayName("web 投料下拉: 被占空的来源照样列出, 可领量 = 这个计划还能投多少")
    void webPickerShowsOwnSource() {
        WipInventoryService wip = mock(WipInventoryService.class);
        IntermediateOwnershipGuard ownership = mock(IntermediateOwnershipGuard.class);
        WipRowDTO depleted = WipRowDTO.builder().intermediateBatchNo(WIP_NO)
                .availableQuantity(BigDecimal.ZERO).status(SemiFinishedInventory.Status.DEPLETED).build();
        when(wip.listWipByFactory(eq(F), any(), any(), any())).thenReturn(List.of());
        when(wip.listWipByFactory(F)).thenReturn(List.of(depleted));
        when(feed.forPlanId(F, "P1")).thenReturn(Optional.of(source));
        when(feed.remainingForPlan(F, source)).thenReturn(new BigDecimal("0.6"));
        SemiFinishedInventoryController controller = new SemiFinishedInventoryController(wip, ownership, feed);

        List<WipRowDTO> rows = controller.listInventory(F, "FG-1", 2, "P1").getData();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getIntermediateBatchNo()).isEqualTo(WIP_NO);
        assertThat(rows.get(0).getAvailableQuantity()).isEqualByComparingTo("0.6");
        assertThat(rows.get(0).getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
    }

    @Test
    @DisplayName("web 投料下拉: 普通计划不多列任何行")
    void webPickerLeavesOrdinaryPlanAlone() {
        WipInventoryService wip = mock(WipInventoryService.class);
        when(wip.listWipByFactory(eq(F), any(), any(), any())).thenReturn(List.of());
        when(feed.forPlanId(F, "P1")).thenReturn(Optional.empty());
        SemiFinishedInventoryController controller =
                new SemiFinishedInventoryController(wip, mock(IntermediateOwnershipGuard.class), feed);

        assertThat(controller.listInventory(F, "FG-1", 2, "P1").getData()).isEmpty();
    }

    @Test
    @DisplayName("App 投料列表: 返工批次列出来源半成品, 可领量 = 这个计划还能投多少")
    void appPickerShowsOwnSource() {
        YieldReportServiceImpl svc = WorkflowStepUnlockWiringContractTest.construct(YieldReportServiceImpl.class,
                Map.of(SemiFinishedInventoryRepository.class, mock(SemiFinishedInventoryRepository.class)));
        ReflectionTestUtils.setField(svc, "secondaryPlanFeedService", feed);
        when(feed.forBatch(F, 500L)).thenReturn(Optional.of(source));
        when(feed.remainingForPlan(F, source)).thenReturn(new BigDecimal("0.2"));

        List<WipRowDTO> rows = svc.listWip(F, 500L);

        assertThat(rows).extracting(WipRowDTO::getIntermediateBatchNo).containsExactly(WIP_NO);
        assertThat(rows.get(0).getAvailableQuantity()).isEqualByComparingTo("0.2");
    }

    @Test
    @DisplayName("整单撤回: 结单入库的半成品退出来; 上线前的结单没有这一行, 不报错")
    void reversalTakesTheStockedSemiBackOut() {
        SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);
        WipInventoryService wip = mock(WipInventoryService.class);
        ReportReversalServiceImpl svc = WorkflowStepUnlockWiringContractTest.construct(ReportReversalServiceImpl.class,
                Map.of(SemiFinishedInventoryRepository.class, wipRepo));
        ReflectionTestUtils.setField(svc, "wipInventoryService", wip);
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId("settlement-1");
        settlement.setActualSemiFinishedQuantity(new BigDecimal("0.4"));
        String anchor = SettlementSemiStockingService.anchor("P1", "settlement-1");

        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, anchor)).thenReturn(Optional.empty());
        ReflectionTestUtils.invokeMethod(svc, "reverseSettlementSemiStocking", F, "P1", settlement, 7L);
        verify(wip, never()).reverseClerkOutput(any(), any(), any(), any(), any());

        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, anchor))
                .thenReturn(Optional.of(new SemiFinishedInventory()));
        ReflectionTestUtils.invokeMethod(svc, "reverseSettlementSemiStocking", F, "P1", settlement, 7L);
        verify(wip).reverseClerkOutput(eq(F), eq(anchor), eq(new BigDecimal("0.4")), isNull(), eq(7L));
    }
}
