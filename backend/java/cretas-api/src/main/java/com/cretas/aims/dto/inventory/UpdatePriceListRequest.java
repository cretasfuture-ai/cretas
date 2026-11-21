package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 更新价格表表头 (名称/生效期/备注); 价格类型与客户归属创建后不可改, 条目走 /items 接口。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePriceListRequest {

    @NotBlank(message = "价格表名称不能为空")
    @Size(max = 200, message = "价格表名称长度不能超过200个字符")
    private String name;

    @NotNull(message = "生效日期不能为空")
    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    @Size(max = 5000, message = "备注长度不能超过5000个字符")
    private String remark;
}
