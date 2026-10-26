package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.UpstreamSource;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProcessEntryIdempotency;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.lineage.FinishedBatchProductionAligner;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.lineage.LineageEdgeWriter;
import com.cretas.aims.dto.processentry.MaterializeContext;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 文员逐道录入的血缘<b>连通性</b>：终端批次的祖先必须能走到首道原料。
 *
 * <h2>为什么断言「连通」而不是「调了某个方法」</h2>
 *
 * <p>2026-10-24 prod 实测 12 个终端成品批 0/12 追得到首道原料 —— 每一跳的单测都是绿的：
 * RAW_TO_PRODUCTION 边确实写了，只是「生产批 → 它产出的 WIP 库存批」那一跳从来没有。
 * 只有把整条链跑一遍、在边集上真走一次祖先，才照得出「中间断了一跳」。
 *
 * <p>边写入走<b>真的</b> {@link LineageEdgeRecorder} + {@link LineageEdgeWriter}（仓库是内存桩），
 * 无活动事务 ⇒ 同步写，读得到确定读数。真 PG（闭包触发器）版本见
 * {@code ClerkChainLineagePostgresIntegrationTest}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("文员逐道录入: 终端批的祖先能走到首道原料 (WIP 库存批那一跳必须有边)")
class ClerkChainLineageConnectivityTest {

    private static final String FACTORY = "F-LINEAGE";
    private static final String PLAN_ID = "PLAN-LINEAGE";
    private static final Long OPERATOR = 42L;

    @Mock private ProductionBatchRepository batchRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProcessEntryIdempotencyRepository idempotencyRepo;
    @Mock private FactoryWarehouseRepository warehouseRepo;
    @Mock private BomRecipeRepository bomRecipeRepo;
    @Mock private BomSeasoningItemRepository bomSeasoningItemRepo;
    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private com.cretas.aims.repository.ProductionReportRepository reportRepo;

    @Mock private BatchLineageEdgeRepository edgeRepository;

    @InjectMocks
    private ClerkProcessEntryServiceImpl service;

    /** 内存里的「库」。 */
    private final Map<String, MaterialBatch> materialBatches = new HashMap<>();
    private final List<BatchLineageEdge> edges = new ArrayList<>();
    private final AtomicLong batchIds = new AtomicLong(9000);
    private final AtomicInteger consumptionIds = new AtomicInteger(1);

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "lineageEdgeRecorder", new LineageEdgeRecorder(
                new LineageEdgeWriter(edgeRepository), new FinishedBatchProductionAligner(org.mockito.Mockito.mock(ProductionBatchRepository.class))));

        when(edgeRepository.findMatchingEdges(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenAnswer(inv -> edges.stream()
                        .filter(e -> e.getFactoryId().equals(inv.getArgument(0))
                                && e.getEdgeType().equals(inv.getArgument(1))
                                && e.getSourceType().equals(inv.getArgument(2))
                                && e.getSourceId().equals(inv.getArgument(3))
                                && e.getTargetType().equals(inv.getArgument(4))
                                && e.getTargetId().equals(inv.getArgument(5)))
                        .toList());
        when(edgeRepository.saveAndFlush(any(BatchLineageEdge.class))).thenAnswer(inv -> {
            edges.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(eq(FACTORY), eq(PLAN_ID), anyString()))
                .thenReturn(Optional.empty());
        when(idempotencyRepo.save(any(ProcessEntryIdempotency.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId("WH-WKS-1");
        wh.setFactoryId(FACTORY);
        wh.setCode("WH-WKS");
        wh.setName("车间仓");
        when(warehouseRepo.findByFactoryIdAndCodeAndDeletedAtIsNull(eq(FACTORY), anyString()))
                .thenReturn(Optional.of(wh));
        when(batchRepo.existsByFactoryIdAndBatchNumber(any(), any())).thenReturn(false);
        when(batchRepo.save(any(ProductionBatch.class))).thenAnswer(inv -> {
            ProductionBatch b = inv.getArgument(0);
            if (b.getId() == null) b.setId(batchIds.incrementAndGet());
            return b;
        });
        when(batchRepo.findByIdAndFactoryId(any(), eq(FACTORY))).thenAnswer(inv -> {
            ProductionBatch b = new ProductionBatch();
            b.setId(inv.getArgument(0));
            b.setFactoryId(FACTORY);
            b.setBatchNumber("EXISTING-" + inv.getArgument(0));
            return Optional.of(b);
        });
        when(materialBatchRepo.saveAndFlush(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch mb = inv.getArgument(0);
            materialBatches.put(mb.getId(), mb);
            return mb;
        });
        when(materialBatchRepo.findByIdAndFactoryId(anyString(), eq(FACTORY)))
                .thenAnswer(inv -> Optional.ofNullable(materialBatches.get(inv.getArgument(0))));
        when(materialBatchRepo.findByBatchNumber(anyString())).thenReturn(Optional.empty());
        when(consumptionRepo.save(any(MaterialConsumption.class))).thenAnswer(inv -> {
            MaterialConsumption c = inv.getArgument(0);
            if (c.getId() == null) c.setId(consumptionIds.getAndIncrement());
            return c;
        });
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(any(), any()))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findByIdAndFactoryId(anyString(), eq(FACTORY))).thenAnswer(inv -> {
            ProductType p = new ProductType();
            p.setId(inv.getArgument(0));
            p.setFactoryId(FACTORY);
            return Optional.of(p);
        });

        MaterialBatch raw = new MaterialBatch();
        raw.setId("RAW-1");
        raw.setFactoryId(FACTORY);
        raw.setBatchNumber("RAW-BATCH-1");
        raw.setMaterialTypeId("MT-PORK");
        raw.setReceiptQuantity(new BigDecimal("100"));
        raw.setUnitPrice(new BigDecimal("20"));
        raw.setQuantityUnit("kg");
        raw.setStatus(MaterialBatchStatus.AVAILABLE);
        raw.setReceiptDate(LocalDate.now());
        materialBatches.put(raw.getId(), raw);
    }

    @Test
    @DisplayName("3 道工序: 原料 → 道1 → WIP1 → 道2 → WIP2 → 道3(成品批) —— 成品批的祖先含首道原料")
    void finishedBatchReachesFirstStepRawMaterial() {
        ProcessChainEntryResult result = service.recordChain(FACTORY, PLAN_ID, threeStepChain("CHAIN-1"), OPERATOR);

        Long finishedBatchId = result.getBatchIdsByKey().get("FIN");
        assertThat(finishedBatchId).as("成品道必须物化出生产批").isNotNull();

        // 「一定会发生」的读数: 两道中间产出各一个 WIP 库存批, 各一条 PRODUCTION_TO_WIP 边。
        // 没有它, 下面那条「祖先含原料」在边集为空时也可能因断言写错而误绿 —— 先证明仪器量到了东西。
        long wipEdges = edges.stream()
                .filter(e -> LineageEdgeRecorder.EDGE_PRODUCTION_TO_WIP.equals(e.getEdgeType())).count();
        System.out.println("[lineage-test] edges=" + edges.size() + " PRODUCTION_TO_WIP=" + wipEdges
                + " wipBatchesMaterialized=" + result.getWipBatchesMaterialized());
        assertThat(result.getWipBatchesMaterialized()).isEqualTo(2);
        assertThat(wipEdges).as("每个中间产出的 WIP 库存批都要有一条『谁产出了它』的边").isEqualTo(2);

        Set<String> ancestors = ancestorsOf(LineageEdgeRecorder.NODE_PRODUCTION_BATCH, String.valueOf(finishedBatchId));
        assertThat(ancestors)
                .as("终端成品批的祖先必须走得到首道原料 RAW-1 (prod 上 0/12 走得到)")
                .contains(LineageEdgeRecorder.NODE_MATERIAL_BATCH + ":RAW-1");
        // 中间节点也都在 —— 断的是哪一跳一眼可见
        assertThat(ancestors).contains(
                LineageEdgeRecorder.NODE_PRODUCTION_BATCH + ":" + result.getBatchIdsByKey().get("W1"),
                LineageEdgeRecorder.NODE_PRODUCTION_BATCH + ":" + result.getBatchIdsByKey().get("W2"));
    }

    @Test
    @DisplayName("原地重物化: 存量 WIP 库存批补上边; 再跑一次不重复写 (幂等键 = 库存批 id)")
    void rematerializeBackfillsMissingEdgeOnceOnly() {
        MaterialBatch wip = new MaterialBatch();
        wip.setId("WIP-OLD");
        wip.setFactoryId(FACTORY);
        wip.setBatchNumber("WIP-CLK-W-OLD");
        wip.setProductTypeId("PT-SEMI");
        wip.setReceiptQuantity(new BigDecimal("8"));
        wip.setQuantityUnit("kg");
        materialBatches.put(wip.getId(), wip);

        MaterializeContext ctx = new MaterializeContext(FACTORY, PLAN_ID, "PT-SEMI", "PT-SEMI", "CLK-W-OLD",
                false, new BigDecimal("26"), "WH-WKS-1", "PT-SEMI", OPERATOR);
        StepEntry step = step(1, "8", "8");

        service.rematerializeInPlace(ctx, 777L, "WIP-OLD", List.of(step), List.of(), new ArrayList<>());
        service.rematerializeInPlace(ctx, 777L, "WIP-OLD", List.of(step), List.of(), new ArrayList<>());

        List<BatchLineageEdge> wipEdges = edges.stream()
                .filter(e -> LineageEdgeRecorder.EDGE_PRODUCTION_TO_WIP.equals(e.getEdgeType())).toList();
        assertThat(wipEdges).hasSize(1);
        assertThat(wipEdges.get(0).getSourceId()).isEqualTo("777");
        assertThat(wipEdges.get(0).getTargetId()).isEqualTo("WIP-OLD");
        assertThat(wipEdges.get(0).getMeta()).containsEntry(LineageEdgeRecorder.META_REF_KEY, "wipStock:WIP-OLD");
    }

    // ─────────────────────────────────────────────────────────────

    /** 从目标节点沿边反向走到底，返回全部祖先 "TYPE:id"。 */
    private Set<String> ancestorsOf(String type, String id) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(type + ":" + id);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (BatchLineageEdge e : edges) {
                String target = e.getTargetType() + ":" + e.getTargetId();
                String source = e.getSourceType() + ":" + e.getSourceId();
                if (target.equals(node) && seen.add(source)) {
                    queue.add(source);
                }
            }
        }
        return seen;
    }

    private ProcessChainEntryRequest threeStepChain(String key) {
        RawInput ri = new RawInput();
        ri.setMaterialBatchId("RAW-1");
        ri.setQuantity(new BigDecimal("10"));
        StepEntry s1 = step(1, "10", "8");
        s1.setRawMaterialInputs(List.of(ri));
        BatchEntry w1 = batch("W1", "PT-SEMI-1", false, s1);

        StepEntry s2 = step(2, "8", "7");
        s2.setUpstreamSources(List.of(upstream("W1", "8")));
        BatchEntry w2 = batch("W2", "PT-SEMI-2", false, s2);

        StepEntry s3 = step(3, "7", "6");
        s3.setUpstreamSources(List.of(upstream("W2", "7")));
        BatchEntry fin = batch("FIN", "PT-FIN", true, s3);

        ProcessChainEntryRequest req = new ProcessChainEntryRequest();
        req.setIdempotencyKey(key);
        req.setBatches(List.of(fin, w1, w2));   // 成品放最前: 服务按「WIP 先于成品」稳定排序
        return req;
    }

    private static StepEntry step(int order, String in, String out) {
        StepEntry s = new StepEntry();
        s.setProcessOrder(order);
        s.setProcessName("工序" + order);
        s.setInputQuantity(new BigDecimal(in));
        s.setOutputQuantity(new BigDecimal(out));
        return s;
    }

    private static UpstreamSource upstream(String key, String kg) {
        UpstreamSource u = new UpstreamSource();
        u.setSourceClientBatchKey(key);
        u.setFeedQuantityKg(new BigDecimal(kg));
        return u;
    }

    private static BatchEntry batch(String key, String productTypeId, boolean finished, StepEntry step) {
        BatchEntry b = new BatchEntry();
        b.setClientBatchKey(key);
        b.setProductTypeId(productTypeId);
        b.setFinished(finished);
        b.setSteps(List.of(step));
        return b;
    }
}
