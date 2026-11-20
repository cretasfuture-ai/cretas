package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 更新批次状态请求
 */
@Data
@Schema(description = "更新批次状态请求")
public class UpdateBatchStatusRequest {

    @Schema(description = "新状态")
    @NotBlank(message = "状态不能为空")
    private String status;

    @Schema(description = "备注")
    private String notes;

    @Schema(description = "放行原因: 把质检中/过期/报废/不良批次改成可投产状态时, 按本厂规则可能必填 (纪律排查 A3)")
    private String reason;
}
