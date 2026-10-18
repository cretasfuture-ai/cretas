package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class FactoryActualProductionCostStrategy implements IndicatorComputationStrategy {
    private final FactoryActualProductionMetrics metrics;
    @Override public String getIndicatorCode() { return "FACTORY_ACTUAL_TOTAL_COST"; }
    @Override public String getSourceVersion() { return FactoryActualProductionMetrics.SOURCE_VERSION; }
    @Override public BigDecimal compute(String factoryId, LocalDate start, LocalDate end) {
        return metrics.compute(factoryId, start, end, FactoryActualProductionMetrics.Metric.TOTAL_COST);
    }
}
