package com.cretas.aims.controller.dto.indicator;

import java.math.BigDecimal;
import java.util.List;

/** Read-only explanation of whether the configured product profile can support metrics. */
public record IndicatorApplicabilityResponse(
        String factoryId,
        Basis basis,
        OverallStatus overallStatus,
        List<ProductApplicability> products,
        List<MetricApplicability> metrics) {
    public enum Basis { CONFIGURATION_ONLY, PROPOSED_CONFIGURATION }
    public enum OverallStatus { COMPLETE, PARTIAL, BLOCKED }
    public enum Status { APPLICABLE, BLOCKED, NOT_APPLICABLE, NEEDS_CONFIRMATION }

    public record ProductProfile(String unit, String inputUnit, BigDecimal gramsPerUnit,
                                 Boolean multiOutput) { }

    public record ProductApplicability(String productId, String productName,
                                       ProductProfile profile,
                                       List<MetricApplicability> metrics) { }

    public record MetricApplicability(String code, String name, Status status, String reason,
                                      List<String> requiredFacts) { }
}
