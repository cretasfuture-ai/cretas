package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Actual completed output net mass / actual first-step input mass. Reuses the reporting authority.
 */
@Component
@RequiredArgsConstructor
public class FactoryLuYieldRateStrategy implements IndicatorComputationStrategy {

    private final FactoryActualProductionMetrics metrics;

    @Override
    public String getSourceVersion() { return FactoryActualProductionMetrics.SOURCE_VERSION; }

    @Override
    public String getIndicatorCode() {
        return "FACTORY_LU_YIELD_RATE";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        return metrics.compute(factoryId, periodStart, periodEnd, FactoryActualProductionMetrics.Metric.YIELD_RATE);
    }
}
