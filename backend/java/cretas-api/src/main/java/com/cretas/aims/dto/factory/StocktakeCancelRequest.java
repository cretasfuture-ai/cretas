package com.cretas.aims.dto.factory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Request to cancel an unsubmitted stocktake created by the current maker. */
@Data
public class StocktakeCancelRequest {

    @NotBlank(message = "取消原因不能为空")
    @Size(max = 500, message = "取消原因不能超过500字")
    private String reason;

    @NotNull(message = "expectedVersion 为必填字段")
    private Long expectedVersion;
}
