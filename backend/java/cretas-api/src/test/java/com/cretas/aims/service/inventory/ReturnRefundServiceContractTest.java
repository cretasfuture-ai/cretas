package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.ReturnRefundDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentRecordStatus;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.enums.PaymentSourceType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.PaymentRecordRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.voucher.VoucherService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ReturnRefundServiceContractTest {
    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private PaymentRequestRepository paymentRequestRepository;
    @Mock private PaymentRecordRepository paymentRecordRepository;
    @Mock private ArApTransactionRepository arApTransactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private VoucherRepository voucherRepository;
    @Mock private VoucherService voucherService;
    @Mock private UserRepository userRepository;
    @Mock private EntityManager entityManager;
    @Mock private Query lockQuery;
    @Mock private Query requestLockQuery;

    private ReturnRefundService service;
    private ReturnOrder order;
    private SalesOrder salesOrder;
    private Customer customer;

    @BeforeEach
    void setUp() {
        service = new ReturnRefundService(returnOrderRepository, salesOrderRepository, paymentRequestRepository,
                paymentRecordRepository, arApTransactionRepository, customerRepository, voucherRepository,
                voucherService, userRepository);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "paymentReferenceLock", new com.cretas.aims.service.finance.FinancePaymentReferenceLock(entityManager));

        order = new ReturnOrder();
        order.setId("RO-1");
        order.setFactoryId("F1");
        order.setReturnNumber("RT-1");
        order.setReturnType(ReturnType.SALES_RETURN);
        order.setStatus(ReturnOrderStatus.COMPLETED);
        order.setWithGoods(true);
        order.setCounterpartyId("C1");
        order.setSourceOrderId("SO-1");
        order.setTotalAmount(new BigDecimal("150.00"));

        salesOrder = new SalesOrder();
        salesOrder.setId("SO-1");
        salesOrder.setFactoryId("F1");
        salesOrder.setCustomerId("C1");
        salesOrder.setTotalAmount(new BigDecimal("300.00"));
        salesOrder.setPaidAmount(new BigDecimal("300.00"));

        customer = new Customer();
        customer.setId("C1");
        customer.setFactoryId("F1");
        customer.setName("客户一");
        customer.setCurrentBalance(new BigDecimal("-150.00"));

    }

    private void stubReturnAndSourceLocks() {
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).contains("payment_requests")
                        ? requestLockQuery : lockQuery);
        when(lockQuery.setParameter(anyString(), any())).thenReturn(lockQuery);
        when(lockQuery.getResultList()).thenReturn(List.of("RO-1"));
        when(entityManager.find(eq(Customer.class), eq("C1"), any(LockModeType.class))).thenReturn(customer);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-1", "F1"))
                .thenReturn(Optional.of(salesOrder));
        when(returnOrderRepository.findById("RO-1")).thenReturn(Optional.of(order));
    }

    private void stubEligibilityEvidence() {
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                "F1", ReturnType.SALES_RETURN, "SO-1")).thenReturn(List.of(order));
        when(paymentRecordRepository.findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                "F1", "SO-1")).thenReturn(List.of(receipt()));
        when(arApTransactionRepository.sumArPaymentsBySalesOrderId("F1", "SO-1"))
                .thenReturn(new BigDecimal("300.00"));
        when(paymentRequestRepository.findGenericSalesBySalesOrderId(eq("F1"), eq("SO-1"), anyList()))
                .thenReturn(List.of());
        when(paymentRequestRepository.findSalesRefundsBySalesOrderId(eq("F1"), eq("SO-1"), anyList()))
                .thenReturn(List.of());
        when(arApTransactionRepository.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                eq("F1"), eq("RETURN_ORDER"), anyList(), eq(ArApTransactionType.AR_ADJUSTMENT)))
                .thenReturn(List.of(adjustment()));
    }

    @Test
    void createBindsReturnSourceAndUsesOnlyAvailableAmount() {
        stubReturnAndSourceLocks();
        stubEligibilityEvidence();
        when(paymentRequestRepository.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F1", "RO-1"))
                .thenReturn(List.of());
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(inv -> {
            PaymentRequest request = inv.getArgument(0);
            request.setId("PR-1");
            return request;
        });
        ReturnRefundDTO.CreateRequest command = new ReturnRefundDTO.CreateRequest();
        command.setAmount(new BigDecimal("150.00"));
        command.setRemark("客户退货退款");

        PaymentRequest result = service.create("F1", "RO-1", command, 10L);

        assertThat(result.getSourceType()).isEqualTo(PaymentSourceType.SALES);
        assertThat(result.getReturnOrderId()).isEqualTo("RO-1");
        assertThat(result.getSalesOrderId()).isEqualTo("SO-1");
        assertThat(result.getAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    void createRejectsSplitRefundInsteadOfLeavingAnUnclaimableRemainder() {
        stubReturnAndSourceLocks();
        stubEligibilityEvidence();
        when(paymentRequestRepository.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F1", "RO-1"))
                .thenReturn(List.of());
        ReturnRefundDTO.CreateRequest command = new ReturnRefundDTO.CreateRequest();
        command.setAmount(new BigDecimal("100.00"));
        command.setRemark("部分退款申请");

        assertThatThrownBy(() -> service.create("F1", "RO-1", command, 10L))
                .hasMessageContaining("一次结清");
        verify(paymentRequestRepository, never()).save(any(PaymentRequest.class));
    }

    @Test
    void markPaidWritesPositiveArRefundAndBalancesCustomerWithoutSecondCreditNote() {
        stubReturnAndSourceLocks();
        stubEligibilityEvidence();
        when(requestLockQuery.setParameter(anyString(), any())).thenReturn(requestLockQuery);
        when(requestLockQuery.getResultList()).thenReturn(List.of("PR-1"));
        PaymentRequest request = new PaymentRequest();
        request.setId("PR-1");
        request.setFactoryId("F1");
        request.setReturnOrderId("RO-1");
        request.setSourceType(PaymentSourceType.SALES);
        request.setSalesOrderId("SO-1");
        request.setCustomerId("C1");
        request.setAmount(new BigDecimal("150.00"));
        request.setCreatedBy(10L);
        request.setApprovedBy(30L);
        request.setApprovedAt(LocalDateTime.now());
        request.setStatus(PaymentRequestStatus.APPROVED);
        when(paymentRequestRepository.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F1", "RO-1"))
                .thenReturn(List.of(request));
        when(entityManager.find(eq(PaymentRequest.class), eq("PR-1"))).thenReturn(request);
        when(entityManager.find(eq(PaymentRequest.class), eq("PR-1"), any(LockModeType.class))).thenReturn(request);
        when(arApTransactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                "F1", "RETURN_REFUND", "PR-1", ArApTransactionType.AR_REFUND)).thenReturn(Optional.empty());
        when(arApTransactionRepository.save(any(ArApTransaction.class))).thenAnswer(inv -> {
            ArApTransaction tx = inv.getArgument(0);
            tx.setId("AR-RF-1");
            return tx;
        });
        when(voucherService.createCashMovementVoucher(anyString(), any(), any(), anyList(), anyString(), anyString(),
                anyString(), anyLong())).thenReturn(new Voucher());
        when(paymentRequestRepository.save(request)).thenReturn(request);

        ReturnRefundDTO.MarkPaidRequest command = new ReturnRefundDTO.MarkPaidRequest();
        command.setPaymentMethod("BANK_TRANSFER");
        command.setPaymentReference("BANK-150");
        command.setRemark("已退回客户");
        PaymentRequest result = service.markPaid("F1", "RO-1", 20L, command);

        assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.PAID);
        assertThat(result.getPaymentReference()).isEqualTo("BANK-150");
        assertThat(result.getArapTransactionId()).isEqualTo("AR-RF-1");
        verify(arApTransactionRepository).save(argThat(tx ->
                tx.getTransactionDate().equals(result.getPaidAt().toLocalDate())));
        verify(entityManager).refresh(request);
        verify(entityManager).createNativeQuery(argThat(sql ->
                sql.contains("payment_requests") && sql.contains("FOR UPDATE")));
        verify(arApTransactionRepository).save(argThat(tx -> tx.getTransactionType() == ArApTransactionType.AR_REFUND
                && tx.getAmount().compareTo(new BigDecimal("150.00")) == 0
                && tx.getBalanceAfter().compareTo(BigDecimal.ZERO) == 0
                && tx.getApprovalStatus() == ArApApprovalStatus.APPROVED
                && tx.getApprovedBy().equals(30L)
                && tx.getCounterpartyType() == CounterpartyType.CUSTOMER));
        verify(voucherService).createCashMovementVoucher(eq("F1"), eq(com.cretas.aims.entity.enums.VoucherType.CASH_PAYMENT),
                eq(result.getPaidAt().toLocalDate()), argThat(entries -> entries.size() == 2
                        && entries.get(0).getSubjectCode().equals("1122")
                        && entries.get(0).getDebit().compareTo(new BigDecimal("150.00")) == 0
                        && entries.get(1).getSubjectCode().equals("1002")
                        && entries.get(1).getCredit().compareTo(new BigDecimal("150.00")) == 0),
                eq("RETURN_REFUND"), eq("PR-1"), anyString(), eq(20L));
    }

    @Test
    void markPaidRejectsMissingPaymentEvidenceBeforeAnyLedgerWrite() {
        PaymentRequest request = new PaymentRequest();
        request.setId("PR-1");
        request.setFactoryId("F1");
        request.setReturnOrderId("RO-1");
        request.setAmount(new BigDecimal("150.00"));
        request.setCreatedBy(10L);
        request.setStatus(PaymentRequestStatus.APPROVED);
        ReturnRefundDTO.MarkPaidRequest command = new ReturnRefundDTO.MarkPaidRequest();
        command.setPaymentMethod("BANK_TRANSFER");
        command.setPaymentReference(" ");
        command.setRemark("已退回客户");

        assertThatThrownBy(() -> service.markPaid("F1", "RO-1", 20L, command))
                .hasMessageContaining("凭证号");
        verifyNoInteractions(arApTransactionRepository, voucherService, customerRepository);
    }

    private PaymentRequest changedEligibilityRequest(PaymentRequestStatus status) {
        stubReturnAndSourceLocks();
        stubEligibilityEvidence();
        PaymentRequest request = new PaymentRequest();
        request.setId("PR-CHANGED"); request.setFactoryId("F1"); request.setReturnOrderId("RO-1");
        request.setSourceType(PaymentSourceType.SALES); request.setSalesOrderId("SO-1"); request.setCustomerId("C1");
        request.setAmount(new BigDecimal("100.00")); request.setCreatedBy(10L); request.setStatus(status);
        request.setApprovedBy(30L); request.setApprovedAt(LocalDateTime.now());
        when(paymentRequestRepository.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F1", "RO-1"))
                .thenReturn(List.of(request));
        when(entityManager.find(PaymentRequest.class, "PR-CHANGED", LockModeType.NONE)).thenReturn(request);
        when(requestLockQuery.setParameter(anyString(), any())).thenReturn(requestLockQuery);
        when(requestLockQuery.getResultList()).thenReturn(List.of("PR-CHANGED"));
        when(entityManager.find(PaymentRequest.class, "PR-CHANGED")).thenReturn(request);
        return request;
    }

    @Test void approveRejectsChangedRefundEntitlement() {
        PaymentRequest request = changedEligibilityRequest(PaymentRequestStatus.FINANCE_REVIEW);
        assertThatThrownBy(() -> service.approve("F1", "RO-1", 20L, "核对"))
                .hasMessageContaining("一次结清");
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.FINANCE_REVIEW);
        verify(paymentRequestRepository, never()).save(any());
        verifyNoInteractions(voucherService);
    }

    @Test void markPaidRejectsChangedRefundEntitlement() {
        PaymentRequest request = changedEligibilityRequest(PaymentRequestStatus.APPROVED);
        ReturnRefundDTO.MarkPaidRequest command = new ReturnRefundDTO.MarkPaidRequest();
        command.setPaymentMethod("CASH"); command.setPaymentReference("LOCAL-CHANGED"); command.setRemark("本地模拟");
        assertThatThrownBy(() -> service.markPaid("F1", "RO-1", 20L, command))
                .hasMessageContaining("一次结清");
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.APPROVED);
        verify(arApTransactionRepository, never()).save(any());
        verify(paymentRequestRepository, never()).save(any());
        verifyNoInteractions(voucherService);
    }

    @Test
    void getFailsClosedWhenVoucherBelongsToAnotherFactory() {
        when(returnOrderRepository.findById("RO-1")).thenReturn(Optional.of(order));
        PaymentRequest request = new PaymentRequest();
        request.setId("PR-1");
        request.setFactoryId("F1");
        request.setReturnOrderId("RO-1");
        request.setSourceType(PaymentSourceType.SALES);
        request.setSalesOrderId("SO-1"); request.setCustomerId("C1"); request.setAmount(new BigDecimal("150"));
        request.setStatus(PaymentRequestStatus.PAID);
        when(paymentRequestRepository.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F1", "RO-1"))
                .thenReturn(List.of(request));
        Voucher foreign = new Voucher();
        foreign.setFactoryId("F2");
        when(voucherRepository.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
                "RETURN_REFUND", "PR-1")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.get("F1", "RO-1"))
                .hasMessageContaining("不属于当前工厂");
    }

    @Test
    void paidViewFailsClosedWhenLedgerEvidenceIsMissing() {
        when(returnOrderRepository.findById("RO-1")).thenReturn(Optional.of(order));
        PaymentRequest request = paidRequest();
        when(paymentRequestRepository.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F1", "RO-1"))
                .thenReturn(List.of(request));
        when(arApTransactionRepository.findById("AR-RF-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("F1", "RO-1"))
                .hasMessageContaining("应收流水不存在");
    }

    @Test
    void paidViewExposesOnlyExactRefundLedgerEvidence() {
        when(returnOrderRepository.findById("RO-1")).thenReturn(Optional.of(order));
        PaymentRequest request = paidRequest();
        when(paymentRequestRepository.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F1", "RO-1"))
                .thenReturn(List.of(request));
        ArApTransaction tx = new ArApTransaction();
        tx.setId("AR-RF-1");
        tx.setFactoryId("F1");
        tx.setTransactionNumber("AR-REFUND-1");
        tx.setTransactionType(ArApTransactionType.AR_REFUND);
        tx.setCounterpartyType(CounterpartyType.CUSTOMER);
        tx.setCounterpartyId("C1");
        tx.setSalesOrderId("SO-1");
        tx.setSourceType("RETURN_REFUND");
        tx.setSourceId("PR-1");
        tx.setAmount(new BigDecimal("150.00"));
        tx.setBalanceAfter(BigDecimal.ZERO);
        tx.setPaymentMethod(com.cretas.aims.entity.enums.PaymentMethod.BANK_TRANSFER);
        tx.setPaymentReference("BANK-150");
        tx.setApprovalStatus(ArApApprovalStatus.APPROVED);
        tx.setApprovedBy(30L);
        tx.setApprovedAt(request.getApprovedAt());
        tx.setOperatedBy(20L);
        when(arApTransactionRepository.findById("AR-RF-1")).thenReturn(Optional.of(tx));
        Voucher voucher = refundVoucher();
        when(voucherRepository.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
                "RETURN_REFUND", "PR-1")).thenReturn(Optional.of(voucher));
        User approver = new User(); approver.setId(30L); approver.setFactoryId("F1"); approver.setFullName("财务甲");
        User foreignOperator = new User(); foreignOperator.setId(20L); foreignOperator.setFactoryId("F2"); foreignOperator.setFullName("外厂姓名");
        when(userRepository.findAllById(anySet())).thenReturn(List.of(approver, foreignOperator));

        ReturnRefundDTO result = service.get("F1", "RO-1");

        assertThat(result.getRequest().getLedger().getTransactionNumber()).isEqualTo("AR-REFUND-1");
        assertThat(result.getRequest().getLedger().getTransactionType()).isEqualTo("AR_REFUND");
        assertThat(result.getRequest().getLedger().getBalanceAfter()).isEqualByComparingTo("0");
        assertThat(result.getRequest().getLedger().getBalanceBefore()).isEqualByComparingTo("-150");
        assertThat(result.getRequest().getLedger().getApprovedByName()).isEqualTo("财务甲");
        assertThat(result.getRequest().getLedger().getOperatedByName()).isNull();
        for (VoucherStatus invalidStatus : List.of(VoucherStatus.VOID, VoucherStatus.REVERSED)) {
            voucher.setStatus(invalidStatus);
            assertThatThrownBy(() -> service.get("F1", "RO-1"))
                    .isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        }
        voucher.setStatus(VoucherStatus.POSTED);
        assertThatThrownBy(() -> service.get("F1", "RO-1"))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        voucher.setApprovedBy(20L); voucher.setApprovedAt(LocalDateTime.now());
        assertThat(service.get("F1", "RO-1").getRequest().getVoucher().getStatus()).isEqualTo("POSTED");
        request.setPaidAt(null);
        assertThatThrownBy(() -> service.get("F1", "RO-1"))
                .hasMessageContaining("应收流水与申请证据不一致");
    }

    private Voucher refundVoucher() {
        Voucher voucher = new Voucher();
        voucher.setId("V-RF-1"); voucher.setFactoryId("F1");
        voucher.setSourceBusinessType("RETURN_REFUND"); voucher.setSourceBusinessId("PR-1");
        voucher.setVoucherType(VoucherType.CASH_PAYMENT); voucher.setStatus(VoucherStatus.DRAFT);
        voucher.setTotalDebit(new BigDecimal("150")); voucher.setTotalCredit(new BigDecimal("150"));
        voucher.getEntries().add(VoucherEntry.builder().subjectCode("1122").debit(new BigDecimal("150"))
                .credit(BigDecimal.ZERO).auxiliaryType(AuxiliaryType.CUSTOMER).auxiliaryEntityId("C1").build());
        voucher.getEntries().add(VoucherEntry.builder().subjectCode("1002").debit(BigDecimal.ZERO)
                .credit(new BigDecimal("150")).build());
        return voucher;
    }

    @Test void requestLookupRejectsDifferentFactoryAndMissingPhysicalSource() {
        PaymentRequest request = paidRequest();
        when(paymentRequestRepository.findById("PR-1")).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> service.getByRequest("F2", "PR-1")).hasMessageContaining("不属于当前工厂");
        request.setReturnOrderId(null);
        assertThatThrownBy(() -> service.getByRequest("F1", "PR-1")).hasMessageContaining("缺少明确");
        verifyNoInteractions(returnOrderRepository, voucherRepository, arApTransactionRepository);
    }

    @Test void requestLookupResolvesExactHistoricalRequestWithoutSubstitutingLatest() {
        PaymentRequest request = paidRequest(); request.setStatus(PaymentRequestStatus.PENDING);
        when(paymentRequestRepository.findById("PR-1")).thenReturn(Optional.of(request));
        when(returnOrderRepository.findById("RO-1")).thenReturn(Optional.of(order));
        assertThat(service.getByRequest("F1", "PR-1").getReturnOrderId()).isEqualTo("RO-1");
        assertThat(service.getByRequest("F1", "PR-1").getRequest().getId()).isEqualTo("PR-1");
        verify(paymentRequestRepository, never()).findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc(anyString(), anyString());
        verify(paymentRequestRepository, never()).save(any());
        verifyNoInteractions(voucherService, customerRepository);
    }

    private PaymentRequest paidRequest() {
        PaymentRequest request = new PaymentRequest();
        request.setId("PR-1");
        request.setFactoryId("F1");
        request.setReturnOrderId("RO-1");
        request.setSourceType(PaymentSourceType.SALES);
        request.setSalesOrderId("SO-1");
        request.setCustomerId("C1");
        request.setAmount(new BigDecimal("150.00"));
        request.setCreatedBy(10L);
        request.setApprovedBy(30L);
        request.setApprovedAt(LocalDateTime.now());
        request.setPaidBy(20L);
        request.setPaidAt(LocalDateTime.now());
        request.setPaymentMethod("BANK_TRANSFER");
        request.setPaymentReference("BANK-150");
        request.setArapTransactionId("AR-RF-1");
        request.setStatus(PaymentRequestStatus.PAID);
        return request;
    }

    @Test
    void createRejectsWhenCurrentReturnIsMissingFromCompletedEvidenceRows() {
        stubReturnAndSourceLocks();
        when(paymentRequestRepository.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F1", "RO-1"))
                .thenReturn(List.of());
        ReturnOrder other = new ReturnOrder();
        other.setId("RO-OTHER");
        other.setFactoryId("F1");
        other.setReturnType(ReturnType.SALES_RETURN);
        other.setStatus(ReturnOrderStatus.COMPLETED);
        other.setWithGoods(true);
        other.setCounterpartyId("C1");
        other.setTotalAmount(new BigDecimal("10.00"));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                "F1", ReturnType.SALES_RETURN, "SO-1")).thenReturn(List.of(other));

        ReturnRefundDTO.CreateRequest command = new ReturnRefundDTO.CreateRequest();
        command.setAmount(new BigDecimal("150.00"));
        command.setRemark("客户退货退款");

        assertThatThrownBy(() -> service.create("F1", "RO-1", command, 10L))
                .hasMessageContaining("当前退货缺少已完成");
        verify(paymentRequestRepository, never()).save(any(PaymentRequest.class));
    }

    @Test void getRejectsRequestWithDifferentCustomerBeforeReusingPaidEvidence() {
        when(returnOrderRepository.findById("RO-1")).thenReturn(Optional.of(order));
        PaymentRequest request = new PaymentRequest();
        request.setId("PR-1"); request.setFactoryId("F1"); request.setReturnOrderId("RO-1");
        request.setSourceType(PaymentSourceType.SALES); request.setSalesOrderId("SO-1");
        request.setCustomerId("other-customer"); request.setAmount(new BigDecimal("150"));
        request.setStatus(PaymentRequestStatus.PAID);
        when(paymentRequestRepository.findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc("F1", "RO-1"))
                .thenReturn(List.of(request));
        assertThatThrownBy(() -> service.get("F1", "RO-1")).hasMessageContaining("来源不一致");
        verifyNoInteractions(voucherRepository, voucherService, customerRepository);
    }

    private PaymentRecord receipt() {
        PaymentRecord row = new PaymentRecord();
        row.setFactoryId("F1");
        row.setSalesOrderId("SO-1");
        row.setStatus(PaymentRecordStatus.VERIFIED);
        row.setAmount(new BigDecimal("300.00"));
        return row;
    }

    private ArApTransaction adjustment() {
        ArApTransaction row = new ArApTransaction();
        row.setId("ADJ-1");
        row.setFactoryId("F1");
        row.setSourceType("RETURN_ORDER");
        row.setSourceId("RO-1");
        row.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);
        row.setCounterpartyType(CounterpartyType.CUSTOMER);
        row.setCounterpartyId("C1");
        row.setAmount(new BigDecimal("-150.00"));
        row.setApprovalStatus(ArApApprovalStatus.APPROVED);
        return row;
    }
}
