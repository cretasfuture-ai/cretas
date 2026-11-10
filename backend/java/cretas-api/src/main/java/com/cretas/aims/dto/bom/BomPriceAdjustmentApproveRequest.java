package com.cretas.aims.dto.bom;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class BomPriceAdjustmentApproveRequest {
    private Long approverId;
    private String comment;
    /** 可选: 审批人看到的建议价; 传了就必须等于建议行当前的建议价, 否则 409 BOM_PRICE_PROPOSAL_STALE。 */
    private BigDecimal expectedUnitPrice;
}
