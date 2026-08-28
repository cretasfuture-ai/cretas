package com.cretas.aims.service.wip;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把报工填的多半成品产出筛成「真正可入账的那几条」。
 *
 * <p>抽成纯函数是刻意的: 过账逻辑埋在 {@code @Transactional} 方法里, 单测够不着,
 * 而「够不着被测行为」与「行为没生效」的读数一模一样、结论相反 —— 本仓吃过这个亏。
 */
public final class WipSemiOutputPlan {

    private WipSemiOutputPlan() {
    }

    /** 取一条产出的数量; 读不出来返回 null (⛔ 不兜底成 0)。调用方过账时用它, 不要自己再写一份解析。 */
    public static BigDecimal quantityOf(Map<String, Object> entry) {
        return entry == null ? null : toDecimal(entry.get("quantity"));
    }

    /** 取一条产出的半成品编码; 空白返回 null。 */
    public static String semiCodeOf(Map<String, Object> entry) {
        if (entry == null) return null;
        Object code = entry.get("semiCode");
        if (code == null) return null;
        String s = String.valueOf(code).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * @param entries 报工填的产出条目; null/空 → 空列表 (旧式报工走老路径)
     * @return 有码且数量为正的条目, 保持原顺序
     */
    public static List<Map<String, Object>> postableEntries(List<Map<String, Object>> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            if (e == null) continue;
            Object code = e.get("semiCode");
            if (code == null || String.valueOf(code).isBlank()) continue;
            BigDecimal qty = toDecimal(e.get("quantity"));
            // ⛔ 数量读不出来就跳过, 不兜底成 0 —— 0 会被下游读成「这批产出为零」这个真实读数。
            if (qty == null || qty.signum() <= 0) continue;
            out.add(e);
        }
        return out;
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
