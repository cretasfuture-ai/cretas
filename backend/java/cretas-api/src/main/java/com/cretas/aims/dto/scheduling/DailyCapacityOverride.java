package com.cretas.aims.dto.scheduling;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 一个产品「一天能做多少」(数值 + 单位)。排程页当场补填与产品页编辑共用。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyCapacityOverride {

    @NotBlank(message = "产品不能为空")
    @Size(max = 191, message = "产品ID过长")
    private String productTypeId;

    @NotNull(message = "日产能不能为空")
    @DecimalMin(value = "0.01", message = "日产能必须大于 0")
    @Digits(integer = 10, fraction = 2, message = "日产能最多 10 位整数、2 位小数")
    private BigDecimal quantityPerDay;

    @NotBlank(message = "日产能单位不能为空")
    @Size(max = 32, message = "日产能单位过长")
    private String unit;
}
