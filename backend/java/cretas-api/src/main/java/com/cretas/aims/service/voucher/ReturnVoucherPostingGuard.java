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
import com.cretas.aims.service.inventory.ReturnAdjustmentEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Business evidence for sales-return/actual-refund vouchers; no payment side effects. */
@Component
@RequiredArgsConstructor
public class ReturnVoucherPostingGuard {
    private final ReturnOrderRepository returnOrderRepository;
    private final ArApTransactionRepository transactionRepository;
    private final PaymentRequestRepository paymentRequestRepository;

    public void assertPostable(Voucher voucher) {
        if ("RETURN_ORDER".equals(voucher.getSourceBusinessType())) {
            ReturnOrder order = loadReturn(voucher.getFactoryId(), voucher.getSourceBusinessId());
            if (order.getReturnType() != ReturnType.SALES_RETURN) return;
            assertReturnCredit(order);
            assertAmount(voucher, order.getTotalAmount());
        } else if ("RETURN_REFUND".equals(voucher.getSourceBusinessType())) {
            PaymentRequest request = paymentRequestRepository.findById(voucher.getSourceBusinessId())
                    .orElseThrow(() -> invalid("找不到原退款记录"));
            if (!Objects.equals(voucher.getFactoryId(), request.getFactoryId())
                    || request.getSourceType() != PaymentSourceType.SALES
                    || request.getStatus() != PaymentRequestStatus.PAID
                    || request.getReturnOrderId() == null || request.getPaidBy() == null
                    || request.getPaidAt() == null || request.getPaymentReference() == null
                    || request.getPaymentReference().isBlank()
                    || Objects.equals(request.getCreatedBy(), request.getApprovedBy())
                    || Objects.equals(request.getCreatedBy(), request.getPaidBy())
                    || request.getApprovedBy() == null || request.getApprovedAt() == null) {
                throw invalid("退款尚未完成独立审批和实际付款登记");
            }
            ReturnOrder order = loadReturn(voucher.getFactoryId(), request.getReturnOrderId());
            assertReturnCredit(order);
            if (!Objects.equals(order.getCounterpartyId(), request.getCustomerId())
                    || !Objects.equals(order.getSourceOrderId(), request.getSalesOrderId())) {
                throw invalid("退款客户或原销售单与退货来源不一致");
            }
            ArApTransaction movement = request.getArapTransactionId() == null ? null
                    : transactionRepository.findById(request.getArapTransactionId()).orElse(null);
            ReturnRefundEvidence.requireMatchingLedger(order, request, movement);
            ReturnRefundEvidence.requireMatchingVoucher(voucher.getFactoryId(), request, voucher);
        }
    }

    private ReturnOrder loadReturn(String factoryId, String returnId) {
        ReturnOrder order = returnOrderRepository.findById(returnId).orElseThrow(() -> invalid("找不到原退货单"));
        if (!Objects.equals(factoryId, order.getFactoryId())) throw invalid("退货来源不属于当前工厂");
        return order;
    }

    private void assertReturnCredit(ReturnOrder order) {
        if (order.getReturnType() != ReturnType.SALES_RETURN || order.getStatus() != ReturnOrderStatus.COMPLETED
                || !Boolean.TRUE.equals(order.getWithGoods()) || order.getTotalAmount() == null
                || order.getTotalAmount().signum() <= 0) throw invalid("请先完成销售退货实物入库");
        List<ArApTransaction> rows = transactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                        order.getFactoryId(), "RETURN_ORDER", List.of(order.getId()), ArApTransactionType.AR_ADJUSTMENT);
        if (!"APPROVED".equals(ReturnAdjustmentEvidence.state(order, rows))) {
            throw invalid("请先核对应收调整并完成财务审批，再过账");
        }
    }

    private void assertAmount(Voucher voucher, BigDecimal expected) {
        if (expected == null || expected.signum() <= 0 || voucher.getTotalDebit() == null
                || voucher.getTotalDebit().compareTo(expected) != 0
                || voucher.getTotalCredit() == null || voucher.getTotalCredit().compareTo(expected) != 0) {
            throw invalid("凭证金额与原业务单不一致，请由财务核对");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(409, message).withCode("RETURN_VOUCHER_EVIDENCE_INVALID");
    }
}
