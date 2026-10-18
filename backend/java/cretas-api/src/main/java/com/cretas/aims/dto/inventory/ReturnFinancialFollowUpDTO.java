package com.cretas.aims.dto.inventory;

import com.cretas.aims.entity.finance.ArApTransaction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnFinancialFollowUpDTO {
    private String returnOrderId;
    private String returnNumber;
    private String status;
    private Boolean canResubmit;
    private List<ArApTransaction> adjustments;
    private VoucherRef voucher;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VoucherRef {
        private String id;
        private String voucherNumber;
        private String status;
    }

    @Data
    public static class ResubmitRequest {
        @NotBlank(message = "请填写重新提交的原因")
        @Size(max = 200, message = "重提原因最多200字")
        private String reason;
    }
}
