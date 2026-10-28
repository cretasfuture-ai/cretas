package com.cretas.aims.dto.purchase;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 添加 / 更新供应商报价请求 (P-NUCLEAR-1).
 *
 * <p>Service 层会判断: 若 (inquiry, supplier) 已存在报价则 UPDATE,
 * 否则 INSERT — 防呆 R4 幂等.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddSupplierPriceRequest {

    @NotBlank(message = "供应商 ID 不能为空")
    @Size(max = 191)
    private String supplierId;

    @NotNull(message = "报价不能为空")
    @Positive(message = "报价必须 > 0")
    private BigDecimal unitPrice;

    /** 税率 (百分比)。不填 = 未知, ⛔ 不按 0% 处理; 报价会被标为不可比。 */
    @DecimalMin(value = "0", message = "税率不能小于 0")
    @DecimalMax(value = "100", message = "税率不能大于 100")
    private BigDecimal taxRate;

    /** 币种 (e.g. CNY)。不填 = 未知, 报价不可比。 */
    @Size(max = 8)
    private String currency;

    /** 报价计价单位。不填 = 未知, 报价不可比。 */
    @Size(max = 20)
    private String quotedUnit;

    @Size(max = 200)
    private String specification;

    /** 起订量 (计价单位下)。 */
    @Positive(message = "起订量必须 > 0")
    private BigDecimal minOrderQuantity;

    /** 价格生效日。 */
    private LocalDate priceEffectiveDate;

    private LocalDate validUntil;

    private Integer deliveryDays;

    @Size(max = 500)
    private String remark;
}
