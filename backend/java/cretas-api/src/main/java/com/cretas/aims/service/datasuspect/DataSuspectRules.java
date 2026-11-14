package com.cretas.aims.service.datasuspect;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 迁移 P5 R30 / R34: 明显的录入错误标「数据疑点 · 找谁核」—— <b>只标不改原值, 也不当业务异常</b>。
 *
 * <p>规则 (与原型 {@code a-api.cjs suspectsOf} 同口径, 换成正式端的报工字段):</p>
 * <ol>
 *   <li>出成率 ≥ 100%: 良品 (outputQuantity, 已是净重) ÷ 投入 ≥ 1, 只在两边单位可比时判
 *       (单位契约判等价, 或都是质量单位换算成 kg; 两边都没写单位视为同一单位)。</li>
 *   <li>起止时间相同: 任一工时段开始 = 结束, 或报工单的开始 = 结束。</li>
 *   <li>单位明显不符: 质量单位换成 kg 后投入或良品 ≥ 5000 kg (像把克当成千克), 投入 &lt; 0.1 kg (像把吨当成千克)。</li>
 * </ol>
 * 单位不知道 / 不可比时不判 (不猜)。单位一律问 {@link UnitContractService}, 不比字符串。
 */
public final class DataSuspectRules {

    public static final BigDecimal GRAM_AS_KG_THRESHOLD_KG = new BigDecimal("5000");
    public static final BigDecimal TON_AS_KG_THRESHOLD_KG = new BigDecimal("0.1");
    public static final String WHO_TO_CHECK = "车间（核对称重录入）";

    private DataSuspectRules() {
    }

    public static List<String> reasonsOf(ProductionReport r, UnitContractService units) {
        List<String> out = new ArrayList<>();
        String factoryId = r.getFactoryId();
        BigDecimal in = r.getInputQuantity();
        BigDecimal good = r.getOutputQuantity();
        boolean noUnits = blank(r.getInputUnit()) && blank(r.getOutputUnit());
        BigDecimal inKg = toKg(units, factoryId, in, r.getInputUnit());
        BigDecimal goodKg = toKg(units, factoryId, good, r.getOutputUnit());

        if (in != null && in.signum() > 0 && good != null) {
            BigDecimal ratio = null;
            if (inKg != null && goodKg != null) {
                ratio = goodKg.divide(inKg, 6, RoundingMode.HALF_UP);
            } else if (noUnits || (!blank(r.getInputUnit()) && !blank(r.getOutputUnit())
                    && units.areEquivalent(factoryId, r.getInputUnit(), r.getOutputUnit()))) {
                ratio = good.divide(in, 6, RoundingMode.HALF_UP);
            }
            if (ratio != null && ratio.compareTo(BigDecimal.ONE) >= 0) {
                out.add("出成率 " + ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()
                        + "%，达到或超过 100%");
            }
        }

        if (sameStartEnd(r)) out.add("开始和结束时间相同");

        if ((inKg != null && inKg.compareTo(GRAM_AS_KG_THRESHOLD_KG) >= 0)
                || (goodKg != null && goodKg.compareTo(GRAM_AS_KG_THRESHOLD_KG) >= 0)) {
            out.add("重量 ≥ 5000 kg，像是把克当成了千克");
        } else if (inKg != null && inKg.signum() > 0 && inKg.compareTo(TON_AS_KG_THRESHOLD_KG) < 0) {
            out.add("投入不到 0.1 kg，像是把吨当成了千克");
        }
        return out;
    }

    private static boolean sameStartEnd(ProductionReport r) {
        if (r.getProductionStartTime() != null && r.getProductionStartTime().equals(r.getProductionEndTime())) {
            return true;
        }
        List<Map<String, Object>> segs = r.getLaborSegments();
        if (segs == null) return false;
        for (Map<String, Object> s : segs) {
            if (s == null) continue;
            Object a = s.get("startTime");
            Object b = s.get("endTime");
            if (a != null && b != null && !a.toString().isBlank() && a.toString().trim().equals(b.toString().trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 质量单位换成 kg (契约里质量的基准单位是 g); 不是质量单位 / 认不出 = null。 */
    static BigDecimal toKg(UnitContractService units, String factoryId, BigDecimal v, String unit) {
        if (v == null || blank(unit)) return null;
        CanonicalUnit u = units.describe(factoryId, unit).orElse(null);
        if (u == null || u.dimension() != UnitDimension.MASS || u.factorToBase() == null) return null;
        BigDecimal grams = v.multiply(u.factorToBase());
        return "g".equals(u.baseCode()) ? grams.movePointLeft(3) : null;
    }
}
