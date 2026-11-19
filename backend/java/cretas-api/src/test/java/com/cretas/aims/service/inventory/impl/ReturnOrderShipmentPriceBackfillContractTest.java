package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.notification.NotificationService;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退货缺价按发货价冲减 (复审 M2, Steve 2026-11-18 定 B): 应收按发货行价挂账, 发货时可能让价,
 * 所以销售实物退货行单价为 null 时 ① 先用该行批次定位到的本厂原发货行单价, ② 发货行无价才用订单行价,
 * ③ 都没有拒绝。发货价 ≠ 订单价时退货照常成功, 另给财务 (finance_manager) 发一条提醒。
 *
 * <p>走产品真实入口 {@link ReturnOrderServiceImpl#createReturnOrder}, 带货退货 (withGoods=true),
 * 批次→发货行定位用的是与批次证据同一条路径。
 */
@DisplayName("销售退货缺价: 先发货价、再订单价、都无拒绝; 发货价≠订单价提醒财务; 别厂发货不被命中")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReturnOrderShipmentPriceBackfillContractTest {

    private static final String F = "F-RTSHIP";
    private static final String OTHER = "F-OTHER";
    private static final String SO = "SO-RTSHIP-1";
    private static final String PT = "PT-A";
    private static final String BATCH_ID = "FG-1";
    private static final String BATCH_NO = "B-001";

    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private ReturnOrderItemRepository returnOrderItemRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    @Mock private SalesDeliveryItemBatchAllocationRepository allocationRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private NotificationService notificationService;

    private ReturnOrderServiceImpl service;
    private final List<SalesDeliveryRecord> deliveries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ReturnOrderServiceImpl(
                returnOrderRepository, returnOrderItemRepository, arApService, applicationEventPublisher);
        ReflectionTestUtils.setField(service, "salesOrderItemRepository", salesOrderItemRepository);
        ReflectionTestUtils.setField(service, "salesDeliveryRecordRepository", salesDeliveryRecordRepository);
        ReflectionTestUtils.setField(service, "salesDeliveryItemBatchAllocationRepository", allocationRepository);
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        when(returnOrderRepository.save(any(ReturnOrder.class))).thenAnswer(inv -> {
            ReturnOrder o = inv.getArgument(0);
            if (o.getId() == null) o.setId("RO-1");
            return o;
        });
        when(returnOrderRepository.countByFactoryIdAndDate(eq(F), any(LocalDate.class))).thenReturn(0L);
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                F, ReturnType.SALES_RETURN, SO)).thenReturn(List.of());
        when(returnOrderItemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(salesDeliveryRecordRepository.findBySalesOrderId(SO)).thenReturn(deliveries);
        FinishedGoodsBatch source = new FinishedGoodsBatch();
        source.setId(BATCH_ID);
        source.setFactoryId(F);
        source.setProductTypeId(PT);
        source.setBatchNumber(BATCH_NO);
        source.setUnit("kg");
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate(BATCH_ID, F)).thenReturn(Optional.of(source));
    }

    @Test
    @DisplayName("发货价 9.00 ≠ 订单价 10.00 → 按发货价冲减 (4×9=36), 行原因标「发货价」, 给财务发一条提醒写明单号/产品/两个价/发货单")
    void shipmentPriceDiffersFromOrderPriceUsesShipmentPriceAndReminds() {
        orderLine("10.00");
        shipment(F, "DEL-1", "FH-0001", 21L, "9.00", F);

        ReturnOrder order = service.createReturnOrder(F, request("4"), 7L);

        ReturnOrderItem saved = savedItems().get(0);
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("9.00");
        assertThat(saved.getLineAmount()).isEqualByComparingTo("36.00");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("36.00");
        assertThat(saved.getReason()).startsWith("客户退回").contains("单价来源: 发货价").contains("FH-0001")
                .doesNotContain("9.00").doesNotContain("10.00"); // M1: 原因是普通字段, 不得带价
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains(order.getReturnNumber())
                .contains("退货-" + PT)
                .contains("按发货价 9.00 冲减")
                .contains("订单价 10.00")
                .contains("FH-0001");
    }

    @Test
    @DisplayName("发货价 = 订单价 (10 与 10.00 同价) → 用发货价, 不发提醒")
    void shipmentPriceEqualToOrderPriceDoesNotRemind() {
        orderLine("10.00");
        shipment(F, "DEL-1", "FH-0001", 21L, "10", F);

        service.createReturnOrder(F, request("2"), 7L);

        ReturnOrderItem saved = savedItems().get(0);
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("10");
        assertThat(saved.getReason()).contains("单价来源: 发货价");
        verify(notificationService, never()).notifyRole(any(), any(), any(), any());
    }

    @Test
    @DisplayName("发货行没价 → 退回订单行价 10.00, 行原因标「订单价」, 不发提醒")
    void shipmentWithoutPriceFallsBackToOrderPrice() {
        orderLine("10.00");
        shipment(F, "DEL-1", "FH-0001", 21L, null, F);

        service.createReturnOrder(F, request("3"), 7L);

        ReturnOrderItem saved = savedItems().get(0);
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(saved.getLineAmount()).isEqualByComparingTo("30.00");
        assertThat(saved.getReason()).contains("单价来源: 订单价").doesNotContain("10");
        verify(notificationService, never()).notifyRole(any(), any(), any(), any());
    }

    @Test
    @DisplayName("发货行与订单行都没价 → 422 拒绝, 不保存退货单, 不记 0")
    void noShipmentPriceAndNoOrderPriceIsRejected() {
        orderLine(null);
        shipment(F, "DEL-1", "FH-0001", 21L, null, F);

        assertThatThrownBy(() -> service.createReturnOrder(F, request("3"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少单价")
                .hasMessageContaining("不能按 0");
        verify(returnOrderRepository, never()).save(any());
        verify(returnOrderItemRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("别厂发货单 (同订单号, 价 7.00) 不被命中: 本厂发货行没价 → 用订单价 10.00, 不提醒")
    void otherFactoryShipmentIsNotHit() {
        orderLine("10.00");
        shipment(F, "DEL-1", "FH-0001", 21L, null, F);
        // 别厂的发货单: 它的 allocation 桩故意返回本厂键 (F) 的行, 让发货单级 factory_id 过滤成为唯一防线 (复审 L1)
        shipment(OTHER, "DEL-X", "FH-X", 31L, "7.00", F);

        service.createReturnOrder(F, request("1"), 7L);

        ReturnOrderItem saved = savedItems().get(0);
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("10.00");
        verify(notificationService, never()).notifyRole(any(), any(), any(), any());
    }

    @Test
    @DisplayName("同一批次在同一发货单里按两个价发过 (9 与 8) → 拒绝, 不猜")
    void sameBatchShippedAtTwoPricesIsRejected() {
        orderLine("10.00");
        SalesDeliveryRecord d = shipment(F, "DEL-1", "FH-0001", 21L, "9.00", F);
        SalesDeliveryItem second = deliveryItem("DEL-1", 22L, "8.00");
        List<SalesDeliveryItem> items = new ArrayList<>(d.getItems());
        items.add(second);
        d.setItems(items);
        when(allocationRepository.findByFactoryIdAndDeliveryItemId(F, "22"))
                .thenReturn(List.of(allocation(F, "22", "5")));

        assertThatThrownBy(() -> service.createReturnOrder(F, request("1"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不同单价");
        verify(returnOrderItemRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("提醒发送失败不回滚退货: 退货照常保存并返回")
    void reminderFailureDoesNotRollBackReturn() {
        orderLine("10.00");
        shipment(F, "DEL-1", "FH-0001", 21L, "9.00", F);
        doThrow(new RuntimeException("notify down"))
                .when(notificationService).notifyRole(any(), any(), any(), any());

        ReturnOrder order = service.createReturnOrder(F, request("4"), 7L);

        assertThat(order.getTotalAmount()).isEqualByComparingTo("36.00");
        assertThat(savedItems()).hasSize(1);
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), anyString(), anyString());
    }

    @Test
    @DisplayName("复审 H1: 发货行价 0、订单价 10 → 不按 0 冲减, 用订单价 10 (与挂账侧 isKnownZeroShipmentValue 同判据), 不提醒")
    void zeroShipmentPriceWithNonZeroOrderPriceIsNotUsed() {
        orderLine("10.00");
        shipment(F, "DEL-1", "FH-0001", 21L, "0", F);

        service.createReturnOrder(F, request("3"), 7L);

        ReturnOrderItem saved = savedItems().get(0);
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(saved.getLineAmount()).isEqualByComparingTo("30.00");
        assertThat(saved.getReason()).contains("单价来源: 订单价");
        verify(notificationService, never()).notifyRole(any(), any(), any(), any());
    }

    @Test
    @DisplayName("复审 H1 对照: 发货行价 0 且订单价也确定为 0 (真赠品) → 认发货价 0")
    void zeroShipmentPriceWithZeroOrderPriceIsUsed() {
        orderLine("0");
        shipment(F, "DEL-1", "FH-0001", 21L, "0.00", F);

        service.createReturnOrder(F, request("3"), 7L);

        ReturnOrderItem saved = savedItems().get(0);
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("0");
        assertThat(saved.getReason()).contains("单价来源: 发货价");
        verify(notificationService, never()).notifyRole(any(), any(), any(), any());
    }

    @Test
    @DisplayName("复审 M2: 报价含税订单行 (含税 11.30 / 未税 10) 发货价 9 → 按订单价 10 冲减, 不走发货价, 不提醒")
    void quotedGrossOrderLineUsesOrderPriceNotShipmentPrice() {
        SalesOrderItem line = orderLine("10.00");
        line.setQuotedGrossUnitPrice(new BigDecimal("11.30"));
        shipment(F, "DEL-1", "FH-0001", 21L, "9.00", F);

        service.createReturnOrder(F, request("2"), 7L);

        ReturnOrderItem saved = savedItems().get(0);
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(saved.getReason()).contains("单价来源: 订单价");
        verify(notificationService, never()).notifyRole(any(), any(), any(), any());
    }

    @Test
    @DisplayName("复审 L2: 同一批次两条发货行一条 9.00 一条没价 → 拒绝, 不静默取有价那条")
    void sameBatchMixedPricedAndUnpricedShipmentLinesIsRejected() {
        orderLine("10.00");
        SalesDeliveryRecord d = shipment(F, "DEL-1", "FH-0001", 21L, "9.00", F);
        List<SalesDeliveryItem> items = new ArrayList<>(d.getItems());
        items.add(deliveryItem("DEL-1", 22L, null));
        d.setItems(items);
        when(allocationRepository.findByFactoryIdAndDeliveryItemId(F, "22"))
                .thenReturn(List.of(allocation(F, "22", "5")));

        assertThatThrownBy(() -> service.createReturnOrder(F, request("1"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("有的有单价、有的没有");
        verify(returnOrderItemRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("复审 M3: 事务内创建时提交前不发提醒, afterCommit 回调后恰好发 1 次")
    void reminderIsSentOnlyAfterCommit() {
        orderLine("10.00");
        shipment(F, "DEL-1", "FH-0001", 21L, "9.00", F);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createReturnOrder(F, request("4"), 7L);
            verify(notificationService, never()).notifyRole(any(), any(), any(), any());

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            syncs.forEach(TransactionSynchronization::afterCommit);

            verify(notificationService, times(1))
                    .notifyRole(eq(F), eq("finance_manager"), anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("复审 M3: 事务回滚 (只走 afterCompletion(ROLLED_BACK)) → 不发提醒")
    void reminderIsNotSentWhenTransactionRollsBack() {
        orderLine("10.00");
        shipment(F, "DEL-1", "FH-0001", 21L, "9.00", F);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createReturnOrder(F, request("4"), 7L);
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            syncs.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(notificationService, never()).notifyRole(any(), any(), any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------------------------------------------------------------- fixtures

    private SalesOrderItem orderLine(String unitPrice) {
        SalesOrderItem line = new SalesOrderItem();
        line.setId(11L);
        line.setProductTypeId(PT);
        line.setUnit("kg");
        line.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        when(salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId(SO, F))
                .thenReturn(new ArrayList<>(List.of(line)));
        return line;
    }

    /** 一张已发货的发货单, 一行 (对上订单行 11), 本批次分配 5 kg; allocation 一律按本厂键 (F) 挂在仓库桩上。 */
    private SalesDeliveryRecord shipment(String factoryId, String deliveryId, String deliveryNumber,
                                         Long itemId, String unitPrice, String allocationFactory) {
        SalesDeliveryRecord d = new SalesDeliveryRecord();
        d.setId(deliveryId);
        d.setDeliveryNumber(deliveryNumber);
        d.setFactoryId(factoryId);
        d.setSalesOrderId(SO);
        d.setStatus(SalesDeliveryStatus.SHIPPED);
        d.setItems(new ArrayList<>(List.of(deliveryItem(deliveryId, itemId, unitPrice))));
        deliveries.add(d);
        when(allocationRepository.findByFactoryIdAndDeliveryItemId(F, String.valueOf(itemId)))
                .thenReturn(List.of(allocation(allocationFactory, String.valueOf(itemId), "5")));
        return d;
    }

    private static SalesDeliveryItem deliveryItem(String deliveryId, Long itemId, String unitPrice) {
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(itemId);
        item.setDeliveryRecordId(deliveryId);
        item.setSalesOrderItemId(11L);
        item.setProductTypeId(PT);
        item.setUnit("kg");
        item.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return item;
    }

    private static SalesDeliveryItemBatchAllocation allocation(String factoryId, String deliveryItemId, String qty) {
        SalesDeliveryItemBatchAllocation a = new SalesDeliveryItemBatchAllocation();
        a.setFactoryId(factoryId);
        a.setDeliveryItemId(deliveryItemId);
        a.setFinishedGoodsBatchId(BATCH_ID);
        a.setBatchNumber(BATCH_NO);
        a.setAllocatedQty(new BigDecimal(qty));
        a.setUnit("kg");
        return a;
    }

    @SuppressWarnings("unchecked")
    private List<ReturnOrderItem> savedItems() {
        ArgumentCaptor<List<ReturnOrderItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(returnOrderItemRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static CreateReturnOrderRequest request(String qty) {
        CreateReturnOrderRequest.ReturnOrderItemDTO dto = new CreateReturnOrderRequest.ReturnOrderItemDTO();
        dto.setProductTypeId(PT);
        dto.setItemName("退货-" + PT);
        dto.setQuantity(new BigDecimal(qty));
        dto.setUnitPrice(null);
        dto.setReason("客户退回");
        CreateReturnOrderRequest r = new CreateReturnOrderRequest();
        r.setReturnType(ReturnType.SALES_RETURN.name());
        r.setCounterpartyId("CUST-1");
        r.setSourceOrderId(SO);
        r.setReturnDate(LocalDate.now());
        r.setWithGoods(true);
        r.setItems(List.of(dto));
        return r;
    }
}
