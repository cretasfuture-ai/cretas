package com.cretas.aims.dto.factory;

import com.cretas.aims.entity.enums.DefectCountOutcome;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 盘点页闭合生产结单不良品待办的请求。 */
@Data
public class StocktakeDefectCountRequest {
    @NotNull(message = "请选择不良品清点结论")
    private DefectCountOutcome outcome;

    /** 仅 outcome=COUNTED 时填写，必须大于 0。 */
    private BigDecimal defectQuantity;
}
