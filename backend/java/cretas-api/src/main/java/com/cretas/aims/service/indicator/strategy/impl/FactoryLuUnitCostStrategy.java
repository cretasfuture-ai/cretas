package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Actual complete collected production cost / qualified output net kg. Never sums boxes with kg.
 */
@Component
@RequiredArgsConstructor
public class FactoryLuUnitCostStrategy implements IndicatorComputationStrategy {

    private final FactoryActualProductionMetrics metrics;

    @Override
    public String getSourceVersion() { return FactoryActualProductionMetrics.SOURCE_VERSION; }

    @Override
    public String getIndicatorCode() {
        return "FACTORY_LU_UNIT_COST";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        return metrics.compute(factoryId, periodStart, periodEnd, FactoryActualProductionMetrics.Metric.UNIT_COST);
    }
}
