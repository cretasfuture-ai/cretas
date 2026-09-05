package com.cretas.aims.dto.bom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 更新 BOM 配方请求 DTO (M-BOM-1, 仅 DRAFT 状态可改).
 *
 * <p>PUT 是 full-replace 语义: items 列表完全替换 (旧 items 全 soft-delete, 新 list insert).
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
@Data
@Schema(description = "更新 BOM 配方请求 (仅 DRAFT 状态)")
public class UpdateBomRecipeRequest {

    @Schema(description = "产品名称")
    @Size(max = 200)
    private String productName;

    // 🔴 2026-09-06 订正: 「由正式批次历史自动计算」在 #594 之后已经不成立 —— 自学习只产
    //    PENDING 建议, 写入只能由人走 POST /bom/yield-estimate/recalculate-apply。
    //    草稿编辑仍然忽略这个字段, 变的是「被忽略之后由谁来填」。
    @Schema(description = "已废弃：草稿编辑不接收整体出成率，人工传值会被忽略；请到「标准出成率」页面填写",
            deprecated = true)
    private BigDecimal overallYieldRate;

    @Schema(description = "已废弃：服务端按 SKU 基本单位固定为 1", deprecated = true)
    private BigDecimal outputQuantityPerUnit;

    @Schema(description = "已废弃：服务端继承 SKU canonical 基本单位", deprecated = true)
    @Size(max = 20)
    private String outputUnit;

    @Schema(description = "配方项 (full-replace)")
    @Valid
    private List<CreateBomRecipeRequest.BomRecipeItemDTO> items;

    @Schema(description = "备注")
    @Size(max = 500)
    private String notes;
}
