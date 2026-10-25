package com.cretas.aims.service.production;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionInterimSettlement;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * G3b 停产单元测试 — 验证存货生产 (SAFETY_STOCK) 计划停产是纯状态关闭:
 * 无 BatchCompletedEvent 发布、无批次查询、无物料扣减。
 */
@DisplayName("ProductionPlan 停产 (G3b 存货生产纯状态关闭)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanStopProductionTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID   = "PP-STOP-1";

    @Mock private ProductionPlanRepository        productionPlanRepository;
    @Mock private ProductionBatchRepository        productionBatchRepository;
    @Mock private MaterialBatchRepository          materialBatchRepository;
    @Mock private MaterialConsumptionRepository    materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository            productTypeRepository;
    @Mock private ProductionPlanMapper             productionPlanMapper;
    @Mock private ConversionRepository             conversionRepository;
    @Mock private SchedulingService                schedulingService;
    @Mock private ProductionLineRepository         productionLineRepository;
    @Mock private UserRepository                   userRepository;
    @Mock private ExcelUtil                        excelUtil;
    @Mock private SalesOrderRepository             salesOrderRepository;
    @Mock private SalesOrderItemRepository         salesOrderItemRepository;
    @Mock private BomService                       bomService;
    @Mock private ApplicationEventPublisher        applicationEventPublisher;
    @Mock private ProcessSheetRowRepository        processSheetRowRepository;
    @Mock private ProductionInterimSettlementRepository productionInterimSettlementRepository;
    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
        ReflectionTestUtils.setField(service, "processSheetRowRepository", processSheetRowRepository);
        ReflectionTestUtils.setField(service, "productionInterimSettlementRepository", productionInterimSettlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
        lenient().when(productionPlanRepository.findByIdForUpdate(anyString()))
                .thenAnswer(inv -> productionPlanRepository.findByIdAndFactoryId(inv.getArgument(0), FACTORY_ID));
    }

    // ─── 存货多成品分摊比例: 取分支成本分解, 不取小结单位成本 (F006【演示L4】8d77ec57, 2026-10-25) ───

    private FinishedGoodsBatch fgOf(String id, String sku, String qty, String unitCost) {
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId(id);
        fg.setProductTypeId(sku);
        fg.setProducedQuantity(new BigDecimal(qty));
        fg.setUnitCost(new BigDecimal(unitCost));
        return fg;
    }

    private com.cretas.aims.entity.ProductionBatch branchBatch(String sku, String batchNumber) {
        com.cretas.aims.entity.ProductionBatch b = new com.cretas.aims.entity.ProductionBatch();
        b.setProductTypeId(sku);
        b.setBatchNumber(batchNumber);
        return b;
    }

    private com.cretas.aims.dto.yield.OrderCostBreakdownDTO breakdownOf(String total) {
        com.cretas.aims.dto.yield.OrderCostBreakdownDTO d = new com.cretas.aims.dto.yield.OrderCostBreakdownDTO();
        d.setCostComplete(true);
        d.setTotalCost(new BigDecimal(total));
        return d;
    }

    @Test
    @DisplayName("存货多成品比例取各成品分支成本分解 (含上游半成品投料): 鸡胸 1835.30 / 鸡翅 1316.02 / 鸡腿 1998.36, "
            + "⛔ 不取小结单位成本 (鸡胸小结只有 692.46, 漏了半成品投料 1142.90)")
    void byStockRatiosUseBranchBreakdownNotInterimUnitCost() {
        com.cretas.aims.service.yield.OrderCostBreakdownService breakdowns =
                org.mockito.Mockito.mock(com.cretas.aims.service.yield.OrderCostBreakdownService.class);
        ReflectionTestUtils.setField(service, "orderCostBreakdownService", breakdowns);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, "PLAN-L4")).thenReturn(List.of(
                branchBatch("SKU-BREAST", "CLK-B-BREAST"), branchBatch("SKU-WING", "CLK-B-WING"),
                branchBatch("SKU-LEG", "CLK-B-LEG")));
        when(breakdowns.computeByBatch(FACTORY_ID, "CLK-B-BREAST", false)).thenReturn(breakdownOf("1835.30"));
        when(breakdowns.computeByBatch(FACTORY_ID, "CLK-B-WING", false)).thenReturn(breakdownOf("1316.02"));
        when(breakdowns.computeByBatch(FACTORY_ID, "CLK-B-LEG", false)).thenReturn(breakdownOf("1998.36"));
        List<FinishedGoodsBatch> fgs = List.of(fgOf("FG-BREAST", "SKU-BREAST", "238", "2.9095"),
                fgOf("FG-WING", "SKU-WING", "110", "5.9018"), fgOf("FG-LEG", "SKU-LEG", "205", "9.7492"));

        java.util.Map<String, BigDecimal> ratios = ReflectionTestUtils.invokeMethod(
                service, "byStockBranchCostRatios", FACTORY_ID, "PLAN-L4", fgs);

        // 1835.30 / 5149.68 = 35.6391%
        org.assertj.core.api.Assertions.assertThat(ratios.get("FG-BREAST")).isEqualByComparingTo("35.639108");
        org.assertj.core.api.Assertions.assertThat(ratios.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isCloseTo(new BigDecimal("100"), org.assertj.core.data.Offset.offset(new BigDecimal("0.00001")));
    }

    @Test
    @DisplayName("分支成本拿不全 (某成品成本未知) → 退回小结单位成本口径, 两种来源不混用")
    void byStockRatiosFallBackToInterimUnitCostWhenAnyBranchIsUnknown() {
        com.cretas.aims.service.yield.OrderCostBreakdownService breakdowns =
                org.mockito.Mockito.mock(com.cretas.aims.service.yield.OrderCostBreakdownService.class);
        ReflectionTestUtils.setField(service, "orderCostBreakdownService", breakdowns);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, "PLAN-L4")).thenReturn(List.of(
                branchBatch("SKU-BREAST", "CLK-B-BREAST"), branchBatch("SKU-LEG", "CLK-B-LEG")));
        when(breakdowns.computeByBatch(FACTORY_ID, "CLK-B-BREAST", false)).thenReturn(breakdownOf("1835.30"));
        com.cretas.aims.dto.yield.OrderCostBreakdownDTO unknown = breakdownOf("0");
        unknown.setCostComplete(false);
        unknown.setTotalCost(null);
        when(breakdowns.computeByBatch(FACTORY_ID, "CLK-B-LEG", false)).thenReturn(unknown);
        List<FinishedGoodsBatch> fgs = List.of(fgOf("FG-BREAST", "SKU-BREAST", "238", "2.9095"),
                fgOf("FG-LEG", "SKU-LEG", "205", "9.7492"));

        java.util.Map<String, BigDecimal> ratios = ReflectionTestUtils.invokeMethod(
                service, "byStockBranchCostRatios", FACTORY_ID, "PLAN-L4", fgs);

        // 692.461 / (692.461 + 1998.586) = 25.7319%
        org.assertj.core.api.Assertions.assertThat(ratios.get("FG-BREAST")).isEqualByComparingTo(
                new BigDecimal("692.4610").multiply(new BigDecimal("100"))
                        .divide(new BigDecimal("692.4610").add(new BigDecimal("1998.5860")), 6, java.math.RoundingMode.HALF_UP));
    }

    private com.cretas.aims.entity.processentry.ProcessSheetRow memberRow(Long batchId, String ratio) {
        com.cretas.aims.entity.processentry.ProcessSheetRow row = new com.cretas.aims.entity.processentry.ProcessSheetRow();
        row.setFactoryId(FACTORY_ID);
        row.setPlanId("PLAN-L5");
        row.setBatchId(batchId);
        row.setProcessCode("zuzhuang");
        row.setProcessOrder(4);
        row.setSubmissionStatus(com.cretas.aims.entity.processentry.ProcessSheetRow.SUBMISSION_SUBMITTED);
        row.setRowPayload("{\"multiOutputMember\":true,\"multiOutputBaseRowId\":\"g4\",\"costAllocationRatio\":" + ratio + "}");
        return row;
    }

    @Test
    @DisplayName("末道多产出且产出都是成品批 (L5 组装 4→2): 投入只记在单层批上 ⇒ 单层/双层 = 整组联合成本 8302.05 × 报工比例 "
            + "49.090909% / 50.909091%; 不在组里的牛肉饼照旧取自己那道批")
    void finalMultiOutputStepSplitsTheGroupJointCostByTheReportedRatios() {
        com.cretas.aims.service.yield.OrderCostBreakdownService breakdowns =
                org.mockito.Mockito.mock(com.cretas.aims.service.yield.OrderCostBreakdownService.class);
        ReflectionTestUtils.setField(service, "orderCostBreakdownService", breakdowns);
        com.cretas.aims.entity.ProductionBatch single = branchBatch("SKU-SINGLE", "CLK-B-SINGLE");
        single.setId(10989L);
        com.cretas.aims.entity.ProductionBatch dbl = branchBatch("SKU-DOUBLE", "CLK-B-DOUBLE");
        dbl.setId(10990L);
        com.cretas.aims.entity.ProductionBatch patty = branchBatch("SKU-PATTY", "CLK-B-PATTY");
        patty.setId(10991L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, "PLAN-L5"))
                .thenReturn(List.of(single, dbl, patty));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, "PLAN-L5"))
                .thenReturn(List.of(memberRow(10989L, "49.090909"), memberRow(10990L, "50.909091")));
        when(breakdowns.computeByBatchIds(eq(FACTORY_ID), any(), eq(List.of(10989L, 10990L)), eq(false)))
                .thenReturn(breakdownOf("8302.05"));
        when(breakdowns.computeByBatch(FACTORY_ID, "CLK-B-PATTY", false)).thenReturn(breakdownOf("875.73"));
        // 若按批单算: 单层批吃掉全部, 双层批算不出 —— 这两个桩不该被用到
        when(breakdowns.computeByBatch(FACTORY_ID, "CLK-B-SINGLE", false)).thenReturn(breakdownOf("8302.05"));
        List<FinishedGoodsBatch> fgs = List.of(fgOf("FG-SINGLE", "SKU-SINGLE", "600", "7.4549"),
                fgOf("FG-DOUBLE", "SKU-DOUBLE", "400", "9.3118"), fgOf("FG-PATTY", "SKU-PATTY", "83", "10.5509"));

        java.util.Map<String, BigDecimal> ratios = ReflectionTestUtils.invokeMethod(
                service, "byStockBranchCostRatios", FACTORY_ID, "PLAN-L5", fgs);

        // 单层 8302.05×49.090909% = 4075.552 ; 双层 4226.498 ; 牛肉饼 875.73 ; 合计 9177.78
        BigDecimal total = new BigDecimal("9177.78");
        org.assertj.core.api.Assertions.assertThat(ratios.get("FG-SINGLE")).isCloseTo(
                new BigDecimal("4075.552").multiply(new BigDecimal("100")).divide(total, 6, java.math.RoundingMode.HALF_UP),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.0001")));
        org.assertj.core.api.Assertions.assertThat(ratios.get("FG-DOUBLE")).isCloseTo(
                new BigDecimal("4226.498").multiply(new BigDecimal("100")).divide(total, 6, java.math.RoundingMode.HALF_UP),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.0001")));
        org.mockito.Mockito.verify(breakdowns, org.mockito.Mockito.never()).computeByBatch(FACTORY_ID, "CLK-B-SINGLE", false);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ProductionPlan byStockPlan() {
        ProductionPlan p = new ProductionPlan();
        p.setId(PLAN_ID);
        p.setFactoryId(FACTORY_ID);
        p.setPlanNumber("PN-STOP-1");
        p.setProductTypeId("PT-1");
        p.setPlannedQuantity(new BigDecimal("1000"));
        p.setStatus(ProductionPlanStatus.IN_PROGRESS);
        p.setSourceType(PlanSourceType.SAFETY_STOCK);
        p.setCreatedBy(1L);
        return p;
    }

    private void stubSettledSubmittedActivity() {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setBatchId(777L);
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        row.setInterimSettledAt(LocalDateTime.now().minusMinutes(1));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(row));
        when(materialConsumptionRepository
                .findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(eq(FACTORY_ID), anyList()))
                .thenReturn(Collections.emptyList());
    }

    private ProductionInterimSettlement interimSettlement(String id, int sequence,
                                                           String batchNumber, String quantity) {
        return ProductionInterimSettlement.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .productionPlanId(PLAN_ID)
                .sessionSeq(sequence)
                .postedAt(LocalDateTime.of(2026, 8, 9, 10, sequence))
                .postedBy(10L)
                .summary(Map.of(
                        "finishedQuantity", new BigDecimal(quantity),
                        "finishedGoodsBatchNumbers", List.of(batchNumber)))
                .build();
    }

    private FinishedGoodsBatch finishedGoods(String id, String batchNumber, String quantity,
                                              String unit, String status,
                                              InventoryOwnership ownership, String ownerCustomerId) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY_ID);
        batch.setProductionPlanId(PLAN_ID);
        batch.setBatchNumber(batchNumber);
        batch.setProductTypeId("PT-1");
        batch.setProducedQuantity(new BigDecimal(quantity));
        batch.setUnit(unit);
        batch.setStatus(status);
        batch.setOwnership(ownership);
        batch.setOwnerCustomerId(ownerCustomerId);
        return batch;
    }

    private void stubInterimBridge(ProductionPlan plan,
                                   List<ProductionInterimSettlement> sessions,
                                   FinishedGoodsBatch... batches) {
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSettledSubmittedActivity();
        when(productionInterimSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(FACTORY_ID, PLAN_ID))
                .thenReturn(sessions);
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        for (FinishedGoodsBatch batch : batches) {
            when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, batch.getBatchNumber()))
                    .thenReturn(Optional.of(batch));
        }
    }

    // ─── tests ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("存货生产 (SAFETY_STOCK) 计划停产 → COMPLETED, endTime 已设, repo.save 被调用")
    void stopProduction_byStock_setsCompletedAndSaves() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSettledSubmittedActivity();

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus(), "状态应为 COMPLETED");
        assertNotNull(plan.getEndTime(), "endTime 必须被设置");
        verify(productionPlanRepository).save(plan);
    }

    @Test
    @DisplayName("停产不发布任何事件 — 确认无 BatchCompletedEvent 双重扣减")
    void stopProduction_publishesNoEvent() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(1));

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSettledSubmittedActivity();

        service.stopProduction(FACTORY_ID, PLAN_ID);

        // 关键: 没有发布任何事件 (BatchCompletedEvent / ProductionCompletedEvent)
        verify(applicationEventPublisher, never()).publishEvent(any());
        // 关键: 没有查询批次 (completeProduction 才会查 findByFactoryIdAndProductionPlanId)
        verify(productionBatchRepository, never()).findByFactoryIdAndProductionPlanId(any(), any());
    }

    @Test
    @DisplayName("startTime 为 null 时停产不伪造开工时间")
    void stopProduction_noStartTime_keepsStartTimeNull() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(null);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSettledSubmittedActivity();

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertNull(plan.getStartTime(), "停产不能伪造实际开工时间");
        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
    }

    @Test
    @DisplayName("非存货生产 (非 SAFETY_STOCK) 计划停产 → BusinessException(400)")
    void stopProduction_byOrder_throws400() {
        ProductionPlan plan = byStockPlan();
        plan.setSourceType(PlanSourceType.CUSTOMER_ORDER);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals(400, ex.getCode(), "非库存业态应抛 400");
        assertTrue(ex.getMessage().contains("仅存货生产计划可停产"));
        verify(productionPlanRepository, never()).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("🔒🔒 Gap2b: 有未结报工消耗时停产 → 409 拒绝 (停产零扣减, 若放行则残料永不扣减=幻库存)")
    void stopProduction_withUnsettledConsumption_blocked() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        ProcessSheetRow row = new ProcessSheetRow();
        row.setBatchId(555L);   // 已物化的 per-道 ProductionBatch id → 纳入 unsettled 检测
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(row));
        // 存在未结算 (interimSettledAt IS NULL) 报工消耗 → 停产必须拦截
        when(materialConsumptionRepository
                .findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(eq(FACTORY_ID), anyList()))
                .thenReturn(List.of(new MaterialConsumption()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals(409, ex.getCode(), "有未结报工消耗应 409 拒绝停产");
        assertEquals("STOP_BLOCKED_UNSETTLED_CONSUMPTION", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("请先小结再停产"));
        // 关键: 状态未翻转, 未保存 (幻库存被前置守卫堵死)
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus(), "状态不应变 COMPLETED");
        verify(productionPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴🔒🔒 BUG3: 中段起步纯 SFI 投料 (SAVED_SFI, batchId=null, 零 MaterialConsumption) 停产 → 409 拒绝 (消耗守卫盲区, 已投 SFI 会成幻库存)")
    void stopProduction_midStartPureSfi_noConsumption_blocked() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        // 纯 SFI 中间道: batchId=null (不物化 WIP), batchNumber=SFI 锚, 未结, 无 MaterialConsumption。
        // 现有消耗守卫按 batchId 定位 → 此行被 filter(id!=null) 排除 → 零消耗 → 旧逻辑放行 (BUG3)。
        ProcessSheetRow sfiRow = new ProcessSheetRow();
        sfiRow.setBatchId(null);
        sfiRow.setBatchNumber("SFI-MIDSTART-1");
        sfiRow.setRowStatus(ProcessSheetRow.STATUS_SAVED_SFI);
        sfiRow.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        sfiRow.setInterimSettledAt(null);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(sfiRow));
        // batchId 全 null → findUnsettledPlanConsumptions 短路返回空 (repo 不被调用) → 消耗守卫放行

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals(409, ex.getCode(), "中段起步 SFI 投料未结应 409 拒绝停产");
        assertEquals("STOP_BLOCKED_UNSETTLED_CONSUMPTION", ex.getErrorCode());
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus(), "状态不应变 COMPLETED");
        verify(productionPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("Gap2b 反向: 报工消耗已全部小结 (无未结) → 正常停产 → COMPLETED (不误伤干净停产)")
    void stopProduction_allConsumptionSettled_completes() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        ProcessSheetRow row = new ProcessSheetRow();
        row.setBatchId(777L);
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        row.setInterimSettledAt(LocalDateTime.now().minusMinutes(1));

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(row));
        // 无未结消耗 (全部已小结) → 停产放行
        when(materialConsumptionRepository
                .findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(eq(FACTORY_ID), anyList()))
                .thenReturn(Collections.emptyList());
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        verify(productionPlanRepository).save(plan);
    }

    @Test
    @DisplayName("BY_STOCK 小结后停产在同一事务补建待仓库确认元数据且不重复创建 FG")
    void stopProduction_afterInterimSettlement_bridgesWarehouseMetadataOnly() {
        ProductionPlan plan = byStockPlan();
        plan.setPlannedQuantity(new BigDecimal("5"));
        plan.setStartTime(LocalDateTime.now().minusHours(2));
        ProcessSheetRow row = new ProcessSheetRow();
        row.setBatchId(777L);
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        row.setInterimSettledAt(LocalDateTime.of(2026, 7, 20, 5, 32));
        ProductionInterimSettlement interim = ProductionInterimSettlement.builder()
                .id("interim-1")
                .factoryId(FACTORY_ID)
                .productionPlanId(PLAN_ID)
                .sessionSeq(1)
                .postedAt(LocalDateTime.of(2026, 7, 20, 5, 32))
                .postedBy(10L)
                .summary(Map.of(
                        "finishedQuantity", new BigDecimal("5"),
                        "finishedGoodsBatchNumbers", List.of("FG-PN-STOP-1-S1-PT-1")))
                .build();
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId("fg-1");
        fg.setFactoryId(FACTORY_ID);
        fg.setProductionPlanId(PLAN_ID);
        fg.setBatchNumber("FG-PN-STOP-1-S1-PT-1");
        fg.setProductTypeId("PT-1");
        fg.setProducedQuantity(new BigDecimal("5"));
        fg.setUnit("box");

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID)).thenReturn(List.of(row));
        when(materialConsumptionRepository
                .findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(eq(FACTORY_ID), anyList()))
                .thenReturn(Collections.emptyList());
        when(productionInterimSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(interim));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(
                FACTORY_ID, "FG-PN-STOP-1-S1-PT-1")).thenReturn(Optional.of(fg));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        verify(productionSettlementRepository).save(argThat(settlement ->
                "POSTED".equals(settlement.getPostingStatus())
                        && "fg-1".equals(settlement.getFinishedGoodsBatchId())
                        && new BigDecimal("5").compareTo(settlement.getActualFinishedQuantity()) == 0));
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("客户归属库存生产多次小结形成多个 FG → 汇总结单且不伪造单一批次、不重复入库")
    void stopProduction_afterMultipleInterimSettlements_aggregatesAllFinishedGoods() {
        ProductionPlan plan = byStockPlan();
        plan.setPlannedQuantity(new BigDecimal("5"));
        plan.setStartTime(LocalDateTime.now().minusHours(2));
        plan.setOutputOwnership(InventoryOwnership.CUSTOMER_OWNED);
        plan.setCustomerId("customer-1");

        ProductionInterimSettlement first = interimSettlement("interim-1", 1, "FG-S1", "2");
        ProductionInterimSettlement second = interimSettlement("interim-2", 2, "FG-S2", "3");
        FinishedGoodsBatch firstBatch = finishedGoods(
                "fg-1", "FG-S1", "2", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.CUSTOMER_OWNED, "customer-1");
        FinishedGoodsBatch secondBatch = finishedGoods(
                "fg-2", "FG-S2", "3", "box", FinishedGoodsBatch.Status.DEPLETED,
                InventoryOwnership.CUSTOMER_OWNED, "customer-1");
        stubInterimBridge(plan, List.of(first, second), firstBatch, secondBatch);

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        verify(productionSettlementRepository).save(argThat(settlement ->
                "POSTED".equals(settlement.getPostingStatus())
                        && settlement.getFinishedGoodsBatchId() == null
                        && new BigDecimal("5").compareTo(settlement.getActualFinishedQuantity()) == 0
                        && new BigDecimal("5").compareTo(settlement.getWarehouseReceivedQuantity()) == 0
                        && "box".equals(settlement.getQuantityUnit())
                        && settlement.getPostingMessage().contains("共 2 个成品批次")));
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(materialBatchRepository, never()).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("撤销后二次小结已真实过账的 DRAFT 行允许停产，且不重放库存")
    void stopProduction_afterInterimReversalAndRepost_acceptsRowsStampedByActiveSessions() {
        ProductionPlan plan = byStockPlan();
        plan.setPlannedQuantity(new BigDecimal("2"));
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        ProductionInterimSettlement first = interimSettlement("interim-1", 1, "FG-S1", "1");
        ProductionInterimSettlement reposted = interimSettlement("interim-2-reposted", 2, "FG-S2", "1");
        FinishedGoodsBatch firstBatch = finishedGoods(
                "fg-1", "FG-S1", "1", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        FinishedGoodsBatch secondBatch = finishedGoods(
                "fg-2", "FG-S2", "1", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        stubInterimBridge(plan, List.of(first, reposted), firstBatch, secondBatch);

        ProcessSheetRow firstRow = new ProcessSheetRow();
        firstRow.setBatchId(777L);
        firstRow.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        firstRow.setInterimSettledAt(first.getPostedAt());
        ProcessSheetRow repostedRawRow = new ProcessSheetRow();
        repostedRawRow.setBatchId(778L);
        repostedRawRow.setSubmissionStatus(ProcessSheetRow.SUBMISSION_DRAFT);
        repostedRawRow.setInterimSettledAt(reposted.getPostedAt());
        ProcessSheetRow repostedFinishedRow = new ProcessSheetRow();
        repostedFinishedRow.setBatchId(779L);
        repostedFinishedRow.setSubmissionStatus(ProcessSheetRow.SUBMISSION_DRAFT);
        repostedFinishedRow.setInterimSettledAt(reposted.getPostedAt());
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(firstRow, repostedRawRow, repostedFinishedRow));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        verify(productionSettlementRepository).save(argThat(settlement ->
                "POSTED".equals(settlement.getPostingStatus())
                        && settlement.getFinishedGoodsBatchId() == null
                        && new BigDecimal("2").compareTo(settlement.getActualFinishedQuantity()) == 0
                        && new BigDecimal("2").compareTo(settlement.getWarehouseReceivedQuantity()) == 0));
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(materialBatchRepository, never()).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("DRAFT 行时间戳不能伪造：未对应有效小结会话时停产必须拒绝")
    void stopProduction_draftRowWithoutMatchingActiveInterimSession_rejectedWithoutInventoryWrites() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));
        ProductionInterimSettlement session = interimSettlement("interim-1", 1, "FG-S1", "1");
        FinishedGoodsBatch batch = finishedGoods(
                "fg-1", "FG-S1", "1", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        stubInterimBridge(plan, List.of(session), batch);

        ProcessSheetRow stampedDraft = new ProcessSheetRow();
        stampedDraft.setBatchId(777L);
        stampedDraft.setSubmissionStatus(ProcessSheetRow.SUBMISSION_DRAFT);
        stampedDraft.setInterimSettledAt(session.getPostedAt().minusMinutes(1));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(stampedDraft));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals("BY_STOCK_SETTLEMENT_ROWS_INCOMPLETE", error.getErrorCode());
        verify(productionSettlementRepository, never()).save(any());
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("多次小结成品批次单位不一致 → fail-closed 且不写结单")
    void stopProduction_multipleInterimSettlementsWithDifferentUnits_rejected() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));
        ProductionInterimSettlement first = interimSettlement("interim-1", 1, "FG-S1", "2");
        ProductionInterimSettlement second = interimSettlement("interim-2", 2, "FG-S2", "3");
        FinishedGoodsBatch firstBatch = finishedGoods(
                "fg-1", "FG-S1", "2", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        FinishedGoodsBatch secondBatch = finishedGoods(
                "fg-2", "FG-S2", "3", "kg", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        stubInterimBridge(plan, List.of(first, second), firstBatch, secondBatch);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals("BY_STOCK_INTERIM_FINISHED_GOODS_INVALID", error.getErrorCode());
        assertTrue(error.getMessage().contains("计量单位不一致"));
        verify(productionSettlementRepository, never()).save(any());
        verify(finishedGoodsBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("多次小结成品批次合计与摘要不守恒 → fail-closed 且不写结单")
    void stopProduction_multipleInterimSettlementQuantityMismatch_rejected() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));
        ProductionInterimSettlement first = interimSettlement("interim-1", 1, "FG-S1", "2");
        ProductionInterimSettlement second = interimSettlement("interim-2", 2, "FG-S2", "3");
        FinishedGoodsBatch firstBatch = finishedGoods(
                "fg-1", "FG-S1", "2", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        FinishedGoodsBatch secondBatch = finishedGoods(
                "fg-2", "FG-S2", "2.5", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        stubInterimBridge(plan, List.of(first, second), firstBatch, secondBatch);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals("BY_STOCK_INTERIM_FINISHED_GOODS_INVALID", error.getErrorCode());
        assertTrue(error.getMessage().contains("批次合计不一致"));
        verify(productionSettlementRepository, never()).save(any());
        verify(finishedGoodsBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("任一小结记录的成品批次不存在 → fail-closed 且不写结单")
    void stopProduction_multipleInterimSettlementsWithMissingBatch_rejected() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));
        ProductionInterimSettlement first = interimSettlement("interim-1", 1, "FG-S1", "2");
        ProductionInterimSettlement second = interimSettlement("interim-2", 2, "FG-S2-MISSING", "3");
        FinishedGoodsBatch firstBatch = finishedGoods(
                "fg-1", "FG-S1", "2", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        stubInterimBridge(plan, List.of(first, second), firstBatch);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals("BY_STOCK_INTERIM_FINISHED_GOODS_INVALID", error.getErrorCode());
        assertTrue(error.getMessage().contains("成品批次不存在"));
        verify(productionSettlementRepository, never()).save(any());
        verify(finishedGoodsBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("任一小结成品批次 SKU 与计划不一致 → fail-closed 且不写结单")
    void stopProduction_multipleInterimSettlementsWithWrongSku_rejected() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));
        ProductionInterimSettlement first = interimSettlement("interim-1", 1, "FG-S1", "2");
        ProductionInterimSettlement second = interimSettlement("interim-2", 2, "FG-S2", "3");
        FinishedGoodsBatch firstBatch = finishedGoods(
                "fg-1", "FG-S1", "2", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        FinishedGoodsBatch secondBatch = finishedGoods(
                "fg-2", "FG-S2", "3", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        secondBatch.setProductTypeId("PT-WRONG");
        stubInterimBridge(plan, List.of(first, second), firstBatch, secondBatch);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals("BY_STOCK_INTERIM_FINISHED_GOODS_INVALID", error.getErrorCode());
        assertTrue(error.getMessage().contains("SKU 与计划终端 SKU 不一致"));
        verify(productionSettlementRepository, never()).save(any());
        verify(finishedGoodsBatchRepository, never()).save(any());
    }

    // ── 多成品存货计划 (F006【演示L4】8d77ec57 / 【演示L5】f9ba5b6f, 2026-10-24) ─────────────
    // 修复前一律 409「多成品计划不能合并为一条仓库实收」⇒ 联产/原料分流的存货计划永远结不了单;
    // 且桥接只写抬头不写产出行 ⇒ 成本重算恒 409「结单产出尚未全部入库」, 设备/其他费用录不进去。

    private ProductionPlan multiTargetWorkflowPlan() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));
        plan.setTargetFinishedGoodIds(List.of("PT-LEG", "PT-BREAST"));
        plan.setWorkflowSelectionMode(com.cretas.aims.entity.ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        plan.setSelectedBomFamilyId("FAM-1");
        plan.setSelectedBomRecipeIdsByProduct(new java.util.LinkedHashMap<>(Map.of("PT-LEG", "R-LEG", "PT-BREAST", "R-BREAST")));
        plan.setSelectedBomVersionsByProduct(new java.util.LinkedHashMap<>(Map.of("PT-LEG", 4, "PT-BREAST", 4)));
        return plan;
    }

    private com.cretas.aims.entity.bom.BomRecipe recipe(String id, String productTypeId, String terminalNode,
                                                        com.cretas.aims.entity.bom.BomRecipe.OutputRole role) {
        com.cretas.aims.entity.bom.BomRecipe r = new com.cretas.aims.entity.bom.BomRecipe();
        r.setId(id);
        r.setFactoryId(FACTORY_ID);
        r.setProductTypeId(productTypeId);
        r.setBomFamilyId("FAM-1");
        r.setVersion(4);
        r.setTargetTerminalNodeId(terminalNode);
        r.setOutputRole(role);
        return r;
    }

    @Test
    @DisplayName("多成品存货计划、各 SKU 单位不同 → 可结单; 抬头不跨单位加总; 逐 SKU 写结单产出行(含 FG 与钉选 BOM)")
    void stopProduction_multiTargetMixedUnits_closesAndWritesOutputLines() {
        ProductionPlan plan = multiTargetWorkflowPlan();
        ProductionInterimSettlement first = interimSettlement("interim-1", 1, "FG-LEG", "205");
        ProductionInterimSettlement second = interimSettlement("interim-2", 2, "FG-BREAST", "238");
        FinishedGoodsBatch leg = finishedGoods("fg-leg", "FG-LEG", "205", "袋",
                FinishedGoodsBatch.Status.AVAILABLE, InventoryOwnership.COMPANY_OWNED, null);
        leg.setProductTypeId("PT-LEG");
        FinishedGoodsBatch breast = finishedGoods("fg-breast", "FG-BREAST", "238", "盒",
                FinishedGoodsBatch.Status.AVAILABLE, InventoryOwnership.COMPANY_OWNED, null);
        breast.setProductTypeId("PT-BREAST");
        stubInterimBridge(plan, List.of(first, second), leg, breast);
        com.cretas.aims.repository.ProductionSettlementOutputLineRepository lineRepo =
                mock(com.cretas.aims.repository.ProductionSettlementOutputLineRepository.class);
        com.cretas.aims.repository.bom.BomRecipeRepository recipeRepo =
                mock(com.cretas.aims.repository.bom.BomRecipeRepository.class);
        ReflectionTestUtils.setField(service, "productionSettlementOutputLineRepository", lineRepo);
        ReflectionTestUtils.setField(service, "bomRecipeRepository", recipeRepo);
        when(lineRepo.lockByFactoryIdAndSettlementId(eq(FACTORY_ID), anyString())).thenReturn(List.of());
        when(recipeRepo.findById("R-LEG")).thenReturn(Optional.of(recipe("R-LEG", "PT-LEG", "m:fL",
                com.cretas.aims.entity.bom.BomRecipe.OutputRole.CO_PRODUCT)));
        when(recipeRepo.findById("R-BREAST")).thenReturn(Optional.of(recipe("R-BREAST", "PT-BREAST", "m:fB",
                com.cretas.aims.entity.bom.BomRecipe.OutputRole.MAIN)));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        org.mockito.ArgumentCaptor<ProductionSettlement> settlement = org.mockito.ArgumentCaptor.forClass(ProductionSettlement.class);
        verify(productionSettlementRepository, atLeastOnce()).save(settlement.capture());
        assertNull(settlement.getValue().getActualFinishedQuantity(), "袋与盒不能相加, 抬头必须留空");
        assertNull(settlement.getValue().getQuantityUnit());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.cretas.aims.entity.ProductionSettlementOutputLine>> lines =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(lineRepo).saveAll(lines.capture());
        assertEquals(2, lines.getValue().size());
        assertEquals(List.of("fg-leg", "fg-breast"), lines.getValue().stream()
                .map(com.cretas.aims.entity.ProductionSettlementOutputLine::getFinishedGoodsBatchId).toList());
        assertEquals(0, new BigDecimal("238").compareTo(lines.getValue().get(1).getReceivedQuantity()));
        assertEquals("R-BREAST", lines.getValue().get(1).getBomRecipeId());
    }

    @Test
    @DisplayName("多成品存货计划: 小结成品 SKU 不在计划终端集合内 → 仍 fail-closed")
    void stopProduction_multiTargetForeignSku_rejected() {
        ProductionPlan plan = multiTargetWorkflowPlan();
        ProductionInterimSettlement first = interimSettlement("interim-1", 1, "FG-LEG", "205");
        FinishedGoodsBatch foreign = finishedGoods("fg-x", "FG-LEG", "205", "袋",
                FinishedGoodsBatch.Status.AVAILABLE, InventoryOwnership.COMPANY_OWNED, null);
        foreign.setProductTypeId("PT-WING");
        stubInterimBridge(plan, List.of(first), foreign);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals("BY_STOCK_INTERIM_FINISHED_GOODS_INVALID", error.getErrorCode());
        assertTrue(error.getMessage().contains("SKU 与计划终端 SKU 不一致"));
    }

    @Test
    @DisplayName("任一小结成品批次已撤销 → fail-closed 且不写结单")
    void stopProduction_multipleInterimSettlementsWithReversedBatch_rejected() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));
        ProductionInterimSettlement first = interimSettlement("interim-1", 1, "FG-S1", "2");
        ProductionInterimSettlement second = interimSettlement("interim-2", 2, "FG-S2", "3");
        FinishedGoodsBatch firstBatch = finishedGoods(
                "fg-1", "FG-S1", "2", "box", FinishedGoodsBatch.Status.AVAILABLE,
                InventoryOwnership.COMPANY_OWNED, null);
        FinishedGoodsBatch secondBatch = finishedGoods(
                "fg-2", "FG-S2", "3", "box", FinishedGoodsBatch.Status.REVERSED,
                InventoryOwnership.COMPANY_OWNED, null);
        stubInterimBridge(plan, List.of(first, second), firstBatch, secondBatch);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals("BY_STOCK_INTERIM_FINISHED_GOODS_INVALID", error.getErrorCode());
        assertTrue(error.getMessage().contains("不是有效的已入库真值"));
        verify(productionSettlementRepository, never()).save(any());
        verify(finishedGoodsBatchRepository, never()).save(any());
    }
}
