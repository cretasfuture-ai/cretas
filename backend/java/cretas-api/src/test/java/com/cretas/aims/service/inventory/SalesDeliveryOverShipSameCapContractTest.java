package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateDeliveryRequest;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
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
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A16 (工厂纪律排查 2026-11-10): 新建发货单原来是 0 容差 (订 10 kg 称出 10.3 kg 开不出单),
 * 确认发货却按「销售发货超发上限」放行。现在两处走同一个上限 —— 订单行累计 ≤ 订单量 × (1 + 上限)。
 * 驱动的是产品真入口 {@code createDeliveryRecord} / {@code warehouseConfirmDelivery}, 上限取自阈值中心 (10%)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesDeliveryOverShipSameCapContractTest {

    private static final String F = "F006";
    private static final String ORDER_ID = "SO-QA-A16";
    private static final long ORDER_ITEM_ID = 1601L;
    private static final String PRODUCT = "PT-QA-A16";

    @Mock SalesOrderRepository salesOrderRepository;
    @Mock SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock SalesOrderItemRepository salesOrderItemRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock CustomerRepository customerRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock ArApService arApService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ThresholdResolverService thresholdResolverService;

    private SalesServiceImpl service;
    private final List<SalesDeliveryRecord> existingDeliveries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new SalesServiceImpl(salesOrderRepository, salesOrderItemRepository, deliveryRecordRepository,
                finishedGoodsBatchRepository, customerRepository, productTypeRepository, arApService, eventPublisher);
        ReflectionTestUtils.setField(service, "thresholdResolverService", thresholdResolverService);
        hubRate("0.10");

        Customer customer = new Customer();
        customer.setId("C-QA-A16");
        customer.setFactoryId(F);
        customer.setName("QA-A16-客户");
        when(customerRepository.findByIdAndFactoryId("C-QA-A16", F)).thenReturn(Optional.of(customer));

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.setFactoryId(F);
        order.setCustomerId("C-QA-A16");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        order.setItems(new ArrayList<>());
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, F)).thenReturn(Optional.of(order));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        SalesOrderItem orderItem = new SalesOrderItem();
        orderItem.setId(ORDER_ITEM_ID);
        orderItem.setSalesOrderId(ORDER_ID);
        orderItem.setProductTypeId(PRODUCT);
        orderItem.setProductName("QA-A16-称重鸡腿");
        orderItem.setQuantity(new BigDecimal("10"));
        orderItem.setUnit("kg");
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(orderItem));
        when(deliveryRecordRepository.findBySalesOrderId(ORDER_ID)).thenAnswer(inv -> List.copyOf(existingDeliveries));
    }

    private void hubRate(String rate) {
        when(thresholdResolverService.getBigDecimal(eq(F), eq(ThresholdKeys.SALES_DELIVERY_OVER_SHIP_RATE), any()))
                .thenReturn(rate == null ? null : new BigDecimal(rate));
    }

    private SalesDeliveryRecord delivery(String id, String quantity, SalesDeliveryStatus status) {
        SalesDeliveryRecord record = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(record, "id", id);
        record.setDeliveryNumber("DN-" + id);
        record.setFactoryId(F);
        record.setSalesOrderId(ORDER_ID);
        record.setStatus(status);
        record.setDeliveryDate(LocalDate.now());
        SalesDeliveryItem item = new SalesDeliveryItem();
        ReflectionTestUtils.setField(item, "id", (long) id.hashCode());
        item.setDeliveryRecordId(id);
        item.setSalesOrderItemId(ORDER_ITEM_ID);
        item.setProductTypeId(PRODUCT);
        item.setProductName("QA-A16-称重鸡腿");
        item.setDeliveredQuantity(new BigDecimal(quantity));
        item.setUnit("kg");
        item.setUnitPrice(new BigDecimal("20"));
        record.setItems(new ArrayList<>(List.of(item)));
        return record;
    }

    /** 走新建入口; 返回被拦下时的 BusinessException, 没被这道闸拦 (放行或之后别的原因失败) 返回 null。 */
    private BusinessException createWith(String quantity) {
        CreateDeliveryRequest.DeliveryItemDTO line = new CreateDeliveryRequest.DeliveryItemDTO();
        line.setSalesOrderItemId(ORDER_ITEM_ID);
        line.setProductTypeId(PRODUCT);
        line.setProductName("QA-A16-称重鸡腿");
        line.setDeliveredQuantity(new BigDecimal(quantity));
        line.setUnit("kg");
        line.setUnitPrice(new BigDecimal("20"));
        CreateDeliveryRequest request = new CreateDeliveryRequest();
        request.setCustomerId("C-QA-A16");
        request.setSalesOrderId(ORDER_ID);
        request.setDeliveryAddress("QA-A16 地址");
        request.setItems(List.of(line));
        try {
            service.createDeliveryRecord(F, request, 77L);
            return null;
        } catch (BusinessException ex) {
            return "DELIVERY_QUANTITY_EXCEEDS_REMAINING".equals(ex.getErrorCode()) ? ex : null;
        } catch (RuntimeException afterTheGate) {
            return null;   // 过了这道闸之后桩没搭全 —— 本测试只读这道闸的判决
        }
    }

    /** 阳性对照: 新建确实走到了这道闸 (上限是在闸前一行解析的), 否则「没被拦」可能只是更早就失败了。 */
    private void assertGateReached(int times) {
        org.mockito.Mockito.verify(thresholdResolverService, org.mockito.Mockito.times(times))
                .getBigDecimal(eq(F), eq(ThresholdKeys.SALES_DELIVERY_OVER_SHIP_RATE), any());
    }

    @Test
    @DisplayName("新建: 订 10 kg 称出 10.3 kg (10% 上限内) → 不再被 0 容差拦下")
    void createWithinCapIsNoLongerBlocked() {
        assertThat(createWith("10.3")).as("原来 0 容差这里就 409").isNull();
        assertThat(createWith("11")).as("恰好 110% 放行 (边界含等号)").isNull();
        assertGateReached(2);
    }

    @Test
    @DisplayName("新建: 超过 订单量 × 110% → 409, 文案带上限与最多还能安排, 带 hint")
    void createBeyondCapIsBlockedWithHint() {
        BusinessException blocked = createWith("11.01");
        assertThat(blocked).isNotNull();
        assertThat(blocked.getCode()).isEqualTo(409);
        assertThat(blocked.getMessage()).contains("含 10% 超发").contains("最多还能安排 11kg");
        assertThat(blocked.getActionHint()).contains("业务阈值配置");
        assertThat(blocked.getHintTarget()).isEqualTo("deliveredQuantity");
    }

    @Test
    @DisplayName("新建: 累计算 —— 已有有效发货单 8 kg, 再开 3.01 kg 超过 11 kg → 拦; 已取消的单不计入")
    void createCountsEffectiveExistingDeliveries() {
        existingDeliveries.add(delivery("DLV-QA-A16-1", "8", SalesDeliveryStatus.SHIPPED));
        existingDeliveries.add(delivery("DLV-QA-A16-X", "5", SalesDeliveryStatus.CANCELLED));
        assertThat(createWith("3")).isNull();
        assertGateReached(1);
        BusinessException blocked = createWith("3.01");
        assertThat(blocked).isNotNull();
        assertThat(blocked.getMessage()).contains("已安排 8kg").contains("最多还能安排 3kg");
    }

    @Test
    @DisplayName("阴性对照: 上限配成 0 → 新建一克都不许多 (与确认发货 0 同义, 不是「不限制」)")
    void zeroCapStillBlocksAnyExcessAtCreate() {
        hubRate("0");
        assertThat(createWith("10")).isNull();
        assertGateReached(1);
        assertThat(createWith("10.001")).isNotNull();
    }

    @Test
    @DisplayName("确认: 新建已按上限多安排之后, 确认时不能再叠一层 —— 订单行累计仍以 订单量 × 110% 为顶")
    void confirmDoesNotCompoundOnTopOfCreate() {
        SalesDeliveryRecord first = delivery("DLV-QA-A16-1", "6", SalesDeliveryStatus.SHIPPED);
        SalesDeliveryRecord second = delivery("DLV-QA-A16-2", "5", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        existingDeliveries.add(first);
        existingDeliveries.add(second);
        when(deliveryRecordRepository.findByIdAndFactoryIdForUpdate("DLV-QA-A16-2", F)).thenReturn(Optional.of(second));
        when(deliveryRecordRepository.findById("DLV-QA-A16-2")).thenReturn(Optional.of(second));
        String itemKey = String.valueOf(second.getItems().get(0).getId());

        // 本单计划 5, 单看本单 5 × 110% = 5.5 是允许的; 但订单行已有 6, 合计 11.5 > 11 → 必须拦
        BusinessException blocked = null;
        try {
            service.warehouseConfirmDelivery(F, "DLV-QA-A16-2", Map.of(itemKey, new BigDecimal("5.5")), 77L);
        } catch (BusinessException ex) {
            blocked = ex;
        } catch (RuntimeException afterTheGate) {
            // 过闸之后的出运桩没搭 —— 下面的断言会说明这道闸没拦
        }
        assertThat(blocked).as("其他发货单 6 + 本次 5.5 = 11.5 > 订单 10 × 110%").isNotNull();
        assertThat(blocked.getErrorCode()).isEqualTo("DELIVERY_QUANTITY_EXCEEDS_OVER_SHIP_CAP");
        assertThat(blocked.getMessage()).contains("其他发货单已安排 6kg").contains("本次最多可发 5kg");
        assertThat(blocked.getActionHint()).isNotBlank();
        assertThat(second.getItems().get(0).getDeliveredQuantity()).as("被拦时不改实体").isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("阴性对照: 确认时订单行累计在上限内 (6 + 5 = 11) → 这道闸不拦")
    void confirmWithinOrderLineCapPasses() {
        SalesDeliveryRecord first = delivery("DLV-QA-A16-1", "6", SalesDeliveryStatus.SHIPPED);
        SalesDeliveryRecord second = delivery("DLV-QA-A16-2", "4", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        existingDeliveries.add(first);
        existingDeliveries.add(second);
        when(deliveryRecordRepository.findByIdAndFactoryIdForUpdate("DLV-QA-A16-2", F)).thenReturn(Optional.of(second));
        when(deliveryRecordRepository.findById("DLV-QA-A16-2")).thenReturn(Optional.of(second));
        String itemKey = String.valueOf(second.getItems().get(0).getId());

        BusinessException capError = null;
        try {
            service.warehouseConfirmDelivery(F, "DLV-QA-A16-2", Map.of(itemKey, new BigDecimal("4.4")), 77L);
        } catch (BusinessException ex) {
            if ("DELIVERY_QUANTITY_EXCEEDS_OVER_SHIP_CAP".equals(ex.getErrorCode())) capError = ex;
        } catch (RuntimeException afterTheGate) {
            // 过闸之后的出运桩没搭, 与本断言无关
        }
        assertThat(capError).as("6 + 4.4 = 10.4 ≤ 11, 不该被超发上限拦").isNull();
        assertThat(second.getItems().get(0).getDeliveredQuantity()).as("实发已写入").isEqualByComparingTo("4.4");
    }

    // ---------------------------------------------------------------- 分批发运子单 (独立审查 2026-11-11)

    /** 母单按 110% 开出 11, 下面两张子单: 已发 6, 待确认计划 5。 */
    private SalesDeliveryRecord masterWithChildren() {
        SalesDeliveryRecord master = delivery("DLV-QA-A16-M", "11", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        master.setRecordRole("MASTER");
        SalesDeliveryRecord shipped = delivery("DLV-QA-A16-C1", "6", SalesDeliveryStatus.SHIPPED);
        shipped.setRecordRole("SHIPMENT");
        shipped.setParentDeliveryId("DLV-QA-A16-M");
        SalesDeliveryRecord pending = delivery("DLV-QA-A16-C2", "5", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        pending.setRecordRole("SHIPMENT");
        pending.setParentDeliveryId("DLV-QA-A16-M");
        existingDeliveries.add(master);
        existingDeliveries.add(shipped);
        existingDeliveries.add(pending);
        for (SalesDeliveryRecord r : List.of(master, shipped, pending)) {
            when(deliveryRecordRepository.findByIdAndFactoryIdForUpdate(r.getId(), F)).thenReturn(Optional.of(r));
            when(deliveryRecordRepository.findById(r.getId())).thenReturn(Optional.of(r));
        }
        return pending;
    }

    private BusinessException confirmCapError(SalesDeliveryRecord record, String actual) {
        String itemKey = String.valueOf(record.getItems().get(0).getId());
        try {
            service.warehouseConfirmDelivery(F, record.getId(), Map.of(itemKey, new BigDecimal(actual)), 77L);
        } catch (BusinessException ex) {
            if ("DELIVERY_QUANTITY_EXCEEDS_OVER_SHIP_CAP".equals(ex.getErrorCode())) return ex;
        } catch (RuntimeException afterTheGate) {
            // 过闸之后的出运桩没搭, 与本断言无关
        }
        return null;
    }

    @Test
    @DisplayName("子单: 母单已按 110% 开出, 子单再各按计划 × 110% 实发会叠成 121% —— 按订单行累计拦下")
    void shipmentChildDoesNotCompoundOnTopOfAnOverPlannedMaster() {
        SalesDeliveryRecord pending = masterWithChildren();

        // 单看本子单: 计划 5 × 110% = 5.5 是允许的; 但兄弟子单已发 6, 合计 11.5 > 订单 10 × 110%
        BusinessException blocked = confirmCapError(pending, "5.5");

        assertThat(blocked).as("子单 6 + 5.5 = 11.5 > 11; 母单本身不重复计入").isNotNull();
        assertThat(blocked.getMessage()).contains("其他发货单已安排 6kg").contains("本次最多可发 5kg");
        assertThat(pending.getItems().get(0).getDeliveredQuantity()).isEqualByComparingTo("5");
    }

    // ---------------------------------------------------------------- 第二轮审查: 别的母单下子单多发的部分

    /** 母单 A 计划 10 (= 订单量), 它的子单按上限实发了 11 (子单确认时允许 10 × 110%)。发运子单不回写母单行量。 */
    private void masterAWhoseChildShippedEleven() {
        SalesDeliveryRecord masterA = delivery("DLV-QA-A16-A", "10", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        masterA.setRecordRole("MASTER");
        SalesDeliveryRecord childA1 = delivery("DLV-QA-A16-A1", "11", SalesDeliveryStatus.SHIPPED);
        childA1.setRecordRole("SHIPMENT");
        childA1.setParentDeliveryId("DLV-QA-A16-A");
        existingDeliveries.add(masterA);
        existingDeliveries.add(childA1);
    }

    @Test
    @DisplayName("新建: 别的母单的子单已多发到 11 (= 上限) → 再开 1 也拦 —— 只数母单计划 10 会放行, 订 10 发出 12")
    void createCountsChildrenShippedAboveTheirMastersPlan() {
        masterAWhoseChildShippedEleven();

        BusinessException blocked = createWith("1");

        assertThat(blocked).as("母单 A 按 max(计划 10, 子单合计 11) = 11 计入; 11 + 1 > 11").isNotNull();
        assertThat(blocked.getMessage()).contains("已安排 11kg").contains("最多还能安排 0kg");
    }

    @Test
    @DisplayName("确认: 另一张母单 B 的子单确认时, 母单 A 下多发的 11 也要算 —— 11 + 1 > 11 拦下")
    void childOfAnotherMasterCountsTheFirstMastersOverShippedChildren() {
        masterAWhoseChildShippedEleven();
        SalesDeliveryRecord masterB = delivery("DLV-QA-A16-B", "1", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        masterB.setRecordRole("MASTER");
        SalesDeliveryRecord childB1 = delivery("DLV-QA-A16-B1", "1", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        childB1.setRecordRole("SHIPMENT");
        childB1.setParentDeliveryId("DLV-QA-A16-B");
        existingDeliveries.add(masterB);
        existingDeliveries.add(childB1);
        for (SalesDeliveryRecord r : List.of(masterB, childB1)) {
            when(deliveryRecordRepository.findByIdAndFactoryIdForUpdate(r.getId(), F)).thenReturn(Optional.of(r));
            when(deliveryRecordRepository.findById(r.getId())).thenReturn(Optional.of(r));
        }

        BusinessException blocked = confirmCapError(childB1, "1");

        assertThat(blocked).as("其他母单 A 计 11, 本母单 B 下无其他子单; 11 + 1 > 11").isNotNull();
        assertThat(blocked.getMessage()).contains("其他发货单已安排 11kg");
    }

    @Test
    @DisplayName("阴性对照: 子单少于母单计划时按母单计划计 (取较大者) —— 计划 10、子单只发 4, 仍按 10 计入")
    void masterPlanCountsWhenChildrenShippedLess() {
        SalesDeliveryRecord masterA = delivery("DLV-QA-A16-A", "10", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        masterA.setRecordRole("MASTER");
        SalesDeliveryRecord childA1 = delivery("DLV-QA-A16-A1", "4", SalesDeliveryStatus.SHIPPED);
        childA1.setRecordRole("SHIPMENT");
        childA1.setParentDeliveryId("DLV-QA-A16-A");
        existingDeliveries.add(masterA);
        existingDeliveries.add(childA1);

        assertThat(createWith("1")).as("10 + 1 = 11 ≤ 11").isNull();
        BusinessException blocked = createWith("1.01");
        assertThat(blocked).isNotNull();
        assertThat(blocked.getMessage()).contains("已安排 10kg");
    }

    @Test
    @DisplayName("阴性对照: 子单合计恰好到 110% (6 + 5 = 11) → 放行; 母单的 11 没有被重复算进去")
    void shipmentChildWithinTheOrderLineCapPasses() {
        SalesDeliveryRecord pending = masterWithChildren();

        assertThat(confirmCapError(pending, "5")).isNull();
        assertThat(pending.getItems().get(0).getDeliveredQuantity()).isEqualByComparingTo("5");
    }
}
