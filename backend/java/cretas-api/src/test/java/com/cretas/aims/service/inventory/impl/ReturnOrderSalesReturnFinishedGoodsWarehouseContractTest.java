package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.voucher.VoucherService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 测试同学 2026-08-07 报的「销售退回成功的成品显示会自动退回至成品仓，但是没有」。
 *
 * <p>prod 实证 (2026-08-19 查证): 三条完成过的销售退货单在日志里都打了
 * {@code 销售退货 不良品入库完成 ... warehouse=WH-LOG} —— 落的是**物流仓**, 而界面承诺的是
 * **成品仓**。F006 一直有 WH-FG 成品仓, {@code resolveFinishedGoodsId} 也一直存在。
 *
 * <p>本闸钉三件事:
 * <ol>
 *   <li><b>落点</b>: 销售退货的成品批次必须落 {@code resolveFinishedGoodsId} (成品仓),
 *       ⛔ 不是 {@code resolveLogisticsId} (物流仓)。</li>
 *   <li><b>隔离状态保留</b>: status 仍是 {@code DEFECTIVE} —— 退回来的货不可直接再卖,
 *       被 {@code status='AVAILABLE'} 的查询过滤掉**是设计意图**, 不是缺陷。
 *       这是一条**阴性对照**: 防止「修落点」时顺手把隔离也改掉。</li>
 *   <li><b>失败要阻断</b>: 入库异常不再被吞成 log.error 后照常 COMPLETED ——
 *       与同一方法里 PURCHASE_RETURN 分支的 fail-loud 纪律一致 (形态 D: 同一件事两份相反行为)。</li>
 * </ol>
 */
@DisplayName("销售退货 with-goods: 成品落成品仓 + 保持隔离 + 入库失败要阻断")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReturnOrderSalesReturnFinishedGoodsWarehouseContractTest {

    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private ReturnOrderItemRepository returnOrderItemRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private UnitContractService unitContractService;
    @Mock private EntityManager entityManager;
    @Mock private Query rowLockQuery;
    @Mock private com.cretas.aims.service.finance.AccountingPeriodService accountingPeriodService;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    @Mock private SalesDeliveryItemBatchAllocationRepository salesDeliveryItemBatchAllocationRepository;
    @Mock private VoucherService voucherService;

    private ReturnOrderServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String WH_FG = "wh-fg-f006";   // 成品仓 (正确落点)
    private static final String WH_LOG = "wh-log-f006"; // 物流仓 (旧的错误落点)
    private static final Long APPROVER = 1309L;

    @Test void hardLockedPhysicalReturnCannotWriteStockOrReceivable() {
        ReturnOrder order = salesReturn(List.of(productLine("PRODUCT-LOCK", BigDecimal.ONE)));
        org.mockito.Mockito.doThrow(new com.cretas.aims.exception.PeriodClosedException(FACTORY,2026,10))
                .when(accountingPeriodService).assertOpen(FACTORY,2026,10);
        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY,order.getId()))
                .isInstanceOf(com.cretas.aims.exception.PeriodClosedException.class);
        org.mockito.Mockito.verifyNoInteractions(arApService);
        org.mockito.Mockito.verify(finishedGoodsBatchRepository,org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        org.mockito.InOrder sequence = org.mockito.Mockito.inOrder(entityManager,accountingPeriodService);
        sequence.verify(entityManager).createNativeQuery(org.mockito.ArgumentMatchers.startsWith("SELECT pg_advisory_xact_lock"));
        sequence.verify(accountingPeriodService).assertOpen(FACTORY,2026,10);
    }

    @BeforeEach
    void setUp() {
        service = new ReturnOrderServiceImpl(
                returnOrderRepository, returnOrderItemRepository, arApService, applicationEventPublisher);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "accountingPeriodService", accountingPeriodService);
        ReflectionTestUtils.setField(service, "salesOrderItemRepository", salesOrderItemRepository);
        ReflectionTestUtils.setField(service, "salesDeliveryRecordRepository", salesDeliveryRecordRepository);
        ReflectionTestUtils.setField(service, "salesDeliveryItemBatchAllocationRepository",
                salesDeliveryItemBatchAllocationRepository);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        when(entityManager.createNativeQuery(anyString())).thenReturn(rowLockQuery);
        when(rowLockQuery.setParameter(anyString(), any())).thenReturn(rowLockQuery);
        when(warehouseResolver.resolveFinishedGoodsId(FACTORY)).thenReturn(WH_FG);
        when(warehouseResolver.resolveLogisticsId(FACTORY)).thenReturn(WH_LOG);
        when(unitContractService.storageUnit(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(returnOrderRepository.save(any(ReturnOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** 真实形状: prod 的 SALES_RETURN 行带 productTypeId + 正数量 (materialTypeId 恒为空)。 */
    private ReturnOrderItem productLine(String productTypeId, BigDecimal qty) {
        ReturnOrderItem it = new ReturnOrderItem();
        it.setProductTypeId(productTypeId);
        it.setBatchNumber("SRC-" + productTypeId);
        it.setItemName("SOP-20260730-01-黄油鸡-成品800g");
        it.setQuantity(qty);
        it.setUnitPrice(new BigDecimal("40.00"));
        it.setReason("质量问题");
        return it;
    }

    private ReturnOrder salesReturn(List<ReturnOrderItem> items) {
        ReturnOrder o = new ReturnOrder();
        o.setId("RO-" + System.nanoTime());
        o.setFactoryId(FACTORY);
        o.setReturnNumber("RT-SAL-20260807-0001");
        o.setReturnType(ReturnType.SALES_RETURN);
        o.setWithGoods(true);
        o.setStatus(ReturnOrderStatus.FINANCE_APPROVED);
        o.setCounterpartyId("CUST-001");
        o.setSourceOrderId("SO-001");
        o.setReturnDate(java.time.LocalDate.of(2026, 10, 18));
        o.setTotalAmount(new BigDecimal("200.00"));
        o.setReason("质量问题");
        o.setApprovedBy(APPROVER);
        o.setItems(items);
        when(returnOrderRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(returnOrderRepository.save(o)).thenReturn(o);
        when(rowLockQuery.getResultList()).thenReturn(List.of(o.getId()));
        for (ReturnOrderItem item : items) {
            if (item != null && item.getProductTypeId() != null && item.getQuantity() != null
                    && item.getQuantity().signum() > 0 && item.getBatchNumber() != null) {
                when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(
                        FACTORY, item.getBatchNumber()))
                        .thenReturn(Optional.of(sourceBatch(item.getBatchNumber(), item.getProductTypeId(), "kg")));
            }
        }
        stubCompletionShipmentEvidence(items);
        return o;
    }

    /** Completion re-validates the same real shipped allocation used by creation. */
    private void stubCompletionShipmentEvidence(List<ReturnOrderItem> items) {
        List<SalesOrderItem> orderLines = new ArrayList<>();
        List<SalesDeliveryItem> deliveryItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ReturnOrderItem item = items.get(i);
            if (item == null || item.getProductTypeId() == null || item.getQuantity() == null
                    || item.getQuantity().signum() <= 0 || item.getBatchNumber() == null) continue;
            long orderLineId = 100L + i;
            long deliveryItemId = 200L + i;
            SalesOrderItem orderLine = new SalesOrderItem();
            orderLine.setId(orderLineId);
            orderLine.setProductTypeId(item.getProductTypeId());
            orderLine.setUnit("kg");
            orderLines.add(orderLine);

            SalesDeliveryItem deliveryItem = new SalesDeliveryItem();
            deliveryItem.setId(deliveryItemId);
            deliveryItem.setDeliveryRecordId("DEL-DEFAULT");
            deliveryItem.setSalesOrderItemId(orderLineId);
            deliveryItem.setProductTypeId(item.getProductTypeId());
            deliveryItem.setUnit("kg");
            deliveryItems.add(deliveryItem);

            SalesDeliveryItemBatchAllocation allocation = allocation(
                    "SOURCE-" + item.getBatchNumber(), item.getBatchNumber(), "kg",
                    item.getQuantity().toPlainString());
            allocation.setDeliveryItemId(String.valueOf(deliveryItemId));
            when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndDeliveryItemId(
                    FACTORY, String.valueOf(deliveryItemId))).thenReturn(List.of(allocation));
            FinishedGoodsBatch source = sourceBatch(item.getBatchNumber(), item.getProductTypeId(), "kg");
            source.setId("SOURCE-" + item.getBatchNumber());
            when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate(
                    source.getId(), FACTORY)).thenReturn(Optional.of(source));
        }
        when(salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId("SO-001", FACTORY))
                .thenReturn(orderLines);
        SalesDeliveryRecord delivery = new SalesDeliveryRecord();
        delivery.setId("DEL-DEFAULT");
        delivery.setFactoryId(FACTORY);
        delivery.setSalesOrderId("SO-001");
        delivery.setStatus(SalesDeliveryStatus.SHIPPED);
        delivery.setItems(deliveryItems);
        when(salesDeliveryRecordRepository.findBySalesOrderId("SO-001"))
                .thenReturn(orderLines.isEmpty() ? Collections.emptyList() : List.of(delivery));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "SO-001"))
                .thenReturn(List.of());
    }

    private FinishedGoodsBatch sourceBatch(String batchNumber, String productTypeId, String unit) {
        FinishedGoodsBatch source = new FinishedGoodsBatch();
        source.setId("SOURCE-" + batchNumber);
        source.setFactoryId(FACTORY);
        source.setBatchNumber(batchNumber);
        source.setProductTypeId(productTypeId);
        source.setUnit(unit);
        return source;
    }

    private CreateReturnOrderRequest createSalesReturnRequest(String productTypeId, BigDecimal quantity,
                                                              String batchNumber) {
        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setReturnType(ReturnType.SALES_RETURN.name());
        request.setCounterpartyId("CUST-001");
        request.setSourceOrderId("SO-001");
        request.setReturnDate(java.time.LocalDate.now());
        request.setWithGoods(true);
        CreateReturnOrderRequest.ReturnOrderItemDTO item = new CreateReturnOrderRequest.ReturnOrderItemDTO();
        item.setProductTypeId(productTypeId);
        item.setQuantity(quantity);
        item.setBatchNumber(batchNumber);
        request.setItems(List.of(item));
        return request;
    }

    private void stubUniqueShippedAllocation(String productTypeId, String orderUnit,
                                              String batchId, String batchNumber, String sourceUnit,
                                              BigDecimal quantity) {
        SalesOrderItem orderLine = new SalesOrderItem();
        orderLine.setId(11L);
        orderLine.setProductTypeId(productTypeId);
        orderLine.setUnit(orderUnit);
        when(salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId("SO-001", FACTORY))
                .thenReturn(List.of(orderLine));

        SalesDeliveryItem deliveryItem = new SalesDeliveryItem();
        deliveryItem.setId(21L);
        deliveryItem.setDeliveryRecordId("DEL-001");
        deliveryItem.setSalesOrderItemId(11L);
        deliveryItem.setProductTypeId(productTypeId);
        deliveryItem.setUnit(orderUnit);
        SalesDeliveryRecord delivery = new SalesDeliveryRecord();
        delivery.setId("DEL-001");
        delivery.setFactoryId(FACTORY);
        delivery.setSalesOrderId("SO-001");
        delivery.setStatus(SalesDeliveryStatus.SHIPPED);
        delivery.setItems(List.of(deliveryItem));
        when(salesDeliveryRecordRepository.findBySalesOrderId("SO-001"))
                .thenReturn(List.of(delivery));

        SalesDeliveryItemBatchAllocation allocation = new SalesDeliveryItemBatchAllocation();
        allocation.setFactoryId(FACTORY);
        allocation.setDeliveryItemId("21");
        allocation.setFinishedGoodsBatchId(batchId);
        allocation.setBatchNumber(batchNumber);
        allocation.setAllocatedQty(quantity);
        allocation.setUnit(orderUnit);
        when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndDeliveryItemId(FACTORY, "21"))
                .thenReturn(List.of(allocation));
        FinishedGoodsBatch source = sourceBatch(batchNumber, productTypeId, sourceUnit);
        source.setId(batchId);
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate(batchId, FACTORY))
                .thenReturn(Optional.of(source));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "SO-001"))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("普通销售退货缺少批次号时，仅从唯一真实发货 allocation 派生原成品批次")
    void createSalesReturnDerivesUniqueShippedBatch() {
        stubUniqueShippedAllocation("PT-001", "kg", "FG-001", "SRC-001", "kg", new BigDecimal("5"));
        CreateReturnOrderRequest request = createSalesReturnRequest("PT-001", new BigDecimal("2"), null);

        ReturnOrder created = service.createReturnOrder(FACTORY, request, APPROVER);

        assertThat(request.getItems().get(0).getBatchNumber()).isEqualTo("SRC-001");
        assertThat(created).isNotNull();
        verify(returnOrderItemRepository).saveAll(argThat(items -> {
            List<ReturnOrderItem> saved = new ArrayList<>();
            items.forEach(item -> saved.add(item));
            return saved.size() == 1
                    && "FG-001".equals(saved.get(0).getSourceFinishedGoodsBatchId());
        }));
    }

    @Test
    @DisplayName("无批次时多源 FG、单位不一致或显式错批次均拒绝并提示按实际批次登记")
    void createSalesReturnRejectsAmbiguousOrMismatchedShipmentEvidence() {
        stubUniqueShippedAllocation("PT-001", "kg", "FG-001", "SRC-001", "kg", new BigDecimal("2"));
        SalesDeliveryItemBatchAllocation second = new SalesDeliveryItemBatchAllocation();
        second.setFactoryId(FACTORY);
        second.setDeliveryItemId("21");
        second.setFinishedGoodsBatchId("FG-002");
        second.setBatchNumber("SRC-002");
        second.setAllocatedQty(new BigDecimal("2"));
        second.setUnit("kg");
        when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndDeliveryItemId(FACTORY, "21"))
                .thenReturn(List.of(allocation("FG-001", "SRC-001", "kg", "2"), second));
        assertThatThrownBy(() -> service.createReturnOrder(
                FACTORY, createSalesReturnRequest("PT-001", new BigDecimal("1"), null), APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("多个实际成品批次")
                .hasFieldOrPropertyWithValue("errorCode", "SALES_RETURN_SHIPMENT_EVIDENCE_REQUIRED");

        stubUniqueShippedAllocation("PT-001", "盒", "FG-003", "SRC-003", "kg", new BigDecimal("2"));
        assertThatThrownBy(() -> service.createReturnOrder(
                FACTORY, createSalesReturnRequest("PT-001", new BigDecimal("1"), null), APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单位不一致")
                .hasFieldOrPropertyWithValue("errorCode", "SALES_RETURN_SHIPMENT_EVIDENCE_REQUIRED")
                .hasFieldOrPropertyWithValue("actionHint", "请按实际发货批次分别登记退货；系统不会把盒直接当作 kg");

        stubUniqueShippedAllocation("PT-001", "kg", "FG-004", "SRC-004", "kg", new BigDecimal("2"));
        assertThatThrownBy(() -> service.createReturnOrder(
                FACTORY, createSalesReturnRequest("PT-001", new BigDecimal("1"), "NOT-SHIPPED"), APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有对应的已发货成品批次");

        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));
    }

    @Test
    @DisplayName("普通入口过滤跨工厂/错 delivery allocation，并按同批次合计申请量检查上限")
    void createSalesReturnRejectsForeignAllocationAndAggregatesSameBatchQuantity() {
        stubUniqueShippedAllocation("PT-001", "kg", "FG-001", "SRC-001", "kg", new BigDecimal("5"));
        SalesDeliveryItemBatchAllocation foreign = allocation("FG-001", "SRC-001", "kg", "5");
        foreign.setFactoryId("F007");
        when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndDeliveryItemId(FACTORY, "21"))
                .thenReturn(List.of(foreign));
        assertThatThrownBy(() -> service.createReturnOrder(
                FACTORY, createSalesReturnRequest("PT-001", new BigDecimal("1"), null), APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有对应的已发货成品批次");

        stubUniqueShippedAllocation("PT-001", "kg", "FG-001", "SRC-001", "kg", new BigDecimal("5"));
        CreateReturnOrderRequest overLimit = createSalesReturnRequest(
                "PT-001", new BigDecimal("3"), "SRC-001");
        CreateReturnOrderRequest.ReturnOrderItemDTO second =
                new CreateReturnOrderRequest.ReturnOrderItemDTO();
        second.setProductTypeId("PT-001");
        second.setQuantity(new BigDecimal("3"));
        second.setBatchNumber("SRC-001");
        overLimit.setItems(List.of(overLimit.getItems().get(0), second));
        assertThatThrownBy(() -> service.createReturnOrder(FACTORY, overLimit, APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可退上限");

        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));
    }

    @Test
    @DisplayName("实物销售退货缺少源销售订单时创建和完成都拒绝")
    void physicalSalesReturnRequiresSourceOrderOnCreateAndCompletion() {
        CreateReturnOrderRequest request = createSalesReturnRequest("PT-001", new BigDecimal("1"), "SRC-001");
        request.setSourceOrderId(null);
        assertThatThrownBy(() -> service.createReturnOrder(FACTORY, request, APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须关联源销售订单")
                .hasFieldOrPropertyWithValue("errorCode", "SALES_RETURN_SHIPMENT_EVIDENCE_REQUIRED");
        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));

        ReturnOrder historical = salesReturn(List.of(productLine("PT-001", new BigDecimal("1"))));
        historical.setSourceOrderId(null);
        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, historical.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须关联源销售订单");
        verify(finishedGoodsBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("退款-only历史单不占用实物退货批次上限")
    void refundOnlyReturnDoesNotConsumePhysicalShipmentCap() {
        stubUniqueShippedAllocation("PT-001", "kg", "FG-001", "SRC-001", "kg", new BigDecimal("5"));
        ReturnOrder refundOnly = new ReturnOrder();
        refundOnly.setId("REFUND-ONLY");
        refundOnly.setFactoryId(FACTORY);
        refundOnly.setReturnType(ReturnType.SALES_RETURN);
        refundOnly.setSourceOrderId("SO-001");
        refundOnly.setWithGoods(false);
        refundOnly.setStatus(ReturnOrderStatus.COMPLETED);
        ReturnOrderItem refundLine = productLine("PT-001", new BigDecimal("5"));
        refundLine.setBatchNumber("SRC-001");
        refundOnly.setItems(List.of(refundLine));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "SO-001"))
                .thenReturn(List.of(refundOnly));

        ReturnOrder created = service.createReturnOrder(
                FACTORY, createSalesReturnRequest("PT-001", new BigDecimal("5"), "SRC-001"), APPROVER);

        assertThat(created).isNotNull();
        verify(returnOrderItemRepository).saveAll(any());
    }

    private void stubSameBatchAcrossTwoShipments() {
        SalesOrderItem orderLine = new SalesOrderItem();
        orderLine.setId(11L);
        orderLine.setProductTypeId("PT-001");
        orderLine.setUnit("kg");
        when(salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId("SO-001", FACTORY))
                .thenReturn(List.of(orderLine));

        SalesDeliveryItem first = deliveryItem(21L, "DEL-A", 11L);
        SalesDeliveryItem second = deliveryItem(22L, "DEL-B", 11L);
        SalesDeliveryRecord firstDelivery = delivery("DEL-A", first);
        SalesDeliveryRecord secondDelivery = delivery("DEL-B", second);
        when(salesDeliveryRecordRepository.findBySalesOrderId("SO-001"))
                .thenReturn(List.of(firstDelivery, secondDelivery));
        SalesDeliveryItemBatchAllocation firstAllocation = allocation("FG-001", "SRC-001", "kg", "3");
        firstAllocation.setDeliveryItemId("21");
        SalesDeliveryItemBatchAllocation secondAllocation = allocation("FG-001", "SRC-001", "kg", "3");
        secondAllocation.setDeliveryItemId("22");
        when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndDeliveryItemId(FACTORY, "21"))
                .thenReturn(List.of(firstAllocation));
        when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndDeliveryItemId(FACTORY, "22"))
                .thenReturn(List.of(secondAllocation));
        FinishedGoodsBatch source = sourceBatch("SRC-001", "PT-001", "kg");
        source.setId("FG-001");
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("FG-001", FACTORY))
                .thenReturn(Optional.of(source));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "SO-001"))
                .thenReturn(List.of());
    }

    private SalesDeliveryItem deliveryItem(Long id, String deliveryId, Long orderLineId) {
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(id);
        item.setDeliveryRecordId(deliveryId);
        item.setSalesOrderItemId(orderLineId);
        item.setProductTypeId("PT-001");
        item.setUnit("kg");
        return item;
    }

    private SalesDeliveryRecord delivery(String id, SalesDeliveryItem item) {
        SalesDeliveryRecord delivery = new SalesDeliveryRecord();
        delivery.setId(id);
        delivery.setFactoryId(FACTORY);
        delivery.setSalesOrderId("SO-001");
        delivery.setStatus(SalesDeliveryStatus.SHIPPED);
        delivery.setItems(List.of(item));
        return delivery;
    }

    private CreateReturnOrderRequest requestWithLines(List<CreateReturnOrderRequest.ReturnOrderItemDTO> lines) {
        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setReturnType(ReturnType.SALES_RETURN.name());
        request.setCounterpartyId("CUST-001");
        request.setSourceOrderId("SO-001");
        request.setReturnDate(java.time.LocalDate.now());
        request.setWithGoods(true);
        request.setItems(lines);
        return request;
    }

    private CreateReturnOrderRequest.ReturnOrderItemDTO requestLine(
            String productTypeId, String batchNumber, String quantity) {
        CreateReturnOrderRequest.ReturnOrderItemDTO line =
                new CreateReturnOrderRequest.ReturnOrderItemDTO();
        line.setProductTypeId(productTypeId);
        line.setBatchNumber(batchNumber);
        line.setQuantity(new BigDecimal(quantity));
        return line;
    }

    private void stubTwoDistinctSourceAllocations() {
        SalesOrderItem firstLine = new SalesOrderItem();
        firstLine.setId(31L);
        firstLine.setProductTypeId("PT-A");
        firstLine.setUnit("kg");
        SalesOrderItem secondLine = new SalesOrderItem();
        secondLine.setId(32L);
        secondLine.setProductTypeId("PT-B");
        secondLine.setUnit("kg");
        when(salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId("SO-001", FACTORY))
                .thenReturn(List.of(firstLine, secondLine));

        SalesDeliveryItem firstDeliveryItem = deliveryItem(41L, "DEL-MULTI", 31L);
        firstDeliveryItem.setProductTypeId("PT-A");
        SalesDeliveryItem secondDeliveryItem = deliveryItem(42L, "DEL-MULTI", 32L);
        secondDeliveryItem.setProductTypeId("PT-B");
        SalesDeliveryRecord delivery = new SalesDeliveryRecord();
        delivery.setId("DEL-MULTI");
        delivery.setFactoryId(FACTORY);
        delivery.setSalesOrderId("SO-001");
        delivery.setStatus(SalesDeliveryStatus.SHIPPED);
        delivery.setItems(List.of(firstDeliveryItem, secondDeliveryItem));
        when(salesDeliveryRecordRepository.findBySalesOrderId("SO-001"))
                .thenReturn(List.of(delivery));

        SalesDeliveryItemBatchAllocation firstAllocation = allocation("FG-002", "SRC-A", "kg", "2");
        firstAllocation.setDeliveryItemId("41");
        SalesDeliveryItemBatchAllocation secondAllocation = allocation("FG-001", "SRC-B", "kg", "2");
        secondAllocation.setDeliveryItemId("42");
        when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndDeliveryItemId(FACTORY, "41"))
                .thenReturn(List.of(firstAllocation));
        when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndDeliveryItemId(FACTORY, "42"))
                .thenReturn(List.of(secondAllocation));

        FinishedGoodsBatch firstSource = sourceBatch("SRC-A", "PT-A", "kg");
        firstSource.setId("FG-002");
        FinishedGoodsBatch secondSource = sourceBatch("SRC-B", "PT-B", "kg");
        secondSource.setId("FG-001");
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("FG-001", FACTORY))
                .thenReturn(Optional.of(secondSource));
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("FG-002", FACTORY))
                .thenReturn(Optional.of(firstSource));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "SO-001"))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("同批次跨多个 shipment 无 marker 时拒绝猜分配")
    void sameBatchAcrossShipmentsWithoutMarkerIsRejected() {
        stubSameBatchAcrossTwoShipments();
        assertThatThrownBy(() -> service.createReturnOrder(
                FACTORY, createSalesReturnRequest("PT-001", new BigDecimal("1"), "SRC-001"), APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("多个实际发货 shipment");
        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));
    }

    @Test
    @DisplayName("反向退货行顺序仍按源批次 ID 升序加锁")
    void reverseLineOrderUsesStableSourceLockOrder() {
        stubTwoDistinctSourceAllocations();
        CreateReturnOrderRequest forward = requestWithLines(List.of(
                requestLine("PT-A", "SRC-A", "1"), requestLine("PT-B", "SRC-B", "1")));
        CreateReturnOrderRequest reverse = requestWithLines(List.of(
                requestLine("PT-B", "SRC-B", "1"), requestLine("PT-A", "SRC-A", "1")));

        service.createReturnOrder(FACTORY, forward, APPROVER);
        service.createReturnOrder(FACTORY, reverse, APPROVER);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(finishedGoodsBatchRepository);
        order.verify(finishedGoodsBatchRepository).findByIdAndFactoryIdForUpdate("FG-001", FACTORY);
        order.verify(finishedGoodsBatchRepository).findByIdAndFactoryIdForUpdate("FG-002", FACTORY);
        order.verify(finishedGoodsBatchRepository).findByIdAndFactoryIdForUpdate("FG-001", FACTORY);
        order.verify(finishedGoodsBatchRepository).findByIdAndFactoryIdForUpdate("FG-002", FACTORY);
    }

    @Test
    @DisplayName("同一源批次重复行只获取一次锁且仍按合计上限拒绝")
    void duplicateSourceBatchLocksOnceAndCapStillApplies() {
        stubTwoDistinctSourceAllocations();
        CreateReturnOrderRequest request = requestWithLines(List.of(
                requestLine("PT-A", "SRC-A", "2"), requestLine("PT-A", "SRC-A", "1")));

        assertThatThrownBy(() -> service.createReturnOrder(FACTORY, request, APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可退上限");
        verify(finishedGoodsBatchRepository, times(1))
                .findByIdAndFactoryIdForUpdate("FG-002", FACTORY);
        verify(finishedGoodsBatchRepository, never())
                .findByIdAndFactoryIdForUpdate("FG-001", FACTORY);
        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));
    }

    @Test
    @DisplayName("SHIPMENT_ID 必须属于当前销售订单的实际发货单")
    void shipmentMarkerMustBelongToCurrentOrder() {
        stubSameBatchAcrossTwoShipments();
        CreateReturnOrderRequest request = createSalesReturnRequest("PT-001", new BigDecimal("1"), "SRC-001");
        request.setRemark("RECALL_EVENT_ID=7;SHIPMENT_ID=DEL-OTHER;");
        assertThatThrownBy(() -> service.createReturnOrder(FACTORY, request, APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前订单");
        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));
    }

    @Test
    @DisplayName("SHIPMENT_ID 按该 shipment 单独累计并阻断超退")
    void shipmentMarkerUsesPerShipmentCumulativeCap() {
        stubSameBatchAcrossTwoShipments();
        ReturnOrder prior = new ReturnOrder();
        prior.setId("PRIOR-A");
        prior.setWithGoods(true);
        prior.setStatus(ReturnOrderStatus.DRAFT);
        prior.setRemark("RECALL_EVENT_ID=7;SHIPMENT_ID=DEL-A;");
        ReturnOrderItem priorLine = productLine("PT-001", new BigDecimal("2"));
        priorLine.setBatchNumber("SRC-001");
        prior.setItems(List.of(priorLine));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "SO-001"))
                .thenReturn(List.of(prior));

        CreateReturnOrderRequest over = createSalesReturnRequest("PT-001", new BigDecimal("2"), "SRC-001");
        over.setRemark("RECALL_EVENT_ID=7;SHIPMENT_ID=DEL-A;");
        assertThatThrownBy(() -> service.createReturnOrder(FACTORY, over, APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可退上限");

        CreateReturnOrderRequest fits = createSalesReturnRequest("PT-001", new BigDecimal("1"), "SRC-001");
        fits.setRemark("RECALL_EVENT_ID=7;SHIPMENT_ID=DEL-A;");
        assertThat(service.createReturnOrder(FACTORY, fits, APPROVER)).isNotNull();
    }

    @Test
    @DisplayName("多 shipment 下已有无 marker 实物退货时拒绝再次猜分配")
    void unassignedPriorPhysicalReturnBlocksMarkeredAllocation() {
        stubSameBatchAcrossTwoShipments();
        ReturnOrder prior = new ReturnOrder();
        prior.setId("PRIOR-UNASSIGNED");
        prior.setWithGoods(true);
        prior.setStatus(ReturnOrderStatus.COMPLETED);
        ReturnOrderItem priorLine = productLine("PT-001", new BigDecimal("1"));
        priorLine.setBatchNumber("SRC-001");
        prior.setItems(List.of(priorLine));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "SO-001"))
                .thenReturn(List.of(prior));
        CreateReturnOrderRequest request = createSalesReturnRequest("PT-001", new BigDecimal("1"), "SRC-001");
        request.setRemark("RECALL_EVENT_ID=7;SHIPMENT_ID=DEL-A;");
        assertThatThrownBy(() -> service.createReturnOrder(FACTORY, request, APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未关联具体 shipment");
    }

    @Test
    @DisplayName("完成时保留已核验的 SHIPMENT_ID marker")
    void completionRevalidatesShipmentMarker() {
        ReturnOrder order = salesReturn(List.of(productLine("PT-001", new BigDecimal("1"))));
        order.setRemark("RECALL_EVENT_ID=7;SHIPMENT_ID=DEL-DEFAULT;");

        service.completeReturnOrder(FACTORY, order.getId());

        verify(finishedGoodsBatchRepository).save(any(FinishedGoodsBatch.class));
    }

    @Test
    @DisplayName("历史销售实物退货完成前仍须有真实 SHIPPED/DELIVERED allocation 证据")
    void completingHistoricalSalesReturnRequiresShipmentEvidence() {
        ReturnOrder order = salesReturn(List.of(productLine("PT-001", new BigDecimal("1"))));
        order.setSourceOrderId("SO-001");
        SalesOrderItem orderLine = new SalesOrderItem();
        orderLine.setId(11L);
        orderLine.setProductTypeId("PT-001");
        orderLine.setUnit("kg");
        when(salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId("SO-001", FACTORY))
                .thenReturn(List.of(orderLine));
        when(salesDeliveryRecordRepository.findBySalesOrderId("SO-001"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有对应的已发货成品批次");
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));
    }

    private SalesDeliveryItemBatchAllocation allocation(String batchId, String batchNumber,
                                                         String unit, String quantity) {
        SalesDeliveryItemBatchAllocation allocation = new SalesDeliveryItemBatchAllocation();
        allocation.setFactoryId(FACTORY);
        allocation.setDeliveryItemId("21");
        allocation.setFinishedGoodsBatchId(batchId);
        allocation.setBatchNumber(batchNumber);
        allocation.setAllocatedQty(new BigDecimal(quantity));
        allocation.setUnit(unit);
        return allocation;
    }

    @Test
    @DisplayName("落点是成品仓 WH-FG, 不是物流仓 WH-LOG")
    void salesReturnLandsInFinishedGoodsWarehouse() {
        ReturnOrder order = salesReturn(List.of(productLine("PT-001", new BigDecimal("5.0000"))));

        service.completeReturnOrder(FACTORY, order.getId());

        ArgumentCaptor<FinishedGoodsBatch> captor = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(1)).save(captor.capture());
        FinishedGoodsBatch saved = captor.getValue();

        assertThat(saved.getWarehouseId())
                .as("销售退货的成品必须落成品仓 —— 界面承诺的就是成品仓")
                .isEqualTo(WH_FG);
        assertThat(saved.getWarehouseId())
                .as("阴性对照: 不许再落物流仓 (prod 日志里那三条 warehouse=WH-LOG 就是这个 bug)")
                .isNotEqualTo(WH_LOG);
        verify(warehouseResolver, never()).resolveLogisticsId(FACTORY);
    }

    @Test
    @DisplayName("多行退货成品批次保存稳定 ReturnOrderItem.id，不依赖列表序号")
    void returnedFinishedGoodsKeepStableReturnOrderItemIds() {
        ReturnOrderItem first = productLine("PT-A", new BigDecimal("2"));
        first.setId(101L);
        first.setBatchNumber("SRC-PT-A");
        ReturnOrderItem second = productLine("PT-B", new BigDecimal("3"));
        second.setId(102L);
        second.setBatchNumber("SRC-PT-B");
        ReturnOrder order = salesReturn(List.of(second, first));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(
                FACTORY, "SRC-PT-A"))
                .thenReturn(Optional.of(sourceBatch("SRC-PT-A", "PT-A", "kg")));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(
                FACTORY, "SRC-PT-B"))
                .thenReturn(Optional.of(sourceBatch("SRC-PT-B", "PT-B", "盒")));

        service.completeReturnOrder(FACTORY, order.getId());

        ArgumentCaptor<FinishedGoodsBatch> captor = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(FinishedGoodsBatch::getReturnOrderItemId)
                .containsExactly(102L, 101L);
    }

    @Test
    @DisplayName("隔离状态 DEFECTIVE 保留 —— 退回来的货不可直接再卖 (这是设计意图, 不是缺陷)")
    void returnedGoodsStayQuarantined() {
        ReturnOrder order = salesReturn(List.of(productLine("PT-001", new BigDecimal("5.0000"))));

        service.completeReturnOrder(FACTORY, order.getId());

        ArgumentCaptor<FinishedGoodsBatch> captor = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus())
                .as("DEFECTIVE 让它被所有 status='AVAILABLE' 的销售/调拨/生产选择器过滤掉 —— 改落点时不许把这个一起改掉")
                .isEqualTo("DEFECTIVE");
        assertThat(captor.getValue().getStatus())
                .as("阴性对照: 绝不能变成可售")
                .isNotEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("入库失败要阻断完成, 不能静默 COMPLETED (与 PURCHASE_RETURN 分支同一纪律)")
    void inboundFailureBlocksCompletion() {
        ReturnOrder order = salesReturn(List.of(productLine("PT-001", new BigDecimal("5.0000"))));
        when(finishedGoodsBatchRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("退货成品入库未成功");

        assertThat(order.getStatus())
                .as("入库失败时退货单必须停在 FINANCE_APPROVED, ⛔ 不许翻成 COMPLETED —— 那正是「界面说成了、实际没成」")
                .isEqualTo(ReturnOrderStatus.FINANCE_APPROVED);
    }

    @Test
    @DisplayName("缺少产品或正数量的销售实物退货行直接拒绝，不静默跳过")
    void incompleteLinesAreRejectedBeforeInbound() {
        ReturnOrderItem valid = productLine("PT-001", new BigDecimal("5.0000"));
        ReturnOrderItem noProductType = productLine(null, new BigDecimal("3.0000"));
        ReturnOrderItem zeroQty = productLine("PT-002", BigDecimal.ZERO);
        ReturnOrder order = salesReturn(new ArrayList<>(List.of(valid, noProductType, zeroQty)));

        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少完整源批次证据");

        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(returnOrderRepository, never()).save(any());
        assertThat(order.getStatus()).isEqualTo(ReturnOrderStatus.FINANCE_APPROVED);
    }

    @Test
    @DisplayName("kg/盒/legacy 单位全部从原始成品批次复制，当前 SKU 不覆盖历史单位")
    void sourceBatchUnitsAreCopiedWithoutHardcodedPieceUnit() {
        ReturnOrderItem kg = productLine("PT-KG", new BigDecimal("2"));
        kg.setBatchNumber("SRC-KG");
        ReturnOrderItem box = productLine("PT-BOX", new BigDecimal("3"));
        box.setBatchNumber("SRC-BOX");
        ReturnOrderItem legacy = productLine("PT-LEGACY", new BigDecimal("4"));
        legacy.setBatchNumber("SRC-LEGACY");
        ReturnOrder order = salesReturn(List.of(kg, box, legacy));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "SRC-KG"))
                .thenReturn(Optional.of(sourceBatch("SRC-KG", "PT-KG", "kg")));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "SRC-BOX"))
                .thenReturn(Optional.of(sourceBatch("SRC-BOX", "PT-BOX", "盒")));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "SRC-LEGACY"))
                .thenReturn(Optional.of(sourceBatch("SRC-LEGACY", "PT-LEGACY", "legacy-unit")));

        FinishedGoodsBatch kgSource = sourceBatch("SRC-KG", "PT-KG", "kg");
        kgSource.setUnitCost(new BigDecimal("6.4165"));
        FinishedGoodsBatch boxSource = sourceBatch("SRC-BOX", "PT-BOX", "盒");
        boxSource.setUnitCost(new BigDecimal("12.5000"));
        FinishedGoodsBatch legacySource = sourceBatch("SRC-LEGACY", "PT-LEGACY", "legacy-unit");
        legacySource.setUnitCost(new BigDecimal("3.2500"));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "SRC-KG"))
                .thenReturn(Optional.of(kgSource));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "SRC-BOX"))
                .thenReturn(Optional.of(boxSource));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "SRC-LEGACY"))
                .thenReturn(Optional.of(legacySource));

        service.completeReturnOrder(FACTORY, order.getId());

        ArgumentCaptor<FinishedGoodsBatch> captor = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(FinishedGoodsBatch::getUnit)
                .containsExactly("kg", "盒", "legacy-unit");
        assertThat(captor.getAllValues()).extracting(FinishedGoodsBatch::getUnitCost)
                .containsExactly(new BigDecimal("6.4165"), new BigDecimal("12.5000"),
                        new BigDecimal("3.2500"));
    }

    @Test
    @DisplayName("原始批次缺失、错 SKU 或跨工厂时拒绝完成")
    void sourceBatchEvidenceMismatchBlocksCompletion() {
        ReturnOrder missing = salesReturn(List.of(productLine("PT-MISSING", new BigDecimal("1"))));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "SRC-PT-MISSING"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, missing.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("找不到原成品批次");

        ReturnOrder wrongSku = salesReturn(List.of(productLine("PT-REQUEST", new BigDecimal("1"))));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "SRC-PT-REQUEST"))
                .thenReturn(Optional.of(sourceBatch("SRC-PT-REQUEST", "PT-SOURCE", "kg")));
        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, wrongSku.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SKU 不一致");

        ReturnOrder crossFactory = salesReturn(List.of(productLine("PT-CROSS", new BigDecimal("1"))));
        FinishedGoodsBatch foreign = sourceBatch("SRC-PT-CROSS", "PT-CROSS", "kg");
        foreign.setFactoryId("F007");
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "SRC-PT-CROSS"))
                .thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, crossFactory.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前工厂");

        ReturnOrder missingUnit = salesReturn(List.of(productLine("PT-NOUNIT", new BigDecimal("1"))));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "SRC-PT-NOUNIT"))
                .thenReturn(Optional.of(sourceBatch("SRC-PT-NOUNIT", "PT-NOUNIT", null)));
        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, missingUnit.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少不可变单位");

        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(returnOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("重复完成被状态门阻断，不重复入库或挂 AR/AP 调整")
    void repeatedCompletionDoesNotDuplicateInboundOrArAp() {
        ReturnOrder order = salesReturn(List.of(productLine("PT-001", new BigDecimal("5"))));

        service.completeReturnOrder(FACTORY, order.getId());

        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有财务审批通过");
        verify(finishedGoodsBatchRepository, times(1)).save(any());
        verify(returnOrderRepository, times(1)).save(order);
        verify(arApService, times(1)).recordAdjustment(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(entityManager, times(2)).refresh(order);
    }

    @Test
    @DisplayName("驳回在主事务内按工厂锁→退货行锁作废 DRAFT/红冲 POSTED，并携带认证 actor")
    void rejectionVoidsDraftAndReversesPostedVoucherInOrder() {
        ReturnOrder draft = salesReturn(List.of());
        draft.setStatus(ReturnOrderStatus.SUBMITTED);
        Voucher draftVoucher = Voucher.builder().id("V-DRAFT").factoryId(FACTORY)
                .sourceBusinessType("RETURN_ORDER").sourceBusinessId(draft.getId())
                .status(VoucherStatus.DRAFT).build();
        when(voucherService.findBySourceBusiness("RETURN_ORDER", draft.getId()))
                .thenReturn(Optional.of(draftVoucher));

        service.rejectReturnOrder(FACTORY, draft.getId(), APPROVER);

        verify(voucherService).voidVoucher(FACTORY, "V-DRAFT", "退货单驳回自动作废", APPROVER);
        assertThat(draft.getStatus()).isEqualTo(ReturnOrderStatus.REJECTED);

        ReturnOrder posted = salesReturn(List.of());
        posted.setStatus(ReturnOrderStatus.SUBMITTED);
        Voucher postedVoucher = Voucher.builder().id("V-POSTED").factoryId(FACTORY)
                .sourceBusinessType("RETURN_ORDER").sourceBusinessId(posted.getId())
                .status(VoucherStatus.POSTED).build();
        when(voucherService.findBySourceBusiness("RETURN_ORDER", posted.getId()))
                .thenReturn(Optional.of(postedVoucher));

        service.rejectReturnOrder(FACTORY, posted.getId(), APPROVER);

        verify(voucherService).voidVoucher(FACTORY, "V-POSTED", "退货单驳回自动作废", APPROVER);
        assertThat(posted.getStatus()).isEqualTo(ReturnOrderStatus.REJECTED);
        org.mockito.InOrder locks = org.mockito.Mockito.inOrder(entityManager, rowLockQuery);
        locks.verify(entityManager).createNativeQuery(org.mockito.ArgumentMatchers.startsWith(
                "SELECT pg_advisory_xact_lock"));
        locks.verify(entityManager).createNativeQuery(org.mockito.ArgumentMatchers.startsWith(
                "SELECT id FROM return_orders"));
    }

    @Test
    @DisplayName("凭证作废失败时驳回整体失败且不保存 REJECTED")
    void rejectionVoucherFailureRollsBackStateTransition() {
        ReturnOrder order = salesReturn(List.of());
        order.setStatus(ReturnOrderStatus.SUBMITTED);
        Voucher voucher = Voucher.builder().id("V-FAIL").factoryId(FACTORY)
                .sourceBusinessType("RETURN_ORDER").sourceBusinessId(order.getId())
                .status(VoucherStatus.POSTED).build();
        when(voucherService.findBySourceBusiness("RETURN_ORDER", order.getId()))
                .thenReturn(Optional.of(voucher));
        org.mockito.Mockito.doThrow(new IllegalStateException("period locked"))
                .when(voucherService).voidVoucher(FACTORY, "V-FAIL", "退货单驳回自动作废", APPROVER);

        assertThatThrownBy(() -> service.rejectReturnOrder(FACTORY, order.getId(), APPROVER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(ReturnOrderStatus.SUBMITTED);
        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));
    }

    @Test
    @DisplayName("缺 actor 或跨工厂退货单均拒绝，不能触碰凭证或保存状态")
    void rejectionRequiresActorAndFactoryScope() {
        ReturnOrder order = salesReturn(List.of());
        order.setStatus(ReturnOrderStatus.SUBMITTED);
        assertThatThrownBy(() -> service.rejectReturnOrder(FACTORY, order.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("工厂账号登录");
        verify(voucherService, never()).findBySourceBusiness(anyString(), anyString());
        verify(returnOrderRepository, never()).save(any(ReturnOrder.class));

        ReturnOrder foreign = salesReturn(List.of());
        foreign.setFactoryId("F007");
        foreign.setStatus(ReturnOrderStatus.SUBMITTED);
        assertThatThrownBy(() -> service.rejectReturnOrder(FACTORY, foreign.getId(), APPROVER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问");
        verify(voucherService, never()).findBySourceBusiness(anyString(), anyString());
    }

    @Test
    @DisplayName("财务驳回在同一事务级联撤销待审批冲减并记录认证 actor")
    void financeRejectionCancelsPendingAdjustment() {
        ReturnOrder order = salesReturn(List.of());
        order.setStatus(ReturnOrderStatus.APPROVED);
        when(voucherService.findBySourceBusiness("RETURN_ORDER", order.getId()))
                .thenReturn(Optional.empty());
        when(arApService.cancelPendingAdjustmentsBySource(
                FACTORY, "RETURN_ORDER", order.getId(), APPROVER,
                "退货单财务驳回, 撤销待审批冲减-" + order.getReturnNumber()))
                .thenReturn(1);

        service.financeRejectReturnOrder(FACTORY, order.getId(), APPROVER);

        verify(arApService).cancelPendingAdjustmentsBySource(
                FACTORY, "RETURN_ORDER", order.getId(), APPROVER,
                "退货单财务驳回, 撤销待审批冲减-" + order.getReturnNumber());
        assertThat(order.getStatus()).isEqualTo(ReturnOrderStatus.REJECTED);
        verify(returnOrderRepository).save(order);
    }
}
