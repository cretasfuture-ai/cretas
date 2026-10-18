package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Qualified production output / actual output, weighted by historically pinned net mass. */
@Component
@RequiredArgsConstructor
public class FactoryGoodOutputRateStrategy implements IndicatorComputationStrategy {
    private final FactoryActualProductionMetrics metrics;
    @Override public String getIndicatorCode() { return "FACTORY_GOOD_OUTPUT_RATE"; }
    @Override public String getSourceVersion() { return FactoryActualProductionMetrics.SOURCE_VERSION; }
    @Override public BigDecimal compute(String factoryId, LocalDate start, LocalDate end) {
        return metrics.compute(factoryId, start, end, FactoryActualProductionMetrics.Metric.GOOD_OUTPUT_RATE);
    }
}
