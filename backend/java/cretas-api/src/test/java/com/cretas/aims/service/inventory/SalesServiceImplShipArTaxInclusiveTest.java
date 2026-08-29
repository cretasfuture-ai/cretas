package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.ArApTransactionType;
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
 * 发货自动挂应收的<b>金额口径</b>钉子 —— 应收必须是【价税合计】, 不是未税额。
 *
 * <h2>它守的是什么</h2>
 * 2026-08-28 生产实测: {@code shipDelivery} 挂 AR_INVOICE 时传的是
 * {@code order.getTotalAmount()} —— 那是<b>未税</b>总额, 于是每张单的应收都少记一个税额。
 * F006 存量 7/7 全中 (命中率 100%), 少记合计 ¥1,692.15。
 *
 * <p>症状是<b>系统自己跟自己矛盾</b>: 同一次发货同时生成的销项发票是含税的
 * ({@code invoice_records.total_amount = amount + tax_amount}), 于是同一张单
 * 「发票说客户欠 250.00 / 应收台账说客户欠 221.24」。客户付掉未税额后 AR 净额归零,
 * 账龄表显示「无欠款」, 而一张 250.00 的销项发票已经开出去了。
 *
 * <h2>⛔ 为什么断言写在【金额】上而不是【调了几次】</h2>
 * 既有的 {@code SalesServiceImplShipArIdempotentTest} 已经钉了「调用次数」,
 * 但它的样本 {@code taxAmount} 是 0 ⇒ 未税额与价税合计恰好相等,
 * <b>那条断言对本缺陷是恒真的</b>。所以这里必须用带税的样本, 并断言到分。
 *
 * <p>变异对照: 把实现改回 {@code order.getTotalAmount()}, 两条用例都会红
 * (221.24 != 250.00, 1017.00 != 1000.00)。
 */
@ExtendWith(MockitoExtension.class)
class SalesServiceImplShipArTaxInclusiveTest {

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
    private static final String ORDER_ID    = "SO-F006-TAX-0001";
    private static final String DELIVERY_ID = "DLV-F006-TAX-0001";
    private static final Long   USER_ID     = 1L;

    /** 本类只钉接线: 让 helper 对空行原样返回这个数, 再断言它被传给了挂账。 */
    private BigDecimal shippedValueForWiring;

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository, salesOrderItemRepository, deliveryRecordRepository,
                finishedGoodsBatchRepository, customerRepository, productTypeRepository,
                arApService, eventPublisher);
        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", arApTransactionRepository);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "userRepository", userRepository);
    }

    private SalesDeliveryRecord makeDelivery() {
        SalesDeliveryRecord d = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(d, "id", DELIVERY_ID);
        d.setDeliveryNumber("DN-TAX-0001");
        d.setFactoryId(FACTORY_ID);
        d.setSalesOrderId(ORDER_ID);
        d.setStatus(SalesDeliveryStatus.DRAFT);
        d.setDeliveryDate(LocalDate.now());
        // ⚠️ 保持【空行】: 空行让 shipDelivery 跳过成品库存扣减, 这是本类原本的夹具设计。
        //   合并口径下 taxInclusiveShippedValue 对空行直接返回 record.getTotalAmount(),
        //   所以本类现在钉的是【接线】: 调用点有没有把 helper 的结果传给挂账。
        //   ⛔ 税额/实发量的算法本身不在这里钉 —— 那在 SalesServiceImplShipArMergedCaliberTest,
        //      它覆盖「含税 且 实发≠订单」这一格 (本类与 #302 的共同盲区)。
        d.setItems(new ArrayList<>());
        d.setTotalAmount(shippedValueForWiring);
        return d;
    }

    private SalesOrder makeOrder(String net, String tax, String discount) {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        o.setFactoryId(FACTORY_ID);
        o.setCustomerId("CUST-TAX-001");
        o.setTotalAmount(new BigDecimal(net));
        o.setTaxAmount(new BigDecimal(tax));
        if (discount != null) o.setDiscountAmount(new BigDecimal(discount));
        o.setStatus(com.cretas.aims.entity.enums.SalesOrderStatus.CONFIRMED);
        o.setItems(new ArrayList<>());
        return o;
    }

    private void stubCommon(SalesDeliveryRecord delivery, SalesOrder order) {
        when(deliveryRecordRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(deliveryRecordRepository.findByIdAndFactoryIdForUpdate(DELIVERY_ID, FACTORY_ID))
                .thenReturn(Optional.of(delivery));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(Collections.emptyList());
        when(deliveryRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // lenient: 合并口径后幂等键换成了 (SALES_DELIVERY, deliveryId), 走
        // existsLegacyArInvoiceWithoutSource, 不再调这个按订单的存在性检查。
        // 保留桩是为了让本类在两种实现下都能起来, ⛔ 但不能让它变成 UnnecessaryStubbing。
        org.mockito.Mockito.lenient().when(
                arApTransactionRepository.existsByFactoryIdAndSalesOrderIdAndTransactionType(
                        eq(FACTORY_ID), eq(ORDER_ID), eq(ArApTransactionType.AR_INVOICE))).thenReturn(false);
    }

    private BigDecimal capturedReceivable() {
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        // 合并后走 recordReceivableIfAbsent (每张子发运单一条幂等键), 不再是 recordReceivable
        verify(arApService, times(1)).recordReceivableIfAbsent(
                eq(FACTORY_ID), eq("CUST-TAX-001"), eq(ORDER_ID),
                any(), any(), amount.capture(), any(), eq(USER_ID), any());
        return amount.getValue();
    }

    /**
     * 生产实测的那一单: SO-20260828-0003 (含税单价 ¥25.00 × 10 盒)。
     * 未税 221.24 + 税 28.76 = 价税合计 250.00 —— 与同一次发货生成的销项发票一致。
     */
    @Test
    @DisplayName("发货挂应收: 未税 221.24 + 税 28.76 → 应收必须是 250.00 (价税合计), 不是 221.24")
    void shipDelivery_recordsReceivableAtTaxInclusiveAmount() {
        shippedValueForWiring = new BigDecimal("250.00");   // 实发价税合计
        SalesDeliveryRecord delivery = makeDelivery();
        SalesOrder order = makeOrder("221.24", "28.76", null);
        stubCommon(delivery, order);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        assertThat(capturedReceivable())
                .as("应收挂账金额必须等于客户实际应付(价税合计), 与销项发票口径一致")
                .isEqualByComparingTo(new BigDecimal("250.00"));
    }

    /**
     * 折扣也要算对 —— 这条钉住「⛔ 不要手写 totalAmount.add(taxAmount)」。
     * 那样写会漏掉折扣, 把应收多记 100 元。
     * 1000.00 - 100.00 + 117.00 = 1017.00
     *
     * <h3>⚠️ 2026-08-29 订正夹具: 喂进去的是【折扣前】的 1117.00</h3>
     * 本条原本喂 {@code shippedValueForWiring = 1017.00} —— 那已经是<b>期望的答案</b>,
     * 而当时的实现<b>根本不算折扣</b> (订单级 discountAmount 完全不参与
     * {@code taxInclusiveShippedValue})。于是 DisplayName 说的「折扣必须参与计算」
     * <b>是一条恒真断言</b>: 无论实现扣不扣折扣, 它都拿到夹具喂进去的 1017.00。
     *
     * <p>现在喂折扣前的 1117.00, 期望仍是 1017.00 ⇒ 那 100.00 只能由
     * {@code orderDiscountShareForShipment} 摊出来。
     * 变异对照: 把折扣摊派整段删掉, 本条会红 (1117.00 != 1017.00) —— 而在订正之前不会。
     */
    @Test
    @DisplayName("发货挂应收: 有折扣时按 未税-折扣+税 = 1017.00, 不扣折扣会算成 1117.00")
    void shipDelivery_receivableAccountsForDiscount() {
        shippedValueForWiring = new BigDecimal("1117.00");
        SalesDeliveryRecord delivery = makeDelivery();
        SalesOrder order = makeOrder("1000.00", "117.00", "100.00");
        stubCommon(delivery, order);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        assertThat(capturedReceivable())
                .as("折扣必须参与计算 —— getPayableAmount() 已是本实体的权威口径")
                .isEqualByComparingTo(new BigDecimal("1017.00"));
    }

    /** 零税率单据不能被这次改动影响 (回归护栏): 未税 == 价税合计。 */
    @Test
    @DisplayName("发货挂应收: 零税率订单仍按原额挂账 (不因本次改动漂移)")
    void shipDelivery_zeroTaxUnchanged() {
        shippedValueForWiring = new BigDecimal("9900.00");
        SalesDeliveryRecord delivery = makeDelivery();
        SalesOrder order = makeOrder("9900.00", "0.00", null);
        stubCommon(delivery, order);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        assertThat(capturedReceivable()).isEqualByComparingTo(new BigDecimal("9900.00"));
    }
}
