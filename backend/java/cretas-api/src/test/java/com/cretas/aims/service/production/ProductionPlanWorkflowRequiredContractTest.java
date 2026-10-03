package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T127 — 存货生产(SAFETY_STOCK) 独立来源验证单测.
 *
 * <p>验证:
 * <ol>
 *   <li>SAFETY_STOCK 来源不绑 SO → 跳过 SO 校验, 创建计划成功, sourceOrderId 为空</li>
 *   <li>SAFETY_STOCK 来源不强制 processName/batchDate (仅 CUSTOMER_ORDER 强制)</li>
 *   <li>SAFETY_STOCK 来源持久化的计划 sourceOrderId 为 null</li>
 *   <li>CUSTOMER_ORDER 来源不传 sourceOrderItemId → 报 400 (回归: SO 校验仍有效)</li>
 * </ol>
 *
 * @author Cretas Team
 * @since 2026-06-07
 */
@DisplayName("建计划: LEGACY 退役后没有可用画布工艺 = 不能生产 (2026-10-03 补测试: 主仓 08-09 退 LEGACY 时没有对应断言)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanWorkflowRequiredContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "1d7fbd73-8797-4933-83f1-46413a45992d"; // 掌中宝

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
    @Mock private BomService bomService;
    @Mock private EntityManager entityManager;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        // ⛔ 本类刻意**不**装 WorkflowAuthorityTestFixture: 默认就是「产品没有可用画布工艺」。
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        // Product type must exist
        when(productTypeRepository.existsByIdAndFactoryId(PRODUCT_TYPE_ID, FACTORY_ID)).thenReturn(true);
        ProductType productType = new ProductType();
        productType.setId(PRODUCT_TYPE_ID);
        productType.setFactoryId(FACTORY_ID);
        productType.setUnit("box");
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT_TYPE_ID, FACTORY_ID))
                .thenReturn(Optional.of(productType));

        // Mapper stub: return a minimal plan entity mirroring sourceOrderId from request
        lenient().when(productionPlanMapper.toEntity(any(CreateProductionPlanRequest.class), any(), any()))
                .thenAnswer(inv -> {
                    CreateProductionPlanRequest req = inv.getArgument(0);
                    ProductionPlan plan = new ProductionPlan();
                    plan.setId("PP-STUB-001");
                    plan.setFactoryId(FACTORY_ID);
                    plan.setPlanNumber("PP-2026-001");
                    plan.setProductTypeId(req.getProductTypeId());
                    plan.setPlannedQuantity(req.getPlannedQuantity());
                    plan.setSourceType(req.getSourceType());
                    plan.setSourceOrderId(req.getSourceOrderId());
                    plan.setClientRequestId(req.getClientRequestId());
                    plan.setStatus(ProductionPlanStatus.PENDING);
                    return plan;
                });
        lenient().when(productionPlanRepository.save(any(ProductionPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productionPlanMapper.toDTO(any(ProductionPlan.class)))
                .thenReturn(new ProductionPlanDTO());
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
    }

    private MockedStatic<TransactionSynchronizationManager> mockTxSync() {
        MockedStatic<TransactionSynchronizationManager> mock =
                Mockito.mockStatic(TransactionSynchronizationManager.class);
        // Allow registerSynchronization to be called without throwing
        mock.when(() -> TransactionSynchronizationManager.registerSynchronization(any(TransactionSynchronization.class)))
                .then(inv -> null);
        return mock;
    }

    private CreateProductionPlanRequest safetyStockRequest() {
        CreateProductionPlanRequest req = new CreateProductionPlanRequest();
        req.setSourceType(PlanSourceType.SAFETY_STOCK);
        req.setProductTypeId(PRODUCT_TYPE_ID);
        req.setPlannedQuantity(new BigDecimal("246"));
        req.setPlannedDate(LocalDate.now());
        return req;
    }

    @Test
    @DisplayName("产品没有可用画布工艺 → 409 WORKFLOW_REQUIRED, 计划不落库 (以前静默回落成 LEGACY)")
    void productWithoutWorkflow_isRejected_andNothingIsSaved() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            CreateProductionPlanRequest req = safetyStockRequest();
            com.cretas.aims.exception.BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    com.cretas.aims.exception.BusinessException.class,
                    () -> service.createProductionPlan(FACTORY_ID, req, 1L));
            org.junit.jupiter.api.Assertions.assertEquals("WORKFLOW_REQUIRED", ex.getErrorCode());
            org.junit.jupiter.api.Assertions.assertEquals(409, ex.getCode());
            verify(productionPlanRepository, never()).save(any(ProductionPlan.class));
        }
    }

    @Test
    @DisplayName("阳性对照: 同一请求, 产品有可用画布工艺 → 建成, 计划钉成 WORKFLOW")
    void sameRequestWithWorkflow_isCreated_asWorkflowPlan() {
        WorkflowAuthorityTestFixture.install(service, FACTORY_ID, PRODUCT_TYPE_ID);
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            CreateProductionPlanRequest req = safetyStockRequest();
            assertDoesNotThrow(() -> service.createProductionPlan(FACTORY_ID, req, 1L));
            org.mockito.ArgumentCaptor<ProductionPlan> saved = org.mockito.ArgumentCaptor.forClass(ProductionPlan.class);
            verify(productionPlanRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
            org.junit.jupiter.api.Assertions.assertEquals(
                    ProductionBatch.WorkflowSelectionMode.WORKFLOW, saved.getValue().getWorkflowSelectionMode());
            org.junit.jupiter.api.Assertions.assertEquals(
                    WorkflowAuthorityTestFixture.WORKFLOW_ID, saved.getValue().getSelectedWorkflowId());
        }
    }
}
