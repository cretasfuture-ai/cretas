package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import org.junit.jupiter.api.Assertions;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ⛔ 内部数据库行 ID 不许出现在给仓管看的库存不足报错里。
 *
 * <h2>2026-09-02 六扇门实拍走查抓到</h2>
 *
 * <pre>
 * 发货行 255（产品：熟肉饼）可用库存不足，无法发货
 * </pre>
 *
 * {@code 255} 是 {@code sales_delivery_items} 的自增主键, 对仓管没有任何可操作性
 * ("255 是哪一行? 我该干什么?")。句子其余部分(产品名 / 为什么不够 / 两条出路)是好的,
 * 只该删掉那个数字, 其它原样保留 —— 这道用例把两半都钉住, 免得"顺手"把好的部分也改没了。
 *
 * <p>仓里现存最接近的两道闸都够不到这条消息:
 * {@code NoInternalIterationMarkersInUserFacingTextContractTest} 只认
 * {@code Sprint N / Loop N / Phase N} 这类内部迭代编号形状, 裸数字 "255" 不在它的正则里;
 * {@code TransitLedgerDisplayIdentityContractTest} 只扫
 * {@code ProductionWarehouseReceiptMobileController} 一个 controller 的 DTO 字段, 够不到
 * {@code SalesServiceImpl} 里手写拼接的 {@code BusinessException} 消息。⇒ 本仓目前没有一道
 * 通用闸能扫到"异常消息里拼了数据库主键"这一类, 这道用例是这个具体实例的窄闸, 不是通用方案。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("发货库存不足报错: 不含内部行 ID, 保留产品名与两条出路")
class ShipDeliveryInsufficientStockMessageTest {

    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock private SalesDeliveryBatchAllocationService batchAllocationService;

    private SalesServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SalesServiceImpl(salesOrderRepository, null, deliveryRecordRepository,
                null, null, null, null, null);
        ReflectionTestUtils.setField(service, "batchAllocationService", batchAllocationService);
        // lockDeliveryForMutation 先查 findByIdAndFactoryIdForUpdate (行锁), Mockito 对未
        // stub 的 Optional 返回方法给的是 Optional.empty() 而不是 null —— 源码里那句「Mockito
        // 对历史构造器测试返回 null」的注释在 MockitoExtension 下不成立, 不桥接这条会让
        // orElseThrow 在真正测到库存不足之前就先炸一个不相干的 404。见
        // SalesDeliveryShipmentContractTest 同款桥接。
        lenient().when(deliveryRecordRepository.findByIdAndFactoryIdForUpdate(anyString(), anyString()))
                .thenAnswer(invocation -> deliveryRecordRepository.findById(invocation.getArgument(0)));
    }

    /** 复现走查现场: 发货行内部 id=255, 产品「熟肉饼」, 计划发 1(kg), 实际库存不够。 */
    private SalesDeliveryRecord shortShipRecord() {
        SalesDeliveryRecord record = new SalesDeliveryRecord();
        record.setId("S-255");
        record.setFactoryId("F006");
        record.setStatus(SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        record.setRecordRole("SHIPMENT");
        record.setDeliveryMethod("SELF_PICKUP");
        record.setDeliveryDate(LocalDate.of(2026, 9, 2));

        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(255L);
        item.setProductTypeId("PT-SHOU-ROU-BING");
        item.setProductName("熟肉饼");
        item.setDeliveredQuantity(new BigDecimal("1"));
        item.setUnit("kg");
        record.setItems(new ArrayList<>(List.of(item)));
        return record;
    }

    private void stubInsufficientStock(SalesDeliveryRecord record) {
        when(deliveryRecordRepository.findById("S-255")).thenReturn(Optional.of(record));
        when(batchAllocationService.isFullyAllocated("F006", "255")).thenReturn(false);
        when(batchAllocationService.autoAllocateFifo(
                eq("F006"), eq("255"), any(), any(), any(), any()))
                .thenReturn(false);
    }

    @Test
    @DisplayName("🟢 阳性对照: 仪器真的到达了库存不足这条抛出路径, 否则下面的断言是空转")
    void positiveControlReachesInsufficientStockBranch() {
        stubInsufficientStock(shortShipRecord());

        assertThatThrownBy(() -> service.shipDelivery("F006", "S-255", 1309L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    @Test
    @DisplayName("🔴 报错正文不含内部行 ID「255」, 产品名与两条出路仍在")
    void messageOmitsInternalRowIdButKeepsProductAndNextSteps() {
        stubInsufficientStock(shortShipRecord());

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> service.shipDelivery("F006", "S-255", 1309L));

        // ⚠️ 双向断言: 只断言「不含 255」是恒真陷阱(换成任何别的坏文案也能通过) ——
        // 必须同时钉住"产品名还在", 才把"删对了地方"和"删多了/文案坏了"分开。
        assertThat(ex.getMessage())
                .as("内部行 ID 255 不该出现在给仓管看的报错里")
                .doesNotContain("255");
        assertThat(ex.getMessage())
                .as("产品名必须还在 —— 这是仓管唯一能核对的信息")
                .contains("熟肉饼")
                .contains("可用库存不足");
        assertThat(ex.getActionHint())
                .as("原有的两条出路(先入库补货 / 从其它库区调拨)不能被这次修复删掉")
                .contains("入库补货")
                .contains("调拨");
    }
}
