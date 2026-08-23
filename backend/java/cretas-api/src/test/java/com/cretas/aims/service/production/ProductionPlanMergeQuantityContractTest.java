package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.BatchPlanFromSalesOrderRequest;
import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
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
import com.cretas.aims.service.production.SalesOrderPlanQuantityNormalizer;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试同学 2026-08-03 报的「[生产计划/SOP] 合并订单无法合并数量」:
 *
 * <blockquote>主10，关联5，计划自动生成10（应为15）。进入生产计划明细查看，销售订单只关联了
 * 主销售订单。进入销售订单模块查看关联销售订单的明细，发现状态仍未处于生产中。</blockquote>
 *
 * <p>三个症状同一个根因: web-admin 的合并选择器一直在收集 {@code extraSourceOrderIds},
 * 但它**从来没进过任何请求体**, 后端也没有接收它的字段 (形态 B: 机制在、没接上)。
 * prod 实证(只读): 8 张在用计划的 {@code source_order_ids} 长度全 ≤1, {@code related_orders} 全 NULL。
 *
 * <p>⚠️ 「生产中」是**派生**标签(由挂在该订单上的计划推出来), 不是 SalesOrderStatus ——
 * 前两层修对了它自然会亮, ⛔ 不给订单硬塞一个 PROCESSING 状态。所以本类不断言那个标签,
 * 只断言它依赖的那个事实: 追加订单进了计划的 sourceOrderIds。
 *
 * <p>⚠️ 硬约束 8: {@code remaining = ordered - delivered - alreadyPlanned} 跨 SO 之后
 * 每一项口径都变了, 这里逐项各钉一条。
 */
@DisplayName("合并订单: 数量要跨 SO 汇总, 来源要全部记下")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanMergeQuantityContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "1d7fbd73-8797-4933-83f1-46413a45992d";
    private static final String OTHER_PRODUCT_ID = "pt-other";
    private static final String SO_MAIN = "SO-MAIN-0001";
    private static final String SO_EXTRA = "SO-EXTRA-0002";
    private static final Long ITEM_MAIN = 9101L;
    private static final Long ITEM_EXTRA = 9202L;

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

    private ProductionPlanServiceImpl service;
    /** 最后一次交给 createProductionPlan 的请求 —— 断言就看它。 */
    private final List<CreateProductionPlanRequest> capturedRequests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        WorkflowAuthorityTestFixture.install(service, FACTORY_ID, PRODUCT_TYPE_ID);

        // 单位换算: SO 行与成品都用 kg ⇒ 走 sameUnit 直通, 不碰 UnitContractService。
        ReflectionTestUtils.setField(service, "salesOrderPlanQuantityNormalizer",
                new SalesOrderPlanQuantityNormalizer(mock(UnitContractService.class)));

        ProductType productType = new ProductType();
        productType.setId(PRODUCT_TYPE_ID);
        productType.setFactoryId(FACTORY_ID);
        productType.setUnit("kg");
        lenient().when(productTypeRepository.existsByIdAndFactoryId(PRODUCT_TYPE_ID, FACTORY_ID)).thenReturn(true);
        lenient().when(productTypeRepository.findByIdAndFactoryId(PRODUCT_TYPE_ID, FACTORY_ID))
                .thenReturn(Optional.of(productType));

        lenient().when(productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        lenient().when(productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        lenient().when(productionPlanRepository.findByFactoryIdAndCreatedByAndClientRequestId(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(productionPlanRepository.save(any(ProductionPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
        lenient().when(productionPlanMapper.toDTO(any(ProductionPlan.class))).thenReturn(new ProductionPlanDTO());

        lenient().when(productionPlanMapper.toEntity(any(CreateProductionPlanRequest.class), any(), any()))
                .thenAnswer(inv -> {
                    CreateProductionPlanRequest req = inv.getArgument(0);
                    capturedRequests.add(req);
                    ProductionPlan plan = new ProductionPlan();
                    plan.setId("PP-MERGE-001");
                    plan.setFactoryId(FACTORY_ID);
                    plan.setPlanNumber("PP-2026-MERGE-001");
                    plan.setProductTypeId(req.getProductTypeId());
                    plan.setPlannedQuantity(req.getPlannedQuantity());
                    plan.setPlannedUnit(req.getPlannedUnit());
                    plan.setSourceType(req.getSourceType());
                    plan.setSourceOrderId(req.getSourceOrderId());
                    plan.setSourceOrderItemId(req.getSourceOrderItemId());
                    plan.setSourceDisplayQuantity(req.getSourceDisplayQuantity());
                    plan.setProcessName(req.getProcessName());
                    // assertCompatibleOwnershipContract 比的是 plan 这四个字段 vs 追加 SO ——
                    // 桩里漏拷就会假红在「客户或加工/供料合同不一致」上(仪器问题, 不是产品问题)。
                    plan.setCustomerId(req.getCustomerId());
                    plan.setProcessingMode(req.getProcessingMode());
                    plan.setMaterialSupplyMode(req.getMaterialSupplyMode());
                    plan.setOutputOwnership(req.getOutputOwnership());
                    if (req.getSourceOrderIds() != null) {
                        plan.setSourceOrderIds(new ArrayList<>(req.getSourceOrderIds()));
                    }
                    plan.setStatus(ProductionPlanStatus.PENDING);
                    return plan;
                });
    }

    // ===================== helpers =====================

    /**
     * ⚠️ 合并有一道既有的闸 {@code assertCompatibleOwnershipContract}: 被合并的订单必须与主订单
     * **同客户 + 同加工/供料合同**。这是对的(不同客户的货不能并成一张工单), 所以这里的桩要给出
     * 一致的值 —— 真实合并场景本来就只在同客户同合同之间发生。
     */
    private SalesOrder stubOrder(String soId, SalesOrderStatus status) {
        SalesOrder so = new SalesOrder();
        so.setId(soId);
        so.setFactoryId(FACTORY_ID);
        so.setOrderNumber(soId);
        so.setStatus(status);
        so.setCustomerId("CUST-001");
        so.setProcessingMode(SalesProcessingMode.STANDARD_SALE);
        so.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        lenient().when(salesOrderRepository.findByIdAndFactoryIdForUpdate(soId, FACTORY_ID))
                .thenReturn(Optional.of(so));
        // createProductionPlan -> validateAndEnrichSalesOrderSource 走的是 findById
        lenient().when(salesOrderRepository.findById(soId)).thenReturn(Optional.of(so));
        return so;
    }

    /** 真实形状: SO 行带 productTypeId + quantity + unit, deliveredQuantity 常为 null。 */
    private SalesOrderItem stubLine(String soId, Long itemId, String productTypeId, String qty) {
        SalesOrderItem it = new SalesOrderItem();
        it.setId(itemId);
        it.setSalesOrderId(soId);
        it.setProductTypeId(productTypeId);
        it.setProductName("黄油鸡成品800g");
        it.setQuantity(new BigDecimal(qty));
        it.setUnit("kg");
        // createProductionPlan -> validateAndEnrichSalesOrderSource 按数字 id 反查这一行
        lenient().when(salesOrderItemRepository.findById(itemId)).thenReturn(Optional.of(it));
        return it;
    }

    private void stubLines(String soId, SalesOrderItem... items) {
        lenient().when(salesOrderItemRepository.findBySalesOrderId(soId)).thenReturn(List.of(items));
    }

    /** createProductionPlan 会注册 afterCommit 自动排程; 单测里没有活动事务, 静态桩掉。 */
    private MockedStatic<TransactionSynchronizationManager> mockTxSync() {
        MockedStatic<TransactionSynchronizationManager> mock =
                Mockito.mockStatic(TransactionSynchronizationManager.class);
        mock.when(() -> TransactionSynchronizationManager.registerSynchronization(
                        any(TransactionSynchronization.class)))
                .then(inv -> null);
        return mock;
    }

    private BatchPlanFromSalesOrderRequest request(List<String> extras) {
        BatchPlanFromSalesOrderRequest req = new BatchPlanFromSalesOrderRequest();
        req.setSourceOrderId(SO_MAIN);
        req.setItemIds(List.of(String.valueOf(ITEM_MAIN)));
        req.setPlannedDate(LocalDate.of(2026, 8, 20));
        req.setBatchDate(LocalDate.of(2026, 8, 20));
        req.setClientRequestId("merge-test-1");
        req.setExtraSourceOrderIds(extras);
        return req;
    }

    // ===================== tests =====================

    @Test
    @DisplayName("主10 + 关联5 → 计划数量 15 (报告里那个 10 应为 15)")
    void mergedPlanSumsQuantitiesAcrossOrders() {
        stubOrder(SO_MAIN, SalesOrderStatus.FINANCE_APPROVED);
        stubOrder(SO_EXTRA, SalesOrderStatus.FINANCE_APPROVED);
        stubLines(SO_MAIN, stubLine(SO_MAIN, ITEM_MAIN, PRODUCT_TYPE_ID, "10"));
        stubLines(SO_EXTRA, stubLine(SO_EXTRA, ITEM_EXTRA, PRODUCT_TYPE_ID, "5"));

        try (MockedStatic<TransactionSynchronizationManager> tx = mockTxSync()) {
            service.createPlansFromSalesOrder(FACTORY_ID, request(List.of(SO_EXTRA)), 1L);
        }

        assertThat(capturedRequests).hasSize(1);
        assertThat(capturedRequests.get(0).getPlannedQuantity())
                .as("主10 + 关联5 必须合并成 15 —— 这就是测试同学报的那个数")
                .isEqualByComparingTo(new BigDecimal("15"));
    }

    @Test
    @DisplayName("计划要记下全部来源订单 —— 详情页「合并订单」和销售订单侧的派生「生产中」都靠它")
    void mergedPlanRecordsAllSourceOrders() {
        stubOrder(SO_MAIN, SalesOrderStatus.FINANCE_APPROVED);
        stubOrder(SO_EXTRA, SalesOrderStatus.FINANCE_APPROVED);
        stubLines(SO_MAIN, stubLine(SO_MAIN, ITEM_MAIN, PRODUCT_TYPE_ID, "10"));
        stubLines(SO_EXTRA, stubLine(SO_EXTRA, ITEM_EXTRA, PRODUCT_TYPE_ID, "5"));

        try (MockedStatic<TransactionSynchronizationManager> tx = mockTxSync()) {
            service.createPlansFromSalesOrder(FACTORY_ID, request(List.of(SO_EXTRA)), 1L);
        }

        assertThat(capturedRequests.get(0).getSourceOrderIds())
                .as("prod 实测 8 张计划的 source_order_ids 长度全 ≤1 —— 就是这里没写进去")
                .containsExactlyInAnyOrder(SO_MAIN, SO_EXTRA);
        assertThat(capturedRequests.get(0).getSourceOrderId())
                .as("主订单仍是主订单")
                .isEqualTo(SO_MAIN);
    }

    /** 阳性对照: 不传 extras 时保持原行为, 证明上面两条不是「一律合并」。 */
    @Test
    @DisplayName("阳性对照: 不合并时仍是单订单的 10")
    void withoutExtrasBehaviourIsUnchanged() {
        stubOrder(SO_MAIN, SalesOrderStatus.FINANCE_APPROVED);
        stubLines(SO_MAIN, stubLine(SO_MAIN, ITEM_MAIN, PRODUCT_TYPE_ID, "10"));

        try (MockedStatic<TransactionSynchronizationManager> tx = mockTxSync()) {
            service.createPlansFromSalesOrder(FACTORY_ID, request(null), 1L);
        }

        assertThat(capturedRequests.get(0).getPlannedQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(capturedRequests.get(0).getSourceOrderIds()).containsExactly(SO_MAIN);
    }

    @Test
    @DisplayName("硬约束8-口径: 合并方的 deliveredQuantity 要各自扣 (关联 5 已交付 2 → 只合并 3)")
    void mergedQuantityHonoursEachLinesDeliveredQuantity() {
        stubOrder(SO_MAIN, SalesOrderStatus.FINANCE_APPROVED);
        stubOrder(SO_EXTRA, SalesOrderStatus.FINANCE_APPROVED);
        stubLines(SO_MAIN, stubLine(SO_MAIN, ITEM_MAIN, PRODUCT_TYPE_ID, "10"));
        SalesOrderItem extra = stubLine(SO_EXTRA, ITEM_EXTRA, PRODUCT_TYPE_ID, "5");
        extra.setDeliveredQuantity(new BigDecimal("2"));
        stubLines(SO_EXTRA, extra);

        try (MockedStatic<TransactionSynchronizationManager> tx = mockTxSync()) {
            service.createPlansFromSalesOrder(FACTORY_ID, request(List.of(SO_EXTRA)), 1L);
        }

        assertThat(capturedRequests.get(0).getPlannedQuantity())
                .as("10 + (5-2) = 13, ⛔ 不是 15 —— delivered 必须逐行取, 不能只对主行扣")
                .isEqualByComparingTo(new BigDecimal("13"));
    }

    @Test
    @DisplayName("合并订单里没有这个产品 → 明确拒绝, ⛔ 不静默忽略")
    void mergingAnOrderWithoutThatProductIsRejected() {
        stubOrder(SO_MAIN, SalesOrderStatus.FINANCE_APPROVED);
        stubOrder(SO_EXTRA, SalesOrderStatus.FINANCE_APPROVED);
        stubLines(SO_MAIN, stubLine(SO_MAIN, ITEM_MAIN, PRODUCT_TYPE_ID, "10"));
        stubLines(SO_EXTRA, stubLine(SO_EXTRA, ITEM_EXTRA, OTHER_PRODUCT_ID, "5"));

        assertThatThrownBy(() -> service.createPlansFromSalesOrder(FACTORY_ID, request(List.of(SO_EXTRA)), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("合并的销售订单里没有该产品");
    }

    @Test
    @DisplayName("合并订单未财审 → 拒绝 (与主订单同一道闸)")
    void mergingAnUnapprovedOrderIsRejected() {
        stubOrder(SO_MAIN, SalesOrderStatus.FINANCE_APPROVED);
        stubOrder(SO_EXTRA, SalesOrderStatus.CONFIRMED);
        stubLines(SO_MAIN, stubLine(SO_MAIN, ITEM_MAIN, PRODUCT_TYPE_ID, "10"));
        stubLines(SO_EXTRA, stubLine(SO_EXTRA, ITEM_EXTRA, PRODUCT_TYPE_ID, "5"));

        assertThatThrownBy(() -> service.createPlansFromSalesOrder(FACTORY_ID, request(List.of(SO_EXTRA)), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未通过财务审核");
    }

    /**
     * 硬约束8-alreadyPlanned: 合并计划只存得下主行一个 sourceOrderItemId ⇒ 被合并的订单
     * 在 effectivePlannedByItem 里查不到。若不另外判据, 第二次合并会重复排产同一批货。
     */
    @Test
    @DisplayName("硬约束8-口径: 已被别的计划合并吃掉的订单, 再合并要 fail-loud")
    void alreadyMergedOrderCannotBeMergedAgain() {
        stubOrder(SO_MAIN, SalesOrderStatus.FINANCE_APPROVED);
        stubOrder(SO_EXTRA, SalesOrderStatus.FINANCE_APPROVED);
        stubLines(SO_MAIN, stubLine(SO_MAIN, ITEM_MAIN, PRODUCT_TYPE_ID, "10"));
        stubLines(SO_EXTRA, stubLine(SO_EXTRA, ITEM_EXTRA, PRODUCT_TYPE_ID, "5"));

        // 已存在一张把 SO_EXTRA 合并进去的有效计划 (它的主行属于另一张订单)
        ProductionPlan existing = new ProductionPlan();
        existing.setId("PP-EXISTING");
        existing.setFactoryId(FACTORY_ID);
        existing.setProductTypeId(PRODUCT_TYPE_ID);
        existing.setSourceOrderId("SO-SOMEONE-ELSE");
        existing.setSourceOrderItemId("8888");
        existing.setSourceOrderIds(new ArrayList<>(List.of("SO-SOMEONE-ELSE", SO_EXTRA)));
        existing.setStatus(ProductionPlanStatus.PENDING);
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(
                FACTORY_ID, "[\"" + SO_EXTRA + "\"]")).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.createPlansFromSalesOrder(FACTORY_ID, request(List.of(SO_EXTRA)), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被有效生产计划覆盖");
    }

    /** 阴性对照: 已取消的计划不占用数量 —— 否则上一条会把合法合并也挡掉。 */
    @Test
    @DisplayName("阴性对照: 已取消的合并计划不占用, 仍可重新合并")
    void cancelledMergePlanDoesNotBlock() {
        stubOrder(SO_MAIN, SalesOrderStatus.FINANCE_APPROVED);
        stubOrder(SO_EXTRA, SalesOrderStatus.FINANCE_APPROVED);
        stubLines(SO_MAIN, stubLine(SO_MAIN, ITEM_MAIN, PRODUCT_TYPE_ID, "10"));
        stubLines(SO_EXTRA, stubLine(SO_EXTRA, ITEM_EXTRA, PRODUCT_TYPE_ID, "5"));

        ProductionPlan cancelled = new ProductionPlan();
        cancelled.setId("PP-CANCELLED");
        cancelled.setFactoryId(FACTORY_ID);
        cancelled.setProductTypeId(PRODUCT_TYPE_ID);
        cancelled.setSourceOrderId("SO-SOMEONE-ELSE");
        cancelled.setSourceOrderItemId("8888");
        cancelled.setSourceOrderIds(new ArrayList<>(List.of("SO-SOMEONE-ELSE", SO_EXTRA)));
        cancelled.setStatus(ProductionPlanStatus.CANCELLED);
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(
                FACTORY_ID, "[\"" + SO_EXTRA + "\"]")).thenReturn(List.of(cancelled));

        try (MockedStatic<TransactionSynchronizationManager> tx = mockTxSync()) {
            service.createPlansFromSalesOrder(FACTORY_ID, request(List.of(SO_EXTRA)), 1L);
        }

        assertThat(capturedRequests.get(0).getPlannedQuantity()).isEqualByComparingTo(new BigDecimal("15"));
    }
}
