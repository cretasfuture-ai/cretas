package com.cretas.aims.service.production;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.entity.ProductionSettlementOutputLine;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementLaborRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.lineage.SemiFeedLineageRecorder;
import com.cretas.aims.utils.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结单族的两处写边接线（装配沿用 {@code ProductionPlanSettlementTest}）：
 * <ol>
 *   <li>结单 SFI 投料严格扣减之后写 WIP_CONSUME（目标 = 行的生产批次）；</li>
 *   <li>按终端产出逐行入库的成品批：把报工生产批号作对齐线索传给 recorder
 *       （prod 上 CLK-B-20260903-624 / -80225 / PB-PLAN-…-46115 三个成品批因此缺边）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("结单写边接线: SFI 投料 WIP_CONSUME + 逐行入库按报工批号对齐")
class SettlementLineageWiringTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PP-SETTLE-LINEAGE";

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;
    @Mock private ProductionSettlementLaborRepository productionSettlementLaborRepository;
    @Mock private SemiFinishedInventoryTransactionRepository semiFinishedInventoryTransactionRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private com.cretas.aims.repository.ProcessSheetRowRepository processSheetRowRepository;
    @Mock private com.cretas.aims.service.wip.WipInventoryService wipInventoryService;
    @Mock private com.cretas.aims.service.inventory.FinishedGoodsFeedService finishedGoodsFeedService;
    @Mock private WorkshopReportReservationService workshopReservationService;
    @Mock private CustomerSuppliedSettlementCoverageGuard customerSuppliedSettlementCoverageGuard;
    @Mock private com.cretas.aims.service.unit.UnitContractService unitContractService;
    @Mock private LineageEdgeRecorder lineageEdgeRecorder;
    @Mock private SemiFeedLineageRecorder semiFeedLineageRecorder;

    private final ObjectMapper mapper = new ObjectMapper();
    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(unitContractService.storageUnit(anyString(), anyString())).thenAnswer(invocation ->
                com.cretas.aims.service.unit.impl.UnitContractServiceImpl
                        .crossLanguageCode(invocation.getArgument(1)));
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository",
                productionSettlementConsumptionRepository);
        ReflectionTestUtils.setField(service, "productionSettlementLaborRepository", productionSettlementLaborRepository);
        ReflectionTestUtils.setField(service, "semiFinishedInventoryTransactionRepository",
                semiFinishedInventoryTransactionRepository);
        when(semiFinishedInventoryTransactionRepository.save(any(SemiFinishedInventoryTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "processSheetRowRepository", processSheetRowRepository);
        ReflectionTestUtils.setField(service, "wipInventoryService", wipInventoryService);
        ReflectionTestUtils.setField(service, "finishedGoodsFeedService", finishedGoodsFeedService);
        ReflectionTestUtils.setField(service, "workshopReservationService", workshopReservationService);
        ReflectionTestUtils.setField(service, "customerSuppliedSettlementCoverageGuard",
                customerSuppliedSettlementCoverageGuard);
        ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
        ReflectionTestUtils.setField(service, "lineageEdgeRecorder", lineageEdgeRecorder);
        ReflectionTestUtils.setField(service, "semiFeedLineageRecorder", semiFeedLineageRecorder);
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
        lenient().when(workshopReservationService.releaseForPlan(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(customerSuppliedSettlementCoverageGuard.audit(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    @Test
    @DisplayName("结单扣常驻 SFI 之后写 来源→行批次(555) 的 WIP_CONSUME, 量 = 实际扣减量")
    void settlementSfiFeedWritesWipConsume() throws Exception {
        ProcessSheetRow row = feedRow("SFI-STOCK", "30");
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan()));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-l")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementConsumptionRepository.saveAll(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<ProductionSettlementConsumption> lines = (List<ProductionSettlementConsumption>) inv.getArgument(0);
            long id = 1;
            for (ProductionSettlementConsumption l : lines) if (l.getId() == null) l.setId(id++);
            return lines;
        });
        when(productionSettlementLaborRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID)).thenReturn(List.of(row));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(new ArrayList<>());
        when(wipInventoryService.consumeClerkSemiStrict(eq(FACTORY_ID), eq("SFI-STOCK"), any()))
                .thenAnswer(inv -> inv.getArgument(2));

        service.settleProduction(FACTORY_ID, PLAN_ID, ProductionSettlementRequest.builder()
                .idempotencyKey("idem-l")
                .actualFinishedQuantity(new BigDecimal("90"))
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(new ArrayList<>())
                .semiFinishedConsumptions(new ArrayList<>())
                .materialVarianceReason("无差异")
                .laborDeferredReason("工时稍后补录")
                .laborSegments(new ArrayList<>())
                .build(), 10L);

        verify(wipInventoryService).consumeClerkSemiStrict(FACTORY_ID, "SFI-STOCK", new BigDecimal("30"));
        verify(semiFeedLineageRecorder).recordSemiFeed(eq(FACTORY_ID), eq("SFI-STOCK"), eq(555L),
                argThat(q -> q != null && q.compareTo(new BigDecimal("30")) == 0), eq(null), any(),
                argThat(k -> k.startsWith("settleSfiFeed:" + PLAN_ID + ":row")), eq("SETTLEMENT_SFI_FEED"));
    }

    @Test
    @DisplayName("逐行入库的成品批: 报工批号 CLK-B-20260903-624 作对齐线索传给 recorder")
    void outputLineReceiptPassesReportedBatchNumberAsHint() {
        ProductType pt = new ProductType();
        pt.setId("PT-FIN");
        pt.setFactoryId(FACTORY_ID);
        pt.setName("成品");
        when(productTypeRepository.findByIdAndFactoryId("PT-FIN", FACTORY_ID)).thenReturn(Optional.of(pt));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "CLK-B-20260903-624"))
                .thenReturn(Optional.empty());
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(inv -> {
            FinishedGoodsBatch fg = inv.getArgument(0);
            fg.setId("fg-624");
            return fg;
        });
        when(warehouseResolver.resolveFinishedGoodsId(FACTORY_ID)).thenReturn("WH-FG");
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setFactoryId(FACTORY_ID);
        settlement.setPlanNumber("PLAN-1788412309596-55FB0E1A");
        ProductionSettlementOutputLine line = new ProductionSettlementOutputLine();
        line.setReportedBatchNumber("CLK-B-20260903-624");
        line.setProductTypeId("PT-FIN");
        line.setQuantityUnit("盒");

        ReflectionTestUtils.invokeMethod(service, "createFinishedGoodsForOutputLine",
                plan(), settlement, line, BigDecimal.ONE, 7L);

        verify(lineageEdgeRecorder).recordProductionToFinishedByPlan(eq(FACTORY_ID), eq(PLAN_ID), eq("PT-FIN"),
                eq("CLK-B-20260903-624"), eq("fg-624"), any(), eq("盒"), eq(7L),
                eq("finishedBatch:fg-624"), any());
    }

    // ─────────────────────────────────────────────────────────────

    private ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("P-001");
        plan.setProductTypeId("PT-1");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        return plan;
    }

    private ProcessSheetRow feedRow(String srcBatchNo, String feedKg) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("r-" + srcBatchNo);
        req.setProcessCode("qidiao");
        req.setProcessOrder(9);
        req.setProductTypeId("PT-1");
        req.setFinished(false);
        req.setOutputQuantity(new BigDecimal("10"));
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber(srcBatchNo);
        ref.setFeedQuantityKg(new BigDecimal(feedKg));
        ref.setSemiFinished(true);
        req.setUpstreamSources(List.of(ref));
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(88L);
        row.setFactoryId(FACTORY_ID);
        row.setPlanId(PLAN_ID);
        row.setBatchId(555L);
        row.setBatchNumber("CLK-B-" + srcBatchNo);
        row.setRowStatus("SAVED");
        try {
            row.setRowPayload(mapper.writeValueAsString(req));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return row;
    }
}
