package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Safe presentation and command DTOs for a customer refund against a completed return. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnRefundDTO {
    private String returnOrderId;
    private String returnNumber;
    private String eligibilityStatus;
    private String reason;
    @PriceSensitive
    private BigDecimal refundableAmount;
    private RequestView request;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestView {
        private String id;
        private String requestNumber;
        private String status;
        @PriceSensitive
        private BigDecimal amount;
        private String paymentMethod;
        private Long createdBy;
        private Long approvedBy;
        private Long paidBy;
        private LocalDateTime paidAt;
        private String paymentReference;
        private String arapTransactionId;
        private LedgerView ledger;
        private VoucherView voucher;
        private ReversalView reversal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReversalView {
        private String status;
        private String reason;
        private LocalDateTime requestedAt;
        private LedgerView ledger;
        private VoucherView voucher;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LedgerView {
        private String id;
        private String transactionNumber;
        private String transactionType;
        private String sourceType;
        private String sourceId;
        @PriceSensitive
        private BigDecimal amount;
        @PriceSensitive
        private BigDecimal balanceAfter;
        @PriceSensitive
        private BigDecimal balanceBefore;
        private String paymentMethod;
        private String paymentReference;
        private Long approvedBy;
        private String approvedByName;
        private LocalDateTime approvedAt;
        private Long operatedBy;
        private String operatedByName;
        private LocalDate transactionDate;
    }

    @Data
    public static class CreateRequest {
        @PriceSensitive
        @DecimalMin(value = "0.01", message = "退款金额必须大于0")
        private BigDecimal amount;

        @NotBlank(message = "退款说明不能为空")
        @Size(max = 500, message = "退款说明最多500字")
        private String remark;
    }

    @Data
    public static class ApproveRequest {
        @Size(max = 200, message = "审批说明最多200字")
        private String note;
    }

    @Data
    public static class MarkPaidRequest {
        @NotBlank(message = "付款方式不能为空")
        private String paymentMethod;

        @NotBlank(message = "付款凭证号不能为空")
        @Size(max = 100, message = "付款凭证号最多100字")
        private String paymentReference;

        @NotBlank(message = "付款说明不能为空")
        @Size(max = 500, message = "付款说明最多500字")
        private String remark;
    }

    @Data
    public static class RejectRequest {
        @NotBlank(message = "拒绝原因不能为空")
        @Size(max = 200, message = "拒绝原因最多200字")
        private String reason;
    }

    @Data
    public static class ReversalRequest {
        @NotBlank(message = "原退款申请不能为空")
        private String requestId;
        @NotBlank(message = "款项收回凭据不能为空")
        @Size(max = 100, message = "款项收回凭据最多100字")
        private String paymentReference;
        @NotBlank(message = "撤销原因不能为空")
        @Size(max = 400, message = "撤销原因最多400字")
        private String reason;
    }

    @Data
    public static class ReversalDecision {
        @NotBlank(message = "原退款申请不能为空")
        private String requestId;
        @NotBlank(message = "撤销申请流水不能为空")
        private String reversalTransactionId;
        @Size(max = 200, message = "驳回说明最多200字")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VoucherView {
        private String id;
        private String voucherNumber;
        private String status;
    }
}
