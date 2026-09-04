package com.cretas.aims.service.finance;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.PayablePaymentStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 🔴🔒 应收金额口径 + per-shipment 幂等 (2026-08-28)。
 *
 * <p><b>口径 bug</b>: 旧实现按<b>销售订单金额</b>挂账 + 按订单幂等。而仓管在「确认发货」时可以
 * 覆盖实际发货数量 ({@code warehouseConfirmDelivery}, 只校验非负、<b>没有上界</b>), 覆盖后重算的是
 * 发货单 totalAmount, 应收取的却是订单金额 —— 两个数分家。prod 实测 (F006):
 * <pre>
 *   SO-20260828-0012  订 2 盒 → 填 5 → 库存扣 5, 发运单 110.62, 应收只有 44.25  ⇒ 3 盒没人收钱
 *   SO-20260828-0013  订 4 盒 → 填 2 → 发运单 44.25,  应收 88.50               ⇒ 客户被多收
 * </pre>
 *
 * <p><b>Fix</b>: {@link ArApService#recordReceivableIfAbsent} 按<b>每张子发运单的实发额</b>分笔挂账,
 * 幂等键 = {@code (sourceType=SALES_DELIVERY, sourceId=deliveryId)}, 累计 = 实发总额。
 *
 * <p><b>doomed-tx</b>: 与采购侧 {@code recordPayableIfAbsent} 同一形状 —— 对「已存在 / 金额缺失 /
 * 客户缺失 / SO 状态不允许」等预期条件返回 existing / null 而<b>绝不抛异常</b>。在共享事务里抛异常
 * 会把事务标记 rollback-only, 结果是<b>货发不出去</b> (采购侧 2026-07-02 踩过)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArApServiceImpl recordReceivableIfAbsent (发货自动挂账: 实发值 + per-shipment 幂等)")
class ArApServiceImplRecordReceivableIfAbsentTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private SalesOrderRepository salesOrderRepository;

    private static final String FACTORY     = "F006";
    private static final String CUSTOMER    = "CUST-001";
    private static final String SO_ID       = "SO-20260828-0012";
    private static final String SOURCE_TYPE = "SALES_DELIVERY";
    private static final String DELIVERY_ID = "DLV-20260828-9649-01";

    /** 实发额 (仓管填 5 盒) —— 挂账必须是它。 */
    private static final BigDecimal SHIPPED = new BigDecimal("110.62");
    /** 订单额 (订 2 盒) —— 挂账绝不能是它。 */
    private static final BigDecimal ORDERED = new BigDecimal("44.25");

    private ArApServiceImpl service() {
        ArApServiceImpl service = new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
        // salesOrderRepository is @Autowired field-injected in ArApServiceImpl.
        ReflectionTestUtils.setField(service, "salesOrderRepository", salesOrderRepository);
        return service;
    }

    private Customer customer(BigDecimal balance) {
        Customer c = new Customer();
        c.setId(CUSTOMER);
        c.setFactoryId(FACTORY);
        c.setName("测试客户");
        c.setCurrentBalance(balance);
        return c;
    }

    private SalesOrder so(SalesOrderStatus status) {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", SO_ID);
        o.setFactoryId(FACTORY);
        o.setCustomerId(CUSTOMER);
        o.setTotalAmount(ORDERED);
        o.setStatus(status);
        return o;
    }

    /** 打通到写库那一步的公共桩。 */
    private void stubHappyPath() {
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, DELIVERY_ID, ArApTransactionType.AR_INVOICE))
                .thenReturn(Optional.empty());
        when(customerRepository.findByIdAndFactoryId(CUSTOMER, FACTORY))
                .thenReturn(Optional.of(customer(BigDecimal.ZERO)));
        when(salesOrderRepository.findById(SO_ID))
                .thenReturn(Optional.of(so(SalesOrderStatus.PARTIAL_DELIVERED)));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(i -> i.getArgument(0));
    }

    private ArApTransaction post(BigDecimal amount) {
        return service().recordReceivableIfAbsent(FACTORY, CUSTOMER, SO_ID, SOURCE_TYPE, DELIVERY_ID,
                amount, LocalDate.now().plusDays(30), 1L, "销售发货自动挂账-DLV-20260828-9649-01");
    }

    // ---------------------------------------------------------------------------
    // 金额口径
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("挂账金额 = 调用方传入的实发额, 逐分不差 (不被订单额顶替)")
    void postsExactlyTheAmountPassedIn() {
        stubHappyPath();

        ArApTransaction result = post(SHIPPED);

        assertNotNull(result);
        ArgumentCaptor<ArApTransaction> saved = ArgumentCaptor.forClass(ArApTransaction.class);
        verify(transactionRepository).save(saved.capture());
        assertEquals(0, saved.getValue().getAmount().compareTo(SHIPPED),
                "挂账金额必须是实发额 110.62");
        assertNotEquals(0, saved.getValue().getAmount().compareTo(ORDERED),
                "回归钉: 绝不能是订单额 44.25 —— 那样多发的 3 盒永远没人收钱");
        assertEquals(ArApTransactionType.AR_INVOICE, saved.getValue().getTransactionType());
    }

    @Test
    @DisplayName("🔴 open-item 回归钉: 挂账即初始化 settled/outstanding/paymentStatus/currency, "
            + "不再是 null (persistReceivable 此前从未调 initializeOpenItem, AP_INVOICE 一直有调)")
    void initializesOpenItemFieldsLikeApInvoiceAlreadyDoes() {
        stubHappyPath();

        ArApTransaction result = post(SHIPPED);

        assertNotNull(result);
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(result.getSettledAmount()),
                "新挂账尚未收款, settled_amount 必须是 0.00, 不能是 null");
        assertEquals(0, SHIPPED.compareTo(result.getOutstandingAmount()),
                "outstanding_amount 必须 = 挂账金额(实发额), 不能是 null");
        assertEquals(PayablePaymentStatus.UNPAID, result.getPaymentStatus(),
                "新挂账的 payment_status 必须是 UNPAID, 不能是 null");
        assertEquals("CNY", result.getCurrencyCode());
    }

    @Test
    @DisplayName("幂等键与 SO 溯源都要写: sourceType/sourceId = 子发运单, salesOrderId = 订单")
    void writesBothIdempotencyKeyAndOrderTrace() {
        stubHappyPath();

        post(SHIPPED);

        ArgumentCaptor<ArApTransaction> saved = ArgumentCaptor.forClass(ArApTransaction.class);
        verify(transactionRepository).save(saved.capture());
        assertEquals(SOURCE_TYPE, saved.getValue().getSourceType(),
                "source_type 必须与 uk_aat_ar_invoice_per_shipment 的取值一致");
        assertEquals(DELIVERY_ID, saved.getValue().getSourceId(),
                "幂等键按【发运单】—— 按订单幂等会让分批发货的第二批永远挂不上账");
        assertEquals(SO_ID, saved.getValue().getSalesOrderId(),
                "SO 溯源不能丢, 否则订单页看不到这笔应收");
    }

    @Test
    @DisplayName("客户余额按实发额累加")
    void customerBalanceGrowsByActualShippedValue() {
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, DELIVERY_ID, ArApTransactionType.AR_INVOICE))
                .thenReturn(Optional.empty());
        Customer existing = customer(new BigDecimal("1000.00"));
        when(customerRepository.findByIdAndFactoryId(CUSTOMER, FACTORY)).thenReturn(Optional.of(existing));
        when(salesOrderRepository.findById(SO_ID))
                .thenReturn(Optional.of(so(SalesOrderStatus.PARTIAL_DELIVERED)));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(i -> i.getArgument(0));

        post(SHIPPED);

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(saved.capture());
        assertEquals(0, saved.getValue().getCurrentBalance().compareTo(new BigDecimal("1110.62")),
                "1000.00 + 实发 110.62");
    }

    // ---------------------------------------------------------------------------
    // 幂等 (防双击) —— 原语义必须保留
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("同一子发运单重复确认: 返回既有记录, 不重复挂账、不重复加余额")
    void sameShipmentTwice_isIdempotent() {
        ArApTransaction existing = new ArApTransaction();
        existing.setId("AAT-EXISTING");
        existing.setTransactionType(ArApTransactionType.AR_INVOICE);
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, DELIVERY_ID, ArApTransactionType.AR_INVOICE))
                .thenReturn(Optional.of(existing));

        ArApTransaction result = post(SHIPPED);

        assertSame(existing, result, "幂等命中必须返回既有那笔");
        verify(transactionRepository, never()).save(any(ArApTransaction.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    // ---------------------------------------------------------------------------
    // doomed-tx: 所有跳过条件都必须【不抛】—— 抛了货就发不出去
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("金额为 null / 0 / 负: 诚实跳过返回 null, 不抛、不写库 (不伪造应收)")
    void missingOrNonPositiveAmount_skipsHonestlyWithoutThrowing() {
        for (BigDecimal bad : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-1.00")}) {
            assertNull(assertDoesNotThrow(() -> post(bad)),
                    "金额 " + bad + " 必须诚实跳过, 且绝不抛 (共享事务里抛 = 货发不出去)");
        }
        verify(transactionRepository, never()).save(any(ArApTransaction.class));
    }

    @Test
    @DisplayName("客户不存在: 返回 null, 不抛")
    void missingCustomer_skipsWithoutThrowing() {
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, DELIVERY_ID, ArApTransactionType.AR_INVOICE))
                .thenReturn(Optional.empty());
        when(customerRepository.findByIdAndFactoryId(CUSTOMER, FACTORY)).thenReturn(Optional.empty());

        assertNull(assertDoesNotThrow(() -> post(SHIPPED)));
        verify(transactionRepository, never()).save(any(ArApTransaction.class));
    }

    @Test
    @DisplayName("SO 状态不允许挂账 (DRAFT): 返回 null, 不抛 —— 发货不被应收侧前置条件阻塞")
    void nonInvoiceableOrderStatus_skipsWithoutThrowing() {
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, DELIVERY_ID, ArApTransactionType.AR_INVOICE))
                .thenReturn(Optional.empty());
        when(customerRepository.findByIdAndFactoryId(CUSTOMER, FACTORY))
                .thenReturn(Optional.of(customer(BigDecimal.ZERO)));
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(so(SalesOrderStatus.DRAFT)));

        assertNull(assertDoesNotThrow(() -> post(SHIPPED)));
        verify(transactionRepository, never()).save(any(ArApTransaction.class));
    }

    // ---------------------------------------------------------------------------
    // 分批发货: 不同发运单各挂各的 (旧实现在这里直接 skip → 第二批永远不挂账)
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("同一 SO 的第二张子发运单: 照常挂它自己的实发额 (累计 = 实发总额)")
    void secondShipmentOfSameOrder_postsItsOwnValue() {
        String secondDelivery = "DLV-20260828-9649-02";
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, secondDelivery, ArApTransactionType.AR_INVOICE))
                .thenReturn(Optional.empty());   // 这张发运单自己还没挂过
        when(customerRepository.findByIdAndFactoryId(CUSTOMER, FACTORY))
                .thenReturn(Optional.of(customer(BigDecimal.ZERO)));
        when(salesOrderRepository.findById(SO_ID))
                .thenReturn(Optional.of(so(SalesOrderStatus.PARTIAL_DELIVERED)));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(i -> i.getArgument(0));

        BigDecimal secondValue = new BigDecimal("44.25");
        ArApTransaction result = service().recordReceivableIfAbsent(
                FACTORY, CUSTOMER, SO_ID, SOURCE_TYPE, secondDelivery,
                secondValue, LocalDate.now().plusDays(30), 1L, "销售发货自动挂账-第二批");

        assertNotNull(result, "同一 SO 的另一张发运单必须能挂账 —— 旧的按订单幂等在这里直接 skip");
        ArgumentCaptor<ArApTransaction> saved = ArgumentCaptor.forClass(ArApTransaction.class);
        verify(transactionRepository).save(saved.capture());
        assertEquals(0, saved.getValue().getAmount().compareTo(secondValue));
        assertEquals(secondDelivery, saved.getValue().getSourceId());
    }
}
