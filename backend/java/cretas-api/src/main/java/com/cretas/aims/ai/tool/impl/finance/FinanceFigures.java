package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 财务比率工具 (finance_roe / finance_roa / finance_dupont / finance_liquidity / finance_solvency /
 * report_finance_ratio) 共用: 金额与比率的可见性判定、取数 (缺数 ⇒ null, 不兜成 0 / 1)、文字。
 *
 * <p>口径 (与 #1801 利润表同, Steve 11-17): 金额与由金额算出的比率只看价格查看权限 ({@link #AMOUNT_SPEC},
 * 经 PriceFieldResponseAdvice#showsSpec 按权限配置判), 不另要 finance:read_write。过了这道门的结果, 出口不再按键名
 * (Rule 2b = finance:read_write) 把金额剥掉 ({@link #sameVisibilityForAmounts}), 否则「有价格查看、无财务读写」
 * 的账号文字里有数、字段里却是 null。
 */
public final class FinanceFigures {

    private FinanceFigures() {
    }

    /** 金额与比率的唯一口径: 价格查看。 */
    public static final String AMOUNT_SPEC = PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION;

    public static final String INSUFFICIENT = "数据不足，无法计算";

    /**
     * 比率键: 键名像金额的 (currentRatio 含 rent、profitMargin 含 profit) 出口会按 Rule 2b 剥, 过了价格门的一并登记为可见。
     */
    public static final List<String> RATIO_KEYS = List.of("roe", "roa", "dupontROE", "profitMargin", "assetTurnover",
            "equityMultiplier", "currentRatio", "quickRatio", "debtRatio", "equityRatio");

    /** 缺数 / 非数字 ⇒ null (不知道就是不知道, ⛔ 不兜成 0 或 1 —— 以前兜底出假的 ROE / ROA / 流动比率)。 */
    public static Double figure(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val == null) return null;
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 净利润: 报表给了 totalProfit 键就用它 (null = 报表说算不出, 例如成本没录); 没给这个键时, 营收与成本都在才相减。
     * 报表标了利润不可算 (工艺保密只算了部分成本) ⇒ null。
     */
    public static Double netProfit(Map<String, Object> finance) {
        if (finance == null || profitUnavailableReason(finance) != null) return null;
        if (finance.containsKey("totalProfit")) return figure(finance, "totalProfit");
        Double revenue = figure(finance, "totalRevenue");
        Double cost = figure(finance, "totalCost");
        return revenue != null && cost != null ? revenue - cost : null;
    }

    public static Object profitUnavailableReason(Map<String, Object> finance) {
        if (finance == null) return null;
        Object reason = finance.get("profitUnavailableReason");
        return reason != null ? reason : finance.get("processSecretNote");
    }

    public static String amountsHiddenMessage(String ratio, String inputs) {
        return ratio + "由" + inputs + "等金额算出，按当前账号的权限配置不显示（与 web 财务报表同一判定，需要价格查看权限）。";
    }

    public static void appendProfitReason(StringBuilder sb, Map<String, Object> finance) {
        Object reason = profitUnavailableReason(finance);
        if (reason != null) {
            sb.append("\n  说明: ").append(reason);
        }
    }

    /** 作分母的金额: 缺数或 ≤ 0 ⇒ null (不能除)。 */
    public static Double positive(Double v) {
        return v != null && v > 0 ? v : null;
    }

    /**
     * 已按 {@link #AMOUNT_SPEC} 判定看得到: 把这次写出的金额键登记为「按口径可见」, 出口不再按键名规则剥
     * (与 AbstractTool#sameVisibilityAsRest 同一机制, 只在对话直调工具的记录期间生效)。
     */
    public static void sameVisibilityForAmounts(Map<String, Object> finance, Collection<String> extraKeys) {
        List<String> keys = new ArrayList<>(extraKeys);
        if (finance != null) {
            keys.add("financeData");
            keys.addAll(finance.keySet());
        }
        PriceSensitiveContext.recordVisibleKeys(keys);
    }
}
