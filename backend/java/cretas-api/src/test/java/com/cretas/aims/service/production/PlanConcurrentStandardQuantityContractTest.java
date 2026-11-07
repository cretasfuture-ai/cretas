package com.cretas.aims.service.production;

import com.cretas.aims.entity.production.PlanStandardCostLine;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PlanConcurrentStandardQuantityContractTest {
    @Test void quantityUsesFrozenBasisWithoutRequiringPriceOrReinterpretingGramsAsKilograms() {
        PlanStandardCostLine line = new PlanStandardCostLine(); line.setLineType(PlanStandardCostLine.TYPE_MATERIAL);
        line.setMaterialTypeId("pack"); line.setStandardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT);
        line.setStandardRate(new BigDecimal("2")); line.setStandardUnit("g");
        var result = PlanCostVarianceCalculator.standardMaterialQuantities(List.of(line), new BigDecimal("80"), new BigDecimal("80"), false).getFirst();
        assertThat(result.quantity()).isEqualByComparingTo("160"); assertThat(result.unit()).isEqualTo("g");
        assertThat(result.reason()).isNull(); assertThat(line.getPriceUnit()).isNull();
    }
    @Test void rawYieldAndPortShareUseTheExistingCostStandardAndDoNotInventPackageWeight() {
        PlanStandardCostLine line = new PlanStandardCostLine(); line.setLineType(PlanStandardCostLine.TYPE_MATERIAL);
        line.setMaterialTypeId("raw"); line.setStandardBasis(PlanStandardCostLine.BASIS_RAW_INPUT_SHARE); line.setRawYieldRate(new BigDecimal("80"));
        assertThat(PlanCostVarianceCalculator.standardMaterialQuantities(List.of(line), new BigDecimal("80"), new BigDecimal("80"), false).getFirst().quantity()).isEqualByComparingTo("100");
        assertThat(PlanCostVarianceCalculator.standardMaterialQuantities(List.of(line), new BigDecimal("80"), null, false).getFirst().quantity()).isNull();
        assertThat(PlanCostVarianceCalculator.standardMaterialQuantities(List.of(line), new BigDecimal("80"), new BigDecimal("80"), true).getFirst().quantity()).isNull();
    }
}
