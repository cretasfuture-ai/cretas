package com.cretas.aims.service.voucher;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.enums.PaymentSourceType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Pure, side-effect-free evidence contract shared by refund creation and posting. */
public final class ReturnRefundEvidence {
    private static final String SOURCE = "RETURN_REFUND";

    private ReturnRefundEvidence() {
    }

    public static void requireMatchingLedger(ReturnOrder order, PaymentRequest request,
                                             ArApTransaction tx) {
        requireLedger(order, request, tx, false);
    }

    public static void requireHistoricalLedger(ReturnOrder order, PaymentRequest request,
                                               ArApTransaction tx) {
        requireLedger(order, request, tx, true);
    }

    private static void requireLedger(ReturnOrder order, PaymentRequest request,
                                      ArApTransaction tx, boolean allowCancelled) {
        boolean valid = order != null && request != null && tx != null
                && order.getStatus() == ReturnOrderStatus.COMPLETED
                && Boolean.TRUE.equals(order.getWithGoods())
                && order.getReturnType() == ReturnType.SALES_RETURN
                && order.getFactoryId() != null && order.getId() != null
                && order.getSourceOrderId() != null && order.getCounterpartyId() != null
                && request.getId() != null && request.getFactoryId() != null
                && request.getReturnOrderId() != null && request.getSalesOrderId() != null
                && request.getCustomerId() != null
                && Objects.equals(order.getFactoryId(), request.getFactoryId())
                && Objects.equals(order.getId(), request.getReturnOrderId())
                && Objects.equals(order.getSourceOrderId(), request.getSalesOrderId())
                && Objects.equals(order.getCounterpartyId(), request.getCustomerId())
                && request.getSourceType() == PaymentSourceType.SALES
                && (request.getStatus() == PaymentRequestStatus.APPROVED
                    || request.getStatus() == PaymentRequestStatus.PAID
                    || (allowCancelled && request.getStatus() == PaymentRequestStatus.CANCELLED
                        && request.getCancelledAt() != null))
                && request.getAmount() != null && request.getAmount().signum() > 0
                && request.getCreatedBy() != null && request.getApprovedBy() != null
                && request.getApprovedAt() != null && request.getPaidBy() != null
                && request.getPaidAt() != null
                && !Objects.equals(request.getCreatedBy(), request.getApprovedBy())
                && !Objects.equals(request.getCreatedBy(), request.getPaidBy())
                && request.getPaymentMethod() != null && !request.getPaymentMethod().isBlank()
                && request.getPaymentReference() != null && !request.getPaymentReference().isBlank()
                && tx.getFactoryId() != null && tx.getSourceId() != null
                && tx.getCounterpartyId() != null
                && Objects.equals(order.getFactoryId(), tx.getFactoryId())
                && SOURCE.equals(tx.getSourceType())
                && Objects.equals(request.getId(), tx.getSourceId())
                && tx.getTransactionType() == ArApTransactionType.AR_REFUND
                && tx.getApprovalStatus() == ArApApprovalStatus.APPROVED
                && tx.getCounterpartyType() == CounterpartyType.CUSTOMER
                && Objects.equals(request.getCustomerId(), tx.getCounterpartyId())
                && Objects.equals(request.getSalesOrderId(), tx.getSalesOrderId())
                && tx.getAmount() != null && tx.getAmount().signum() > 0
                && request.getAmount().compareTo(tx.getAmount()) == 0
                && tx.getBalanceAfter() != null
                && Objects.equals(request.getPaymentReference(), tx.getPaymentReference())
                && tx.getPaymentMethod() != null
                && Objects.equals(request.getPaymentMethod(), tx.getPaymentMethod().name())
                && Objects.equals(request.getApprovedBy(), tx.getApprovedBy())
                && Objects.equals(request.getApprovedAt(), tx.getApprovedAt())
                && Objects.equals(request.getPaidBy(), tx.getOperatedBy());
        if (!valid) throw invalid("应收流水与申请证据不一致，退款不能结清");
    }

    public static void requireMatchingVoucher(String factoryId, PaymentRequest request,
                                              Voucher voucher) {
        requireVoucher(factoryId, request, voucher, false);
    }

    public static void requireReversedVoucher(String factoryId, PaymentRequest request,
                                              Voucher voucher) {
        requireVoucher(factoryId, request, voucher, true);
    }

    private static void requireVoucher(String factoryId, PaymentRequest request,
                                        Voucher voucher, boolean reversed) {
        if (factoryId == null || request == null || voucher == null
                || !Objects.equals(factoryId, request.getFactoryId())
                || !Objects.equals(factoryId, voucher.getFactoryId())
                || !SOURCE.equals(voucher.getSourceBusinessType())
                || !Objects.equals(request.getId(), voucher.getSourceBusinessId())
                || voucher.getVoucherType() != VoucherType.CASH_PAYMENT
                || ((voucher.getStatus() == VoucherStatus.POSTED || voucher.getStatus() == VoucherStatus.REVERSED)
                    && (voucher.getApprovedBy() == null || voucher.getApprovedBy() <= 0 || voucher.getApprovedAt() == null))
                || (reversed
                    ? request.getStatus() != PaymentRequestStatus.CANCELLED || request.getCancelledAt() == null
                        || (voucher.getStatus() != VoucherStatus.VOID && voucher.getStatus() != VoucherStatus.REVERSED)
                    : voucher.getStatus() != VoucherStatus.DRAFT && voucher.getStatus() != VoucherStatus.POSTED)
                || request.getAmount() == null || request.getAmount().signum() <= 0
                || voucher.getTotalDebit() == null || voucher.getTotalCredit() == null
                || voucher.getTotalDebit().compareTo(request.getAmount()) != 0
                || voucher.getTotalCredit().compareTo(request.getAmount()) != 0) {
            throw invalid("退款凭证来源、状态、类型或金额证据不一致");
        }
        String cashCode = switch (request.getPaymentMethod() == null ? "" : request.getPaymentMethod()) {
            case "CASH" -> "1001";
            case "BANK_TRANSFER" -> "1002";
            default -> null;
        };
        List<VoucherEntry> entries = voucher.getEntries();
        if (cashCode == null || entries == null || entries.size() != 2
                || entries.stream().anyMatch(ReturnRefundEvidence::hasNegativeOrTwoSidedAmount)
                || !hasCustomerDebit(entries, request.getCustomerId(), request.getAmount())
                || !hasCashCredit(entries, cashCode, request.getAmount())) {
            throw invalid("退款凭证客户应收与现金银行分录证据不一致");
        }
    }

    private static boolean hasNegativeOrTwoSidedAmount(VoucherEntry entry) {
        BigDecimal debit = entry.getDebit();
        BigDecimal credit = entry.getCredit();
        return debit == null || credit == null || debit.signum() < 0 || credit.signum() < 0
                || (debit.signum() > 0 && credit.signum() > 0)
                || (debit.signum() == 0 && credit.signum() == 0);
    }

    private static boolean hasCustomerDebit(List<VoucherEntry> entries, String customerId, BigDecimal amount) {
        return entries.stream().anyMatch(entry -> "1122".equals(entry.getSubjectCode())
                && amount.compareTo(entry.getDebit()) == 0 && entry.getCredit().signum() == 0
                && entry.getAuxiliaryType() == AuxiliaryType.CUSTOMER
                && Objects.equals(customerId, entry.getAuxiliaryEntityId()));
    }

    private static boolean hasCashCredit(List<VoucherEntry> entries, String subjectCode, BigDecimal amount) {
        return entries.stream().anyMatch(entry -> subjectCode.equals(entry.getSubjectCode())
                && amount.compareTo(entry.getCredit()) == 0 && entry.getDebit().signum() == 0
                && entry.getAuxiliaryType() == null && entry.getAuxiliaryEntityId() == null);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(409, message).withCode("RETURN_REFUND_EVIDENCE_INVALID");
    }
}
