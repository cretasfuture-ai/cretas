package com.cretas.aims.dto.material;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CorrectMaterialBatchCostRequest {
    @NotNull
    @DecimalMin(value = "0.000001")
    private BigDecimal unitPrice;

    @NotBlank
    @Size(max = 255)
    private String reason;
}
