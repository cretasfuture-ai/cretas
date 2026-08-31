package com.cretas.aims.dto.production;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductionReportCorrectionRequest(
        @PositiveOrZero BigDecimal outputQuantity,
        @PositiveOrZero BigDecimal goodQuantity,
        @PositiveOrZero BigDecimal defectQuantity,
        @PositiveOrZero Integer totalWorkers,
        @PositiveOrZero Integer totalWorkMinutes,
        @Size(max = 500) String notes,
        @NotBlank @Size(max = 500) String reason) {
}
