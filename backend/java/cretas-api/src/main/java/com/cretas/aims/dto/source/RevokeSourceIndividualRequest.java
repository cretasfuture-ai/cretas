package com.cretas.aims.dto.source;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 撤销源头个体档案请求: 原因放请求体(不进 URL/访问日志), 必填, 最长 255。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "撤销源头个体档案")
public class RevokeSourceIndividualRequest {

    @NotBlank(message = "撤销原因不能为空")
    @Size(max = 255, message = "撤销原因最长 255 字")
    @Schema(description = "撤销原因 (必填, ≤255)")
    private String reason;
}
