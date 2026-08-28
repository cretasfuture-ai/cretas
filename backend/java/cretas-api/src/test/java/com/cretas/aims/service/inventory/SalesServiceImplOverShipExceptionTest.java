package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
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
import org.junit.jupiter.api.Nested;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 超发被拦时的<b>留痕</b> —— {@code warehouseConfirmDelivery} → {@code SalesExceptionService} 的接线。
 *
 * <h1>🔴 这个类能证明什么、不能证明什么（先读这一段再看断言）</h1>
 *
 * <table border="1">
 *   <tr><th>命题</th><th>本类能证明吗</th><th>凭什么</th></tr>
 *   <tr><td>超限时留痕方法<b>被调用了</b>，且带着对的参数</td><td>✅ 能</td>
 *       <td>驱动的是产品真入口 {@code warehouseConfirmDelivery}，不是私有闸</td></tr>
 *   <tr><td>没超限 / 不限制 / 少发时<b>不</b>留痕</td><td>✅ 能</td><td>阴性对照，见 {@link NegativeControls}</td></tr>
 *   <tr><td>留痕<b>发生在抛 409 之前</b></td><td>✅ 能</td><td>桩抛异常时仍先被调用；且 409 照常抛出</td></tr>
 *   <tr><td>留痕失败<b>不会把 409 变成 500</b></td><td>✅ 能</td>
 *       <td>让桩抛 {@code RuntimeException}，断言外面拿到的仍是 409 且文案未变</td></tr>
 *   <tr><td>没注入这个 bean 时闸<b>照样拦</b></td><td>✅ 能</td><td>legacy 构造器路径</td></tr>
 *   <tr><td>🔴 <b>异常单在 409 回滚之后真的还在库里</b></td><td>❌ <b>不能</b></td>
 *       <td>本类<b>没有事务、没有数据库</b>。协作者是 Mockito 桩，
 *           「独立事务提交」这件事在这里连发生的机会都没有。<br>
 *           那条命题由 {@code SalesExceptionServiceImplTest} 的<b>结构断言</b>
 *           （REQUIRES_NEW 标在一个跨 bean 的 public 方法上）间接支撑，
 *           而<b>真正的运行时证明需要一个带真实事务管理器的集成测试，本轮没有做</b>。
 *           ⛔ 不要把本类的绿读成「留痕一定留得下来」。</td></tr>
 * </table>
 *
 * <p>⚠️ 这张表是本类最重要的部分。上一次本仓栽在「Mockito 桩证明不了任何关于事务的事」
 * 这一条上时，症状是所有断言都绿而生产上什么都没写进去。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesServiceImplOverShipExceptionTest {

    @Mock SalesOrderRepository           salesOrderRepository;
    @Mock SalesDeliveryRecordRepository  deliveryRecordRepository;
    @Mock SalesOrderItemRepository       salesOrderItemRepository;
    @Mock FinishedGoodsBatchRepository   finishedGoodsBatchRepository;
    @Mock CustomerRepository             customerRepository;
    @Mock ProductTypeRepository          productTypeRepository;
    @Mock ArApService                    arApService;
    @Mock ApplicationEventPublisher      eventPublisher;
    @Mock ThresholdResolverService       thresholdResolverService;
    @Mock SalesExceptionService          salesExceptionService;

    private static final String FACTORY_ID  = "F006";
    private static final String ORDER_ID    = "SO-F006-OSE-0001";
    private static final String DELIVERY_ID = "DLV-F006-OSE-0001";
    private static final Long   ITEM_ID     = 4242L;
    private static final Long   USER_ID     = 77L;

    private SalesServiceImpl salesService;
    private SalesDeliveryRecord delivery;

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository, salesOrderItemRepository, deliveryRecordRepository,
                finishedGoodsBatchRepository, customerRepository, productTypeRepository,
                arApService, eventPublisher);
        ReflectionTestUtils.setField(salesService, "thresholdResolverService", thresholdResolverService);
        ReflectionTestUtils.setField(salesService, "salesExceptionService", salesExceptionService);

        delivery = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(delivery, "id", DELIVERY_ID);
        delivery.setDeliveryNumber("DN-OSE-0001");
        delivery.setFactoryId(FACTORY_ID);
        delivery.setSalesOrderId(ORDER_ID);
        delivery.setStatus(SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        delivery.setDeliveryDate(LocalDate.now());
        delivery.setItems(new ArrayList<>(List.of(line())));

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.setFactoryId(FACTORY_ID);
        order.setItems(new ArrayList<>());

        when(deliveryRecordRepository.findByIdAndFactoryIdForUpdate(DELIVERY_ID, FACTORY_ID))
                .thenReturn(Optional.of(delivery));
        when(deliveryRecordRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY_ID))
                .thenReturn(Optional.of(order));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    /** 计划发 2 盒。 */
    private SalesDeliveryItem line() {
        SalesDeliveryItem item = new SalesDeliveryItem();
        ReflectionTestUtils.setField(item, "id", ITEM_ID);
        item.setDeliveryRecordId(DELIVERY_ID);
        item.setProductTypeId("PT-OSE-1");
        item.setProductName("冷冻带鱼段");
        item.setDeliveredQuantity(new BigDecimal("2"));
        item.setUnit("盒");
        item.setUnitPrice(new BigDecimal("25.00"));
        return item;
    }

    /** 阈值中心返回 rate（null = 没配 ⇒ 不限制）。 */
    private void hubRate(String rate) {
        when(thresholdResolverService.getBigDecimal(
                eq(FACTORY_ID), eq(ThresholdKeys.SALES_DELIVERY_OVER_SHIP_RATE), any()))
                .thenReturn(rate == null ? null : new BigDecimal(rate));
    }

    private Map<String, BigDecimal> actual(String qty) {
        Map<String, BigDecimal> m = new HashMap<>();
        m.put(String.valueOf(ITEM_ID), new BigDecimal(qty));
        return m;
    }

    private void confirm(String qty) {
        salesService.warehouseConfirmDelivery(FACTORY_ID, DELIVERY_ID, actual(qty), USER_ID);
    }

    // ==================================================================
    @Nested
    @DisplayName("正向: 超发被拦 → 留痕, 且 409 照抛")
    class RecordsOnBlock {

        @Test
        @DisplayName("🔴 生产那一单(计划 2 实发 5 @10%): 抛 409 的同时写一张异常单")
        void blockedAttempt_isRecorded() {
            hubRate("0.10");

            assertThatThrownBy(() -> confirm("5"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("超出超发上限");

            verify(salesExceptionService).recordBlockedOverShip(
                    eq(FACTORY_ID), eq(delivery), any(SalesDeliveryItem.class),
                    any(), any(), any(), any(), eq(USER_ID));
        }

        @Test
        @DisplayName("🔴 留痕带的每一格都对: 计划/尝试实发/上限/上限来源/操作人")
        void recordCarriesEveryField() {
            hubRate("0.10");

            assertThatThrownBy(() -> confirm("5")).isInstanceOf(BusinessException.class);

            ArgumentCaptor<SalesDeliveryItem> item = ArgumentCaptor.forClass(SalesDeliveryItem.class);
            ArgumentCaptor<BigDecimal> attempted = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<BigDecimal> rate = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<BigDecimal> maxAllowed = ArgumentCaptor.forClass(BigDecimal.class);

            verify(salesExceptionService).recordBlockedOverShip(
                    eq(FACTORY_ID), eq(delivery), item.capture(), attempted.capture(),
                    rate.capture(), source.capture(), maxAllowed.capture(), eq(USER_ID));

            assertThat(item.getValue().getId()).isEqualTo(ITEM_ID);
            assertThat(item.getValue().getDeliveredQuantity())
                    .as("🔴 计划量必须还是【原值 2】—— 闸在写入之前拦下, "
                            + "若这里读到 5 说明实现先改了内存再抛, 留痕就把「计划」记成了「尝试」")
                    .isEqualByComparingTo("2");
            assertThat(attempted.getValue())
                    .as("尝试实发量 = 仓管想填的那个数")
                    .isEqualByComparingTo("5");
            assertThat(rate.getValue()).isEqualByComparingTo("0.10");
            assertThat(source.getValue())
                    .as("🔴 来源不能说谎 —— 排查「为什么我发不了货」时答案通常就在这一行")
                    .isEqualTo(OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB);
            assertThat(maxAllowed.getValue())
                    .as("最大可发 = 2 × 1.10")
                    .isEqualByComparingTo("2.20");
        }

        @Test
        @DisplayName("rate=0 (一件都不许超发) 也要留痕 —— ⛔ 0 不是「没配」")
        void zeroRate_alsoRecords() {
            hubRate("0");

            assertThatThrownBy(() -> confirm("2.01")).isInstanceOf(BusinessException.class);

            verify(salesExceptionService).recordBlockedOverShip(
                    eq(FACTORY_ID), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("阴性对照: 没被拦的一律不留痕")
    class NegativeControls {

        @Test
        @DisplayName("🔴 实发 == 计划 → 一个字都不写 (否则异常表会被正常发货灌满)")
        void exactQuantity_writesNothing() {
            hubRate("0.10");
            try {
                confirm("2");
            } catch (RuntimeException expectedDownstream) {
                // 本类不搭批次分配/库存那一长串桩, shipDelivery 之后会失败 —— 与本条无关。
                // 承重的是下面这句 verifyNoInteractions: 闸没拦 ⇒ 留痕一次都不该发生。
            }
            verifyNoInteractions(salesExceptionService);
        }

        @Test
        @DisplayName("🔴 上限为「不限制」→ 不留痕 (证明留痕确实由那道闸驱动, 不是逢确认必写)")
        void unlimited_writesNothing() {
            hubRate(null);   // 谁也没配过 ⇒ 不限制
            try {
                confirm("99999");
            } catch (RuntimeException expectedDownstream) {
                // 同上
            }
            verify(salesExceptionService, never()).recordBlockedOverShip(
                    any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("少发 (实发 < 计划) → 不留痕, 这道闸只管上限")
        void underShipment_writesNothing() {
            hubRate("0.10");
            try {
                confirm("1");
            } catch (RuntimeException expectedDownstream) {
                // 同上
            }
            verifyNoInteractions(salesExceptionService);
        }

        @Test
        @DisplayName("负数被 400 挡在更前面 → 不留痕 (那不是超发)")
        void negativeQuantity_writesNothing() {
            hubRate("0.10");
            assertThatThrownBy(() -> confirm("-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能为负");
            verifyNoInteractions(salesExceptionService);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("🔴 留痕失败不许改变结果 —— 409 不能变成 500")
    class RecordingNeverChangesTheOutcome {

        @Test
        @DisplayName("留痕抛 RuntimeException → 外面拿到的仍然是原样的 409")
        void recorderBlowingUp_stillYields409() {
            hubRate("0.10");
            when(salesExceptionService.recordBlockedOverShip(
                    any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("模拟: 留痕库挂了"));

            assertThatThrownBy(() -> confirm("5"))
                    .as("🔴 仓管看到的必须仍是「最大可发 2.2盒」这种可执行的话, "
                            + "⛔ 不能变成「服务器内部错误」—— 发货本来就该被拒绝, "
                            + "变的只是他还能不能知道为什么")
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(409);
                        assertThat(ex.getErrorCode())
                                .isEqualTo("DELIVERY_QUANTITY_EXCEEDS_OVER_SHIP_CAP");
                        assertThat(ex.getMessage()).contains("最大可发 2.2盒");
                    });
        }

        @Test
        @DisplayName("留痕返回 null (内部已吞掉失败) → 409 仍然照抛")
        void recorderReturningNull_stillYields409() {
            hubRate("0.10");
            when(salesExceptionService.recordBlockedOverShip(
                    any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(null);

            assertThatThrownBy(() -> confirm("5"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(409));
        }

        @Test
        @DisplayName("🔴 没注入 SalesExceptionService (legacy 单测/降级) → 不留痕, 但闸照拦")
        void withoutTheBean_gateStillBlocks() {
            ReflectionTestUtils.setField(salesService, "salesExceptionService", null);
            hubRate("0.10");

            assertThatThrownBy(() -> confirm("5"))
                    .as("闸与留痕是两件事 —— 缺一个不该带垮另一个")
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getErrorCode())
                                    .isEqualTo("DELIVERY_QUANTITY_EXCEEDS_OVER_SHIP_CAP"));
        }
    }
}
