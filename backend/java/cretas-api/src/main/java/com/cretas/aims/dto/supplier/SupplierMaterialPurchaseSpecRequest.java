package com.cretas.aims.dto.supplier;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class SupplierMaterialPurchaseSpecRequest {
    @NotBlank @Size(max = 100) private String name;
    @NotBlank @Size(max = 20) private String purchasePackageUnit;
    @NotBlank @Size(max = 20) private String inventoryBaseUnit;
    @NotNull @DecimalMin(value = "0.000000000001") private BigDecimal factor;
    /** 未税报价 —— 兼容既有集成; 界面已不再让人填未税 (19②)。 */
    @Positive(message = "采购规格报价必须大于0") private BigDecimal quotedPrice;

    /** 含税报价 —— 19② 之后人工录入的权威值, 未税由物料税率派生。 */
    @Positive(message = "采购规格含税报价必须大于0") private BigDecimal taxIncludedQuotedPrice;
    @Pattern(regexp = "^[A-Z]{3}$") private String currency;
    @Positive private BigDecimal minOrderQuantity;
    @PositiveOrZero private Integer leadTimeDays;
    private Boolean defaultSpec;
    private Boolean active;
    private Long version;
}
