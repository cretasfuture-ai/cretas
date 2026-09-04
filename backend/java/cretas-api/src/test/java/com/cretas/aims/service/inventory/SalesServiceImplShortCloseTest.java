package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CloseShortShipmentRequest;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 短交结案 (T-SHORT-SHIP, 2026-09-04 owner 拍板) 契约测试。
 *
 * <p>硬约束 8 (measurement-and-wiring): 唯一能把 SO 从 PARTIAL_DELIVERED 挪走的既有路径是
 * {@code updateOrderDeliveryStatus} 的整单发完自动转 COMPLETED —— cancelOrder/SplitOrderTool/
 * 退货流程都到不了这个状态 (见 PR 描述里的转移表)。本测试只覆盖新增的显式关闭路径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("销售订单短交结案契约")
class SalesServiceImplShortCloseTest {

    private static final String FACTORY = "F006";
    private static final String ORDER_ID = "SO-SHORT-CLOSE";

    @Mock SalesOrderRepository salesOrderRepository;
    @Mock SalesOrderItemRepository salesOrderItemRepository;
    @Mock SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock CustomerRepository customerRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock ArApService arApService;
    @Mock ApplicationEventPublisher eventPublisher;

    private SalesServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SalesServiceImpl(
                salesOrderRepository, salesOrderItemRepository, deliveryRecordRepository,
                finishedGoodsBatchRepository, customerRepository, productTypeRepository,
                arApService, eventPublisher);
        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("happy path: 部分发货 + 无活跃发运单 → SHORT_CLOSED, 缺口=下单量-实发量")
    void happyPathClosesOrderAndRecordsShortfall() {
        SalesOrder order = partiallyDeliveredOrder();
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        when(deliveryRecordRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of());
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(item("10", "9.8")));

        SalesOrder result = service.closeShortShipment(FACTORY, ORDER_ID,
                closeRequest(CloseShortShipmentRequest.ReasonCode.CUSTOMER_WAIVED_REMAINDER, "客户确认放弃剩余"),
                1309L);

        assertThat(result.getStatus()).isEqualTo(SalesOrderStatus.SHORT_CLOSED);
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.SHORT_CLOSED);
        assertThat(order.getShortCloseReasonCode()).isEqualTo("CUSTOMER_WAIVED_REMAINDER");
        assertThat(order.getShortCloseNotes()).isEqualTo("客户确认放弃剩余");
        assertThat(order.getShortClosedBy()).isEqualTo(1309L);
        assertThat(order.getShortClosedAt()).isNotNull();
        assertThat(order.getShortClosedQuantity()).isEqualByComparingTo("0.2");
        verify(salesOrderRepository).save(order);
    }

    @Test
    @DisplayName("拒绝: 仍有未终结的子发运单 (PENDING_WAREHOUSE_CONFIRM) 不能结案")
    void activeShipmentBlocksClose() {
        SalesOrder order = partiallyDeliveredOrder();
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        SalesDeliveryRecord pendingChild = deliveryRecord("DLV-CHILD-01", "SHIPMENT",
                SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        when(deliveryRecordRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(pendingChild));

        assertThatThrownBy(() -> service.closeShortShipment(FACTORY, ORDER_ID,
                closeRequest(CloseShortShipmentRequest.ReasonCode.SOURCE_SHORTAGE, null), 1309L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("SALES_SHORT_CLOSE_ACTIVE_DELIVERY");
                    assertThat(e.getMessage()).contains("DLV-CHILD-01");
                });

        verify(salesOrderRepository, never()).save(any());
        verifyNoInteractions(salesOrderItemRepository);
    }

    @Test
    @DisplayName("放行: 母单 (MASTER) 处于未终结状态不拦 —— 只有子发运单才可能继续产出实发量")
    void pendingMasterAloneDoesNotBlock() {
        SalesOrder order = partiallyDeliveredOrder();
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        SalesDeliveryRecord master = deliveryRecord("DLV-MASTER-01", "MASTER",
                SalesDeliveryStatus.PARTIALLY_SCHEDULED);
        when(deliveryRecordRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(master));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(item("10", "9.8")));

        SalesOrder result = service.closeShortShipment(FACTORY, ORDER_ID,
                closeRequest(CloseShortShipmentRequest.ReasonCode.CUSTOMER_WAIVED_REMAINDER, null), 1309L);

        assertThat(result.getStatus()).isEqualTo(SalesOrderStatus.SHORT_CLOSED);
    }

    @Test
    @DisplayName("放行: 子发运单已到终态 (SHIPPED/CANCELLED) 不拦")
    void terminalShipmentStatusesDoNotBlock() {
        SalesOrder order = partiallyDeliveredOrder();
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        when(deliveryRecordRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(
                deliveryRecord("DLV-CHILD-01", "SHIPMENT", SalesDeliveryStatus.SHIPPED),
                deliveryRecord("DLV-CHILD-02", "SHIPMENT", SalesDeliveryStatus.CANCELLED)));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(item("10", "9.8")));

        SalesOrder result = service.closeShortShipment(FACTORY, ORDER_ID,
                closeRequest(CloseShortShipmentRequest.ReasonCode.CUSTOMER_WAIVED_REMAINDER, null), 1309L);

        assertThat(result.getStatus()).isEqualTo(SalesOrderStatus.SHORT_CLOSED);
    }

    @Test
    @DisplayName("拒绝: 订单状态不是 PARTIAL_DELIVERED")
    void wrongStatusRejected() {
        SalesOrder order = partiallyDeliveredOrder();
        order.setStatus(SalesOrderStatus.CONFIRMED);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.closeShortShipment(FACTORY, ORDER_ID,
                closeRequest(CloseShortShipmentRequest.ReasonCode.CUSTOMER_WAIVED_REMAINDER, null), 1309L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("SALES_SHORT_CLOSE_STATUS_INVALID"));
        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("拒绝: 订单已全部实发 (等价于会自动转 COMPLETED), 没有缺口可关")
    void noShortfallRejected() {
        SalesOrder order = partiallyDeliveredOrder();
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        when(deliveryRecordRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of());
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(item("10", "10")));

        assertThatThrownBy(() -> service.closeShortShipment(FACTORY, ORDER_ID,
                closeRequest(CloseShortShipmentRequest.ReasonCode.CUSTOMER_WAIVED_REMAINDER, null), 1309L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("SALES_SHORT_CLOSE_NO_SHORTFALL"));
        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("拒绝: 未选择结案原因")
    void reasonRequired() {
        SalesOrder order = partiallyDeliveredOrder();
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        when(deliveryRecordRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of());
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(item("10", "9.8")));

        assertThatThrownBy(() -> service.closeShortShipment(FACTORY, ORDER_ID, null, 1309L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("SALES_SHORT_CLOSE_REASON_REQUIRED"));
        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("拒绝: 原因为「其他」但未填补充说明")
    void otherReasonRequiresNotes() {
        SalesOrder order = partiallyDeliveredOrder();
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        when(deliveryRecordRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of());
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(item("10", "9.8")));

        assertThatThrownBy(() -> service.closeShortShipment(FACTORY, ORDER_ID,
                closeRequest(CloseShortShipmentRequest.ReasonCode.OTHER, "  "), 1309L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("SALES_SHORT_CLOSE_NOTES_REQUIRED"));
        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("幂等: 已经 SHORT_CLOSED 的订单重放直接原样返回, 不二次校验、不重复审计")
    void repeatedCloseIsIdempotent() {
        SalesOrder order = partiallyDeliveredOrder();
        order.setStatus(SalesOrderStatus.SHORT_CLOSED);
        order.setShortCloseReasonCode("SOURCE_SHORTAGE");
        order.setShortClosedBy(1309L);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));

        SalesOrder result = service.closeShortShipment(FACTORY, ORDER_ID,
                closeRequest(CloseShortShipmentRequest.ReasonCode.OTHER, "重复点击"), 9999L);

        assertThat(result.getShortCloseReasonCode()).isEqualTo("SOURCE_SHORTAGE");
        assertThat(result.getShortClosedBy()).isEqualTo(1309L);
        verify(salesOrderRepository, never()).save(any());
        verifyNoInteractions(salesOrderItemRepository, deliveryRecordRepository);
    }

    private SalesOrder partiallyDeliveredOrder() {
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY);
        order.setOrderNumber("SO-20260904-0001");
        order.setStatus(SalesOrderStatus.PARTIAL_DELIVERED);
        order.setVersion(1L);
        return order;
    }

    private SalesOrderItem item(String quantity, String delivered) {
        SalesOrderItem item = new SalesOrderItem();
        item.setId(1L);
        item.setSalesOrderId(ORDER_ID);
        item.setProductTypeId("PRODUCT-BURGER");
        item.setQuantity(new BigDecimal(quantity));
        item.setDeliveredQuantity(new BigDecimal(delivered));
        item.setUnit("kg");
        return item;
    }

    private SalesDeliveryRecord deliveryRecord(String number, String recordRole, SalesDeliveryStatus status) {
        SalesDeliveryRecord record = new SalesDeliveryRecord();
        record.setId(number);
        record.setFactoryId(FACTORY);
        record.setSalesOrderId(ORDER_ID);
        record.setDeliveryNumber(number);
        record.setRecordRole(recordRole);
        record.setStatus(status);
        return record;
    }

    private CloseShortShipmentRequest closeRequest(CloseShortShipmentRequest.ReasonCode reasonCode, String notes) {
        CloseShortShipmentRequest request = new CloseShortShipmentRequest();
        request.setReasonCode(reasonCode);
        request.setNotes(notes);
        return request;
    }
}
