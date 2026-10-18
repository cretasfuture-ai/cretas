package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.ReturnRefundDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.*;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.service.voucher.impl.VoucherServiceImpl;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Spring/JDBC rollback around real refund and voucher methods; JPA locks are separately gated. */
class ReturnRefundPaymentTransactionContractTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ReturnRefundService service;
    private Voucher existing;
    private VoucherRepository vouchers;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:refund_rollback_" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        // Keep this test's isolated in-memory database alive across transaction connections.
        ds.setUrl(ds.getUrl() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds); transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("create table refund_probe_customer(balance decimal(15,2))");
        jdbc.execute("create table refund_probe_request(status varchar(32),paid_at timestamp)");
        jdbc.execute("create table refund_probe_ledger(id varchar(32),amount decimal(15,2))");
        jdbc.update("insert into refund_probe_customer values(-150)");
        jdbc.update("insert into refund_probe_request values('APPROVED',null)");

        var returns = mock(ReturnOrderRepository.class); var sales = mock(SalesOrderRepository.class);
        var requests = mock(PaymentRequestRepository.class); var receipts = mock(PaymentRecordRepository.class);
        var ledger = mock(ArApTransactionRepository.class); var customers = mock(CustomerRepository.class);
        vouchers = mock(VoucherRepository.class); var users = mock(UserRepository.class);
        var em = mock(EntityManager.class); var query = mock(Query.class);
        AtomicReference<PaymentRequest> active = new AtomicReference<>();
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(),any())).thenReturn(query);
        when(query.getResultList()).thenAnswer(inv -> List.of("PR"));
        var orderQuery = mock(Query.class);
        when(em.createNativeQuery(argThat(sql -> sql != null && sql.contains("return_orders")))).thenReturn(orderQuery);
        when(orderQuery.setParameter(anyString(),any())).thenReturn(orderQuery);
        when(orderQuery.getResultList()).thenReturn(List.of("RO"));
        when(em.find(eq(PaymentRequest.class),eq("PR"),any(LockModeType.class))).thenAnswer(inv -> active.get());
        when(em.find(PaymentRequest.class,"PR")).thenAnswer(inv -> active.get());
        when(em.find(eq(Customer.class),eq("C"),any(LockModeType.class))).thenAnswer(inv -> {
            var c = new Customer(); c.setId("C"); c.setFactoryId("F"); c.setName("本地客户");
            c.setCurrentBalance(jdbc.queryForObject("select balance from refund_probe_customer",BigDecimal.class)); return c;
        });
        ReturnOrder order = new ReturnOrder(); order.setId("RO"); order.setFactoryId("F");
        order.setReturnNumber("RT-1"); order.setReturnType(ReturnType.SALES_RETURN); order.setStatus(ReturnOrderStatus.COMPLETED);
        order.setWithGoods(true); order.setSourceOrderId("SO"); order.setCounterpartyId("C"); order.setTotalAmount(new BigDecimal("150"));
        when(returns.findById("RO")).thenReturn(Optional.of(order));
        when(returns.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc("F",ReturnType.SALES_RETURN,"SO"))
                .thenReturn(List.of(order));
        SalesOrder sale = new SalesOrder(); sale.setId("SO"); sale.setFactoryId("F"); sale.setCustomerId("C");
        sale.setTotalAmount(new BigDecimal("300")); sale.setPaidAmount(new BigDecimal("300"));
        when(sales.findByIdAndFactoryIdForUpdate("SO","F")).thenReturn(Optional.of(sale));
        when(requests.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F","RO")).thenAnswer(inv -> {
            var p = new PaymentRequest(); p.setId("PR"); p.setFactoryId("F"); p.setReturnOrderId("RO");
            p.setSourceType(PaymentSourceType.SALES); p.setSalesOrderId("SO"); p.setCustomerId("C");
            p.setAmount(new BigDecimal("150")); p.setCreatedBy(10L); p.setApprovedBy(20L);
            p.setApprovedAt(LocalDateTime.of(2026,10,16,10,0)); p.setStatus(PaymentRequestStatus.APPROVED);
            active.set(p); return List.of(p);
        });
        when(requests.findById("PR")).thenAnswer(inv -> Optional.of(active.get()));
        var credit = new ArApTransaction(); credit.setId("credit"); credit.setFactoryId("F");
        credit.setSourceType("RETURN_ORDER"); credit.setSourceId("RO"); credit.setCounterpartyType(CounterpartyType.CUSTOMER);
        credit.setCounterpartyId("C"); credit.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);
        credit.setAmount(new BigDecimal("-150")); credit.setApprovalStatus(ArApApprovalStatus.APPROVED);
        when(ledger.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                "F","RETURN_ORDER",List.of("RO"),ArApTransactionType.AR_ADJUSTMENT)).thenReturn(List.of(credit));
        PaymentRecord receipt = new PaymentRecord(); receipt.setStatus(PaymentRecordStatus.VERIFIED); receipt.setAmount(new BigDecimal("300"));
        when(receipts.findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc("F","SO")).thenReturn(List.of(receipt));
        when(ledger.sumArPaymentsBySalesOrderId("F","SO")).thenReturn(new BigDecimal("300"));
        AtomicReference<ArApTransaction> movement = new AtomicReference<>();
        when(ledger.save(any(ArApTransaction.class))).thenAnswer(inv -> {
            ArApTransaction row = inv.getArgument(0); row.setId("movement"); movement.set(row);
            jdbc.update("insert into refund_probe_ledger values(?,?)",row.getId(),row.getAmount()); return row;
        });
        when(ledger.findById("movement")).thenAnswer(inv -> Optional.of(movement.get()));
        when(customers.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0); jdbc.update("update refund_probe_customer set balance=?",c.getCurrentBalance()); return c;
        });
        when(requests.save(any(PaymentRequest.class))).thenAnswer(inv -> {
            PaymentRequest p = inv.getArgument(0); jdbc.update("update refund_probe_request set status=?,paid_at=?",p.getStatus().name(),p.getPaidAt()); return p;
        });
        existing = Voucher.builder().id("V").factoryId("F").sourceBusinessType("RETURN_REFUND").sourceBusinessId("PR")
                .status(VoucherStatus.DRAFT).voucherType(VoucherType.CASH_PAYMENT)
                .totalDebit(new BigDecimal("150")).totalCredit(new BigDecimal("150")).build();
        existing.getEntries().add(VoucherEntry.builder().subjectCode("1122").debit(new BigDecimal("150")).credit(BigDecimal.ZERO)
                .auxiliaryType(AuxiliaryType.CUSTOMER).auxiliaryEntityId("C").build());
        existing.getEntries().add(VoucherEntry.builder().subjectCode("1001").debit(BigDecimal.ZERO).credit(new BigDecimal("150")).build());
        when(vouchers.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_REFUND","PR"))
                .thenAnswer(inv -> Optional.of(existing));
        VoucherServiceImpl voucherService = mock(VoucherServiceImpl.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(voucherService,"voucherRepo",vouchers);
        ReflectionTestUtils.setField(voucherService,"entityManager",em);
        ReflectionTestUtils.setField(voucherService,"paymentRequestRepo",requests);
        ReflectionTestUtils.setField(voucherService,"arApTransactionRepo",ledger);
        ReflectionTestUtils.setField(voucherService,"returnOrderRepo",returns);
        service = new ReturnRefundService(returns,sales,requests,receipts,ledger,customers,vouchers,voucherService,users);
        ReflectionTestUtils.setField(service,"entityManager",em);
        ReflectionTestUtils.setField(service,"paymentReferenceLock",new com.cretas.aims.service.finance.FinancePaymentReferenceLock(em));
    }

    @Test void invalidExistingRefundVouchersRollBackAlreadyWrittenLedgerAndCustomerBalance() {
        for (VoucherStatus status : List.of(VoucherStatus.VOID,VoucherStatus.REVERSED)) {
            existing.setStatus(status);
            assertThatThrownBy(() -> transaction.execute(s -> service.markPaid("F","RO",20L,command())))
                    .isInstanceOf(com.cretas.aims.exception.BusinessException.class).hasMessageContaining("退款凭证");
            assertThat(jdbc.queryForObject("select count(*) from refund_probe_ledger",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select balance from refund_probe_customer",BigDecimal.class)).isEqualByComparingTo("-150");
            assertThat(jdbc.queryForObject("select status from refund_probe_request",String.class)).isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("select count(*) from refund_probe_request where paid_at is not null",Integer.class)).isZero();
        }
        existing.setStatus(VoucherStatus.DRAFT);
        transaction.executeWithoutResult(s -> service.markPaid("F","RO",20L,command()));
        assertThat(jdbc.queryForObject("select count(*) from refund_probe_ledger",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select balance from refund_probe_customer",BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select status from refund_probe_request",String.class)).isEqualTo("PAID");
        verify(vouchers,never()).save(any());
    }

    private ReturnRefundDTO.MarkPaidRequest command() {
        var command = new ReturnRefundDTO.MarkPaidRequest(); command.setPaymentMethod("CASH");
        command.setPaymentReference("LOCAL-ROLLBACK-150"); command.setRemark("仅隔离内存数据库事务验证"); return command;
    }
}
