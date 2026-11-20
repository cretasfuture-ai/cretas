package com.cretas.aims.dto.finance.report;

import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 经营分析「财务」区块的系统毛利汇总，发货收入按工厂口径计算。
 *
 * <p>收入 / 成本 / 毛利 / 毛利率是 {@code @PriceSensitive}; 接口上的 {@code @PriceScope("finance:read")}
 * 把它们的口径换成工厂权限配置的财务读 (走 {@link com.cretas.aims.security.PriceVisibility}):
 * 无权时由响应拦截置为 null, 状态 / 条数 / 原因仍返回。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ShippedGrossProfitDTO {
    /** 能完整算出。 */
    public static final String OK = "OK";
    /** 期间内没有已确认发货的发运单: 收入、毛利都显示「—」。 */
    public static final String NO_SHIPMENT = "NO_SHIPMENT";
    /** 有已发货明细缺少售价: 收入算不出。 */
    public static final String PRICE_MISSING = "PRICE_MISSING";
    /** 净额所需的折扣或退货凭据缺失。 */
    public static final String ADJUSTMENT_INCOMPLETE = "ADJUSTMENT_INCOMPLETE";
    /** 收入可算, 但实际出库或退货回库的批次成本不完整: 成本、毛利显示「—」。 */
    public static final String COST_INCOMPLETE = "COST_INCOMPLETE";

    public static final String COST_BASIS =
            "销售成本：这些发运单实际出库的成品批次单位成本 × 出库数量（与销售成本结转同源）";
    public static final String NET_COST_BASIS = COST_BASIS + "，扣本期已完成销售退货的回库批次成本";

    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String status;
    /** 期间内已确认发货的明细行数 (不含母单)。 */
    private int shipmentLineCount;
    @PriceSensitive private BigDecimal revenue;
    @PriceSensitive private BigDecimal cost;
    @PriceSensitive private BigDecimal grossProfit;
    /** 毛利率 (%), 收入为 0 时没有定义 (null)。 */
    @PriceSensitive private BigDecimal marginRate;
    /** status != OK 时说明缺什么。 */
    private String unavailableReason;
    private ShippedRevenueBasis basis;
    private String revenueBasis;
    private String costBasis;
}
