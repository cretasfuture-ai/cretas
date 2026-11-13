package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.service.shortage.CostDeviationEvaluator.Reading;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 成本偏差案例落库的产品名不能是内部占位 (__RAW_WORKFLOW_OWNER__…): 查不到真实产品存 null, 真名原样保留。 */
class CostDeviationPlaceholderProductNameContractTest {

    private static final BigDecimal FIVE = new BigDecimal("5");

    private static PlanCostVarianceDTO variance(String productName) {
        return PlanCostVarianceDTO.builder().planId("P1").planNumber("PL-1").productName(productName)
                .comparable(true).varianceRate(new BigDecimal("9")).totalVariance(new BigDecimal("100")).build();
    }

    @Test
    void costCase_placeholderProductName_isNotStored() {
        Reading r = CostDeviationEvaluator.evaluateCost(variance("__RAW_WORKFLOW_OWNER__RMT_123"), FIVE);
        assertThat(r.productName()).isNull();
    }

    @Test
    void costCase_realProductName_isKept() {
        Reading r = CostDeviationEvaluator.evaluateCost(variance("安格斯牛肉饼"), FIVE);
        assertThat(r.productName()).isEqualTo("安格斯牛肉饼");
    }
}
