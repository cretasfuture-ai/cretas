package com.cretas.aims.service.finance;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔴🔒 AR_INVOICE open-item parity (2026-09-04).
 *
 * <p>{@code persistPayable}/{@code recordPayableFromSource} (AP_INVOICE) have always called
 * {@code initializeOpenItem} to set settled_amount/outstanding_amount/payment_status/currency_code
 * at booking time. {@code persistReceivable} (AR_INVOICE, shared by both {@link ArApService#recordReceivable}
 * and {@link ArApService#recordReceivableIfAbsent}) never did — prod census 2026-09-04: AP_INVOICE
 * 31/31 rows have these fields set, AR_INVOICE 0/15 do. This test covers the manual finance-entry
 * path ({@link ArApService#recordReceivable}, used by {@code ArApController POST /receivable});
 * {@link ArApServiceImplRecordReceivableIfAbsentTest} covers the other caller (shipDelivery auto-AR).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArApServiceImpl recordReceivable (手工挂账应收: open-item 初始化)")
class ArApServiceImplRecordReceivableTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private SalesOrderRepository salesOrderRepository;

    private static final String FACTORY  = "F006";
    private static final String CUSTOMER = "CUST-001";
    private static final String SO_ID    = "SO-MANUAL-0001";

    private ArApServiceImpl service() {
        ArApServiceImpl service = new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
        ReflectionTestUtils.setField(service, "salesOrderRepository", salesOrderRepository);
        return service;
    }

    private Customer customer() {
        Customer c = new Customer();
        c.setId(CUSTOMER);
        c.setFactoryId(FACTORY);
        c.setName("测试客户");
        c.setCurrentBalance(BigDecimal.ZERO);
        return c;
    }

    private SalesOrder so() {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", SO_ID);
        o.setFactoryId(FACTORY);
        o.setCustomerId(CUSTOMER);
        o.setTotalAmount(new BigDecimal("500.00"));
        o.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        return o;
    }

    @Test
    @DisplayName("挂账即初始化 open item: settled=0, outstanding=amount, status=UNPAID, currency=CNY "
            + "(回归钉: 这条路径此前从未调用 initializeOpenItem — AR_INVOICE 落库时四个字段全 NULL)")
    void recordingReceivableInitializesOpenItem() {
        when(customerRepository.findByIdAndFactoryId(CUSTOMER, FACTORY)).thenReturn(Optional.of(customer()));
        when(transactionRepository.existsByFactoryIdAndSalesOrderIdAndTransactionType(
                FACTORY, SO_ID, ArApTransactionType.AR_INVOICE)).thenReturn(false);
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(so()));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(i -> i.getArgument(0));

        ArApTransaction result = service().recordReceivable(
                FACTORY, CUSTOMER, SO_ID, new BigDecimal("500.00"),
                LocalDate.now().plusDays(30), 1L, "手工挂账应收");

        assertNotNull(result);
        assertEquals(ArApTransactionType.AR_INVOICE, result.getTransactionType());
        assertEquals(CounterpartyType.CUSTOMER, result.getCounterpartyType());

        ArgumentCaptor<ArApTransaction> saved = ArgumentCaptor.forClass(ArApTransaction.class);
        verify(transactionRepository).save(saved.capture());
        ArApTransaction t = saved.getValue();
        assertEquals(0, new BigDecimal("0.00").compareTo(t.getSettledAmount()),
                "新挂账尚未收款, 已结清额必须是 0, 不能是 null");
        assertEquals(0, new BigDecimal("500.00").compareTo(t.getOutstandingAmount()),
                "未收款项必须 = 挂账金额, 不能是 null");
        assertEquals(PayablePaymentStatus.UNPAID, t.getPaymentStatus(),
                "新挂账的付款状态必须是 UNPAID, 不能是 null");
        assertEquals("CNY", t.getCurrencyCode());
    }
}
