package com.cretas.aims.service.voucher;

import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import java.util.Objects;

/** Immutable evidence binding a cash recovery to its original refund and accounting reversal. */
public final class ReturnRefundReversalEvidence {
    public static final String SOURCE = "RETURN_REFUND_REVERSAL";
    private ReturnRefundReversalEvidence() { }

    public static void requireReversal(ReturnOrder order, PaymentRequest request, ArApTransaction tx) {
        if (order == null || request == null || tx == null
                || request.getId() == null || order.getId() == null || order.getFactoryId() == null
                || order.getStatus() != ReturnOrderStatus.COMPLETED || order.getReturnType() != ReturnType.SALES_RETURN
                || !Boolean.TRUE.equals(order.getWithGoods()) || request.getSourceType() != PaymentSourceType.SALES
                || !Objects.equals(order.getFactoryId(), request.getFactoryId())
                || !Objects.equals(order.getId(), request.getReturnOrderId())
                || !Objects.equals(order.getSourceOrderId(), request.getSalesOrderId())
                || !Objects.equals(order.getCounterpartyId(), request.getCustomerId())
                || !Objects.equals(request.getFactoryId(), tx.getFactoryId())
                || !SOURCE.equals(tx.getSourceType()) || !Objects.equals(request.getId(), tx.getSourceId())
                || tx.getTransactionType() != ArApTransactionType.AR_ADJUSTMENT
                || tx.getCounterpartyType() != CounterpartyType.CUSTOMER
                || !Objects.equals(request.getCustomerId(), tx.getCounterpartyId())
                || !Objects.equals(request.getSalesOrderId(), tx.getSalesOrderId())
                || request.getAmount() == null || request.getAmount().signum() <= 0
                || tx.getAmount() == null || request.getAmount().negate().compareTo(tx.getAmount()) != 0
                || tx.getPaymentMethod() == null || !Objects.equals(request.getPaymentMethod(), tx.getPaymentMethod().name())
                || tx.getPaymentReference() == null || tx.getPaymentReference().isBlank()
                || Objects.equals(request.getPaymentReference(), tx.getPaymentReference())
                || tx.getOperatedBy() == null || tx.getOperatedBy() <= 0 || tx.getTransactionDate() == null
                || tx.getBalanceAfter() == null || tx.getRemark() == null || tx.getRemark().isBlank()
                || tx.getApprovalStatus() == null) throw invalid();
        if (tx.getApprovalStatus() == ArApApprovalStatus.PENDING) {
            if (tx.getApprovedBy() != null || tx.getApprovedAt() != null) throw invalid();
        } else if (tx.getApprovedBy() == null || tx.getApprovedBy() <= 0 || tx.getApprovedAt() == null
                || Objects.equals(tx.getOperatedBy(), tx.getApprovedBy())) throw invalid();
    }

    public static void requireApprovedReversal(ReturnOrder order, PaymentRequest request, ArApTransaction tx) {
        requireReversal(order, request, tx);
        if (tx.getApprovalStatus() != ArApApprovalStatus.APPROVED
                || (request.getStatus() != PaymentRequestStatus.PAID
                    && request.getStatus() != PaymentRequestStatus.CANCELLED)) throw invalid();
    }

    public static void requireCompletedReversal(ReturnOrder order, PaymentRequest request,
            ArApTransaction originalLedger, ArApTransaction reversalLedger, Voucher original, Voucher mirror) {
        requireApprovedReversal(order, request, reversalLedger);
        if (request.getStatus() != PaymentRequestStatus.CANCELLED || request.getCancelledAt() == null
                || !request.getCancelledAt().equals(reversalLedger.getApprovedAt())) throw invalid();
        ReturnRefundEvidence.requireHistoricalLedger(order, request, originalLedger);
        ReturnRefundEvidence.requireReversedVoucher(order.getFactoryId(), request, original);
        if (originalLedger.getTransactionDate() == null || original.getVoucherDate() == null
                || reversalLedger.getTransactionDate() == null
                || !Objects.equals(originalLedger.getTransactionDate(), original.getVoucherDate())
                || !Objects.equals(reversalLedger.getTransactionDate(), original.getVoucherDate())) throw invalid();
        if (original.getOriginalVoucherId() != null) throw invalid();
        if (original.getStatus() == VoucherStatus.VOID) {
            if (original.getReversalVoucherId() != null || mirror != null
                    || !Objects.equals(original.getApprovedBy(), reversalLedger.getApprovedBy())
                    || original.getApprovedAt() == null || original.getApprovedAt().isBefore(reversalLedger.getApprovedAt())) throw invalid();
            return;
        }
        if (mirror == null || mirror.getId() == null
                || !Objects.equals(original.getReversalVoucherId(), mirror.getId())
                || !Objects.equals(original.getId(), mirror.getOriginalVoucherId())
                || !Objects.equals(order.getFactoryId(), mirror.getFactoryId())
                || !"VOUCHER_REVERSAL".equals(mirror.getSourceBusinessType())
                || !Objects.equals(original.getId(), mirror.getSourceBusinessId())
                || mirror.getStatus() != VoucherStatus.POSTED || mirror.getVoucherType() != original.getVoucherType()
                || !Objects.equals(original.getVoucherDate(), mirror.getVoucherDate())
                || !Objects.equals(reversalLedger.getApprovedBy(), mirror.getApprovedBy())
                || !Objects.equals(reversalLedger.getTransactionDate(), mirror.getVoucherDate())
                || mirror.getApprovedAt() == null || mirror.getApprovedAt().isBefore(reversalLedger.getApprovedAt())
                || mirror.getTotalDebit() == null || mirror.getTotalCredit() == null
                || mirror.getTotalDebit().compareTo(request.getAmount()) != 0
                || mirror.getTotalCredit().compareTo(request.getAmount()) != 0
                || mirror.getEntries() == null || mirror.getEntries().size() != original.getEntries().size()
                || original.getEntries().stream().anyMatch(entry -> mirror.getEntries().stream()
                    .filter(other -> mirrors(entry, other)).count() != 1)) throw invalid();
    }

    private static boolean mirrors(VoucherEntry original, VoucherEntry mirror) {
        return Objects.equals(original.getSubjectCode(), mirror.getSubjectCode())
                && Objects.equals(original.getAuxiliaryType(), mirror.getAuxiliaryType())
                && Objects.equals(original.getAuxiliaryEntityId(), mirror.getAuxiliaryEntityId())
                && mirror.getDebit() != null && mirror.getCredit() != null
                && original.getDebit().compareTo(mirror.getCredit()) == 0
                && original.getCredit().compareTo(mirror.getDebit()) == 0;
    }

    private static BusinessException invalid() {
        return new BusinessException(409, "退款撤销的来源、审批、款项收回或冲销凭证证据不一致，请由财务核对")
                .withCode("RETURN_REFUND_REVERSAL_EVIDENCE_INVALID")
                .withHint("请由财务打开退款详情，核对来源、审批、收回款项和冲销凭证");
    }
}
