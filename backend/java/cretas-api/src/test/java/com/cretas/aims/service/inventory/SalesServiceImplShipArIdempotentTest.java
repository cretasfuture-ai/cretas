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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 发货自动应收挂账的<b>幂等</b>保护 (防双击 / 重复确认不重复挂账)。
 *
 * <h2>⚠️ 2026-08-28 本类的断言被<b>重新瞄准</b>过, 不是被删弱</h2>
 *
 * 改动前这三条测试守的是「<b>每销售订单</b>挂一笔、金额 = 订单额」。其中 Test 2 白纸黑字
 * 钉着 {@code eq(new BigDecimal("9900.00"))} —— 那正是 {@code order.getTotalAmount()}。
 * 也就是说<b>这条断言把缺陷本身钉成了契约</b>: 仓管把实发数量改成任意值之后, 应收照挂订单额。
 *
 * <p>生产实测 (F006): 订 2 盒仓管填 5 → 库存真扣 5、发运单 110.62、<b>应收只有 44.25</b>,
 * 3 盒没人收钱。⇒ 被守的行为已判定为缺陷, 断言必须跟着需求走 (而不是把改动回退掉)。
 *
 * <p><b>守的东西从「字面金额」抬到「性质」</b>:
 * <ul>
 *   <li>幂等这个<b>性质</b>原样保留 —— 只是键从「每订单」变成「每子发运单」
 *       (旧键让分批发货的第二批永远挂不上账), 且判定下沉进
 *       {@code ArApService#recordReceivableIfAbsent};</li>
 *   <li>金额口径改由 {@link SalesServiceImplShipArActualQuantityTest} 逐笔钉死;</li>
 *   <li>本类新增阴性对照: 旧的按订单金额挂账入口 {@code recordReceivable} <b>不许再被调用</b>。</li>
 * </ul>
 *
 * <p>三个结构性场景保持不变:
 * <ol>
 *   <li>切换缝生效 — 存量整单应收已存在 → 不再挂账 (否则在整单额之上双重计费);</li>
 *   <li>切换缝不生效 — 无存量整单应收 → 按实发额挂账恰好一次;</li>
 *   <li>NULL GUARD — {@code arApTransactionRepository} 缺失 (legacy 上下文) → 不 NPE, fail-open。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SalesServiceImplShipArIdempotentTest {

    // --- ctor-injected required dependencies ---
    @Mock SalesOrderRepository           salesOrderRepository;
    @Mock SalesDeliveryRecordRepository  deliveryRecordRepository;
    @Mock SalesOrderItemRepository       salesOrderItemRepository;
    @Mock FinishedGoodsBatchRepository   finishedGoodsBatchRepository;
    @Mock CustomerRepository             customerRepository;
    @Mock ProductTypeRepository          productTypeRepository;
    @Mock ArApService                    arApService;
    @Mock ApplicationEventPublisher      eventPublisher;

    // --- optional @Autowired(required=false) field dependencies ---
    @Mock ArApTransactionRepository      arApTransactionRepository;
    @Mock WarehouseResolver              warehouseResolver;
    @Mock UserRepository                 userRepository;

    SalesServiceImpl salesService;

    private static final String FACTORY_ID  = "F006";
    private static final String ORDER_ID    = "SO-F006-0001";
    private static final String DELIVERY_ID = "DLV-F006-0001";
    private static final Long   USER_ID     = 1L;

    /** 本次子发运单的实发额 —— 挂账金额必须是它, 不是订单额。 */
    private static final BigDecimal SHIPPED_VALUE = new BigDecimal("4200.00");
    /** 订单额 —— 故意与实发额不同, 这样「挂错了哪个」一眼可见。 */
    private static final BigDecimal ORDER_VALUE   = new BigDecimal("9900.00");

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

        // Inject optional @Autowired(required=false) fields via ReflectionTestUtils,
        // consistent with SalesServiceImplSalespersonTest pattern.
        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", arApTransactionRepository);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "userRepository", userRepository);
    }

    // ---------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------

    /**
     * 子发运单 (record_role=SHIPMENT), 带实发额, 无行项。
     * 空 items 让批次分配闸与扣库存循环空转 —— 本类只关心应收那一段。
     */
    private SalesDeliveryRecord makeDelivery() {
        SalesDeliveryRecord d = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(d, "id", DELIVERY_ID);
        d.setDeliveryNumber("DN-0001");
        d.setFactoryId(FACTORY_ID);
        d.setSalesOrderId(ORDER_ID);
        d.setStatus(SalesDeliveryStatus.DRAFT);
        d.setRecordRole("SHIPMENT");
        // 非 LEGACY 记录且未指定配送方式时默认按 LOGISTICS 处理, 那会要求物流公司 + 运单号
        // (SHIPMENT_TRACKING_REQUIRED)。本类不测配送方式, 选自提绕开。
        d.setDeliveryMethod("SELF_PICKUP");
        // fool-proof-design required-field guard (SHIPMENT_DATE_REQUIRED): shipDelivery now
        // 400s with "确认发货前必须填写实际发运日期" when deliveryDate is unset.
        d.setDeliveryDate(LocalDate.now());
        d.setTotalAmount(SHIPPED_VALUE);
        d.setItems(new ArrayList<>()); // empty — batchAllocationService null-guard skips check
        return d;
    }

    private SalesOrder makeOrder() {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        o.setFactoryId(FACTORY_ID);
        o.setCustomerId("CUST-001");
        o.setTotalAmount(ORDER_VALUE);
        o.setStatus(com.cretas.aims.entity.enums.SalesOrderStatus.CONFIRMED);
        o.setItems(new ArrayList<>());
        return o;
    }

    /**
     * Shared repository stubs required by {@code shipDelivery} regardless of AR guard outcome.
     * 详见各 stub 上的说明: 未打桩时 Mockito 对 {@code Optional} 返回 {@code Optional.empty()},
     * 会让 {@code orElseThrow()} 在测试触达应收那一段之前就先炸。
     */
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

    // ---------------------------------------------------------------------------
    // Test 1 — 切换缝生效: 存量整单应收已存在 → 不再挂账。
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("shipDelivery: 已有存量整单 AR_INVOICE → 不得再挂账 (否则在整单额之上双重计费)")
    void shipDelivery_whenLegacyWholeOrderArExists_skipsPosting() {
        SalesDeliveryRecord delivery = makeDelivery();
        SalesOrder          order    = makeOrder();
        stubCommonDeliveryDeps(delivery, order);

        // 升级前挂过整单应收 (source_id 为 NULL) 且订单还有未发量 —— prod 实测 SO-20260828-0013 之形。
        when(arApTransactionRepository.existsLegacyArInvoiceWithoutSource(FACTORY_ID, ORDER_ID))
                .thenReturn(true);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        verify(arApService, never()).recordReceivableIfAbsent(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------------------
    // Test 2 — 切换缝不生效: 按【实发额】挂账恰好一次。
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("shipDelivery: 无存量整单 AR → 按实发额挂账恰好一次 (幂等键 = 本子发运单)")
    void shipDelivery_whenNoLegacyAr_postsActualShippedValueOnce() {
        SalesDeliveryRecord delivery = makeDelivery();
        SalesOrder          order    = makeOrder();
        stubCommonDeliveryDeps(delivery, order);

        when(arApTransactionRepository.existsLegacyArInvoiceWithoutSource(FACTORY_ID, ORDER_ID))
                .thenReturn(false);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        verify(arApService, times(1)).recordReceivableIfAbsent(
                eq(FACTORY_ID),
                eq("CUST-001"),
                eq(ORDER_ID),
                eq("SALES_DELIVERY"),   // 幂等键类型 — 与 uk_aat_ar_invoice_per_shipment 一致
                eq(DELIVERY_ID),        // 幂等键 = 子发运单, 不是订单
                eq(SHIPPED_VALUE),      // 🔴 实发额。旧断言在这里钉的是订单额 9900.00 = 缺陷本身
                any(),                  // due-date: LocalDate.now() + 30 — not worth pinning
                eq(USER_ID),
                contains("DN-0001"));

        // 阴性对照: 按订单金额挂账的旧入口不许再被调用。
        verify(arApService, never()).recordReceivable(any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------------------
    // Test 3 — NULL GUARD: repo absent in legacy context → fail-open, no NPE.
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("shipDelivery: arApTransactionRepository null (legacy) → 不 NPE, 仍按实发额挂账 (fail-open)")
    void shipDelivery_whenRepoIsNull_doesNotThrow_andPostsActualShippedValue() {
        // Override the setUp injection — simulate legacy context where optional bean is absent.
        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", null);

        SalesDeliveryRecord delivery = makeDelivery();
        SalesOrder          order    = makeOrder();
        stubCommonDeliveryDeps(delivery, order);

        // Must not throw even though arApTransactionRepository is null.
        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        // Fail-open: 切换缝查不了时仍然挂账 (避免静默丢账), 且金额仍须是实发额。
        // 重复挂账由 recordReceivableIfAbsent 的 (sourceType, sourceId) 幂等键兜底。
        verify(arApService, times(1)).recordReceivableIfAbsent(
                any(), any(), any(), eq("SALES_DELIVERY"), eq(DELIVERY_ID),
                eq(SHIPPED_VALUE), any(), any(), any());
    }
}
