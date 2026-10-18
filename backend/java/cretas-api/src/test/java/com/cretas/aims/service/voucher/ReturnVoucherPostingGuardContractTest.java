package com.cretas.aims.service.voucher;

import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReturnVoucherPostingGuardContractTest {
    @Mock ReturnOrderRepository returnOrderRepository;
    @Mock ArApTransactionRepository transactionRepository;
    @Mock PaymentRequestRepository paymentRequestRepository;
    @InjectMocks ReturnVoucherPostingGuard guard;

    private ReturnOrder order() {
        ReturnOrder order = new ReturnOrder();
        order.setId("return"); order.setFactoryId("A"); order.setReturnNumber("退货1");
        order.setReturnType(ReturnType.SALES_RETURN); order.setStatus(ReturnOrderStatus.COMPLETED);
        order.setWithGoods(true); order.setTotalAmount(new BigDecimal("150.00"));
        order.setCounterpartyId("customer"); order.setSourceOrderId("sale");
        when(returnOrderRepository.findById("return")).thenReturn(Optional.of(order));
        return order;
    }

    private ArApTransaction credit() {
        ArApTransaction row = new ArApTransaction();
        row.setId("credit"); row.setFactoryId("A"); row.setSourceType("RETURN_ORDER"); row.setSourceId("return");
        row.setTransactionType(ArApTransactionType.AR_ADJUSTMENT); row.setCounterpartyType(CounterpartyType.CUSTOMER);
        row.setCounterpartyId("customer"); row.setAmount(new BigDecimal("-150.00")); row.setApprovalStatus(ArApApprovalStatus.APPROVED);
        when(transactionRepository.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                "A", "RETURN_ORDER", List.of("return"), ArApTransactionType.AR_ADJUSTMENT)).thenReturn(List.of(row));
        return row;
    }

    private Voucher voucher(String source, String id) {
        return Voucher.builder().factoryId("A").sourceBusinessType(source).sourceBusinessId(id)
                .voucherType(VoucherType.CASH_PAYMENT).status(VoucherStatus.DRAFT)
                .totalDebit(new BigDecimal("150.00")).totalCredit(new BigDecimal("150.00")).build();
    }

    @Test void salesReturnRequiresCompletedPhysicalReturnAndExactApprovedCredit() {
        ReturnOrder order = order(); ArApTransaction row = credit(); Voucher voucher = voucher("RETURN_ORDER", "return");
        assertDoesNotThrow(() -> guard.assertPostable(voucher));
        row.setApprovalStatus(ArApApprovalStatus.PENDING);
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        row.setApprovalStatus(ArApApprovalStatus.APPROVED); row.setAmount(new BigDecimal("-149"));
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        row.setAmount(new BigDecimal("-150")); order.setStatus(ReturnOrderStatus.APPROVED);
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        order.setStatus(ReturnOrderStatus.COMPLETED); order.setFactoryId("B");
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
    }

    @Test void refundRequiresPaidSourceAndCustomerDebitCashCredit() {
        order(); credit();
        PaymentRequest request = new PaymentRequest();
        request.setId("refund"); request.setFactoryId("A"); request.setReturnOrderId("return");
        request.setSourceType(PaymentSourceType.SALES); request.setSalesOrderId("sale"); request.setCustomerId("customer");
        request.setAmount(new BigDecimal("150.00")); request.setStatus(PaymentRequestStatus.PAID);
        request.setCreatedBy(93001L); request.setApprovedBy(1L); request.setApprovedAt(LocalDateTime.now());
        request.setPaidBy(1L); request.setPaidAt(LocalDateTime.now()); request.setPaymentReference("本地模拟退款-1");
        request.setPaymentMethod("CASH"); request.setArapTransactionId("movement");
        when(paymentRequestRepository.findById("refund")).thenReturn(Optional.of(request));
        ArApTransaction movement = new ArApTransaction();
        movement.setFactoryId("A"); movement.setSourceType("RETURN_REFUND"); movement.setSourceId("refund");
        movement.setTransactionType(ArApTransactionType.AR_REFUND); movement.setApprovalStatus(ArApApprovalStatus.APPROVED);
        movement.setSalesOrderId("sale"); movement.setPaymentMethod(PaymentMethod.CASH);
        movement.setPaymentReference(request.getPaymentReference()); movement.setApprovedBy(1L);
        movement.setApprovedAt(request.getApprovedAt()); movement.setOperatedBy(1L);
        movement.setBalanceAfter(BigDecimal.ZERO);
        movement.setCounterpartyType(CounterpartyType.CUSTOMER); movement.setCounterpartyId("customer"); movement.setAmount(request.getAmount());
        when(transactionRepository.findById("movement")).thenReturn(Optional.of(movement));
        Voucher voucher = voucher("RETURN_REFUND", "refund");
        voucher.getEntries().add(VoucherEntry.builder().subjectCode("1122").debit(request.getAmount())
                .credit(BigDecimal.ZERO).auxiliaryType(AuxiliaryType.CUSTOMER).auxiliaryEntityId("customer").build());
        voucher.getEntries().add(VoucherEntry.builder().subjectCode("1001").debit(BigDecimal.ZERO).credit(request.getAmount()).build());
        assertDoesNotThrow(() -> guard.assertPostable(voucher));
        voucher.setVoucherType(VoucherType.CASH_RECEIPT);
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        voucher.setVoucherType(VoucherType.CASH_PAYMENT);
        voucher.getEntries().get(0).setSubjectCode("2202");
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        voucher.getEntries().get(0).setSubjectCode("1122");
        voucher.setTotalDebit(new BigDecimal("149.00"));
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        voucher.setTotalDebit(request.getAmount());
        voucher.getEntries().get(0).setAuxiliaryEntityId("other-customer");
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        voucher.getEntries().get(0).setAuxiliaryEntityId("customer");
        voucher.getEntries().get(0).setDebit(request.getAmount().negate());
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        voucher.getEntries().get(0).setDebit(request.getAmount());
        movement.setAmount(request.getAmount().negate());
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        movement.setAmount(request.getAmount()); movement.setSalesOrderId("other-sale");
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        movement.setSalesOrderId("sale"); movement.setPaymentReference("wrong-reference");
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        movement.setPaymentReference(request.getPaymentReference()); movement.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        movement.setPaymentMethod(PaymentMethod.CASH); movement.setOperatedBy(99L);
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        movement.setOperatedBy(1L); movement.setApprovedAt(LocalDateTime.now().plusMinutes(1));
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        movement.setApprovedAt(request.getApprovedAt()); movement.setBalanceAfter(null);
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        movement.setBalanceAfter(BigDecimal.ZERO); request.setPaidBy(request.getCreatedBy());
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        request.setPaidBy(1L); request.setStatus(PaymentRequestStatus.APPROVED);
        assertThrows(BusinessException.class, () -> guard.assertPostable(voucher));
        request.setStatus(PaymentRequestStatus.PAID);
        assertDoesNotThrow(() -> guard.assertPostable(voucher));
    }
}
