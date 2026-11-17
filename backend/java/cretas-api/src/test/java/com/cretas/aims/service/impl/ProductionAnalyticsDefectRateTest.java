package com.cretas.aims.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionAnalyticsDefectRateTest {

    @Test
    void zeroOutputGivesNullNotZero() {
        assertThat(ProductionAnalyticsServiceImpl.defectRate(new BigDecimal("3"), BigDecimal.ZERO)).isNull();
        assertThat(ProductionAnalyticsServiceImpl.defectRate(null, new BigDecimal("10"))).isNull();
    }

    @Test
    void rateIsDefectOverOutput() {
        assertThat(ProductionAnalyticsServiceImpl.defectRate(new BigDecimal("1.25"), new BigDecimal("100")))
                .isEqualByComparingTo("0.0125");
    }

    @Test
    void differentUnitsStaySeparateRowsEachWithOwnRate() {
        List<Map<String, Object>> rows = List.of(
                Map.of("process_category", "装盒", "unit", "kg", "output", new BigDecimal("100"), "defect", new BigDecimal("5")),
                Map.of("process_category", "装盒", "unit", "盒", "output", BigDecimal.ZERO, "defect", BigDecimal.ZERO));
        List<Map<String, Object>> out = ProductionAnalyticsServiceImpl.withDefectRate(rows);
        assertThat(out).hasSize(2);
        assertThat((BigDecimal) out.get(0).get("defectRate")).isEqualByComparingTo("0.05");
        assertThat(out.get(1).get("defectRate")).isNull();
    }
}
