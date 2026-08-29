package com.cretas.aims.dto.production;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ProductionCostRecalculationResponse {
    private String productionPlanId;
    private String settlementId;
    private BigDecimal authoritativeTotalCost;
    private BigDecimal sharedUnitCost;
    private int outputLineCount;
    private int finishedGoodsBatchCount;
    private int reworkWipCount;
    private int productionBatchCount;
}
