package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 采购统计 (S5-12 D-S5-07)。
 *
 * <p>原来直接返回 {@code Map}: PriceFieldResponseAdvice 的 Rule 2b 会把 Map 里名字带 amount 的键
 * 对没有 {@code finance:read_write} 的人一律剥掉, 于是有采购读的采购经理反而看不到月采购额。
 * 改成类型化 DTO, 金额只按采购价口径 ({@link PriceSensitive#PURCHASE}) 判。JSON 键名不变。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseStatisticsDTO {

    private Long monthlyOrderCount;

    private Long pendingApprovalCount;

    @PriceSensitive(permission = PriceSensitive.PURCHASE)
    private BigDecimal monthlyPurchaseAmount;

    public static PurchaseStatisticsDTO from(Map<String, Object> stats) {
        return PurchaseStatisticsDTO.builder()
                .monthlyOrderCount(toLong(stats.get("monthlyOrderCount")))
                .pendingApprovalCount(toLong(stats.get("pendingApprovalCount")))
                .monthlyPurchaseAmount(stats.get("monthlyPurchaseAmount") instanceof BigDecimal b ? b : null)
                .build();
    }

    private static Long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }
}
