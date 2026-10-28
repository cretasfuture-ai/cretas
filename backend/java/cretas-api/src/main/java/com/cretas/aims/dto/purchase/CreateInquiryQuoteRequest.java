package com.cretas.aims.dto.purchase;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 创建核价单请求 (P-NUCLEAR-1).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateInquiryQuoteRequest {

    @Size(max = 200, message = "标题长度不能超过200个字符")
    private String title;

    @NotBlank(message = "物料类型 ID 不能为空")
    @Size(max = 191)
    private String materialTypeId;

    @Size(max = 200, message = "物料名称长度不能超过200个字符")
    private String materialName;

    @Size(max = 200)
    private String specification;

    @NotNull(message = "询价数量不能为空")
    @Positive(message = "询价数量必须 > 0")
    private BigDecimal quantity;

    @NotBlank(message = "单位不能为空")
    @Size(max = 20)
    private String unit;

    @NotNull(message = "询价日期不能为空")
    private LocalDate inquiryDate;

    private LocalDate requiredDate;

    @Size(max = 5000)
    private String remark;

    /**
     * 发起比价的采购草稿 ID (可选)。有值时选定报价回填这张草稿, 不另建采购单;
     * 草稿必须是本厂 DRAFT 状态且恰好有一行是本物料。
     */
    @Size(max = 191)
    private String sourcePurchaseOrderId;
}
