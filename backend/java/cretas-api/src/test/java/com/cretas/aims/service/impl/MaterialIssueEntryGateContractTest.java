package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.impl.dataop.InventoryOperationTool;
import com.cretas.aims.ai.tool.impl.material.MaterialInventoryOutboundTool;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
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
 * 排查 2026-11-10 A4 / A5: 领料入口只比数量, 不查待检 / 过期 / 报废 / 不良品, 也不查归属;
 * AI 出库工具走的 useBatchQuantity 同样只比数量、不记消耗。
 *
 * <p>本类覆盖:
 * <ul>
 *   <li>A4 批次级投料 {@code ProcessingServiceImpl.recordMaterialConsumption}
 *       (POST /processing/batches/{id}/material-consumption): 状态 + 归属</li>
 *   <li>A4 计划级领料 {@code ProductionPlanServiceImpl.recordMaterialConsumption}
 *       (POST /production-plans/{id}/consumption): 状态 (归属原本就有)</li>
 *   <li>A5 AI {@code material_inventory_outbound} / {@code inventory_operation OUTBOUND} —— 产品真实入口:
 *       工具 → 真实 {@link MaterialBatchServiceImpl#useBatchMaterial} (状态 + 归属 + 消耗留痕)</li>
 *   <li>同族兄弟端点 reserve 的归属 (清点补出)</li>
 * </ul>
 */
@DisplayName("排查 A4/A5: 领料 / 投料 / AI 出库入口统一查状态和归属")
class MaterialIssueEntryGateContractTest {

    private static final String F = "F006";
    private static final List<MaterialBatchStatus> BLOCKED = List.of(MaterialBatchStatus.INSPECTING,
            MaterialBatchStatus.EXPIRED, MaterialBatchStatus.SCRAPPED, MaterialBatchStatus.DEFECTIVE);

    private final MaterialBatchRepository batches = mock(MaterialBatchRepository.class);
    private final MaterialConsumptionRepository consumptions = mock(MaterialConsumptionRepository.class);
    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final ProductionBatchRepository productionBatches = mock(ProductionBatchRepository.class);
    private final RawMaterialTypeRepository materialTypes = mock(RawMaterialTypeRepository.class);

    private MaterialBatch batch;
    private ProductionPlan companyPlan;

    @BeforeEach
    void setUp() {
        batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId(F);
        batch.setBatchNumber("QA-MB-1");
        batch.setMaterialTypeId("RMT-1");
        batch.setReceiptQuantity(new BigDecimal("100"));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnitPrice(new BigDecimal("2"));
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setOwnership(InventoryOwnership.COMPANY_OWNED);
        when(batches.findById("MB-1")).thenReturn(Optional.of(batch));
        when(batches.findByIdAndFactoryId("MB-1", F)).thenReturn(Optional.of(batch));
        when(batches.findByIdAndFactoryIdForUpdate("MB-1", F)).thenReturn(Optional.of(batch));
        when(batches.save(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(consumptions.save(any(MaterialConsumption.class))).thenAnswer(i -> i.getArgument(0));
        when(materialTypes.findById(anyString())).thenReturn(Optional.empty());

        companyPlan = new ProductionPlan();
        companyPlan.setId("PLAN-1");
        companyPlan.setFactoryId(F);
        companyPlan.setCreatedBy(9L);
        when(plans.findById("PLAN-1")).thenReturn(Optional.of(companyPlan));
        when(plans.findByIdAndFactoryId("PLAN-1", F)).thenReturn(Optional.of(companyPlan));
    }

    // ───────────────────────── A5: AI 出库 → 真实 useBatchMaterial ─────────────────────────

    private MaterialBatchServiceImpl realWarehouseService() {
        MaterialBatchServiceImpl svc = new MaterialBatchServiceImpl(batches, null, null, mock(MaterialBatchMapper.class),
                null, null, null, null);
        ReflectionTestUtils.setField(svc, "materialConsumptionRepository", consumptions);
        ReflectionTestUtils.setField(svc, "materialTypeRepository", materialTypes);
        ReflectionTestUtils.setField(svc, "productionPlanRepository", plans);
        ReflectionTestUtils.setField(svc, "inventoryLowStockEventPublisher", mock(InventoryLowStockEventPublisher.class));
        return svc;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> execute(Object tool, Map<String, Object> params) throws Exception {
        Method m = null;
        for (Class<?> c = tool.getClass(); c != null && m == null; c = c.getSuperclass()) {
            for (Method x : c.getDeclaredMethods()) {
                if (x.getName().equals("doExecute") && x.getParameterCount() == 3) {
                    m = x;
                }
            }
        }
        m.setAccessible(true);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", F);
        ctx.put("userId", 42L);
        try {
            return (Map<String, Object>) m.invoke(tool, F, params, ctx);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private MaterialInventoryOutboundTool outboundTool(MaterialBatchService svc) {
        MaterialInventoryOutboundTool tool = new MaterialInventoryOutboundTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", svc);
        return tool;
    }

    private InventoryOperationTool operationTool(MaterialBatchService svc) {
        InventoryOperationTool tool = new InventoryOperationTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", svc);
        return tool;
    }

    @Test
    @DisplayName("A5 真实入口: AI 出库领待检 / 过期 / 报废 / 不良品批次 → 409, 库存不动, 不记消耗 (两个工具)")
    void aiOutboundRejectsBlockedBatches() {
        MaterialBatchServiceImpl svc = realWarehouseService();
        for (MaterialBatchStatus st : BLOCKED) {
            batch.setStatus(st);
            assertThatThrownBy(() -> execute(outboundTool(svc), Map.of("materialName", "猪肉",
                    "quantity", new BigDecimal("5"), "batchId", "MB-1")))
                    .as("outbound %s", st).isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getActionHint()).isNotBlank();
                    });
            assertThatThrownBy(() -> execute(operationTool(svc), Map.of("batchId", "MB-1",
                    "operationType", "OUTBOUND", "quantity", new BigDecimal("5"))))
                    .as("inventory_operation %s", st).isInstanceOf(BusinessException.class);
        }
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("0");
        verify(consumptions, never()).save(any());
    }

    @Test
    @DisplayName("A5 真实入口: 客户来料不关联计划不能 AI 出库 → 409 带提示; 关联的计划查不到 → 404")
    void aiOutboundChecksOwnership() {
        MaterialBatchServiceImpl svc = realWarehouseService();
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        assertThatThrownBy(() -> execute(outboundTool(svc), Map.of("materialName", "客户猪肉",
                "quantity", new BigDecimal("5"), "batchId", "MB-1")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("CUSTOMER_OWNED_MATERIAL_REQUIRES_PLAN");
                    assertThat(e.getActionHint()).contains("计划");
                });
        batch.setOwnership(InventoryOwnership.COMPANY_OWNED);
        // 计划存在, 但属于别的工厂: 不能退化成「无计划」口径放过去
        ProductionPlan otherFactoryPlan = new ProductionPlan();
        otherFactoryPlan.setId("PLAN-OTHER");
        otherFactoryPlan.setFactoryId("F999");
        when(plans.existsById("PLAN-OTHER")).thenReturn(true);
        when(plans.findById("PLAN-OTHER")).thenReturn(Optional.of(otherFactoryPlan));
        assertThatThrownBy(() -> execute(outboundTool(svc), Map.of("materialName", "猪肉",
                "quantity", new BigDecimal("5"), "batchId", "MB-1", "productionPlanId", "PLAN-OTHER")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PRODUCTION_MATERIAL_PLAN_NOT_FOUND"));
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("0");
        verify(consumptions, never()).save(any());
    }

    @Test
    @DisplayName("A5 阳性: 可用的公司料 AI 出库 → 扣库存 + 记一条消耗 (谁、原因), 原来一条都不记")
    void aiOutboundRecordsConsumption() throws Exception {
        MaterialBatchServiceImpl svc = realWarehouseService();
        execute(outboundTool(svc), Map.of("materialName", "猪肉", "quantity", new BigDecimal("5"),
                "batchId", "MB-1", "reason", "生产领用"));
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("5");
        ArgumentCaptor<MaterialConsumption> cap = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptions).save(cap.capture());
        assertThat(cap.getValue().getRecordedBy()).isEqualTo(42L);
        assertThat(cap.getValue().getBatchId()).isEqualTo("MB-1");
        assertThat(cap.getValue().getNotes()).contains("AI 出库");

        execute(operationTool(svc), Map.of("batchId", "MB-1", "operationType", "OUTBOUND",
                "quantity", new BigDecimal("3")));
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("同族 reserve: 客户来料不关联计划不能预留 → 409, 预留量不变 (清点补出)")
    void reserveChecksOwnership() {
        MaterialBatchServiceImpl svc = realWarehouseService();
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        assertThatThrownBy(() -> svc.reserveBatchMaterial(F, "MB-1", new BigDecimal("5"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("CUSTOMER_OWNED_MATERIAL_REQUIRES_PLAN"));
        assertThat(batch.getReservedQuantity()).isEqualByComparingTo("0");
    }

    // ───────────────────────── A4: 计划级领料 ─────────────────────────

    private ProductionPlanServiceImpl planService() throws Exception {
        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class) args[i] = plans;
            else if (types[i] == MaterialBatchRepository.class) args[i] = batches;
            else if (types[i] == MaterialConsumptionRepository.class) args[i] = consumptions;
            else args[i] = null;
        }
        ProductionPlanServiceImpl svc = (ProductionPlanServiceImpl) ctor.newInstance(args);
        ReflectionTestUtils.setField(svc, "inventoryLowStockEventPublisher", mock(InventoryLowStockEventPublisher.class));
        return svc;
    }

    @Test
    @DisplayName("A4 计划级领料: 待检 / 过期 / 报废 / 不良品 → 409 带提示, 不扣不记; 阴性对照: 可用照常")
    void planIssueRejectsBlockedBatches() throws Exception {
        ProductionPlanServiceImpl svc = planService();
        for (MaterialBatchStatus st : BLOCKED) {
            batch.setStatus(st);
            assertThatThrownBy(() -> svc.recordMaterialConsumption(F, "PLAN-1", "MB-1", new BigDecimal("5"), 42L))
                    .as("%s", st).isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getErrorCode()).isEqualTo("MATERIAL_BATCH_" + st.name());
                        assertThat(e.getActionHint()).isNotBlank();
                    });
        }
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("0");
        verify(consumptions, never()).save(any());

        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        svc.recordMaterialConsumption(F, "PLAN-1", "MB-1", new BigDecimal("5"), 42L);
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("5");
    }

    // ───────────────────────── A4: 批次级投料 ─────────────────────────

    private ProcessingServiceImpl processingService() throws Exception {
        Constructor<?> ctor = null;
        for (Constructor<?> c : ProcessingServiceImpl.class.getDeclaredConstructors()) {
            if (ctor == null || c.getParameterCount() > ctor.getParameterCount()) {
                ctor = c;
            }
        }
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class) args[i] = plans;
            else if (types[i] == MaterialBatchRepository.class) args[i] = batches;
            else if (types[i] == MaterialConsumptionRepository.class) args[i] = consumptions;
            else if (types[i] == ProductionBatchRepository.class) args[i] = productionBatches;
            else if (types[i] == RawMaterialTypeRepository.class) args[i] = materialTypes;
            else if (!types[i].isPrimitive()) args[i] = mock(types[i]);
        }
        ProcessingServiceImpl svc = (ProcessingServiceImpl) ctor.newInstance(args);
        ReflectionTestUtils.setField(svc, "inventoryLowStockEventPublisher", mock(InventoryLowStockEventPublisher.class));
        return svc;
    }

    private void productionBatchOnPlan(String planId) {
        ProductionBatch pb = new ProductionBatch();
        pb.setId(501L);
        pb.setFactoryId(F);
        pb.setCreatedBy(9L);
        pb.setProductionPlanId(planId);
        when(productionBatches.findByIdAndFactoryId(501L, F)).thenReturn(Optional.of(pb));
        when(productionBatches.findById(501L)).thenReturn(Optional.of(pb));
    }

    private static List<Map<String, Object>> feed(String qty) {
        return List.of(Map.of("materialBatchId", "MB-1", "quantity", qty));
    }

    @Test
    @DisplayName("A4 批次级投料: 待检 / 过期 / 报废 / 不良品 → 409, 不扣不记")
    void batchFeedRejectsBlockedBatches() throws Exception {
        ProcessingServiceImpl svc = processingService();
        productionBatchOnPlan("PLAN-1");
        for (MaterialBatchStatus st : BLOCKED) {
            batch.setStatus(st);
            assertThatThrownBy(() -> svc.recordMaterialConsumption(F, "501", feed("5"), 42L))
                    .as("%s", st).isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getActionHint()).isNotBlank();
                    });
        }
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("0");
        verify(consumptions, never()).save(any());
    }

    @Test
    @DisplayName("A4 批次级投料归属: 工厂备料计划不能投客户来料; 批次挂的计划查不到 → 404; 阴性对照: 公司料照常")
    void batchFeedChecksOwnership() throws Exception {
        ProcessingServiceImpl svc = processingService();
        productionBatchOnPlan("PLAN-1");
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        assertThatThrownBy(() -> svc.recordMaterialConsumption(F, "501", feed("5"), 42L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("CUSTOMER_OWNED_MATERIAL_FORBIDDEN"));

        batch.setOwnership(InventoryOwnership.COMPANY_OWNED);
        productionBatchOnPlan("PLAN-GONE");
        assertThatThrownBy(() -> svc.recordMaterialConsumption(F, "501", feed("5"), 42L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PRODUCTION_MATERIAL_PLAN_NOT_FOUND"));
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("0");
        verify(consumptions, never()).save(any());

        productionBatchOnPlan("PLAN-1");
        svc.recordMaterialConsumption(F, "501", feed("5"), 42L);
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("5");
    }
}
