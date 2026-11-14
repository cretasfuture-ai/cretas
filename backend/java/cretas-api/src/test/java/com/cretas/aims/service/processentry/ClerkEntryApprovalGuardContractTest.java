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
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import com.cretas.aims.service.production.PendingApprovalStartGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 1551-11 红线 1 独立审查 B2: 文员录入 (recordChain → materializeBatch → createProductionBatch) 原先只在
 * 「计划还是待处理」的 filter 里调守卫; 强制插单被驳回后计划是 CANCELLED, filter 把它筛掉, 照样建出生产中批次。
 * 现在守卫在 createProductionBatch 开头无条件调用 (工序表 ProcessSheetServiceImpl 直接调 materializeBatch,
 * 也经过这里)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClerkEntryApprovalGuardContractTest {

    private static final String F = "F006";
    private static final String PLAN_ID = "PP-QA-URG-CLK";
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

    @InjectMocks private ClerkProcessEntryServiceImpl service;

    private ProductionPlan plan;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(F);
        plan.setPlanNumber("QA-URG-CLK-1");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.markAsForceInsert(1L, "客户急单", true);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, F)).thenReturn(Optional.of(plan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(eq(F), eq(PLAN_ID), anyString()))
                .thenReturn(Optional.empty());
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId("WH-WKS-1");
        wh.setFactoryId(F);
        wh.setCode("WH-WKS");
        wh.setName("车间仓");
        when(warehouseRepo.findByFactoryIdAndCodeAndDeletedAtIsNull(eq(F), anyString())).thenReturn(Optional.of(wh));
        when(batchRepo.existsByFactoryIdAndBatchNumber(any(), any())).thenReturn(false);
        when(batchRepo.save(any(ProductionBatch.class))).thenAnswer(inv -> {
            ProductionBatch b = inv.getArgument(0);
            if (b.getId() == null) b.setId(7001L);
            return b;
        });
        when(consumptionRepo.save(any(MaterialConsumption.class))).thenAnswer(inv -> inv.getArgument(0));
        when(idempotencyRepo.save(any(ProcessEntryIdempotency.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(any(), any())).thenReturn(Optional.empty());
        MaterialBatch mb = new MaterialBatch();
        mb.setId("RAW-CLK-1");
        mb.setFactoryId(F);
        mb.setBatchNumber("RAW-CLK-1");
        mb.setMaterialTypeId("MT-PORK");
        mb.setReceiptQuantity(new BigDecimal("500"));
        mb.setUnitPrice(new BigDecimal("10"));
        mb.setQuantityUnit("kg");
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setReceiptDate(LocalDate.now());
        when(materialBatchRepo.findByIdAndFactoryId("RAW-CLK-1", F)).thenReturn(Optional.of(mb));
    }

    private ProcessChainEntryRequest request(String key) {
        RawInput in = new RawInput();
        in.setMaterialBatchId("RAW-CLK-1");
        in.setQuantity(new BigDecimal("100"));
        StepEntry step = new StepEntry();
        step.setProcessOrder(1);
        step.setProcessName("领料");
        step.setInputQuantity(new BigDecimal("100"));
        step.setOutputQuantity(new BigDecimal("80"));
        step.setRawMaterialInputs(List.of(in));
        BatchEntry be = new BatchEntry();
        be.setClientBatchKey("FINAL");
        be.setProductTypeId("PT-PRODUCT");
        be.setFinished(true);
        be.setSteps(List.of(step));
        ProcessChainEntryRequest r = new ProcessChainEntryRequest();
        r.setIdempotencyKey(key);
        r.setBatches(List.of(be));
        return r;
    }

    private void assertNothingWritten() {
        verify(batchRepo, never()).save(any());
        verify(consumptionRepo, never()).save(any());
        verify(idempotencyRepo, never()).save(any());
        verify(planRepository, never()).save(any());
    }

    @Test
    void rejectedForceInsertCannotBeProducedThroughClerkEntry() {
        plan.reject(9L, "QA生产经理", "产能已满");
        plan.setStatus(ProductionPlanStatus.CANCELLED);   // 驳回 = 作废

        assertThatThrownBy(() -> service.recordChain(F, PLAN_ID, request("CLK-REJ-1"), OPERATOR))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo(PendingApprovalStartGuard.CODE_CLOSED);
                    assertThat(e.getMessage()).contains("QA-URG-CLK-1").contains("已被驳回");
                });
        assertNothingWritten();
    }

    @Test
    void pendingForceInsertIsStillRefused() {
        assertThatThrownBy(() -> service.recordChain(F, PLAN_ID, request("CLK-PEND-1"), OPERATOR))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PendingApprovalStartGuard.CODE));
        assertNothingWritten();
    }

    @Test
    void negativeControlApprovedForceInsertIsRecordedAndThePlanStarts() {
        plan.approve(9L, "QA生产经理", "同意");

        service.recordChain(F, PLAN_ID, request("CLK-OK-1"), OPERATOR);

        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.IN_PROGRESS);
        verify(batchRepo, atLeastOnce()).save(any(ProductionBatch.class));
    }
}
