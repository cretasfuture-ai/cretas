package com.cretas.aims.dto.purchase;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 报价价格变化对 BOM 的影响估算 (L3, S5-04). <b>只读估算</b>。
 *
 * <p>以报价的未税单价和价格生效日为来源, 逐条 BOM 行给出「现行单价 → 报价单价」的行成本变化。
 * ⛔ 不回写 BOM、不改任何历史批次成本: 已投产批次的成本取自批次实际记录, 与本估算无关。
 * BOM 价格的正式变更仍走 BOM 调价建议审批。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InquiryQuotePriceImpactDTO {

    private String inquiryId;
    private String inquiryNumber;
    private String priceId;
    private String supplierId;
    private String supplierName;
    private String materialTypeId;
    private String materialName;

    /** 估算来源: 报价未税单价 + 计价单位 + 生效日。 */
    @PriceSensitive(permission = PriceSensitive.PURCHASE)
    private BigDecimal quotedUnitPrice;
    private String quotedUnit;
    private LocalDate priceEffectiveDate;
    private LocalDate evaluatedOn;

    /** 固定为 ESTIMATE: 这是估算, 不是实际成本。 */
    private String nature;
    /** 历史批次成本是否被改动 —— 本接口只读, 恒为 false。 */
    private boolean historicalCostsModified;
    private String note;

    private int affectedRecipeCount;
    private int computableRowCount;
    /** 因工艺保密无权查看而略去的配方数。 */
    private int hiddenRecipeCount;

    @Builder.Default
    private List<Row> rows = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private String recipeId;
        private String recipeCode;
        private String productTypeId;
        private String productName;
        /** BOM 版本 (估算挂在哪个版本上)。 */
        private Integer recipeVersion;
        private String recipeStatus;
        private Boolean isCurrent;
        private Long recipeItemId;

        private BigDecimal standardQuantity;
        private String priceUnit;

        @PriceSensitive(permission = PriceSensitive.PURCHASE)
        private BigDecimal currentUnitPrice;
        @PriceSensitive(permission = PriceSensitive.PURCHASE)
        private BigDecimal currentItemCost;
        @PriceSensitive(permission = PriceSensitive.PURCHASE)
        private BigDecimal estimatedItemCost;
        @PriceSensitive(permission = PriceSensitive.PURCHASE)
        private BigDecimal deltaItemCost;

        private boolean computable;
        /** 不可算的原因 (单位不同 / 无参考用量 / 现行单价缺失)。 */
        private String notComputableReason;
    }
}
