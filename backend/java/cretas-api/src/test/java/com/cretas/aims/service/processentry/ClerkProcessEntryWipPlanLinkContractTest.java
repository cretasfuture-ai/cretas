package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProcessEntryIdempotency;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClerkProcessEntryServiceImpl — 母计划状态同步 (PENDING → IN_PROGRESS)")
/**
 * 方案 B 的正面守卫 (2026-10-03 补测试: 主仓 08-11 方案 B 落地时没有对应断言):
 * 逐道报工的中间半成品批次 (非成品道) 也挂计划 —— 否则 DB 触发器
 * pin_production_batch_workflow_selection() 把「不挂计划」当 LEGACY 老路拒绝, 多工序画布第一道工序就 500。
 * 但它的消耗行 planId 保持「非成品道为 null」的原语义 (见 ClerkProcessEntryServiceImpl#consumptionPlanId)。
 */
class ClerkProcessEntryWipPlanLinkContractTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-WIP-LINK-001";
    private static final Long OPERATOR_ID = 42L;

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

    @InjectMocks
    private ClerkProcessEntryServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void injectObjectMapper() throws Exception {
        var f = ClerkProcessEntryServiceImpl.class.getDeclaredField("objectMapper");
        f.setAccessible(true);
        f.set(service, objectMapper);
    }

    private void stubNoIdempotency(String key) {
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(FACTORY, PLAN_ID, key))
                .thenReturn(Optional.empty());
    }

    private ProductionPlan pendingPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setPlannedQuantity(new BigDecimal("100"));
        return plan;
    }

    private void stubWarehouse() {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId("WH-WKS-1");
        wh.setFactoryId(FACTORY);
        wh.setCode("WH-WKS");
        wh.setName("车间仓");
        when(warehouseRepo.findByFactoryIdAndCodeAndDeletedAtIsNull(eq(FACTORY), anyString()))
                .thenReturn(Optional.of(wh));
    }

    private void stubBatchSave() {
        when(batchRepo.existsByFactoryIdAndBatchNumber(any(), any())).thenReturn(false);
        when(batchRepo.save(any(ProductionBatch.class))).thenAnswer(inv -> {
            ProductionBatch b = inv.getArgument(0);
            if (b.getId() == null) b.setId(System.nanoTime() % 100_000L);
            return b;
        });
    }

    private void stubConsumptionSave() {
        when(consumptionRepo.save(any(MaterialConsumption.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubIdempotencySave() {
        when(idempotencyRepo.save(any(ProcessEntryIdempotency.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubNoRecipe() {
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(any(), any()))
                .thenReturn(Optional.empty());
    }

    private MaterialBatch rawMb(String id, BigDecimal unitPrice) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setFactoryId(FACTORY);
        mb.setBatchNumber("RAW-" + id);
        mb.setMaterialTypeId("MT-PORK");
        mb.setReceiptQuantity(new BigDecimal("500"));
        mb.setUnitPrice(unitPrice);
        mb.setQuantityUnit("kg");
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setReceiptDate(LocalDate.now());
        return mb;
    }

    private RawInput rawInput(String mbId, String qty) {
        RawInput r = new RawInput();
        r.setMaterialBatchId(mbId);
        r.setQuantity(new BigDecimal(qty));
        return r;
    }

    private StepEntry rawStep(int order, String inQty, String outQty, List<RawInput> inputs) {
        StepEntry s = new StepEntry();
        s.setProcessOrder(order);
        s.setProcessName("领料");
        s.setInputQuantity(new BigDecimal(inQty));
        s.setOutputQuantity(new BigDecimal(outQty));
        s.setRawMaterialInputs(inputs);
        return s;
    }

    private BatchEntry finishedBatch(String key, String productTypeId, List<StepEntry> steps) {
        BatchEntry b = new BatchEntry();
        b.setClientBatchKey(key);
        b.setProductTypeId(productTypeId);
        b.setFinished(true);
        b.setSteps(steps);
        return b;
    }

    private ProcessChainEntryRequest req(String idempKey, List<BatchEntry> batches) {
        ProcessChainEntryRequest r = new ProcessChainEntryRequest();
        r.setIdempotencyKey(idempKey);
        r.setBatches(batches);
        return r;
    }

    private BatchEntry wipBatch(String key, String productTypeId, List<StepEntry> steps) {
        BatchEntry b = finishedBatch(key, productTypeId, steps);
        b.setFinished(false);
        return b;
    }

    @Test
    @DisplayName("WIP 批次挂计划, 其消耗行 planId 为 null; 成品批次与其消耗都挂计划")
    void wipBatchLinksPlan_butItsConsumptionKeepsNullPlan() {
        stubNoIdempotency("WIP-LINK-1");
        ProductionPlan plan = pendingPlan();
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        stubWarehouse();
        stubBatchSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();
        MaterialBatch rawA = rawMb("RAW-A", new BigDecimal("10"));
        MaterialBatch rawB = rawMb("RAW-B", new BigDecimal("12"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-A", FACTORY)).thenReturn(Optional.of(rawA));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-B", FACTORY)).thenReturn(Optional.of(rawB));
        // WIP 道会建半成品物料批次, 要求产出产品属于本厂 (requireOwnedOutputProduct)。
        com.cretas.aims.entity.ProductType semi = new com.cretas.aims.entity.ProductType();
        semi.setId("PT-SEMI");
        semi.setFactoryId(FACTORY);
        semi.setName("半成品");
        when(productTypeRepository.findByIdAndFactoryId("PT-SEMI", FACTORY)).thenReturn(Optional.of(semi));
        when(materialBatchRepo.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        BatchEntry wip = wipBatch("WIP-1", "PT-SEMI", List.of(
                rawStep(1, "100", "80", List.of(rawInput("RAW-A", "100")))));
        BatchEntry fin = finishedBatch("FIN-1", "PT-PRODUCT", List.of(
                rawStep(1, "50", "40", List.of(rawInput("RAW-B", "50")))));

        service.recordChain(FACTORY, PLAN_ID, req("WIP-LINK-1", List.of(wip, fin)), OPERATOR_ID);

        org.mockito.ArgumentCaptor<ProductionBatch> batches = org.mockito.ArgumentCaptor.forClass(ProductionBatch.class);
        verify(batchRepo, atLeastOnce()).save(batches.capture());
        ProductionBatch savedWip = batches.getAllValues().stream()
                .filter(b -> "PT-SEMI".equals(b.getProductTypeId())).findFirst().orElseThrow();
        ProductionBatch savedFin = batches.getAllValues().stream()
                .filter(b -> "PT-PRODUCT".equals(b.getProductTypeId())).findFirst().orElseThrow();
        assertThat(savedWip.getProductionPlanId())
                .as("WIP 批次必须挂计划, 否则 DB 触发器把它当 LEGACY 老路拒绝")
                .isEqualTo(PLAN_ID);
        assertThat(savedFin.getProductionPlanId()).isEqualTo(PLAN_ID);

        org.mockito.ArgumentCaptor<MaterialConsumption> cons = org.mockito.ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo, atLeastOnce()).save(cons.capture());
        MaterialConsumption wipCons = cons.getAllValues().stream()
                .filter(m -> "RAW-A".equals(m.getBatchId())).findFirst().orElseThrow();
        MaterialConsumption finCons = cons.getAllValues().stream()
                .filter(m -> "RAW-B".equals(m.getBatchId())).findFirst().orElseThrow();
        assertThat(wipCons.getProductionPlanId())
                .as("在制道消耗行保持 planId=null 的原语义 (撤销小结等按 planId 查消耗的路径不该多命中)")
                .isNull();
        assertThat(finCons.getProductionPlanId()).isEqualTo(PLAN_ID);
    }
}
