package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateDeliveryShipmentRequest;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import com.cretas.aims.service.sales.SellableLineDomainResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷 42（owner 拍板「出库照抄入库」，2026-09-02）：创建子发运单（分批发运）时，
 * 成品行按 FIFO 自动预填批次分配 —— 镜像入库侧
 * {@code web-admin/src/views/warehouse/materials/PendingPurchaseReceivingPanel.vue}
 * 第 402-448 行 {@code openReceive()} 的原则：一打开收货表单，每一行的
 * {@code receivedQuantity} 就默认填成 {@code remainingReceivableQuantity}（全量），
 * 用户可编辑，不需要额外点一次“把每行填满”。
 *
 * <p>出库这边此前 FIFO 推荐只在用户手动点开“分配批次”对话框
 * （{@code detail.vue} 的 {@code openBatchAllocDialog}，调
 * {@code GET .../batch-allocations/recommend-fifo}）时才发生。子发运单创建
 * （{@link SalesServiceImpl#createDeliveryShipment}）本身不碰批次分配 —— 这正是
 * “出库照抄入库”还没抄到的那一块。本用例钉住新增的
 * {@code autoPrefillFifoAllocations}：创建子发运单后，直接调用与
 * {@link SalesServiceImpl#shipDelivery} P0-13 同一个
 * {@link SalesDeliveryBatchAllocationService#autoAllocateFifo} 做预填。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("缺陷42: 创建子发运单时自动按 FIFO 预填成品批次分配")
class AutoFifoPrefillOnShipmentCreateTest {

    private static final String FID = "F006";

    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock private SalesDeliveryBatchAllocationService batchAllocationService;
    @Mock private SellableLineDomainResolver sellableLineDomainResolver;

    private SalesServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SalesServiceImpl(salesOrderRepository, null, deliveryRecordRepository,
                null, null, null, null, null);
        ReflectionTestUtils.setField(service, "batchAllocationService", batchAllocationService);
        ReflectionTestUtils.setField(service, "sellableLineDomainResolver", sellableLineDomainResolver);
        // 默认：都是成品行（不是物料）。个别用例按需覆盖成 true。
        lenient().when(sellableLineDomainResolver.isMaterialLine(anyString(), anyString())).thenReturn(false);
        // lockDeliveryForMutation 先查 findByIdAndFactoryIdForUpdate（行锁）；Mockito 对未 stub 的
        // Optional 返回方法给的是 Optional.empty() 而不是 null —— 不桥接会让 orElseThrow 在真正测到
        // 目标行为之前就先炸一个不相干的 404。见 SalesDeliveryShipmentContractTest 同款桥接。
        lenient().when(deliveryRecordRepository.findByIdAndFactoryIdForUpdate(anyString(), anyString()))
                .thenAnswer(invocation -> deliveryRecordRepository.findById(invocation.getArgument(0)));
    }

    private SalesDeliveryRecord masterWithItems(SalesDeliveryItem... items) {
        SalesDeliveryRecord parent = new SalesDeliveryRecord();
        parent.setId("M1");
        parent.setFactoryId(FID);
        parent.setStatus(SalesDeliveryStatus.PENDING_SPLIT);
        parent.setRecordRole("MASTER");
        parent.setSalesOrderId("SO-1");
        parent.setDeliveryAddress("客户地址");
        parent.setItems(new ArrayList<>(List.of(items)));
        return parent;
    }

    private SalesDeliveryItem item(long id, long orderItemId, String productTypeId, String qty, String unit) {
        SalesDeliveryItem row = new SalesDeliveryItem();
        row.setId(id);
        row.setSalesOrderItemId(orderItemId);
        row.setProductTypeId(productTypeId);
        row.setProductName("产品-" + productTypeId);
        row.setDeliveredQuantity(new BigDecimal(qty));
        row.setUnit(unit);
        return row;
    }

    private CreateDeliveryShipmentRequest.Item lineReq(long parentItemId, String qty) {
        CreateDeliveryShipmentRequest.Item i = new CreateDeliveryShipmentRequest.Item();
        i.setParentDeliveryItemId(parentItemId);
        i.setQuantity(new BigDecimal(qty));
        return i;
    }

    private CreateDeliveryShipmentRequest requestFor(String key, CreateDeliveryShipmentRequest.Item... items) {
        CreateDeliveryShipmentRequest r = new CreateDeliveryShipmentRequest();
        r.setIdempotencyKey(key);
        r.setPlannedShipmentDate(LocalDate.of(2026, 9, 2));
        r.setDeliveryMethod("SELF_PICKUP");
        r.setDeliveryAddress("客户地址");
        r.setItems(List.of(items));
        return r;
    }

    /** 让 findBySalesOrderId 反映"到目前为止真的存过的记录"，供"剩余量"计算使用。 */
    private void stubOrderLookup(SalesDeliveryRecord parent, List<SalesDeliveryRecord> liveRecords) {
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-1", FID))
                .thenReturn(Optional.of(new SalesOrder()));
        when(deliveryRecordRepository.findBySalesOrderId("SO-1"))
                .thenAnswer(inv -> new ArrayList<>(liveRecords));
        when(deliveryRecordRepository.save(any())).thenAnswer(inv -> {
            SalesDeliveryRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId("S-" + (liveRecords.size() + 1));
            return r;
        });
    }

    @Test
    @DisplayName("🟢 阳性对照: 创建子发运单会为非物料行调用一次 FIFO 自动分配, 且量与单位对得上")
    void createShipment_triggersFifoAutoAllocatePerLine() {
        SalesDeliveryRecord parent = masterWithItems(item(100L, 1L, "PT-1", "10", "kg"));
        when(deliveryRecordRepository.findById("M1")).thenReturn(Optional.of(parent));
        stubOrderLookup(parent, new ArrayList<>(List.of(parent)));
        when(batchAllocationService.autoAllocateFifo(
                eq(FID), anyString(), eq("PT-1"), eq(new BigDecimal("10")), eq("kg"), any()))
                .thenReturn(true);

        SalesDeliveryRecord child = service.createDeliveryShipment(
                FID, "M1", requestFor("k1", lineReq(100L, "10")), 1309L);

        assertNotNull(child);
        verify(batchAllocationService).autoAllocateFifo(
                eq(FID), anyString(), eq("PT-1"), eq(new BigDecimal("10")), eq("kg"), any());
    }

    @Test
    @DisplayName("🔴 阴性对照(零库存): 该行找不到可分配批次 → 不写分配、不崩、子发运单照样创建成功")
    void createShipment_zeroStockLine_noAllocationNoCrash() {
        SalesDeliveryRecord parent = masterWithItems(item(100L, 1L, "PT-NOSTOCK", "5", "kg"));
        when(deliveryRecordRepository.findById("M1")).thenReturn(Optional.of(parent));
        stubOrderLookup(parent, new ArrayList<>(List.of(parent)));
        // ⚠️ 阳性对照证明"调用了"; 这里返回 false 模拟"全厂无可用批次"(与
        // SalesDeliveryBatchAllocationServiceImpl.autoAllocateFifo "凑不够就一个都不写" 一致)。
        when(batchAllocationService.autoAllocateFifo(
                eq(FID), anyString(), eq("PT-NOSTOCK"), any(), eq("kg"), any()))
                .thenReturn(false);

        SalesDeliveryRecord result = assertDoesNotThrow(() -> service.createDeliveryShipment(
                FID, "M1", requestFor("k2", lineReq(100L, "5")), 1309L));

        assertNotNull(result, "零库存不该让子发运单创建本身失败");
        assertEquals("SHIPMENT", result.getRecordRole());
        verify(batchAllocationService).autoAllocateFifo(
                eq(FID), anyString(), eq("PT-NOSTOCK"), any(), eq("kg"), any());
    }

    @Test
    @DisplayName("🔴 阴性对照(FIFO预填抛异常): 预填是便利功能不是硬闸, 抛异常也不能拖垮子发运单创建")
    void createShipment_fifoPrefillThrows_stillSucceeds() {
        SalesDeliveryRecord parent = masterWithItems(item(100L, 1L, "PT-BAD-UNIT", "5", "kg"));
        when(deliveryRecordRepository.findById("M1")).thenReturn(Optional.of(parent));
        stubOrderLookup(parent, new ArrayList<>(List.of(parent)));
        when(batchAllocationService.autoAllocateFifo(
                eq(FID), anyString(), eq("PT-BAD-UNIT"), any(), eq("kg"), any()))
                .thenThrow(new BusinessException(409, "单位换算冲突"));

        SalesDeliveryRecord result = assertDoesNotThrow(() -> service.createDeliveryShipment(
                FID, "M1", requestFor("k3", lineReq(100L, "5")), 1309L));

        assertNotNull(result, "FIFO 预填内部抛异常, 子发运单创建仍应成功, 缺口留给手工分配");
    }

    @Test
    @DisplayName("物料行没有成品批次可分配 —— 跳过, 不调用 FIFO 自动分配")
    void createShipment_materialLine_skipsFifoPrefill() {
        SalesDeliveryRecord parent = masterWithItems(item(100L, 1L, "RMT-1", "3", "kg"));
        when(sellableLineDomainResolver.isMaterialLine(FID, "RMT-1")).thenReturn(true);
        when(deliveryRecordRepository.findById("M1")).thenReturn(Optional.of(parent));
        stubOrderLookup(parent, new ArrayList<>(List.of(parent)));

        service.createDeliveryShipment(FID, "M1", requestFor("k4", lineReq(100L, "3")), 1309L);

        verify(batchAllocationService, never()).autoAllocateFifo(
                anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("🔴 缺陷40验收: 同一母单上第二次「新建分批发运」允许 —— 没有唯一性陷阱")
    void secondShipmentOnSameOrder_isAllowed_noUniquenessTrap() {
        SalesDeliveryRecord parent = masterWithItems(item(100L, 1L, "PT-1", "10", "kg"));
        List<SalesDeliveryRecord> liveRecords = new ArrayList<>(List.of(parent));
        when(deliveryRecordRepository.findById("M1")).thenReturn(Optional.of(parent));
        stubOrderLookup(parent, liveRecords);
        when(batchAllocationService.autoAllocateFifo(
                eq(FID), anyString(), eq("PT-1"), any(), eq("kg"), any())).thenReturn(true);

        SalesDeliveryRecord first = service.createDeliveryShipment(
                FID, "M1", requestFor("first", lineReq(100L, "6")), 1309L);
        assertEquals(1, first.getShipmentSequence());
        liveRecords.add(first);

        // 第一张子发运单已经占用了 6/10；第二张请求剩余的 4，必须被接受，
        // 且不是因为"母单已有子发运单"被拒绝 —— 那正是采购收货侧
        // (PurchaseServiceImpl.createReceiveRecord: "该采购订单已有未完成收货单…不能重复创建")
        // 的唯一性陷阱，销售发货这边不应该照抄。
        SalesDeliveryRecord second = assertDoesNotThrow(() -> service.createDeliveryShipment(
                FID, "M1", requestFor("second", lineReq(100L, "4")), 1309L));

        assertNotNull(second);
        assertEquals(2, second.getShipmentSequence(), "第二张子单序号应递增, 证明两张都真的建出来了");
        verify(batchAllocationService, times(2)).autoAllocateFifo(
                eq(FID), anyString(), eq("PT-1"), any(), eq("kg"), any());
    }
}
