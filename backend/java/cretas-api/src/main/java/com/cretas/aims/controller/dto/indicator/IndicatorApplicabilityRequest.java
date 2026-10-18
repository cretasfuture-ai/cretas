package com.cretas.aims.controller.dto.indicator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;

/** Explicit proposed profiles used by platform onboarding before applying configuration. */
public record IndicatorApplicabilityRequest(@Valid List<ProductSpec> products) {
    public record ProductSpec(@NotBlank String productId, String productName, String unit,
                              String inputUnit, BigDecimal gramsPerUnit, Boolean multiOutput) { }
}
