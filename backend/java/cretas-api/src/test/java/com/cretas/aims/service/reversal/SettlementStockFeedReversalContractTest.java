package com.cretas.aims.service.reversal;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementLaborRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.production.SettlementStockFeedDetail;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N1 (2026-11-09): 结单按逐道录入行扣的投料要逐笔记下, 整单撤回照着原样还回并把没撤的行重新标成待扣。
 *
 * <p>两端都走真实入口 ({@code settleProduction} / {@code executeReversal}), 仓储是桩;
 * 账在真库上是否守恒由 {@code SettlementReversalResettleLedgerPostgresContractTest} 验。
 */
class SettlementStockFeedReversalContractTest {

    private static final String F = "F006";
    private static final String PLAN = "PLAN-N1";
    private static final Long BATCH = 901L;
    private static final Long LOG = 77L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDateTime SETTLED_AT = LocalDateTime.of(2026, 11, 9, 10, 0);

    // ---------- reversal side ----------
    private final ReportReversalLogRepository reversalLogRepo = mock(ReportReversalLogRepository.class);
    private final ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
    private final ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
    private final SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);
    private final SemiFinishedInventoryTransactionRepository txnRepo = mock(SemiFinishedInventoryTransactionRepository.class);
    private final FinishedGoodsBatchRepository fgbRepo = mock(FinishedGoodsBatchRepository.class);
    private final WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
    private final ProcessSheetRowRepository rowRepo = mock(ProcessSheetRowRepository.class);
    private final MaterialBatchRepository materialBatchRepo = mock(MaterialBatchRepository.class);
    private final MaterialConsumptionRepository consumptionRepo = mock(MaterialConsumptionRepository.class);
    private final ProductionSettlementRepository settlementRepo = mock(ProductionSettlementRepository.class);
    private final ProductionSettlementConsumptionRepository settlementLineRepo =
            mock(ProductionSettlementConsumptionRepository.class);
    private final ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
    private final WipInventoryService wipService = mock(WipInventoryService.class);
    private final FinishedGoodsFeedService fgFeed = mock(FinishedGoodsFeedService.class);
    private final ReversalConsumptionCleanupService cleanup = mock(ReversalConsumptionCleanupService.class);

    private ReportReversalServiceImpl reversal;
    private ProductionSettlement settlement;
    private ProductionPlan plan;

    @BeforeEach
    void setUp() {
        reversal = new ReportReversalServiceImpl(reversalLogRepo, batchRepo, reportRepo, wipRepo, txnRepo, fgbRepo,
                mock(com.cretas.aims.repository.UserRepository.class), taskRepo, mock(NotificationService.class));
        ReflectionTestUtils.setField(reversal, "processSheetRowRepository", rowRepo);
        ReflectionTestUtils.setField(reversal, "materialBatchRepository", materialBatchRepo);
        ReflectionTestUtils.setField(reversal, "materialConsumptionRepository", consumptionRepo);
        ReflectionTestUtils.setField(reversal, "productionSettlementRepository", settlementRepo);
        ReflectionTestUtils.setField(reversal, "productionSettlementConsumptionRepository", settlementLineRepo);
        ReflectionTestUtils.setField(reversal, "productionPlanRepository", planRepo);
        ReflectionTestUtils.setField(reversal, "wipInventoryService", wipService);
        ReflectionTestUtils.setField(reversal, "finishedGoodsFeedService", fgFeed);
        ReflectionTestUtils.setField(reversal, "reversalConsumptionCleanupService", cleanup);

        ReportReversalLog log = ReportReversalLog.builder()
                .id(LOG).factoryId(F).batchId(BATCH).planId(PLAN).submittedBy(7L)
                .status(ReportReversalLog.ReversalStatus.APPROVED)
                .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER).build();
        when(reversalLogRepo.findByIdAndFactoryIdForUpdate(LOG, F)).thenReturn(Optional.of(log));
        when(reversalLogRepo.save(any(ReportReversalLog.class))).thenAnswer(inv -> inv.getArgument(0));

        plan = new ProductionPlan();
        plan.setId(PLAN);
        plan.setFactoryId(F);
        plan.setStatus(ProductionPlanStatus.COMPLETED);
        ProductionBatch batch = ProductionBatch.builder().id(BATCH).factoryId(F).productionPlanId(PLAN)
                .productTypeId("FG-1").batchNumber("CLK-B-1").quantity(BigDecimal.TEN).unit("kg")
                .status(ProductionBatchStatus.IN_PROGRESS).batchType("REGULAR").build();
        when(batchRepo.findByIdAndFactoryIdForUpdate(BATCH, F)).thenReturn(Optional.of(batch));
        when(batchRepo.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of(batch));
        when(planRepo.findByIdForUpdate(PLAN)).thenReturn(Optional.of(plan));

        settlement = new ProductionSettlement();
        settlement.setId("S-1");
        settlement.setFactoryId(F);
        settlement.setProductionPlanId(PLAN);
        settlement.setSettledAt(SETTLED_AT);
        when(settlementRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN)).thenReturn(Optional.of(settlement));
        when(settlementRepo.findByFactoryIdAndProductionPlanIdForUpdate(F, PLAN)).thenReturn(Optional.of(settlement));
        when(settlementLineRepo.findBySettlementIdAndDeletedAtIsNull("S-1")).thenReturn(List.of());

        lenient().when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(F, BATCH)).thenReturn(List.of());
        lenient().when(reportRepo.findYieldReportsByBatch(F, BATCH)).thenReturn(List.of());
        lenient().when(rowRepo.findByFactoryIdAndPlanId(F, PLAN)).thenReturn(List.of());
        lenient().when(fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN)).thenReturn(List.of());
        lenient().when(wipService.restoreClerkSemiExact(anyString(), anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> new WipInventoryService.ClerkSemiRestore(inv.getArgument(2), 1L));
    }

    private static ProcessSheetRow stampedRow(Long id, ProcessSheetRowRequest.UpstreamRef... feeds) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("row-" + id);
        req.setUpstreamSources(new ArrayList<>(List.of(feeds)));
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(F);
        row.setPlanId(PLAN);
        row.setBatchId(500L + id);
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        row.setInterimSettledAt(SETTLED_AT);
        try {
            row.setRowPayload(JSON.writeValueAsString(req));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return row;
    }

    private static ProcessSheetRowRequest.UpstreamRef ref(String batchNo, String kg, boolean semi, boolean fg) {
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber(batchNo);
        ref.setFeedQuantityKg(new BigDecimal(kg));
        ref.setSemiFinished(semi);
        ref.setFinishedGoods(fg);
        return ref;
    }

    @Test
    void reversalRestoresExactlyWhatTheSettlementDeductedAndMarksSurvivingRowsPendingAgain() {
        // 结单当时: 半成品 30kg 折成 3 盒扣掉, 成品 20 —— 撤回按当时扣的 3 盒还, ⛔ 不按 30kg 重折
        settlement.setStockFeedDetail(SettlementStockFeedDetail.write(List.of(
                new SettlementStockFeedDetail.Entry(SettlementStockFeedDetail.KIND_SEMI, "SRC", new BigDecimal("30"),
                        new BigDecimal("3"), 11L),
                new SettlementStockFeedDetail.Entry(SettlementStockFeedDetail.KIND_FINISHED, "FGX", new BigDecimal("20"),
                        new BigDecimal("20"), 12L))));
        ProcessSheetRow survivor = stampedRow(11L, ref("SRC", "30", true, false));
        when(rowRepo.findByFactoryIdAndPlanIdAndInterimSettledAt(F, PLAN, SETTLED_AT)).thenReturn(List.of(survivor));
        MaterialConsumption stampedConsumption = new MaterialConsumption();
        stampedConsumption.setInterimSettledAt(SETTLED_AT);
        when(consumptionRepo.findByFactoryIdAndProductionBatchIdInAndInterimSettledAt(eq(F), anyList(), eq(SETTLED_AT)))
                .thenReturn(List.of(stampedConsumption));

        reversal.executeReversal(LOG, F);

        verify(wipService).restoreClerkSemiExact(F, "SRC", new BigDecimal("3"), "reversal-log:" + LOG, 7L);
        verify(wipService, never()).restoreClerkSemiStrict(any(), any(), any(), any(), any());
        verify(fgFeed).restoreForFeed(F, "FGX", new BigDecimal("20"), 7L);
        assertThat(survivor.getInterimSettledAt()).as("没撤的那道重新待扣").isNull();
        assertThat(stampedConsumption.getInterimSettledAt()).as("报工消耗重新待扣").isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> batchIds = ArgumentCaptor.forClass(List.class);
        verify(consumptionRepo).findByFactoryIdAndProductionBatchIdInAndInterimSettledAt(eq(F), batchIds.capture(),
                eq(SETTLED_AT));
        assertThat(batchIds.getValue()).as("被撤那一道的报工消耗也在清戳范围").contains(BATCH, 511L);
        verify(cleanup, atLeastOnce()).softDeleteUnpostedForReversedBatch(eq(F), eq(BATCH), eq(PLAN), any());
        assertThat(settlement.getDeletedAt()).isNotNull();
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.IN_PROGRESS);
    }

    @Test
    void legacySettlementThatDeductedFeedsWithoutDetailIsRefusedBeforeAnyChange() {
        settlement.setStockFeedDetail(null);
        ProcessSheetRow stamped = stampedRow(11L, ref("SRC", "30", true, false));
        when(rowRepo.findByFactoryIdAndPlanIdAndInterimSettledAt(F, PLAN, SETTLED_AT)).thenReturn(List.of(stamped));

        assertThatThrownBy(() -> reversal.executeReversal(LOG, F))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("SETTLEMENT_STOCK_FEED_DETAIL_MISSING");
                    assertThat(e.getMessage()).contains("SRC×30kg");
                });

        verify(wipService, never()).restoreClerkSemiExact(any(), any(), any(), any(), any());
        verify(rowRepo, never()).saveAll(anyList());
        verify(settlementRepo, never()).save(any());
        assertThat(stamped.getInterimSettledAt()).isEqualTo(SETTLED_AT);
        assertThat(settlement.getDeletedAt()).isNull();
    }

    @Test
    void legacySettlementWhoseRowsOnlyHadSubmitTimeOrManualFeedsStillReverses() {
        settlement.setStockFeedDetail(null);
        ProcessSheetRowRequest.UpstreamRef atSubmit = ref("REWORK", "5", true, false);
        atSubmit.setStockConsumedAtSubmit(true);
        when(rowRepo.findByFactoryIdAndPlanIdAndInterimSettledAt(F, PLAN, SETTLED_AT))
                .thenReturn(List.of(stampedRow(11L, atSubmit)));

        reversal.executeReversal(LOG, F);

        verify(wipService, never()).restoreClerkSemiExact(any(), any(), any(), any(), any());
        assertThat(settlement.getDeletedAt()).as("旧结单没扣过投料, 照常撤回").isNotNull();
    }

    @Test
    void finishedGoodsFeedWithoutItsServiceFailsClosedBeforeAnyChange() {
        settlement.setStockFeedDetail(SettlementStockFeedDetail.write(List.of(
                new SettlementStockFeedDetail.Entry(SettlementStockFeedDetail.KIND_FINISHED, "FGX", new BigDecimal("20"),
                        new BigDecimal("20"), 12L))));
        ReflectionTestUtils.setField(reversal, "finishedGoodsFeedService", null);

        assertThatThrownBy(() -> reversal.executeReversal(LOG, F))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("REVERSAL_SETTLEMENT_DEPENDENCY_MISSING"));
        assertThat(settlement.getDeletedAt()).isNull();
    }

    // ---------- settlement side ----------

    @Test
    void settlementRecordsTheQuantityActuallyDrawnPerFeed() throws Exception {
        ProductionPlanServiceImpl service = planService();
        ProductionPlanRepository plans = (ProductionPlanRepository) ReflectionTestUtils.getField(service, "productionPlanRepository");
        ProductionPlan open = new ProductionPlan();
        open.setId(PLAN);
        open.setFactoryId(F);
        open.setPlanNumber("P-N1");
        open.setProductTypeId("PT-1");
        open.setPlannedQuantity(new BigDecimal("100"));
        open.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(plans.findByIdForUpdate(PLAN)).thenReturn(Optional.of(open));
        when(plans.findByIdAndFactoryId(PLAN, F)).thenReturn(Optional.of(open));
        when(plans.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        ProcessSheetRow semiRow = stampedRow(11L, ref("SRC", "30", true, false));
        semiRow.setInterimSettledAt(null);
        semiRow.setRowStatus("SAVED");
        ProcessSheetRow fgRow = stampedRow(12L, ref("FGX", "20", false, true));
        fgRow.setInterimSettledAt(null);
        fgRow.setRowStatus("SAVED");
        ProcessSheetRowRepository rows = (ProcessSheetRowRepository) ReflectionTestUtils.getField(service, "processSheetRowRepository");
        when(rows.findByFactoryIdAndPlanId(F, PLAN)).thenReturn(List.of(semiRow, fgRow));
        WipInventoryService wip = (WipInventoryService) ReflectionTestUtils.getField(service, "wipInventoryService");
        // 盒装来源: 30kg 折成 3 盒
        when(wip.consumeClerkSemiStrict(eq(F), any(), eq("SRC"), eq(new BigDecimal("30")))).thenReturn(new BigDecimal("3"));
        FinishedGoodsFeedService feed = (FinishedGoodsFeedService) ReflectionTestUtils.getField(service, "finishedGoodsFeedService");
        when(feed.consumeForFeedStrict(eq(F), any(), eq("FGX"), eq(new BigDecimal("20")), eq("kg")))
                .thenReturn(new BigDecimal("20"));

        service.settleProduction(F, PLAN, ProductionSettlementRequest.builder()
                .confirm(true).manualOverride(true).manualOverrideReason("测试人工补录").idempotencyKey("idem-n1")
                .actualFinishedQuantity(new BigDecimal("90")).actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(new ArrayList<>()).semiFinishedConsumptions(new ArrayList<>())
                .materialVarianceReason("无差异").laborDeferredReason("工时稍后补录").laborSegments(new ArrayList<>())
                .build(), 10L);

        ProductionSettlementRepository settlements =
                (ProductionSettlementRepository) ReflectionTestUtils.getField(service, "productionSettlementRepository");
        ArgumentCaptor<ProductionSettlement> saved = ArgumentCaptor.forClass(ProductionSettlement.class);
        verify(settlements, atLeastOnce()).save(saved.capture());
        List<SettlementStockFeedDetail.Entry> entries =
                SettlementStockFeedDetail.parse(saved.getValue().getStockFeedDetail());
        assertThat(entries).containsExactly(
                new SettlementStockFeedDetail.Entry(SettlementStockFeedDetail.KIND_SEMI, "SRC", new BigDecimal("30"),
                        new BigDecimal("3"), 11L),
                new SettlementStockFeedDetail.Entry(SettlementStockFeedDetail.KIND_FINISHED, "FGX", new BigDecimal("20"),
                        new BigDecimal("20"), 12L));
    }

    @Test
    void settlementWithoutFeedsStillWritesAnEmptyDetailSoReversalCanTellItApartFromLegacy() throws Exception {
        ProductionPlanServiceImpl service = planService();
        ProductionPlanRepository plans = (ProductionPlanRepository) ReflectionTestUtils.getField(service, "productionPlanRepository");
        ProductionPlan open = new ProductionPlan();
        open.setId(PLAN);
        open.setFactoryId(F);
        open.setPlanNumber("P-N1");
        open.setProductTypeId("PT-1");
        open.setPlannedQuantity(new BigDecimal("100"));
        open.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(plans.findByIdForUpdate(PLAN)).thenReturn(Optional.of(open));
        when(plans.findByIdAndFactoryId(PLAN, F)).thenReturn(Optional.of(open));
        when(plans.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.settleProduction(F, PLAN, ProductionSettlementRequest.builder()
                .confirm(true).manualOverride(true).manualOverrideReason("测试人工补录").idempotencyKey("idem-n1b")
                .actualFinishedQuantity(new BigDecimal("90")).actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(new ArrayList<>()).semiFinishedConsumptions(new ArrayList<>())
                .materialVarianceReason("无差异").laborDeferredReason("工时稍后补录").laborSegments(new ArrayList<>())
                .build(), 10L);

        ProductionSettlementRepository settlements =
                (ProductionSettlementRepository) ReflectionTestUtils.getField(service, "productionSettlementRepository");
        ArgumentCaptor<ProductionSettlement> saved = ArgumentCaptor.forClass(ProductionSettlement.class);
        verify(settlements, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getStockFeedDetail()).isEqualTo("[]");
    }

    /** 真的 ProductionPlanServiceImpl, 所有依赖是桩 (与 ProductionPlanSettlementTest 的 R2 接线同一形状)。 */
    private static ProductionPlanServiceImpl planService() throws Exception {
        Constructor<?> ctor = Arrays.stream(ProductionPlanServiceImpl.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes()).map(type -> (Object) mock(type)).toArray();
        ProductionPlanServiceImpl service = (ProductionPlanServiceImpl) ctor.newInstance(args);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.hasPermission(any(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        com.cretas.aims.repository.UserRepository users =
                (com.cretas.aims.repository.UserRepository) ReflectionTestUtils.getField(service, "userRepository");
        when(users.findById(any())).thenReturn(Optional.of(new com.cretas.aims.entity.User()));
        ProductionSettlementRepository settlements = mock(ProductionSettlementRepository.class);
        when(settlements.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "productionSettlementRepository", settlements);
        ProductionSettlementConsumptionRepository lines = mock(ProductionSettlementConsumptionRepository.class);
        when(lines.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository", lines);
        ProductionSettlementLaborRepository labor = mock(ProductionSettlementLaborRepository.class);
        ReflectionTestUtils.setField(service, "productionSettlementLaborRepository", labor);
        ReflectionTestUtils.setField(service, "processSheetRowRepository", mock(ProcessSheetRowRepository.class));
        ReflectionTestUtils.setField(service, "wipInventoryService", mock(WipInventoryService.class));
        ReflectionTestUtils.setField(service, "finishedGoodsFeedService", mock(FinishedGoodsFeedService.class));
        ReflectionTestUtils.setField(service, "intermediateOwnershipGuard", mock(IntermediateOwnershipGuard.class));
        ReflectionTestUtils.setField(service, "semiFinishedInventoryRepository", mock(SemiFinishedInventoryRepository.class));
        ProductionBatchRepository batches =
                (ProductionBatchRepository) ReflectionTestUtils.getField(service, "productionBatchRepository");
        when(batches.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(new ArrayList<>());
        lenient().when(batches.findByFactoryIdAndProductionPlanId(anyString(), anyString())).thenReturn(new ArrayList<>());
        lenient().when(batches.findById(anyLong())).thenReturn(Optional.empty());
        return service;
    }
}
