package com.cretas.aims.service.finance;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PayablePaymentStatus;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.finance.ArApPaymentAllocation;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApPaymentAllocationRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import com.cretas.aims.service.finance.FinancePaymentReferenceLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔴🔒 AR 收款发票级核销 (2026-09-04, follow-up to #577)。
 *
 * <p>{@code recordArPayment} 此前只更新 Customer.currentBalance / SalesOrder.paidAmount, 从不
 * 回写 AR_INVOICE 的 settled_amount/outstanding_amount/paymentStatus (initializeOpenItem 挂账
 * 时写一次之后就再没人碰过)。本测试覆盖新增的 {@code applyPaymentToOpenArInvoices} 私有方法通过
 * {@code recordArPayment} 的四种边界: 无 salesOrderId / 单张发票 / 一个 SO 下多张发票(FIFO) /
 * 超付, 以及幂等性(同一笔收款重复提交不会二次核销)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArApServiceImpl recordArPayment (发票级核销分配)")
class ArApServiceImplRecordArPaymentTest {

    private static final String FACTORY = "F006";
    private static final String CUSTOMER = "CUST-001";
    private static final String SO_ID = "SO-0001";

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private ArApPaymentAllocationRepository allocationRepository;
    @Mock private FinancePaymentReferenceLock paymentReferenceLock;

    private ArApServiceImpl service() {
        ArApServiceImpl service = new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
        ReflectionTestUtils.setField(service, "salesOrderRepository", salesOrderRepository);
        ReflectionTestUtils.setField(service, "allocationRepository", allocationRepository);
        ReflectionTestUtils.setField(service, "paymentReferenceLock", paymentReferenceLock);
        when(paymentReferenceLock.acquire(any(), any())).thenAnswer(invocation -> {
            String reference = invocation.getArgument(1);
            return reference == null || reference.isBlank() ? reference : reference.trim();
        });
        return service;
    }

    private Customer customer(String balance) {
        Customer c = new Customer();
        c.setId(CUSTOMER);
        c.setFactoryId(FACTORY);
        c.setName("测试客户");
        c.setCurrentBalance(new BigDecimal(balance));
        return c;
    }

    private SalesOrder so(String totalAmount) {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", SO_ID);
        o.setFactoryId(FACTORY);
        o.setCustomerId(CUSTOMER);
        o.setTotalAmount(new BigDecimal(totalAmount));
        o.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        return o;
    }

    private ArApTransaction arInvoice(String id, String amount, String settled, String outstanding,
                                       PayablePaymentStatus status, LocalDate txnDate) {
        ArApTransaction t = new ArApTransaction();
        t.setId(id);
        t.setFactoryId(FACTORY);
        t.setTransactionType(ArApTransactionType.AR_INVOICE);
        t.setCounterpartyType(CounterpartyType.CUSTOMER);
        t.setCounterpartyId(CUSTOMER);
        t.setCounterpartyName("测试客户");
        t.setSalesOrderId(SO_ID);
        t.setAmount(new BigDecimal(amount));
        t.setBalanceAfter(new BigDecimal(amount));
        t.setSettledAmount(new BigDecimal(settled));
        t.setOutstandingAmount(new BigDecimal(outstanding));
        t.setPaymentStatus(status);
        t.setCurrencyCode("CNY");
        t.setTransactionDate(txnDate);
        return t;
    }

    /** Common stubs shared by every scenario that actually posts a payment. */
    private void stubCommon(BigDecimal customerBalance) {
        when(customerRepository.findByIdAndFactoryId(CUSTOMER, FACTORY))
                .thenReturn(Optional.of(customer(customerBalance.toPlainString())));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** Only needed by scenarios that pass a non-null salesOrderId (SO.paidAmount sync block). */
    private void stubSalesOrder() {
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(so("1000.00")));
    }

    @Test
    @DisplayName("无 salesOrderId: 不查询、不核销任何发票, 只更新客户余额 (客户挂账预收款)")
    void noSalesOrderId_doesNotTouchAnyInvoice() {
        stubCommon(new BigDecimal("500.00"));

        ArApTransaction result = service().recordArPayment(
                FACTORY, CUSTOMER, null, new BigDecimal("100.00"),
                PaymentMethod.BANK_TRANSFER, "PAY-NOSO-1", 1L, "预收款");

        System.out.println("BEHAVIOUR: noSalesOrderId payment id=" + result.getId()
                + " salesOrderId=" + result.getSalesOrderId());
        assertEquals(null, result.getSalesOrderId());
        verify(transactionRepository, never())
                .findOpenArInvoicesForSettlementBySalesOrderId(any(), any());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    @DisplayName("单张发票部分核销: outstanding 100->60, settled 0->40, 状态 UNPAID->PARTIALLY_PAID")
    void singleInvoice_partialPayment_updatesOutstandingAndStatus() {
        stubCommon(new BigDecimal("500.00"));
        stubSalesOrder();
        ArApTransaction invoice = arInvoice("AR-1", "100.00", "0.00", "100.00",
                PayablePaymentStatus.UNPAID, LocalDate.of(2026, 9, 1));
        when(transactionRepository.findOpenArInvoicesForSettlementBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(List.of(invoice));
        when(transactionRepository.sumArPaymentsBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(new BigDecimal("40.00"));

        service().recordArPayment(FACTORY, CUSTOMER, SO_ID, new BigDecimal("40.00"),
                PaymentMethod.BANK_TRANSFER, "PAY-1", 1L, "收款");

        System.out.println("BEHAVIOUR: singleInvoice settled=" + invoice.getSettledAmount()
                + " outstanding=" + invoice.getOutstandingAmount() + " status=" + invoice.getPaymentStatus());
        assertEquals(0, new BigDecimal("40.00").compareTo(invoice.getSettledAmount()));
        assertEquals(0, new BigDecimal("60.00").compareTo(invoice.getOutstandingAmount()));
        assertEquals(PayablePaymentStatus.PARTIALLY_PAID, invoice.getPaymentStatus());

        ArgumentCaptor<ArApPaymentAllocation> allocCaptor = ArgumentCaptor.forClass(ArApPaymentAllocation.class);
        verify(allocationRepository).save(allocCaptor.capture());
        assertEquals("AR-1", allocCaptor.getValue().getPayableTransactionId());
        assertEquals(0, new BigDecimal("40.00").compareTo(allocCaptor.getValue().getAllocatedAmount()));
        assertEquals("CNY", allocCaptor.getValue().getCurrencyCode());
    }

    @Test
    @DisplayName("单张发票全额核销: 剩余付清后状态转 PAID, outstanding=0")
    void singleInvoice_fullPayment_marksPaid() {
        stubCommon(new BigDecimal("500.00"));
        stubSalesOrder();
        ArApTransaction invoice = arInvoice("AR-1", "100.00", "40.00", "60.00",
                PayablePaymentStatus.PARTIALLY_PAID, LocalDate.of(2026, 9, 1));
        when(transactionRepository.findOpenArInvoicesForSettlementBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(List.of(invoice));
        when(transactionRepository.sumArPaymentsBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(new BigDecimal("100.00"));

        service().recordArPayment(FACTORY, CUSTOMER, SO_ID, new BigDecimal("60.00"),
                PaymentMethod.BANK_TRANSFER, "PAY-2", 1L, "结清");

        System.out.println("BEHAVIOUR: fullPayment settled=" + invoice.getSettledAmount()
                + " outstanding=" + invoice.getOutstandingAmount() + " status=" + invoice.getPaymentStatus());
        assertEquals(0, new BigDecimal("100.00").compareTo(invoice.getSettledAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(invoice.getOutstandingAmount()));
        assertEquals(PayablePaymentStatus.PAID, invoice.getPaymentStatus());
    }

    @Test
    @DisplayName("一个 SO 下有多张开放发票: 按 FIFO(最早挂账优先) 依次核销")
    void multipleInvoicesForSameSO_appliesFifo() {
        stubCommon(new BigDecimal("500.00"));
        stubSalesOrder();
        ArApTransaction older = arInvoice("AR-OLD", "50.00", "0.00", "50.00",
                PayablePaymentStatus.UNPAID, LocalDate.of(2026, 8, 1));
        ArApTransaction newer = arInvoice("AR-NEW", "80.00", "0.00", "80.00",
                PayablePaymentStatus.UNPAID, LocalDate.of(2026, 9, 1));
        // Repository ORDER BY transactionDate ASC — mock returns pre-ordered (older first).
        when(transactionRepository.findOpenArInvoicesForSettlementBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(List.of(older, newer));
        when(transactionRepository.sumArPaymentsBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(new BigDecimal("70.00"));

        // Pays off AR-OLD (50) fully + partially applies remaining 20 to AR-NEW.
        service().recordArPayment(FACTORY, CUSTOMER, SO_ID, new BigDecimal("70.00"),
                PaymentMethod.BANK_TRANSFER, "PAY-FIFO", 1L, "分批核销");

        System.out.println("BEHAVIOUR: FIFO older(outstanding=" + older.getOutstandingAmount()
                + ",status=" + older.getPaymentStatus() + ") newer(outstanding=" + newer.getOutstandingAmount()
                + ",status=" + newer.getPaymentStatus() + ")");
        assertEquals(0, BigDecimal.ZERO.compareTo(older.getOutstandingAmount()), "较早那张必须先被付清");
        assertEquals(PayablePaymentStatus.PAID, older.getPaymentStatus());
        assertEquals(0, new BigDecimal("60.00").compareTo(newer.getOutstandingAmount()),
                "剩余 20 应用到较新那张, 80-20=60");
        assertEquals(PayablePaymentStatus.PARTIALLY_PAID, newer.getPaymentStatus());

        verify(allocationRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("超付: 金额超过该 SO 已知开放发票的未清总额, 不抛异常, 发票全部付清, 剩余不核销到任何发票")
    void overpaymentBeyondSalesOrderInvoices_doesNotThrow() {
        stubCommon(new BigDecimal("500.00"));
        stubSalesOrder();
        ArApTransaction invoice = arInvoice("AR-1", "50.00", "0.00", "50.00",
                PayablePaymentStatus.UNPAID, LocalDate.of(2026, 9, 1));
        when(transactionRepository.findOpenArInvoicesForSettlementBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(List.of(invoice));
        when(transactionRepository.sumArPaymentsBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(new BigDecimal("120.00"));

        // Pay 120 against a SO whose only known open invoice totals 50 outstanding.
        // Customer balance (500) comfortably covers it, so the customer-level guard doesn't fire.
        ArApTransaction result = service().recordArPayment(FACTORY, CUSTOMER, SO_ID, new BigDecimal("120.00"),
                PaymentMethod.BANK_TRANSFER, "PAY-OVER", 1L, "多收");

        System.out.println("BEHAVIOUR: overpayment invoice.outstanding=" + invoice.getOutstandingAmount()
                + " status=" + invoice.getPaymentStatus() + " paymentId=" + result.getId());
        assertEquals(0, BigDecimal.ZERO.compareTo(invoice.getOutstandingAmount()),
                "已知发票必须被付清, 不能出现负数 outstanding");
        assertTrue(invoice.getOutstandingAmount().signum() >= 0, "outstanding 不能变负");
        assertEquals(PayablePaymentStatus.PAID, invoice.getPaymentStatus());
        // Only ONE allocation (50) — the remaining 70 is intentionally left unallocated.
        ArgumentCaptor<ArApPaymentAllocation> allocCaptor = ArgumentCaptor.forClass(ArApPaymentAllocation.class);
        verify(allocationRepository, times(1)).save(allocCaptor.capture());
        assertEquals(0, new BigDecimal("50.00").compareTo(allocCaptor.getValue().getAllocatedAmount()));
    }

    @Test
    @DisplayName("该 SO 下没有状态已知的开放发票(全部 NEEDS_RECONCILIATION 或已结清): 不抛异常, 不核销任何发票")
    void noEligibleInvoices_doesNotThrowAndAllocatesNothing() {
        stubCommon(new BigDecimal("500.00"));
        stubSalesOrder();
        when(transactionRepository.findOpenArInvoicesForSettlementBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(List.of());
        when(transactionRepository.sumArPaymentsBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(new BigDecimal("30.00"));

        ArApTransaction result = service().recordArPayment(FACTORY, CUSTOMER, SO_ID, new BigDecimal("30.00"),
                PaymentMethod.BANK_TRANSFER, "PAY-NOELIG", 1L, "存量订单收款");

        System.out.println("BEHAVIOUR: noEligibleInvoices paymentId=" + result.getId());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    @DisplayName("幂等: 同一 paymentReference 重复提交, 第二次 409 且不会二次核销发票")
    void duplicatePaymentReference_rejectedWithoutDoubleSettling() {
        stubCommon(new BigDecimal("500.00"));
        stubSalesOrder();
        ArApTransaction invoice = arInvoice("AR-1", "100.00", "0.00", "100.00",
                PayablePaymentStatus.UNPAID, LocalDate.of(2026, 9, 1));
        when(transactionRepository.findOpenArInvoicesForSettlementBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(List.of(invoice));
        when(transactionRepository.sumArPaymentsBySalesOrderId(FACTORY, SO_ID))
                .thenReturn(new BigDecimal("40.00"));
        // First call: reference not yet used. Second call: same reference now exists.
        when(transactionRepository.existsByFactoryIdAndPaymentReference(FACTORY, "PAY-DUP"))
                .thenReturn(false, true);

        ArApServiceImpl svc = service();
        svc.recordArPayment(FACTORY, CUSTOMER, SO_ID, new BigDecimal("40.00"),
                PaymentMethod.BANK_TRANSFER, "  PAY-DUP  ", 1L, "第一次收款");

        System.out.println("BEHAVIOUR (after 1st call): settled=" + invoice.getSettledAmount()
                + " outstanding=" + invoice.getOutstandingAmount());
        assertEquals(0, new BigDecimal("40.00").compareTo(invoice.getSettledAmount()));
        assertEquals(0, new BigDecimal("60.00").compareTo(invoice.getOutstandingAmount()));

        BusinessException error = assertThrows(BusinessException.class, () ->
                svc.recordArPayment(FACTORY, CUSTOMER, SO_ID, new BigDecimal("40.00"),
                        PaymentMethod.BANK_TRANSFER, "  PAY-DUP  ", 1L, "重复提交(双击/重试)"));

        System.out.println("BEHAVIOUR (after 2nd call attempt): settled=" + invoice.getSettledAmount()
                + " outstanding=" + invoice.getOutstandingAmount() + " errorCode=" + error.getErrorCode());
        assertEquals(0, new BigDecimal("40.00").compareTo(invoice.getSettledAmount()),
                "第二次(被拒的)提交不能再次核销 —— settled 必须保持第一次之后的值, 不能变成 80");
        assertEquals(0, new BigDecimal("60.00").compareTo(invoice.getOutstandingAmount()),
                "outstanding 同样不能被第二次提交再次扣减");
        // Only ONE allocation row across both attempts.
        verify(allocationRepository, times(1)).save(any());
        verify(paymentReferenceLock, times(2)).acquire(FACTORY, "  PAY-DUP  ");
    }

    @Test
    @DisplayName("回归钉: 只用 3 参数构造 ArApServiceImpl 时 allocationRepository 未注入, 不影响不涉及收款核销的既有用法")
    void allocationRepositoryDefaultsToNullWithoutReflection() {
        // 未调用 service() helper 里的 ReflectionTestUtils.setField —— 模拟 7 个既有测试文件仍在用
        // 的 3-arg 构造用法 (它们不碰 recordArPayment, 所以 allocationRepository 保持 null 是安全的)。
        ArApServiceImpl bare = new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
        assertEquals(null, ReflectionTestUtils.getField(bare, "allocationRepository"));
    }
}
