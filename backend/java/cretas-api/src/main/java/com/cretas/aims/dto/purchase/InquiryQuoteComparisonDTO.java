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
 * 核价单比价结果 (L3 报价可比, S5-01 / S5-02).
 *
 * <p>只有「可比」的报价参与排序 ({@link Row#getRank()} 非空)。币种 / 单位 / 规格 / 税率
 * 任一项未知或与比较基准不同, 该报价进入「不可比」状态并逐条给出原因, ⛔ 不参与排序 ——
 * 缺依据的报价不能被排成低价。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InquiryQuoteComparisonDTO {

    private String inquiryId;
    private String inquiryNumber;
    private String materialTypeId;
    private String materialName;

    /** 比较基准: 询价数量 + 询价单位 (报价计价单位必须与它一致, 不做计数↔重量换算)。 */
    private BigDecimal quantity;
    private String referenceUnit;
    /** 比较基准: 采购单币种 (采购单不带币种字段, 一律按人民币记账)。 */
    private String referenceCurrency;
    /** 比较基准: 询价规格; 为空时不按规格比较。 */
    private String referenceSpecification;
    /** 比较基准: 物料档案税率 (百分比); 为空 = 物料未配置, 此时各报价税率必须彼此一致。 */
    private BigDecimal referenceTaxRate;
    /** referenceTaxRate 的来源说明。 */
    private String referenceTaxRateSource;
    private LocalDate requiredDate;
    /** 计算这份比价的日期 (有效期 / 生效日以它判断)。 */
    private LocalDate evaluatedOn;
    /** 排序口径。 */
    private String rankingBasis;

    /** 选定后回填的采购草稿 (有值时选定不会另建采购单)。 */
    private String sourcePurchaseOrderId;

    private int comparableCount;
    private int selectableCount;

    @Builder.Default
    private List<Row> rows = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private String priceId;
        private String supplierId;
        private String supplierName;

        /** 未税单价 (计价单位下)。 */
        @PriceSensitive(permission = PriceSensitive.PURCHASE)
        private BigDecimal unitPrice;
        @PriceSensitive(permission = PriceSensitive.PURCHASE)
        private BigDecimal taxRate;
        /** 含税单价 = 未税单价 × (1 + 税率); 税率未知时为空, ⛔ 不按 0 算。 */
        @PriceSensitive(permission = PriceSensitive.PURCHASE)
        private BigDecimal taxInclusiveUnitPrice;
        /** 按询价数量估算的未税金额; 不可比时为空。 */
        @PriceSensitive(permission = PriceSensitive.PURCHASE)
        private BigDecimal estimatedAmount;

        private String currency;
        private String quotedUnit;
        private String specification;

        private BigDecimal minOrderQuantity;
        /** QUOTE = 本次报价填写; SUPPLIER_MATERIAL = 供应关系维护值; null = 都没有。 */
        private String minOrderQuantitySource;
        private String minOrderQuantityUnit;

        private Integer leadTimeDays;
        /** QUOTE / SUPPLIER_MATERIAL / null。 */
        private String leadTimeSource;
        /** 今天下单的预计到货日 = 今天 + 交期天数。 */
        private LocalDate expectedArrivalDate;

        private LocalDate validUntil;
        private LocalDate priceEffectiveDate;

        private boolean comparable;
        @Builder.Default
        private List<Issue> notComparableReasons = new ArrayList<>();

        private boolean selectable;
        /** 不能选定的原因 (含不可比原因之外的: 过期 / 未生效 / 低于起订量 / 供应关系缺失等)。 */
        @Builder.Default
        private List<Issue> blockingReasons = new ArrayList<>();
        /** 不阻止选定、但需要看到的提示 (交期晚于需求日 / 未注明有效期等)。 */
        @Builder.Default
        private List<Issue> warnings = new ArrayList<>();

        /** 可比报价中的名次 (1 = 最低); 不可比为空。 */
        private Integer rank;
        /** 选定时将使用的采购包装规格 (供应关系配置了包装规格时)。 */
        private String purchasePackagingSpecId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Issue {
        private String code;
        private String message;
    }
}
