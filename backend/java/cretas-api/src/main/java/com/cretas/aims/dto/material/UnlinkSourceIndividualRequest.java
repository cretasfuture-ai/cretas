package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 解除批次来源个体挂载的请求体 (#1844 终审 L-d)。
 *
 * <p>⚠️ 与 {@link UpdateMaterialBatchRequest} 刻意分开: 那个是 partial body
 * ({@code sourceIndividualId} null = 不改), 解除是一个<b>有原因、要留痕</b>的动作。
 */
@Data
@Schema(description = "解除批次来源个体挂载 (原因必填, 写操作日志)")
public class UnlinkSourceIndividualRequest {

    @Schema(description = "解除原因 (必填, ≤255)", example = "挂错了个体, 实际来自另一头",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "解除原因不能为空")
    @Size(max = 255, message = "解除原因不能超过255个字符")
    private String reason;
}
