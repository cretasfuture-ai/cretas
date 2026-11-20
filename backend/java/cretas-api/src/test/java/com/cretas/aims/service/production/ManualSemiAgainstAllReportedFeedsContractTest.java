package com.cretas.aims.service.production;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementLaborRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R3 (2026-11-09): 结单手工半成品量要和<b>全部</b>已报比 —— 逐道录入 (结单时才扣的 + 提交时已扣的)
 * 加 App 报工过账从来源扣掉的量。此前只比结单时才扣的逐道行: App 已报 30、再手工填 30, 来源共扣 60。
 *
 * <p>真入口 {@code settleProduction} (人工补录 + 预览两条路), 仓储是桩。
 */
class ManualSemiAgainstAllReportedFeedsContractTest {

    private static final String F = "F006";
    private static final String PLAN = "PLAN-R3";
    private static final Long SEMI_ID = 7L;
    private static final String SEMI_NO = "SFI-R3";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProductionPlanServiceImpl service;
    private ProductionPlanRepository plans;
    private ProductionBatchRepository batches;
    private ProductionReportRepository reports;
    private ProcessSheetRowRepository rows;
    private SemiFinishedInventoryRepository semis;
    private SemiFinishedInventoryTransactionRepository ledger;
    private WipInventoryService wip;
    private ProductionSettlementRepository settlements;
    private SemiFinishedInventory semi;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<?> ctor = Arrays.stream(ProductionPlanServiceImpl.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes()).map(type -> (Object) mock(type)).toArray();
        service = (ProductionPlanServiceImpl) ctor.newInstance(args);
        plans = (ProductionPlanRepository) ReflectionTestUtils.getField(service, "productionPlanRepository");
        batches = (ProductionBatchRepository) ReflectionTestUtils.getField(service, "productionBatchRepository");
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.hasPermission(any(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        com.cretas.aims.repository.UserRepository users =
                (com.cretas.aims.repository.UserRepository) ReflectionTestUtils.getField(service, "userRepository");
        when(users.findById(any())).thenReturn(Optional.of(new com.cretas.aims.entity.User()));

        settlements = mock(ProductionSettlementRepository.class);
        when(settlements.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "productionSettlementRepository", settlements);
        ProductionSettlementConsumptionRepository lines = mock(ProductionSettlementConsumptionRepository.class);
        when(lines.saveAll(anyList())).thenAnswer(inv -> {
            List<com.cretas.aims.entity.ProductionSettlementConsumption> saved = inv.getArgument(0);
            long id = 1;
            for (var line : saved) {
                line.setId(id++);
            }
            return saved;
        });
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository", lines);
        ReflectionTestUtils.setField(service, "productionSettlementLaborRepository",
                mock(ProductionSettlementLaborRepository.class));
        rows = mock(ProcessSheetRowRepository.class);
        ReflectionTestUtils.setField(service, "processSheetRowRepository", rows);
        wip = mock(WipInventoryService.class);
        when(wip.resolveSemiFeedQtyInSourceUnit(eq(F), eq(SEMI_NO), any())).thenAnswer(inv -> inv.getArgument(2));
        ReflectionTestUtils.setField(service, "wipInventoryService", wip);
        ReflectionTestUtils.setField(service, "finishedGoodsFeedService", mock(FinishedGoodsFeedService.class));
        ReflectionTestUtils.setField(service, "intermediateOwnershipGuard", mock(IntermediateOwnershipGuard.class));
        semis = mock(SemiFinishedInventoryRepository.class);
        ReflectionTestUtils.setField(service, "semiFinishedInventoryRepository", semis);
        ledger = mock(SemiFinishedInventoryTransactionRepository.class);
        when(ledger.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "semiFinishedInventoryTransactionRepository", ledger);
        reports = mock(ProductionReportRepository.class);
        ReflectionTestUtils.setField(service, "productionReportRepository", reports);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN);
        plan.setFactoryId(F);
        plan.setPlanNumber("P-R3");
        plan.setProductTypeId("PT-1");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(plans.findByIdForUpdate(PLAN)).thenReturn(Optional.of(plan));
        when(plans.findByIdAndFactoryId(PLAN, F)).thenReturn(Optional.of(plan));
        when(plans.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductionBatch batch = new ProductionBatch();
        batch.setId(31L);
        batch.setFactoryId(F);
        batch.setProductionPlanId(PLAN);
        when(batches.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(new ArrayList<>(List.of(batch)));
        when(rows.findByFactoryIdAndPlanId(F, PLAN)).thenReturn(List.of());
        when(reports.findYieldReportsByBatch(F, 31L)).thenReturn(List.of());

        semi = SemiFinishedInventory.builder().id(SEMI_ID).factoryId(F).intermediateBatchNo(SEMI_NO).unit("kg")
                .producedQuantity(new BigDecimal("100")).consumedQuantity(new BigDecimal("30"))
                .availableQuantity(new BigDecimal("70")).status(SemiFinishedInventory.Status.AVAILABLE).build();
        when(semis.findByIdForUpdate(SEMI_ID)).thenReturn(Optional.of(semi));
        when(semis.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        SemiFinishedInventoryRepository.SourceRef ref = new SemiFinishedInventoryRepository.SourceRef() {
            public Long getId() { return SEMI_ID; }
            public String getFactoryId() { return F; }
            public String getIntermediateBatchNo() { return SEMI_NO; }
            public String getProductTypeId() { return "SEMI-1"; }
            public String getUnit() { return "kg"; }
        };
        when(semis.findSourceRefById(SEMI_ID)).thenReturn(Optional.of(ref));
        // 结单扣逐道投料时按 findById 认「这一批已走手工领用」(resolveManualSemiBatchNumbers)
        when(semis.findById(SEMI_ID)).thenReturn(Optional.of(semi));
    }

    /** App 在本计划批次 31 上报工, 过账时从 SFI-R3 扣掉 qty。 */
    private void appReported(String qty) {
        ProductionReport report = new ProductionReport();
        report.setId(501L);
        report.setBatchId(31L);
        when(reports.findYieldReportsByBatch(F, 31L)).thenReturn(List.of(report));
        SemiFinishedInventoryTransaction out = SemiFinishedInventoryTransaction.builder()
                .factoryId(F).semiFinishedId(SEMI_ID).reportId(501L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .quantity(new BigDecimal(qty).negate()).build();
        when(ledger.findByFactoryIdAndReportId(F, 501L)).thenReturn(List.of(out));
    }

    private void rowFeed(String kg, boolean consumedAtSubmit) throws Exception {
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber(SEMI_NO);
        ref.setFeedQuantityKg(new BigDecimal(kg));
        ref.setSemiFinished(true);
        ref.setStockConsumedAtSubmit(consumedAtSubmit);
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("r3-row");
        req.setUpstreamSources(List.of(ref));
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(41L);
        row.setFactoryId(F);
        row.setPlanId(PLAN);
        row.setBatchId(31L);
        row.setRowStatus("SAVED");
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        row.setRowPayload(JSON.writeValueAsString(req));
        when(rows.findByFactoryIdAndPlanId(F, PLAN)).thenReturn(List.of(row));
    }

    private ProductionSettlementRequest manual(String qty, boolean confirm) {
        return ProductionSettlementRequest.builder()
                .confirm(confirm).manualOverride(true).manualOverrideReason("文员补录").idempotencyKey("idem-r3")
                .actualFinishedQuantity(new BigDecimal("90")).actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(new ArrayList<>())
                .semiFinishedConsumptions(new ArrayList<>(List.of(ProductionSettlementRequest.ConsumptionLine.builder()
                        .semiFinishedInventoryId(SEMI_ID).quantity(new BigDecimal(qty)).unit("kg").build())))
                .laborDeferredReason("工时稍后补录").laborSegments(new ArrayList<>())
                .build();
    }

    private void assertNothingDeducted() {
        assertThat(semi.getConsumedQuantity()).as("来源一点没动").isEqualByComparingTo("30");
        verify(semis, never()).save(any(SemiFinishedInventory.class));
        verify(settlements, never()).save(any(ProductionSettlement.class));
    }

    @Test
    void manualEqualToWhatTheAppAlreadyPostedIsRefusedInsteadOfDeductedTwice() {
        appReported("30");

        assertThatThrownBy(() -> service.settleProduction(F, PLAN, manual("30", true), 10L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("SETTLEMENT_MANUAL_SEMI_ALREADY_POSTED");
                    assertThat(e.getMessage()).contains("App 报工 30");
                });
        assertNothingDeducted();
    }

    @Test
    void manualThatDisagreesWithTheAppLegIsAMismatchNamingBothLegs() {
        appReported("30");

        assertThatThrownBy(() -> service.settleProduction(F, PLAN, manual("20", true), 10L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("SETTLEMENT_MANUAL_SEMI_MISMATCH");
                    assertThat(e.getMessage()).contains("已报投料 30").contains("逐道录入 0").contains("App 报工 30");
                });
        assertNothingDeducted();
    }

    @Test
    void manualEqualToRowFeedsAlreadyConsumedAtSubmitIsRefused() throws Exception {
        rowFeed("10", true);

        assertThatThrownBy(() -> service.settleProduction(F, PLAN, manual("10", true), 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("SETTLEMENT_MANUAL_SEMI_ALREADY_POSTED"));
        assertNothingDeducted();
    }

    @Test
    void previewJudgesTheSameWay() {
        appReported("30");

        assertThatThrownBy(() -> service.settleProduction(F, PLAN, manual("30", false), 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("SETTLEMENT_MANUAL_SEMI_ALREADY_POSTED"));
        assertNothingDeducted();
    }

    @Test
    void manualReplacingRowFeedsThatAreStillPendingDeductsExactlyOnce() throws Exception {
        rowFeed("30", false);

        service.settleProduction(F, PLAN, manual("30", true), 10L);

        assertThat(semi.getConsumedQuantity()).as("只按手工行扣一次").isEqualByComparingTo("60");
        verify(wip, never()).consumeClerkSemiStrict(any(), any(), eq(SEMI_NO), any());
    }

    @Test
    void manualDrawOfABatchNobodyReportedIsAnExternalDraw() {
        service.settleProduction(F, PLAN, manual("15", true), 10L);

        assertThat(semi.getConsumedQuantity()).isEqualByComparingTo("45");
    }
}
