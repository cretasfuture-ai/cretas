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
 * (Rule 2b = finance:read_write) 把<b>本工具用到的</b>金额与比率剥掉 ({@link #sameVisibilityForAmounts}), 否则「有价格查看、
 * 无财务读写」的账号文字里有数、字段里却是 null; 报表里其余键仍按 web 同一判定处理。
 * (键名像金额的比率, 如 currentRatio 含 rent、profitMargin 含 profit, 也要登记。)
 */
public final class FinanceFigures {

    private FinanceFigures() {
    }

    /** 金额与比率的唯一口径: 价格查看。 */
    public static final String AMOUNT_SPEC = PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION;

    public static final String INSUFFICIENT = "数据不足，无法计算";

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
     * 净利润 ({@link #netProfit}) 实际读了报表哪几个键: 报表给了 totalProfit 就只读它, 否则读营收与成本。
     */
    public static List<String> profitInputKeys(Map<String, Object> finance) {
        if (finance != null && finance.containsKey("totalProfit")) {
            return List.of("totalProfit");
        }
        return List.of("totalRevenue", "totalCost");
    }

    /**
     * 已按 {@link #AMOUNT_SPEC} 判定看得到: <b>只</b>把本工具算比率实际读的报表键和写出的金额 / 比率键登记为「按口径可见」,
     * 出口对这些键不再按键名规则剥 (与 AbstractTool#sameVisibilityAsRest 同一机制, 只在对话直调工具的记录期间生效)。
     *
     * <p>⛔ 不登记整份报表: financeData 里没参与计算的键 (分项成本、应收 / 应付 / 预收等) 留给出口按 web
     * GET /reports/finance 同一个判定 (PriceFieldResponseAdvice 的键名规则) 处理 —— 以前整份登记, 「有价格查看、无财务读写」
     * 的账号经比率工具拿到了 web 对它遮掉的分项成本 (#1806 复审「中」)。
     */
    public static void sameVisibilityForAmounts(Collection<String> usedKeys) {
        PriceSensitiveContext.recordVisibleKeys(new ArrayList<>(usedKeys));
    }

    /** 拼接键列表 (报表输入键 + 输出键)。 */
    @SafeVarargs
    public static List<String> keys(Collection<String>... groups) {
        List<String> all = new ArrayList<>();
        for (Collection<String> g : groups) {
            all.addAll(g);
        }
        return all;
    }
}
