package com.cretas.aims.service.yield;

import com.cretas.aims.service.processentry.IntermediateOwnershipGuard.Owner;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionInterimSettlement;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.lineage.SemiFeedLineageRecorder;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.impl.InterimSettleServiceImpl;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小结（库存生产）的两处写边接线：
 * <ol>
 *   <li>SFI 投料严格扣减之后写 WIP_CONSUME（目标 = 本行生产批次）；</li>
 *   <li>成品批按<b>本次小结的贡献行批次</b>写 PRODUCTION_TO_FINISHED —— prod 上
 *       FG-PLAN-…3D170080-S1 所在计划有 2 个同产品正式批次，按计划对齐恒 AMBIGUOUS、一条边都没有。</li>
 * </ol>
 * 装配沿用 {@code InterimSettleServiceTest}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("小结写边接线: SFI 投料 WIP_CONSUME + 成品按贡献批次")
class InterimSettleLineageWiringTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN1";

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProcessSheetRowRepository rowRepository;
    @Mock private MaterialConsumptionRepository consumptionRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionInterimSettlementRepository settlementRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private FinishedGoodsFeedService finishedGoodsFeedService;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private ClerkProcessEntryService clerkProcessEntryService;
    @Mock private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock private WorkProcessRepository workProcessRepository;
    @Mock private LineageEdgeRecorder lineageEdgeRecorder;
    @Mock private SemiFeedLineageRecorder semiFeedLineageRecorder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<ProductionInterimSettlement> saved = new ArrayList<>();
    private final AtomicInteger fgIds = new AtomicInteger(1);
    private InterimSettleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InterimSettleServiceImpl(planRepository, rowRepository, consumptionRepository,
                materialBatchRepository, settlementRepository, finishedGoodsBatchRepository, productTypeRepository,
                wipInventoryService, finishedGoodsFeedService, warehouseResolver, objectMapper,
                batchRepository, clerkProcessEntryService, productWorkProcessRepository, workProcessRepository);
        ReflectionTestUtils.setField(service, "lineageEdgeRecorder", lineageEdgeRecorder);
        ReflectionTestUtils.setField(service, "semiFeedLineageRecorder", semiFeedLineageRecorder);

        when(clerkProcessEntryService.resolveLaborRate(eq(FACTORY), any())).thenReturn(new BigDecimal("20"));
        when(clerkProcessEntryService.computeLaborCost(any(), any())).thenReturn(BigDecimal.ZERO);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber("PP-001");
        plan.setProductTypeId("PT1");
        plan.setSourceType(PlanSourceType.SAFETY_STOCK);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        when(settlementRepository.saveAndFlush(any(ProductionInterimSettlement.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(settlementRepository.findTopByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqDesc(
                FACTORY, PLAN_ID)).thenAnswer(inv -> saved.stream()
                .max((a, b) -> Integer.compare(a.getSessionSeq(), b.getSessionSeq())));
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(
                FACTORY, PLAN_ID)).thenAnswer(inv -> new ArrayList<>(saved));
        when(rowRepository.save(any(ProcessSheetRow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(consumptionRepository.findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(eq(FACTORY), any()))
                .thenReturn(new ArrayList<MaterialConsumption>());
        when(productTypeRepository.findByIdAndFactoryId(any(), eq(FACTORY))).thenReturn(Optional.empty());
        when(warehouseResolver.resolveFinishedGoodsId(FACTORY)).thenReturn("WH-FG");
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(eq(FACTORY), any()))
                .thenReturn(Optional.empty());
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class))).thenAnswer(inv -> {
            FinishedGoodsBatch fg = inv.getArgument(0);
            if (fg.getId() == null) fg.setId("fg-" + fgIds.getAndIncrement());
            return fg;
        });
        when(wipInventoryService.consumeClerkSemiStrict(any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(3));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    @Test
    @DisplayName("成品道吃常驻 SFI: 扣减之后写 来源→本行批次(20) 的 WIP_CONSUME, 量 = 实际扣减量")
    void sfiFeedWritesWipConsumeToTheRowBatch() {
        ProcessSheetRow r = row(20L, finishedReq("CLK-B-X", "40", upstreamSemi("SFI-STANDING-1", "30")));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(r));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        verify(wipInventoryService).consumeClerkSemiStrict(FACTORY, Owner.COMPANY, "SFI-STANDING-1", new BigDecimal("30"));
        verify(semiFeedLineageRecorder).recordSemiFeed(eq(FACTORY), eq("SFI-STANDING-1"), eq(20L),
                argThat(q -> q != null && q.compareTo(new BigDecimal("30")) == 0), eq(null), eq(7L),
                argThat(k -> k.startsWith("interimSfiFeed:" + PLAN_ID + ":s1:row20:")),
                eq("INTERIM_SETTLE_SFI_FEED"));
    }

    @Test
    @DisplayName("两条同产品成品行(批次 31/32)合成一个成品批: 各写一条 PRODUCTION_TO_FINISHED, 不走按计划对齐")
    void finishedBatchEdgesComeFromContributingRows() {
        ProcessSheetRow a = row(31L, finishedReq("CLK-B-31", "6", null));
        ProcessSheetRow b = row(32L, finishedReq("CLK-B-32", "4", null));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(a, b));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        verify(lineageEdgeRecorder).recordProductionToFinished(eq(FACTORY), eq(31L), eq("fg-1"),
                argThat(q -> q.compareTo(new BigDecimal("6")) == 0), any(), eq(7L),
                eq("finishedBatch:fg-1:pb:31"), any());
        verify(lineageEdgeRecorder).recordProductionToFinished(eq(FACTORY), eq(32L), eq("fg-1"),
                argThat(q -> q.compareTo(new BigDecimal("4")) == 0), any(), eq(7L),
                eq("finishedBatch:fg-1:pb:32"), any());
        verify(lineageEdgeRecorder, never()).recordProductionToFinishedByPlan(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("单贡献行: 幂等键沿用 finishedBatch:<id> (与回填 / 旧在线同键, 不会双写)")
    void singleContributorKeepsLegacyRefKey() {
        ProcessSheetRow a = row(41L, finishedReq("CLK-B-41", "5", null));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(a));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        verify(lineageEdgeRecorder).recordProductionToFinished(eq(FACTORY), eq(41L), eq("fg-1"),
                any(), any(), eq(7L), eq("finishedBatch:fg-1"), any());
    }

    // ─────────────────────────────────────────────────────────────

    private ProcessSheetRow row(Long id, ProcessSheetRowRequest req) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN_ID);
        row.setProcessCode(req.getProcessCode());
        row.setProcessOrder(req.getProcessOrder());
        row.setClientRowId("c" + id);
        row.setBatchId(id);
        row.setBatchNumber(req.getBatchNumber());
        row.setRowStatus("SAVED");
        try {
            row.setRowPayload(objectMapper.writeValueAsString(req));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return row;
    }

    private static ProcessSheetRowRequest finishedReq(String batchNumber, String output, List<UpstreamRef> upstream) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("c-" + batchNumber);
        req.setProcessCode("p9");
        req.setProcessOrder(9);
        req.setProductTypeId("PT1");
        req.setBatchNumber(batchNumber);
        req.setFinished(true);
        req.setOutputQuantity(new BigDecimal(output));
        req.setUnit("盒");
        req.setUpstreamSources(upstream);
        return req;
    }

    private static List<UpstreamRef> upstreamSemi(String no, String kg) {
        UpstreamRef ref = new UpstreamRef();
        ref.setSourceBatchNumber(no);
        ref.setFeedQuantityKg(new BigDecimal(kg));
        ref.setSemiFinished(true);
        return List.of(ref);
    }
}
