package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.ReturnRefundDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.*;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.service.voucher.*;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReturnRefundReversalContractTest {
    private ReturnRefundService service;
    private final ReturnOrderRepository returns=mock(ReturnOrderRepository.class);
    private final SalesOrderRepository sales=mock(SalesOrderRepository.class);
    private final PaymentRequestRepository requests=mock(PaymentRequestRepository.class);
    private final PaymentRecordRepository receipts=mock(PaymentRecordRepository.class);
    private final ArApTransactionRepository ledger=mock(ArApTransactionRepository.class);
    private final CustomerRepository customers=mock(CustomerRepository.class);
    private final VoucherRepository vouchers=mock(VoucherRepository.class);
    private final VoucherService accounting=mock(VoucherService.class);
    private final EntityManager em=mock(EntityManager.class);
    private final List<ArApTransaction> reversals=new ArrayList<>();
    private final List<PaymentRequest> refundRequests=new ArrayList<>();
    private ReturnOrder order;
    private PaymentRequest refund;
    private Customer customer;
    private ArApTransaction paid;
    private Voucher original,mirror;

    @BeforeEach void setup() {
        service=new ReturnRefundService(returns,sales,requests,receipts,ledger,customers,vouchers,accounting,mock(UserRepository.class));
        ReflectionTestUtils.setField(service,"entityManager",em);
        ReflectionTestUtils.setField(service,"paymentReferenceLock",new com.cretas.aims.service.finance.FinancePaymentReferenceLock(em));
        order=new ReturnOrder();order.setId("RO");order.setFactoryId("F");order.setReturnNumber("退货1");
        order.setReturnType(ReturnType.SALES_RETURN);order.setStatus(ReturnOrderStatus.COMPLETED);order.setWithGoods(true);
        order.setSourceOrderId("SO");order.setCounterpartyId("C");order.setTotalAmount(new BigDecimal("15"));
        when(returns.findById("RO")).thenReturn(Optional.of(order));
        when(returns.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc("F",ReturnType.SALES_RETURN,"SO"))
                .thenReturn(List.of(order));
        SalesOrder sale=new SalesOrder();sale.setId("SO");sale.setFactoryId("F");sale.setCustomerId("C");
        sale.setTotalAmount(new BigDecimal("15"));sale.setPaidAmount(new BigDecimal("15"));
        when(sales.findByIdAndFactoryIdForUpdate("SO","F")).thenReturn(Optional.of(sale));
        when(sales.findById("SO")).thenReturn(Optional.of(sale));
        customer=new Customer();customer.setId("C");customer.setFactoryId("F");customer.setName("体验客户");customer.setCurrentBalance(new BigDecimal("540"));
        when(em.find(eq(Customer.class),eq("C"),any(LockModeType.class))).thenAnswer(i->customer);
        refund=new PaymentRequest();refund.setId("PR");refund.setFactoryId("F");refund.setRequestNumber("退款1");
        refund.setSourceType(PaymentSourceType.SALES);refund.setSalesOrderId("SO");refund.setReturnOrderId("RO");refund.setCustomerId("C");
        refund.setAmount(new BigDecimal("15"));refund.setCreatedBy(10L);refund.setApprovedBy(20L);refund.setApprovedAt(LocalDateTime.of(2026,10,16,10,0));
        refund.setPaidBy(20L);refund.setPaidAt(LocalDateTime.of(2026,10,16,11,0));refund.setPaymentMethod("CASH");
        refund.setPaymentReference("付出凭据");refund.setArapTransactionId("PAID");refund.setStatus(PaymentRequestStatus.PAID);refundRequests.add(refund);
        when(requests.findById("PR")).thenReturn(Optional.of(refund));
        when(requests.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F","RO")).thenAnswer(i->List.copyOf(refundRequests));
        when(requests.findSalesRefundsBySalesOrderId(eq("F"),eq("SO"),anyList())).thenAnswer(i->{
            List<PaymentRequestStatus> statuses=i.getArgument(2);return refundRequests.stream().filter(p->statuses.contains(p.getStatus())).toList();});
        when(em.find(PaymentRequest.class,"PR")).thenAnswer(i->refund);
        when(em.createNativeQuery(anyString())).thenAnswer(i->{
            Query q=mock(Query.class);when(q.setParameter(anyString(),any())).thenReturn(q);
            when(q.getResultList()).thenReturn(((String)i.getArgument(0)).contains("payment_requests")?List.of("PR"):List.of("RO"));return q;});
        paid=new ArApTransaction();paid.setId("PAID");paid.setFactoryId("F");paid.setSourceType("RETURN_REFUND");paid.setSourceId("PR");
        paid.setTransactionType(ArApTransactionType.AR_REFUND);paid.setCounterpartyType(CounterpartyType.CUSTOMER);paid.setCounterpartyId("C");paid.setSalesOrderId("SO");
        paid.setAmount(new BigDecimal("15"));paid.setBalanceAfter(new BigDecimal("540"));paid.setPaymentMethod(PaymentMethod.CASH);paid.setPaymentReference(refund.getPaymentReference());
        paid.setTransactionDate(LocalDate.of(2026,10,16));
        paid.setApprovedBy(20L);paid.setApprovedAt(refund.getApprovedAt());paid.setOperatedBy(20L);paid.setApprovalStatus(ArApApprovalStatus.APPROVED);
        when(ledger.findById("PAID")).thenReturn(Optional.of(paid));
        ArApTransaction credit=new ArApTransaction();credit.setId("CREDIT");credit.setFactoryId("F");credit.setSourceType("RETURN_ORDER");credit.setSourceId("RO");
        credit.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);credit.setCounterpartyType(CounterpartyType.CUSTOMER);credit.setCounterpartyId("C");credit.setAmount(new BigDecimal("-15"));
        credit.setApprovalStatus(ArApApprovalStatus.APPROVED);
        when(ledger.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(eq("F"),anyString(),anyList(),eq(ArApTransactionType.AR_ADJUSTMENT)))
                .thenAnswer(i->"RETURN_ORDER".equals(i.getArgument(1))?List.of(credit):List.copyOf(reversals));
        when(ledger.save(any())).thenAnswer(i->{ArApTransaction tx=i.getArgument(0);if(tx.getId()==null){tx.setId("REV-"+reversals.size());tx.setCreatedAt(LocalDateTime.now());reversals.add(tx);}return tx;});
        when(requests.save(any())).thenAnswer(i->{PaymentRequest p=i.getArgument(0);if(p.getId()==null){p.setId("NEW");refundRequests.add(0,p);}return p;});
        PaymentRecord receipt=new PaymentRecord();receipt.setStatus(PaymentRecordStatus.VERIFIED);receipt.setAmount(new BigDecimal("15"));
        when(receipts.findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc("F","SO")).thenReturn(List.of(receipt));
        when(ledger.sumArPaymentsBySalesOrderId("F","SO")).thenReturn(new BigDecimal("15"));
        original=Voucher.builder().id("V").factoryId("F").sourceBusinessType("RETURN_REFUND").sourceBusinessId("PR").voucherNumber("凭证1")
                .voucherDate(LocalDate.of(2026,10,16)).voucherType(VoucherType.CASH_PAYMENT).status(VoucherStatus.POSTED)
                .approvedBy(100L).approvedAt(LocalDateTime.of(2026,10,16,12,0))
                .totalDebit(new BigDecimal("15")).totalCredit(new BigDecimal("15")).build();
        original.getEntries().add(VoucherEntry.builder().subjectCode("1122").debit(new BigDecimal("15")).credit(BigDecimal.ZERO).auxiliaryType(AuxiliaryType.CUSTOMER).auxiliaryEntityId("C").build());
        original.getEntries().add(VoucherEntry.builder().subjectCode("1001").debit(BigDecimal.ZERO).credit(new BigDecimal("15")).build());
        when(vouchers.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_REFUND","PR")).thenAnswer(i->Optional.of(original));
        when(vouchers.findById("MIRROR")).thenAnswer(i->Optional.ofNullable(mirror));
        when(accounting.reverseReturnRefund(eq("F"),eq("PR"),anyString(),eq(20L))).thenAnswer(i->reverseVoucher());
    }

    private Voucher reverseVoucher() {
        if(original.getStatus()==VoucherStatus.DRAFT){original.setStatus(VoucherStatus.VOID);original.setApprovedBy(20L);original.setApprovedAt(LocalDateTime.now());return original;}
        mirror=Voucher.builder().id("MIRROR").factoryId("F").voucherNumber("冲销1").voucherType(original.getVoucherType()).voucherDate(original.getVoucherDate())
                .sourceBusinessType("VOUCHER_REVERSAL").sourceBusinessId("V").originalVoucherId("V").status(VoucherStatus.POSTED)
                .approvedBy(20L).approvedAt(LocalDateTime.now()).totalDebit(original.getTotalCredit()).totalCredit(original.getTotalDebit()).build();
        for(VoucherEntry e:original.getEntries())mirror.getEntries().add(VoucherEntry.builder().subjectCode(e.getSubjectCode()).auxiliaryType(e.getAuxiliaryType())
                .auxiliaryEntityId(e.getAuxiliaryEntityId()).debit(e.getCredit()).credit(e.getDebit()).build());
        original.setStatus(VoucherStatus.REVERSED);original.setReversalVoucherId("MIRROR");return original;
    }
    private ReturnRefundDTO.ReversalRequest command(String reference) {
        var c=new ReturnRefundDTO.ReversalRequest();c.setRequestId("PR");c.setPaymentReference(reference);c.setReason("现金已经收回，撤销本次错误退款");return c;
    }
    private ReturnRefundDTO.ReversalDecision decision() {
        var c=new ReturnRefundDTO.ReversalDecision();c.setRequestId("PR");c.setReversalTransactionId(reversals.get(reversals.size()-1).getId());return c;
    }

    @Test void pendingRequestPreservesMoneyAndIdempotentlyKeepsOneApplication() {
        service.requestReversal("F","RO",10L,command("收回1"));service.requestReversal("F","RO",10L,command("收回1"));
        assertThat(reversals).hasSize(1);assertThat(customer.getCurrentBalance()).isEqualByComparingTo("540");
        assertThat(reversals.get(0).getTransactionDate()).isEqualTo(paid.getTransactionDate());
        assertThat(refund.getStatus()).isEqualTo(PaymentRequestStatus.PAID);
        var view=service.getByRequest("F","PR").getRequest().getReversal();assertThat(view.getStatus()).isEqualTo("PENDING");
        assertThat(view.getLedger().getSourceType()).isEqualTo("RETURN_REFUND_REVERSAL");assertThat(view.getLedger().getSourceId()).isEqualTo("PR");
        assertThat(view.getLedger().getBalanceAfter()).isNull();assertThat(view.getLedger().getBalanceBefore()).isNull();
        verifyNoInteractions(accounting);verify(customers,never()).save(any());
    }
    @Test void priorMonthRefundReversalKeepsAccountingDateAndActualApprovalTime() {
        LocalDate originalDate = LocalDate.now().withDayOfMonth(1).minusDays(1);
        refund.setApprovedAt(originalDate.atTime(10,0));
        refund.setPaidAt(originalDate.atTime(11,0));
        paid.setApprovedAt(refund.getApprovedAt());paid.setTransactionDate(originalDate);
        original.setVoucherDate(originalDate);original.setApprovedAt(originalDate.atTime(12,0));
        LocalDateTime before = LocalDateTime.now();
        service.requestReversal("F","RO",10L,command("跨月收回凭据"));
        assertThat(reversals.get(0).getTransactionDate()).isEqualTo(originalDate);
        assertThat(customer.getCurrentBalance()).isEqualByComparingTo("540");
        service.approveReversal("F","RO",20L,decision());
        var completed = service.getByRequest("F","PR").getRequest().getReversal();
        assertThat(completed.getLedger().getTransactionDate()).isEqualTo(originalDate);
        assertThat(mirror.getVoucherDate()).isEqualTo(originalDate);
        assertThat(refund.getCancelledAt()).isBetween(before, LocalDateTime.now());
        assertThat(completed.getLedger().getApprovedAt()).isEqualTo(refund.getCancelledAt());
        assertThat(customer.getCurrentBalance()).isEqualByComparingTo("525");
    }

    @Test void approvalAdjustsBalancePreservesOriginalLedgerAndAllowsNoDuplicateEffect() {
        service.requestReversal("F","RO",10L,command("收回1"));var d=decision();
        service.approveReversal("F","RO",20L,d);service.approveReversal("F","RO",20L,d);
        assertThat(customer.getCurrentBalance()).isEqualByComparingTo("525");assertThat(paid.getBalanceAfter()).isEqualByComparingTo("540");
        assertThat(refund.getStatus()).isEqualTo(PaymentRequestStatus.CANCELLED);assertThat(refund.getPaidAt()).isNotNull();
        var view=service.getByRequest("F","PR");assertThat(view.getEligibilityStatus()).isEqualTo("REVERSED");
        assertThat(view.getRequest().getReversal().getLedger().getBalanceBefore()).isEqualByComparingTo("540");
        assertThat(view.getRequest().getReversal().getLedger().getBalanceAfter()).isEqualByComparingTo("525");
        assertThat(view.getRequest().getReversal().getVoucher().getId()).isEqualTo("MIRROR");
        verify(accounting,times(1)).reverseReturnRefund(eq("F"),eq("PR"),anyString(),eq(20L));
        verify(customers,times(1)).save(customer);
    }
    @Test void draftVoucherIsVoidedWithoutInventingAPostedCashReceipt() {
        original.setStatus(VoucherStatus.DRAFT);service.requestReversal("F","RO",10L,command("收回1"));
        service.approveReversal("F","RO",20L,decision());
        assertThat(original.getStatus()).isEqualTo(VoucherStatus.VOID);assertThat(mirror).isNull();
        assertThat(service.getByRequest("F","PR").getRequest().getReversal().getVoucher()).isNull();
    }
    @Test void rejectedAttemptDoesNotChangeMoneyAndNewAttemptCanSucceed() {
        service.requestReversal("F","RO",10L,command("收回1"));var reject=decision();reject.setReason("凭据需补充");
        service.rejectReversal("F","RO",20L,reject);
        assertThat(customer.getCurrentBalance()).isEqualByComparingTo("540");assertThat(original.getStatus()).isEqualTo(VoucherStatus.POSTED);
        service.requestReversal("F","RO",10L,command("收回2"));service.approveReversal("F","RO",20L,decision());
        assertThat(reversals).hasSize(2);assertThat(reversals.get(0).getApprovalStatus()).isEqualTo(ArApApprovalStatus.REJECTED);
        assertThat(customer.getCurrentBalance()).isEqualByComparingTo("525");
    }
    @Test void sameActorWrongRequestOrChangedLedgerCannotApprove() {
        service.requestReversal("F","RO",10L,command("收回1"));var d=decision();
        assertThatThrownBy(()->service.approveReversal("F","RO",10L,d)).hasMessageContaining("不能审批自己的");
        d.setReversalTransactionId("OTHER");assertThatThrownBy(()->service.approveReversal("F","RO",20L,d)).hasMessageContaining("流水与原退款不一致");
        order.setFactoryId("FOREIGN");assertThatThrownBy(()->service.approveReversal("F","RO",20L,d)).hasMessageContaining("不属于当前工厂");
        verifyNoInteractions(accounting);verify(customers,never()).save(any());
    }
    @Test void missingRecoveryEvidenceAndDuplicateReferenceFailBeforeWrite() {
        var c=command(" ");assertThatThrownBy(()->service.requestReversal("F","RO",10L,c)).hasMessageContaining("款项收回凭据");
        when(ledger.existsByFactoryIdAndPaymentReference("F","收回1")).thenReturn(true);
        assertThatThrownBy(()->service.requestReversal("F","RO",10L,command("收回1"))).hasMessageContaining("已在本工厂使用");
        assertThat(reversals).isEmpty();verifyNoInteractions(accounting);
    }
    @Test void receiptUniquenessCheckIsInsideFactoryScopedTransactionLock() {
        service.requestReversal("F","RO",10L,command("  同一收回凭据  "));
        var orderOfCalls=inOrder(em,ledger);
        orderOfCalls.verify(em).createNativeQuery(contains("pg_advisory_xact_lock(hashtextextended(:referenceKey, 0))"));
        orderOfCalls.verify(ledger).existsByFactoryIdAndPaymentReference("F","同一收回凭据");
        orderOfCalls.verify(ledger).save(argThat(tx->tx.getPaymentReference().equals("同一收回凭据")));
    }
    @Test void completedReversalCannotHideMissingOrTamperedAccountingEvidence() {
        service.requestReversal("F","RO",10L,command("收回1"));service.approveReversal("F","RO",20L,decision());
        mirror.getEntries().get(0).setAuxiliaryEntityId("OTHER");
        assertThatThrownBy(()->service.getByRequest("F","PR")).hasMessageContaining("证据不一致");
        mirror.getEntries().get(0).setAuxiliaryEntityId("C");refund.setCancelledAt(null);
        assertThatThrownBy(()->service.getByRequest("F","PR")).hasMessageContaining("证据不一致");
    }
    @Test void completedReversalRejectsAccountingDateDriftOrMissingMirrorDate() {
        service.requestReversal("F","RO",10L,command("收回1"));service.approveReversal("F","RO",20L,decision());
        LocalDate originalDate = original.getVoucherDate();
        for (LocalDate mirrorDate : java.util.Arrays.asList(null, originalDate.minusDays(1), originalDate.plusDays(1))) {
            mirror.setVoucherDate(mirrorDate);
            assertThatThrownBy(()->service.getByRequest("F","PR")).hasMessageContaining("证据不一致");
        }
        mirror.setVoucherDate(originalDate);
        for (LocalDate reversalDate : java.util.Arrays.asList(null, originalDate.minusDays(1), originalDate.plusDays(1))) {
            reversals.get(0).setTransactionDate(reversalDate);
            assertThatThrownBy(()->service.getByRequest("F","PR")).hasMessageContaining("证据不一致");
        }
        reversals.get(0).setTransactionDate(originalDate);
        paid.setTransactionDate(originalDate.plusDays(1));
        assertThatThrownBy(()->service.getByRequest("F","PR")).hasMessageContaining("证据不一致");
    }
    @Test void reapplicationRestoresRefundCapacityAndHistoricalLookupNeverSubstitutesNewRequest() {
        service.requestReversal("F","RO",10L,command("收回1"));service.approveReversal("F","RO",20L,decision());
        var create=new ReturnRefundDTO.CreateRequest();create.setAmount(new BigDecimal("15"));create.setRemark("重新退款");
        PaymentRequest next=service.create("F","RO",create,10L);assertThat(next.getId()).isEqualTo("NEW");
        assertThat(service.getByRequest("F","PR").getRequest().getId()).isEqualTo("PR");
        assertThat(service.get("F","RO").getRequest().getId()).isEqualTo("NEW");
    }
    @Test void genericAdjustmentApprovalRejectAndCreationCannotBypassRefundClosure() {
        service.requestReversal("F","RO",10L,command("收回1"));var pending=reversals.get(0);
        when(ledger.findById(pending.getId())).thenReturn(Optional.of(pending));
        var generic=new ArApServiceImpl(ledger,customers,mock(SupplierRepository.class));
        assertThatThrownBy(()->generic.approveAdjustment("F",pending.getId(),20L)).hasMessageContaining("原退款详情审核");
        assertThatThrownBy(()->generic.rejectAdjustment("F",pending.getId(),20L,"原因")).hasMessageContaining("原退款详情审核");
        assertThatThrownBy(()->generic.recordAdjustment("F",CounterpartyType.CUSTOMER,"C",new BigDecimal("-15"),20L,"原因",ReturnRefundReversalEvidence.SOURCE,"PR"))
                .hasMessageContaining("原退款详情审核");
        assertThatThrownBy(()->generic.cancelPendingAdjustmentsBySource("F",ReturnRefundReversalEvidence.SOURCE,"PR",20L,"原因"))
                .hasMessageContaining("原退款详情审核");
        verify(customers,never()).save(any());
    }
    @Test void accountingFailureRollsBackRealJdbcBalanceAndReversalApprovalWithPositiveControl() {
        service.requestReversal("F","RO",10L,command("收回1"));var row=reversals.get(0);
        var ds=new DriverManagerDataSource("jdbc:h2:mem:reversal_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        ds.setDriverClassName("org.h2.Driver");var jdbc=new JdbcTemplate(ds);var transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("create table probe_customer(balance decimal(15,2))");jdbc.update("insert into probe_customer values(540)");
        jdbc.execute("create table probe_reversal(status varchar(32),approved_at timestamp)");jdbc.update("insert into probe_reversal values('PENDING',null)");
        jdbc.execute("create table probe_refund(status varchar(32))");jdbc.update("insert into probe_refund values('PAID')");
        when(em.find(eq(Customer.class),eq("C"),any(LockModeType.class))).thenAnswer(i->{customer.setCurrentBalance(jdbc.queryForObject("select balance from probe_customer",BigDecimal.class));return customer;});
        when(ledger.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull("F",ReturnRefundReversalEvidence.SOURCE,List.of("PR"),ArApTransactionType.AR_ADJUSTMENT))
                .thenAnswer(i->{row.setApprovalStatus(ArApApprovalStatus.valueOf(jdbc.queryForObject("select status from probe_reversal",String.class)));
                    if(row.getApprovalStatus()==ArApApprovalStatus.PENDING){row.setApprovedAt(null);row.setApprovedBy(null);}return List.of(row);});
        when(ledger.save(row)).thenAnswer(i->{jdbc.update("update probe_reversal set status=?,approved_at=?",row.getApprovalStatus().name(),row.getApprovedAt());return row;});
        when(customers.save(customer)).thenAnswer(i->{jdbc.update("update probe_customer set balance=?",customer.getCurrentBalance());return customer;});
        when(requests.save(refund)).thenAnswer(i->{jdbc.update("update probe_refund set status=?",refund.getStatus().name());return refund;});
        doThrow(new IllegalStateException("会计期间已结账")).when(accounting).reverseReturnRefund(eq("F"),eq("PR"),anyString(),eq(20L));
        var decision=decision();assertThatThrownBy(()->transaction.execute(s->service.approveReversal("F","RO",20L,decision))).hasMessageContaining("期间已结账");
        assertThat(jdbc.queryForObject("select balance from probe_customer",BigDecimal.class)).isEqualByComparingTo("540");
        assertThat(jdbc.queryForObject("select status from probe_reversal",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from probe_reversal where approved_at is not null",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select status from probe_refund",String.class)).isEqualTo("PAID");
        doAnswer(i->reverseVoucher()).when(accounting).reverseReturnRefund(eq("F"),eq("PR"),anyString(),eq(20L));
        transaction.executeWithoutResult(s->service.approveReversal("F","RO",20L,decision));
        assertThat(jdbc.queryForObject("select balance from probe_customer",BigDecimal.class)).isEqualByComparingTo("525");
        assertThat(jdbc.queryForObject("select status from probe_reversal",String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select status from probe_refund",String.class)).isEqualTo("CANCELLED");
    }
}
