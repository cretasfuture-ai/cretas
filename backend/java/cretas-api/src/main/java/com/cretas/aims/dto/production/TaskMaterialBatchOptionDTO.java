package com.cretas.aims.dto.production;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/** Minimal, non-price-sensitive material option for a task-scoped picker. */
@Value
@Builder
public class TaskMaterialBatchOptionDTO {
    String id;
    String batchNumber;
    String materialTypeId;
    String materialName;
    BigDecimal currentQuantity;
    String quantityUnit;
    String warehouseId;
    /** 该原料端口的主料份额(%) —— App 报工「配比核对」用 (缺口 06); null = 不参与配比。 */
    BigDecimal inputShareRatio;
}
