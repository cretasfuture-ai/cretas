package com.cretas.aims.dto.factory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Request to release one drifted approved stocktake into a fresh count cycle. */
@Data
public class StocktakeRecountRequest {

    @NotNull(message = "expectedVersion 为必填字段")
    private Long expectedVersion;

    @NotBlank(message = "重盘原因不能为空")
    @Size(max = 1000, message = "重盘原因不能超过1000字")
    private String reason;
}
