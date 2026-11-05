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
    @DisplayName("/settle 的提示语跟着结果走: 预览不能说「已提交」")
    void settleMessageFollowsTheOutcome() {
        var plans = mock(com.cretas.aims.service.ProductionPlanService.class);
        var mobile = mock(com.cretas.aims.service.MobileService.class);
        when(mobile.getUserFromToken(any())).thenReturn(new com.cretas.aims.dto.user.UserDTO());
        var controller = WorkflowStepUnlockWiringContractTest.construct(
                com.cretas.aims.controller.ProductionPlanController.class,
                Map.of(com.cretas.aims.service.ProductionPlanService.class, plans,
                        com.cretas.aims.service.MobileService.class, mobile));
        var request = new com.cretas.aims.dto.production.ProductionSettlementRequest();

        when(plans.settleProduction(any(), any(), any(), any())).thenReturn(
                com.cretas.aims.dto.production.ProductionSettlementResponse.builder().settled(false).build());
        assertThat(controller.settleProduction(F, "P1", "Bearer t", request).getMessage()).contains("未写入");

        when(plans.settleProduction(any(), any(), any(), any())).thenReturn(
                com.cretas.aims.dto.production.ProductionSettlementResponse.builder().settled(true).build());
        assertThat(controller.settleProduction(F, "P1", "Bearer t", request).getMessage()).contains("已提交");
    }

    private ProductionSettlement stockedSettlement(Long fromBatch) {
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId("settlement-1");
        settlement.setActualSemiFinishedQuantity(new BigDecimal("30"));
        settlement.setSemiStockingDetail("[{\"anchor\":\"CLK-SEMI-1\",\"productionBatchId\":" + fromBatch
                + ",\"materialBatchId\":\"MB-W\",\"quantity\":30,\"storageQuantity\":30000,"
                + "\"transferredCost\":600}]");
        return settlement;
    }

    private record ReversalFixture(ReportReversalServiceImpl service, WipInventoryService wip,
                                   com.cretas.aims.entity.MaterialBatch batch) {
    }

    private ReversalFixture reversal() {
        var materialRepo = mock(com.cretas.aims.repository.MaterialBatchRepository.class);
        var batch = new com.cretas.aims.entity.MaterialBatch();
        batch.setReceiptQuantity(new BigDecimal("90000"));
        batch.setUsedQuantity(new BigDecimal("90000"));
        when(materialRepo.findByIdAndFactoryIdForUpdate("MB-W", F)).thenReturn(Optional.of(batch));
        WipInventoryService wip = mock(WipInventoryService.class);
        ReportReversalServiceImpl svc = WorkflowStepUnlockWiringContractTest.construct(
                ReportReversalServiceImpl.class, Map.of());
        ReflectionTestUtils.setField(svc, "wipInventoryService", wip);
        ReflectionTestUtils.setField(svc, "materialBatchRepository", materialRepo);
        return new ReversalFixture(svc, wip, batch);
    }

    @Test
    @DisplayName("撤回别的道: 半成品库退出来, 在制批次的已用量也按库存单位还回去, 两边都还")
    void reversalRestoresBothSides() {
        ReversalFixture fixture = reversal();

        ReflectionTestUtils.invokeMethod(fixture.service(), "reverseSettlementSemiStocking",
                F, stockedSettlement(501L), 999L, 7L);

        // 成本一起退: reverseClerkOutput 带着当时划走的金额
        verify(fixture.wip()).reverseClerkOutput(F, "CLK-SEMI-1", new BigDecimal("30"), new BigDecimal("600"), 7L);
        assertThat(fixture.batch().getUsedQuantity()).isEqualByComparingTo("60000");
    }

    @Test
    @DisplayName("撤回的就是这一道: 在制批次已被作废, 不再还")
    void reversalOfTheProducingStepSkipsTheVoidedBatch() {
        ReversalFixture fixture = reversal();

        ReflectionTestUtils.invokeMethod(fixture.service(), "reverseSettlementSemiStocking",
                F, stockedSettlement(501L), 501L, 7L);

        verify(fixture.wip()).reverseClerkOutput(F, "CLK-SEMI-1", new BigDecimal("30"), new BigDecimal("600"), 7L);
        assertThat(fixture.batch().getUsedQuantity()).isEqualByComparingTo("90000");
    }

    @Test
    @DisplayName("上线前的结单没有入库明细: 不动任何库存")
    void reversalOfOldSettlementTouchesNothing() {
        ReversalFixture fixture = reversal();
        ProductionSettlement old = new ProductionSettlement();
        old.setActualSemiFinishedQuantity(new BigDecimal("30"));

        ReflectionTestUtils.invokeMethod(fixture.service(), "reverseSettlementSemiStocking", F, old, 999L, 7L);

        verify(fixture.wip(), never()).reverseClerkOutput(any(), any(), any(), any(), any());
        assertThat(fixture.batch().getUsedQuantity()).isEqualByComparingTo("90000");
    }

    @Test
    @DisplayName("批次完工把计划结束时: 返工计划的占用还回; 普通计划不碰")
    void batchCompletionClosesTheHold() {
        var planRepo = mock(ProductionPlanRepository.class);
        var batchRepo = mock(com.cretas.aims.repository.ProductionBatchRepository.class);
        ProductionPlan plan = new ProductionPlan();
        plan.setId("P1");
        plan.setPlanSourceType("SECONDARY");
        plan.setSecondarySourceWipId(403L);
        when(planRepo.findById("P1")).thenReturn(Optional.of(plan));
        when(batchRepo.countByProductionPlanIdAndStatusNot(eq("P1"), any())).thenReturn(0L);
        var orchestrator = WorkflowStepUnlockWiringContractTest.construct(
                com.cretas.aims.service.orchestration.SupplyChainOrchestrator.class,
                Map.of(ProductionPlanRepository.class, planRepo,
                        com.cretas.aims.repository.ProductionBatchRepository.class, batchRepo));
        ReflectionTestUtils.setField(orchestrator, "secondaryPlanFeedService", feed);
        var batch = new com.cretas.aims.entity.ProductionBatch();
        batch.setFactoryId(F);
        batch.setProductionPlanId("P1");

        ReflectionTestUtils.invokeMethod(orchestrator, "updateProductionPlanProgress", batch);
        verify(feed).closeHold(eq(F), eq(plan), any());

        plan.setPlanSourceType("NORMAL");
        plan.setSecondarySourceWipId(null);
        org.mockito.Mockito.clearInvocations(feed);
        ReflectionTestUtils.invokeMethod(orchestrator, "updateProductionPlanProgress", batch);
        verify(feed, never()).closeHold(any(), any(), any());
    }

    @Test
    @DisplayName("追溯: 结单入库的半成品行能解析到来源生产批; 普通锚点行照旧解析不出")
    void stockedSemiResolvesToItsSourceBatch() {
        var resolver = WorkflowStepUnlockWiringContractTest.construct(
                com.cretas.aims.service.lineage.SemiSourceBatchResolver.class, Map.of());
        SemiFinishedInventory stocked = new SemiFinishedInventory();
        stocked.setIntermediateBatchNo("CLK-SEMI-1");
        stocked.setMaterialBatchRefs(List.of(Map.of("materialBatchId", "MB-W", "productionBatchId", 501L)));

        var resolved = resolver.resolve(F, stocked, "TEST");

        assertThat(resolved.isResolved()).isTrue();
        assertThat(resolved.productionBatchId()).isEqualTo(501L);

        SemiFinishedInventory pooled = new SemiFinishedInventory();
        pooled.setIntermediateBatchNo("CLK-SEMI-2");
        assertThat(resolver.resolve(F, pooled, "TEST").isResolved()).isFalse();
    }
}
