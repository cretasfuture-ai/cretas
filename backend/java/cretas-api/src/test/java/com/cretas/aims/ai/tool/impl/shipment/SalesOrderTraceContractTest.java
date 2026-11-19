package com.cretas.aims.ai.tool.impl.shipment;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.TraceabilityService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.trace.SalesOrderTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话里「追溯销售单 XXX」：销售单号走按销售单追溯，批次号走原批次追溯（阴性对照），查不到如实说。
 * 问题 39：以前销售单号被当批次号，查不到也报成功，回答只剩「批次号：DEMO-…」。
 */
class SalesOrderTraceContractTest {

    private static final String F = "F006";
    private static final String SO_NO = "DEMO-20261105-SO-001";

    private SalesOrderRepository salesOrderRepository;
    private SalesService salesService;
    private ProductionPlanRepository planRepository;
    private ProductionBatchRepository batchRepository;
    private TraceabilityService traceabilityService;
    private SalesOrderTraceService service;
    private TraceFullTool fullTool;
    private TraceBatchTool batchTool;

    @BeforeEach
    void setUp() {
        salesOrderRepository = mock(SalesOrderRepository.class);
        salesService = mock(SalesService.class);
        planRepository = mock(ProductionPlanRepository.class);
        batchRepository = mock(ProductionBatchRepository.class);
        traceabilityService = mock(TraceabilityService.class);
        service = new SalesOrderTraceService(salesOrderRepository, salesService, planRepository,
                batchRepository, traceabilityService);
        fullTool = new TraceFullTool();
        ReflectionTestUtils.setField(fullTool, "traceabilityService", traceabilityService);
        ReflectionTestUtils.setField(fullTool, "salesOrderTraceService", service);
        batchTool = new TraceBatchTool();
        ReflectionTestUtils.setField(batchTool, "traceabilityService", traceabilityService);
        ReflectionTestUtils.setField(batchTool, "salesOrderTraceService", service);
    }

    private SalesOrder order() {
        SalesOrder o = new SalesOrder();
        o.setId("so-id-1");
        o.setOrderNumber(SO_NO);
        o.setCustomerName("上海示例超市");
        return o;
    }

    private void wireOrderToOneBatch() {
        SalesOrder o = order();
        when(salesOrderRepository.findByFactoryIdAndOrderNumber(F, SO_NO)).thenReturn(Optional.of(o));
        when(salesService.getSalesOrderById(F, "so-id-1")).thenReturn(o);
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-1");
        plan.setPlanNumber("PLAN-001");
        plan.setPlannedQuantity(new java.math.BigDecimal("32.00"));
        plan.setPlannedUnit("kg");
        when(planRepository.findByFactoryIdAndSourceOrderIdExact(F, "so-id-1")).thenReturn(List.of(plan));
        when(planRepository.findByFactoryIdAndSourceOrderIdsContaining(anyString(), anyString())).thenReturn(List.of());
        ProductionBatch pb = new ProductionBatch();
        pb.setBatchNumber("PB-001");
        pb.setProductionPlanId("plan-1");
        when(batchRepository.findByFactoryIdAndProductionPlanIdIn(F, List.of("plan-1"))).thenReturn(List.of(pb));
        when(batchRepository.findByFactoryIdAndBatchNumber(F, SO_NO)).thenReturn(Optional.empty());
        when(traceabilityService.getFullTrace(F, "PB-001")).thenReturn(fullTrace());
    }

    private TraceabilityDTO.FullTraceResponse fullTrace() {
        return TraceabilityDTO.FullTraceResponse.builder()
                .production(TraceabilityDTO.ProductionInfo.builder()
                        .batchNumber("PB-001").productName("酱牛肉").build())
                .materials(List.of(TraceabilityDTO.MaterialInfo.builder()
                        .batchNumber("MB-100").materialName("牛腱").supplierName("北方牧业").build()))
                .processingStages(List.of(
                        TraceabilityDTO.ProcessingStageInfo.builder().stageName("解冻").stageOrder(1).build(),
                        TraceabilityDTO.ProcessingStageInfo.builder().stageName("卤制").stageOrder(2).build()))
                .qualityInspections(List.of(TraceabilityDTO.QualityInfo.builder()
                        .result("PASS").passRate(98.0).sampleSize(32.0).passCount(32.0).inspectorName("王质检").build()))
                .shipments(List.of(TraceabilityDTO.ShipmentInfo.builder()
                        .shipmentNumber("SH-001").customerName("上海示例超市")
                        .shipmentDate(LocalDate.of(2026, 11, 6)).quantity(120.0).unit("kg")
                        .status("DELIVERED").build()))
                .build();
    }

    @Test
    void salesOrderNoInBatchNumberSlotTracesBySalesOrder() throws Exception {
        wireOrderToOneBatch();

        Map<String, Object> result = fullTool.doExecute(F, new HashMap<>(Map.of("batchNumber", SO_NO)), Map.of());

        assertThat(result.get("success")).isEqualTo(true);
        String text = (String) result.get("message");
        assertThat(text).contains("牛腱", "MB-100", "北方牧业")      // 原料批次 + 供应商
                .contains("PB-001", "解冻 → 卤制")                    // 生产批次 + 工序
                .contains("合格", "98%")                              // 质检结论
                .contains("SH-001", "上海示例超市", "已送达");        // 出货 + 客户
        // 不外露字段名 / 英文 code / 「详见 trace 字段」
        assertThat(text).doesNotContain("PASS", "DELIVERED", "traceInfo", "batchNumber", "trace 字段");
        verify(traceabilityService).getFullTrace(F, "PB-001");
    }

    @Test
    void replyHasProductionPlanSectionAndPassCountNotJustRate() throws Exception {
        // 问题 39 收尾: 溯源链要单列生产计划 (计划号/产品/计划量); 质检要给件数 32/32, 不只一个百分比
        wireOrderToOneBatch();

        Map<String, Object> result = fullTool.doExecute(F, new HashMap<>(Map.of("batchNumber", SO_NO)), Map.of());

        String text = (String) result.get("message");
        assertThat(text).contains("生产计划", "PLAN-001", "产品 酱牛肉", "计划量 32kg");
        assertThat(text).contains("32/32 合格", "合格率 98%");
        // 生产计划是链路的第一节, 在原料之前
        assertThat(text.indexOf("PLAN-001")).isLessThan(text.indexOf("牛腱"));
    }

    @Test
    void explicitSalesOrderNoParamIsHonoured() throws Exception {
        wireOrderToOneBatch();

        Map<String, Object> result = batchTool.doExecute(F, new HashMap<>(Map.of("salesOrderNo", SO_NO)), Map.of());

        assertThat((String) result.get("message")).contains("PB-001", "北方牧业");
        assertThat(batchTool.validateRequiredParams(new HashMap<>(Map.of("salesOrderNo", SO_NO)))).isEmpty();
        assertThat(batchTool.validateRequiredParams(new HashMap<>())).containsExactly("batchNumber");
    }

    @Test
    void realBatchNumberStillTracesTheBatchAndNeverTouchesSalesOrders() throws Exception {
        // 阴性对照：批次号存在 → 原批次追溯，哪怕号码长得像销售单
        when(batchRepository.findByFactoryIdAndBatchNumber(F, "BATCH-SO-1")).thenReturn(Optional.of(new ProductionBatch()));
        TraceabilityDTO.FullTraceResponse trace = fullTrace();
        when(traceabilityService.getFullTrace(F, "BATCH-SO-1")).thenReturn(trace);

        Map<String, Object> result = fullTool.doExecute(F, new HashMap<>(Map.of("batchNumber", "BATCH-SO-1")), Map.of());

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("fullTraceInfo")).isSameAs(trace);
        verify(salesOrderRepository, never()).findByFactoryIdAndOrderNumber(anyString(), anyString());
        verify(planRepository, never()).findByFactoryIdAndSourceOrderIdExact(anyString(), anyString());
    }

    @Test
    void plainBatchNumberThatIsNotASalesOrderKeepsOriginalPath() throws Exception {
        when(batchRepository.findByFactoryIdAndBatchNumber(F, "PB-001")).thenReturn(Optional.empty());
        when(salesOrderRepository.findByFactoryIdAndOrderNumber(F, "PB-001")).thenReturn(Optional.empty());
        when(traceabilityService.getBatchTrace(F, "PB-001"))
                .thenReturn(TraceabilityDTO.BatchTraceResponse.builder().materialCount(2).build());

        Map<String, Object> result = batchTool.doExecute(F, new HashMap<>(Map.of("batchNumber", "PB-001")), Map.of());

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result).containsKey("traceInfo");
        verify(traceabilityService).getBatchTrace(F, "PB-001");
    }

    @Test
    void unknownSalesOrderSaysNotFoundHonestly() throws Exception {
        when(batchRepository.findByFactoryIdAndBatchNumber(F, "SO-20990101-001")).thenReturn(Optional.empty());
        when(salesOrderRepository.findByFactoryIdAndOrderNumber(F, "SO-20990101-001")).thenReturn(Optional.empty());

        Map<String, Object> result = fullTool.doExecute(F, new HashMap<>(Map.of("batchNumber", "SO-20990101-001")), Map.of());

        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("message")).contains("没有查到销售单 SO-20990101-001");
        verify(traceabilityService, never()).getFullTrace(anyString(), anyString());
    }

    @Test
    void unknownBatchIsNoLongerReportedAsSuccess() throws Exception {
        when(batchRepository.findByFactoryIdAndBatchNumber(F, "XYZ-1")).thenReturn(Optional.empty());
        when(salesOrderRepository.findByFactoryIdAndOrderNumber(F, "XYZ-1")).thenReturn(Optional.empty());
        when(traceabilityService.getFullTrace(F, "XYZ-1")).thenReturn(null);

        Map<String, Object> result = fullTool.doExecute(F, new HashMap<>(Map.of("batchNumber", "XYZ-1")), Map.of());

        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("message")).contains("没有查到批次 XYZ-1");
    }

    @Test
    void salesOrderWithoutProductionBatchesSaysSo() {
        SalesOrder o = order();
        when(salesOrderRepository.findByFactoryIdAndOrderNumber(F, SO_NO)).thenReturn(Optional.of(o));
        when(salesService.getSalesOrderById(F, "so-id-1")).thenReturn(o);
        when(planRepository.findByFactoryIdAndSourceOrderIdExact(F, "so-id-1")).thenReturn(List.of());
        when(planRepository.findByFactoryIdAndSourceOrderIdsContaining(anyString(), anyString())).thenReturn(List.of());

        Map<String, Object> result = service.trace(F, SO_NO);

        assertThat((String) result.get("message")).contains("还没有关联到生产批次");
        verify(traceabilityService, never()).getFullTrace(anyString(), any());
    }

    @Test
    void dataScopeDenialPropagatesInsteadOfLeakingTheChain() {
        SalesOrder o = order();
        when(salesOrderRepository.findByFactoryIdAndOrderNumber(F, SO_NO)).thenReturn(Optional.of(o));
        when(salesService.getSalesOrderById(F, "so-id-1")).thenThrow(new BusinessException(403, "无权访问该销售订单"));

        assertThatThrownBy(() -> service.trace(F, SO_NO)).isInstanceOf(BusinessException.class);
        verify(traceabilityService, never()).getFullTrace(anyString(), any());
    }

    @Test
    void salesOrderNoShapeRecognition() {
        assertThat(SalesOrderTraceService.looksLikeSalesOrderNo("DEMO-20261105-SO-001")).isTrue();
        assertThat(SalesOrderTraceService.looksLikeSalesOrderNo("SO-20261105-001")).isTrue();
        assertThat(SalesOrderTraceService.looksLikeSalesOrderNo("so_20261105_001")).isTrue();
        assertThat(SalesOrderTraceService.looksLikeSalesOrderNo("MB-20261105-001")).isFalse();
        assertThat(SalesOrderTraceService.looksLikeSalesOrderNo("PB-SOYA-1")).isFalse();
        assertThat(SalesOrderTraceService.looksLikeSalesOrderNo(null)).isFalse();
    }
}
