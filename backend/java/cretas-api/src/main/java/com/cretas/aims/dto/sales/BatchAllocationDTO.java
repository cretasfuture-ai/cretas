package com.cretas.aims.dto.sales;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 销售发货批次分配请求单元（P0-13）。
 */
@Data
public class BatchAllocationDTO {
    /** 对应 FinishedGoodsBatch.id */
    private String finishedGoodsBatchId;
    private BigDecimal allocatedQty;
    /**
     * 操作人确认过这批的规格和发货行不一致、换算无误 (O-309-1)。
     * 不传 = 没确认, 规格不一致就 409 BATCH_PACKAGING_SPEC_MISMATCH。
     */
    private Boolean packagingConversionConfirmed;
}
