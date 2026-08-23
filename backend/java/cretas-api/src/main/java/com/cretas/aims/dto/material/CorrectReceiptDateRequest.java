package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 更正批次入库日期的请求体。
 *
 * <p>⚠️ 与 {@link UpdateMaterialBatchRequest} 刻意分开：那个 DTO 是 partial body（所有字段可空，
 * 见它自己的 Rule 17.6 说明），而更正是一个<b>有原因、要留痕</b>的动作，两个字段都必填。
 * 把「必填」和「可选」混在同一个 DTO 里，@Valid 就只能按最宽的那一侧放行。
 */
@Data
@Schema(description = "更正批次入库日期请求 (原因必填, 会写审计台账)")
public class CorrectReceiptDateRequest {

    @Schema(description = "更正后的入库日期", example = "2026-08-01", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "更正后的入库日期不能为空")
    private LocalDate receiptDate;

    /**
     * 长度上限与 {@code material_batch_adjustments.reason} 一致 (varchar 255) —— 两条留痕路径
     * 对用户是同一个限长。⛔ 不截断：审计原因被悄悄砍掉一半比报错更糟 (与 /adjust 同一条纪律)。
     */
    @Schema(description = "更正原因 (必填, 会写入审计台账)", example = "收货当天漏登记, 实际到货 8-01",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "更正原因不能为空")
    @Size(max = 255, message = "更正原因不能超过255个字符")
    private String reason;
}
