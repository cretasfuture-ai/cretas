package com.cretas.aims.dto.bom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CorrectBomItemCostRequest {
    @NotBlank
    @Size(max = 255)
    private String reason;
}
