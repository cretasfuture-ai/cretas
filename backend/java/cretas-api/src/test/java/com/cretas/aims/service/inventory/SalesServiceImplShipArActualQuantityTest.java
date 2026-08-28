package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 应收金额必须反映【实发】数量, 不是【订单】数量。
 *
 * <h2>为什么既有测试没抓住这个缺陷</h2>
 *
 * {@code SalesServiceImplShipArIdempotentTest} 只 verify「{@code recordReceivable} 被调了几次」,
 * 金额一律写成 {@code any(BigDecimal.class)} —— <b>金额传什么都是绿的</b>。
 * 本测试类存在的全部意义就是<b>钉住金额</b>。
 *
 * <h2>生产实测的两笔 (F006, 2026-08-28)</h2>
 *
 * 仓管在「确认发货」时可以覆盖实际发货数量 ({@code warehouseConfirmDelivery},
 * 客户原话「车装不下 / 现场少装」), 只校验非负、<b>没有上界</b>。覆盖后重算的是
 * <b>发货单</b> totalAmount, 而应收挂账取的是<b>订单</b>金额 —— 两个数从此分家:
 *
 * <pre>
 *   SO-20260828-0012  订 2 盒 → 仓管填 5 → 库存真扣 5, 子发运单 110.62,
 *                     应收只挂 44.25, 订单 COMPLETED   ⇒ 3 盒没人收钱  (无条件错)
 *   SO-20260828-0013  订 4 盒 → 仓管填 2 → 子发运单 44.25,
 *                     应收挂 88.50                      ⇒ 客户被多收
 * </pre>
 *
 * <p>⛔ 本测试<b>不表态</b>「超发允不允许、允许多少」—— 那是产品决定 (采购侧有
 * {@code overReceiveRate} + {@code purchase_exceptions} 的先例, 销售侧两样都没有)。
 * 这里只钉会计正确性: <b>无论超发少发, 应收 = 实发额</b>。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("发货自动应收 — 金额必须是实发额")
class SalesServiceImplShipArActualQuantityTest {

    @Mock SalesOrderRepository           salesOrderRepository;
    @Mock SalesDeliveryRecordRepository  deliveryRecordRepository;
    @Mock SalesOrderItemRepository       salesOrderItemRepository;
    @Mock FinishedGoodsBatchRepository   finishedGoodsBatchRepository;
    @Mock CustomerRepository             customerRepository;
    @Mock ProductTypeRepository          productTypeRepository;
    @Mock ArApService                    arApService;
    @Mock ApplicationEventPublisher      eventPublisher;

    @Mock ArApTransactionRepository      arApTransactionRepository;
    @Mock WarehouseResolver              warehouseResolver;
    @Mock UserRepository                 userRepository;

    SalesServiceImpl salesService;

    private static final String FACTORY_ID  = "F006";
    private static final String ORDER_ID    = "SO-F006-0012";
    private static final String DELIVERY_ID = "DLV-F006-9649-01";
    private static final Long   USER_ID     = 1L;

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository,
                salesOrderItemRepository,
                deliveryRecordRepository,
                finishedGoodsBatchRepository,
                customerRepository,
                productTypeRepository,
                arApService,
                eventPublisher);
        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", arApTransactionRepository);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "userRepository", userRepository);
    }

    /**
     * 子发运单 (record_role=SHIPMENT)。{@code totalAmount} 就是仓管确认后的<b>实发额</b> ——
     * {@code warehouseConfirmDelivery} 在覆盖数量后会 {@code setTotalAmount(Σ 实发 × 单价)}。
     * items 留空: 空 items 让批次分配闸与扣库存循环空转, 本测试只关心应收金额那一段。
     */
    private SalesDeliveryRecord makeShipment(BigDecimal actualShippedValue) {
        SalesDeliveryRecord d = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(d, "id", DELIVERY_ID);
        d.setDeliveryNumber("DLV-20260828-9649-01");
        d.setFactoryId(FACTORY_ID);
        d.setSalesOrderId(ORDER_ID);
        d.setStatus(SalesDeliveryStatus.DRAFT);
        d.setRecordRole("SHIPMENT");
        // 非 LEGACY 记录且未指定配送方式时 shipDelivery 默认按 LOGISTICS 处理, 那会要求
        // 物流公司 + 运单号 (SHIPMENT_TRACKING_REQUIRED)。本类不测配送方式, 选自提绕开。
        d.setDeliveryMethod("SELF_PICKUP");
        d.setDeliveryDate(LocalDate.now());
        d.setTotalAmount(actualShippedValue);
        d.setItems(new ArrayList<>());
        return d;
    }

    private SalesOrder makeOrder(BigDecimal orderedValue) {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        o.setFactoryId(FACTORY_ID);
        o.setCustomerId("CUST-001");
        o.setTotalAmount(orderedValue);
        o.setStatus(com.cretas.aims.entity.enums.SalesOrderStatus.CONFIRMED);
        o.setItems(new ArrayList<>());
        return o;
    }

    private void stubCommonDeliveryDeps(SalesDeliveryRecord delivery, SalesOrder order) {
        when(deliveryRecordRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(deliveryRecordRepository.findByIdAndFactoryIdForUpdate(DELIVERY_ID, FACTORY_ID))
                .thenReturn(Optional.of(delivery));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(Collections.emptyList());
        when(deliveryRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 捕获传给应收挂账的金额 —— 本测试类的核心, 既有测试正是在这里写了 any()。 */
    private BigDecimal capturePostedAmount() {
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(arApService).recordReceivableIfAbsent(
                eq(FACTORY_ID), eq("CUST-001"), eq(ORDER_ID),
                eq("SALES_DELIVERY"), eq(DELIVERY_ID),
                amount.capture(), any(), eq(USER_ID), any());
        return amount.getValue();
    }

    // ---------------------------------------------------------------------------
    // 超发 —— 无条件错: 多发的货没人收钱
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("超发 (订 2 盒发 5 盒): 应收必须是实发额 110.62, 不是订单额 44.25")
    void overShipment_postsActualShippedValue_notOrderValue() {
        BigDecimal orderedValue = new BigDecimal("44.25");    // 2 盒
        BigDecimal shippedValue = new BigDecimal("110.62");   // 仓管填 5 盒, 库存真扣 5

        SalesDeliveryRecord delivery = makeShipment(shippedValue);
        SalesOrder          order    = makeOrder(orderedValue);
        stubCommonDeliveryDeps(delivery, order);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        BigDecimal posted = capturePostedAmount();
        assertThat(posted)
                .as("应收挂的必须是【实发】额 —— 挂订单额 44.25 就是多发的 3 盒永远没人收钱")
                .isEqualByComparingTo(shippedValue);
        assertThat(posted)
                .as("回归钉: 绝不能回落到订单金额, 那正是本次修复的缺陷本身")
                .isNotEqualByComparingTo(orderedValue);
    }

    // ---------------------------------------------------------------------------
    // 少发 —— 客户被多收
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("少发 (订 4 盒发 2 盒): 应收必须是实发额 44.25, 不是订单额 88.50")
    void underShipment_postsActualShippedValue_notOrderValue() {
        BigDecimal orderedValue = new BigDecimal("88.50");   // 4 盒
        BigDecimal shippedValue = new BigDecimal("44.25");   // 现场只装下 2 盒

        SalesDeliveryRecord delivery = makeShipment(shippedValue);
        SalesOrder          order    = makeOrder(orderedValue);
        stubCommonDeliveryDeps(delivery, order);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        BigDecimal posted = capturePostedAmount();
        assertThat(posted)
                .as("只发了 2 盒就只能收 2 盒的钱 —— 挂订单额 88.50 是向客户多收")
                .isEqualByComparingTo(shippedValue);
        assertThat(posted)
                .as("回归钉: 绝不能回落到订单金额")
                .isNotEqualByComparingTo(orderedValue);
    }

    // ---------------------------------------------------------------------------
    // 足额发货 —— 两个口径本来就相等, 行为不变 (阴性对照: 本修复没有改坏正常路径)
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("足额发货: 实发额 == 订单额, 挂账金额不变 (正常路径未被改坏)")
    void exactShipment_postsSameValueAsBefore() {
        BigDecimal value = new BigDecimal("442.48");

        SalesDeliveryRecord delivery = makeShipment(value);
        SalesOrder          order    = makeOrder(value);
        stubCommonDeliveryDeps(delivery, order);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        assertThat(capturePostedAmount())
                .as("足额发货时两个口径相等 —— 绝大多数订单走这条路, 金额必须原样不变")
                .isEqualByComparingTo(value);
    }

    // ---------------------------------------------------------------------------
    // 幂等键: 必须按【子发运单】而不是按【订单】—— 否则分批发货第二批永远不挂账
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("幂等键必须是 (SALES_DELIVERY, 发运单ID), 不是订单ID —— 分批发货各挂各的")
    void idempotencyKeyIsThePerShipmentSource() {
        SalesDeliveryRecord delivery = makeShipment(new BigDecimal("44.25"));
        SalesOrder          order    = makeOrder(new BigDecimal("88.50"));
        stubCommonDeliveryDeps(delivery, order);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        ArgumentCaptor<String> sourceType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceId   = ArgumentCaptor.forClass(String.class);
        verify(arApService).recordReceivableIfAbsent(
                any(), any(), any(), sourceType.capture(), sourceId.capture(),
                any(), any(), any(), any());

        assertThat(sourceType.getValue())
                .as("必须与 DB 唯一键 uk_aat_ar_invoice_per_shipment 的 source_type 取值一致")
                .isEqualTo("SALES_DELIVERY");
        assertThat(sourceId.getValue())
                .as("幂等键按【发运单】—— 按订单幂等会让分批发货的第二批永远挂不上账")
                .isEqualTo(DELIVERY_ID)
                .isNotEqualTo(ORDER_ID);
    }

    // ---------------------------------------------------------------------------
    // 切换缝: 升级前挂过整单应收的在飞订单, 不许再挂 (否则双重计费)
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("存量整单挂账的在飞订单: 再发一批不再挂账 (避免在整单应收之上双重计费)")
    void legacyWholeOrderAr_skipsPostingToAvoidDoubleBilling() {
        SalesDeliveryRecord delivery = makeShipment(new BigDecimal("44.25"));
        SalesOrder          order    = makeOrder(new BigDecimal("88.50"));
        stubCommonDeliveryDeps(delivery, order);

        // prod 实测形态: SO-20260828-0013 已挂 88.50 整单额 (source_id 为 NULL), 还剩 2 盒未发。
        when(arApTransactionRepository.existsLegacyArInvoiceWithoutSource(FACTORY_ID, ORDER_ID))
                .thenReturn(true);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        verify(arApService, never()).recordReceivableIfAbsent(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------------------
    // 旧入口不得再被使用 —— 它按订单金额挂账, 正是缺陷本身
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("发货路径不得再调用按订单金额挂账的 recordReceivable 旧入口")
    void legacyOrderAmountEntryPointIsNoLongerUsed() {
        SalesDeliveryRecord delivery = makeShipment(new BigDecimal("110.62"));
        SalesOrder          order    = makeOrder(new BigDecimal("44.25"));
        stubCommonDeliveryDeps(delivery, order);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        verify(arApService, never()).recordReceivable(any(), any(), any(), any(), any(), any(), any());
    }
}
