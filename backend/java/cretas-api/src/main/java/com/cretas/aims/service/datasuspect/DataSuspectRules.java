package com.cretas.aims.service.datasuspect;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 迁移 P5 R30 / R34: 明显的录入错误标「数据疑点 · 找谁核」—— <b>只标不改原值, 也不当业务异常</b>。
 *
 * <p>规则 (与原型 {@code a-api.cjs suspectsOf} 同口径, 换成正式端的报工字段):</p>
 * <ol>
 *   <li>出成率 ≥ 100%: 良品 (outputQuantity, 已是净重) ÷ 投入 ≥ 1, 只在两边单位可比时判
 *       (单位契约判等价, 或都是质量单位换算成 kg; 两边都没写单位视为同一单位)。</li>
 *   <li>起止时间相同: 任一工时段开始 = 结束, 或报工单的开始 = 结束。</li>
 *   <li>单位明显不符: 质量单位换成 kg 后投入或良品 ≥ 本厂上限 (默认 5000 kg, 像把克当成千克),
 *       投入 &lt; 本厂下限 (默认 0.1 kg, 像把吨当成千克)。上下限按厂配置 (阈值中心), 见 {@link Thresholds}。</li>
 * </ol>
 *
 * <p><b>判据范围 (审查 #1708 第 3 条)</b>: 规则 1、3 只判<b>一条报工里同时有投入和良品</b>的报工 ——
 * 旧式整合报工与称重页报工 ({@code reportKind} 为空)。三段报工的 INPUT 行没有产出、OUTPUT 行没有投入,
 * 单看一行判出成率没有意义; INPUT 行的少量辅料 (盐、香料 &lt; 0.1 kg) 也不是录错。规则 2 对所有报工都判。</p>
 *
 * 单位不知道 / 不可比时不判 (不猜)。单位一律问 {@link UnitContractService}, 不比字符串;
 * 同一次扫描用 {@link #unitLookup} 只按「不同的单位写法」各问一次 (审查 #1708 第 2 条: 不再每行查单位目录)。
 */
public final class DataSuspectRules {

    public static final BigDecimal DEFAULT_MAX_WEIGHT_KG = new BigDecimal("5000");
    public static final BigDecimal DEFAULT_MIN_INPUT_KG = new BigDecimal("0.1");
    public static final String WHO_TO_CHECK = "车间（核对称重录入）";

    private DataSuspectRules() {
    }

    /** 本厂「单位明显不符」的上下限 (kg)。没配 / 配成 ≤ 0 的按默认。 */
    public record Thresholds(BigDecimal maxWeightKg, BigDecimal minInputKg) {
        public static final Thresholds DEFAULT = new Thresholds(DEFAULT_MAX_WEIGHT_KG, DEFAULT_MIN_INPUT_KG);

        public static Thresholds of(BigDecimal maxWeightKg, BigDecimal minInputKg) {
            return new Thresholds(
                    maxWeightKg == null || maxWeightKg.signum() <= 0 ? DEFAULT_MAX_WEIGHT_KG : maxWeightKg,
                    minInputKg == null || minInputKg.signum() <= 0 ? DEFAULT_MIN_INPUT_KG : minInputKg);
        }
    }

    /** 一次扫描内的单位查询: 同一工厂、同一写法只问单位契约一次。 */
    public interface UnitLookup {
        Optional<CanonicalUnit> describe(String rawUnit);
    }

    /** 按请求记忆的单位查询 (每个不同写法一次 {@code describe}, 等价判断由同一结果推出, 不再另查)。 */
    public static UnitLookup unitLookup(UnitContractService units, String factoryId) {
        Map<String, Optional<CanonicalUnit>> memo = new HashMap<>();
        return raw -> {
            if (blank(raw)) return Optional.empty();
            return memo.computeIfAbsent(raw.trim(), k -> units.describe(factoryId, k));
        };
    }

    /** 单条判 (默认阈值); 批量请用 {@link #reasonsOf(ProductionReport, UnitLookup, Thresholds)} 并复用同一个 lookup。 */
    public static List<String> reasonsOf(ProductionReport r, UnitContractService units) {
        return reasonsOf(r, unitLookup(units, r.getFactoryId()), Thresholds.DEFAULT);
    }

    public static List<String> reasonsOf(ProductionReport r, UnitLookup units, Thresholds t) {
        List<String> out = new ArrayList<>();
        boolean combined = r.getReportKind() == null || r.getReportKind().isBlank();
        BigDecimal in = r.getInputQuantity();
        BigDecimal good = r.getOutputQuantity();
        BigDecimal inKg = combined ? toKg(units, in, r.getInputUnit()) : null;
        BigDecimal goodKg = combined ? toKg(units, good, r.getOutputUnit()) : null;

        if (combined && in != null && in.signum() > 0 && good != null) {
            boolean noUnits = blank(r.getInputUnit()) && blank(r.getOutputUnit());
            BigDecimal ratio = null;
            if (inKg != null && goodKg != null) {
                ratio = goodKg.divide(inKg, 6, RoundingMode.HALF_UP);
            } else if (noUnits || equivalent(units, r.getInputUnit(), r.getOutputUnit())) {
                ratio = good.divide(in, 6, RoundingMode.HALF_UP);
            }
            if (ratio != null && ratio.compareTo(BigDecimal.ONE) >= 0) {
                out.add("出成率 " + ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()
                        + "%，达到或超过 100%");
            }
        }

        if (sameStartEnd(r)) out.add("开始和结束时间相同");

        if (combined) {
            if ((inKg != null && inKg.compareTo(t.maxWeightKg()) >= 0)
                    || (goodKg != null && goodKg.compareTo(t.maxWeightKg()) >= 0)) {
                out.add("重量 ≥ " + plain(t.maxWeightKg()) + " kg，像是把克当成了千克");
            } else if (inKg != null && inKg.signum() > 0 && inKg.compareTo(t.minInputKg()) < 0) {
                out.add("投入不到 " + plain(t.minInputKg()) + " kg，像是把吨当成了千克");
            }
        }
        return out;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static boolean equivalent(UnitLookup units, String a, String b) {
        if (blank(a) || blank(b)) return false;
        String ca = units.describe(a).map(CanonicalUnit::code).orElse(null);
        String cb = units.describe(b).map(CanonicalUnit::code).orElse(null);
        return ca != null && ca.equals(cb);
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
    static BigDecimal toKg(UnitLookup units, BigDecimal v, String unit) {
        if (v == null || blank(unit)) return null;
        CanonicalUnit u = units.describe(unit).orElse(null);
        if (u == null || u.dimension() != UnitDimension.MASS || u.factorToBase() == null) return null;
        BigDecimal grams = v.multiply(u.factorToBase());
        return "g".equals(u.baseCode()) ? grams.movePointLeft(3) : null;
    }
}
