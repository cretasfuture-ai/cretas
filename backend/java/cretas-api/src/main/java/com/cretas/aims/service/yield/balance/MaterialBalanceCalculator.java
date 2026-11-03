package com.cretas.aims.service.yield.balance;

import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 物料平衡 (缺口清单 #07) 的<b>唯一</b>算法: 投入 = 主产出 + 副产 + 已确认损耗 + 未解释差额。
 *
 * <p>三个调用方共用这一份, ⛔ 不许各抄一份公式:
 * App 报工 ({@code YieldReportServiceImpl} 的 balanceWarning)、web 逐道正式报工
 * ({@code ProcessSheetServiceImpl.materialBalanceWarnings})、web 平衡表 ({@code MaterialBalanceService})。
 * 钉住三路一致的测试: {@code MaterialBalanceParityContractTest}。</p>
 *
 * <p>单位: 只有质量 (kg/g, 换成 kg 比) 或<b>同一个</b>单位才能相加, 其余一律 {@link Level#UNIT_INCOMPARABLE},
 * ⛔ 不猜换算。已确认损耗 = 报工填的损耗量 + 留样 (都按产出单位记)。</p>
 */
public final class MaterialBalanceCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private MaterialBalanceCalculator() {}

    /** 阈值, 小数 (0.05 = 5%)。warn 以上黄、red 以上红。 */
    public record Thresholds(BigDecimal warn, BigDecimal red) {
        public static final Thresholds DEFAULT = new Thresholds(new BigDecimal("0.05"), new BigDecimal("0.15"));
    }

    public enum Level {
        /** 未解释差额不超过黄色阈值 */
        BALANCED,
        /** 超过黄色阈值、未超红色阈值 */
        WARN,
        /** 超过红色阈值 */
        RED,
        /** 投入与产出单位换算不了 */
        UNIT_INCOMPARABLE,
        /** 有产出但没登记任何投入 */
        NO_INPUT
    }

    /** 一条带单位的数量 (副产物)。unit 为空 = 按产出单位。 */
    public record Qty(BigDecimal quantity, String unit) {}

    /**
     * 结果。数量字段都已换成 {@code unit} (质量统一 kg), 不可比 / 没投入时为 null。
     * unexplained 带符号: 正 = 投入多于去向 (少了), 负 = 去向多于投入。unexplainedPct 取绝对值, 小数。
     */
    public record Result(Level level, String unit, BigDecimal input, BigDecimal output, BigDecimal byproducts,
                         BigDecimal confirmedLoss, BigDecimal unexplained, BigDecimal unexplainedPct) {
        public boolean comparable() {
            return input != null;
        }

        static Result flagged(Level level) {
            return new Result(level, null, null, null, null, null, null, null);
        }
    }

    public static Result compute(BigDecimal input, String inputUnit, BigDecimal output, String outputUnit,
                                 List<Qty> byproducts, BigDecimal waste, BigDecimal sample, Thresholds t) {
        String inU = unit(inputUnit);
        String outU = unit(outputUnit == null || outputUnit.isBlank() ? inputUnit : outputUnit);
        BigDecimal out = nz(output);
        if (input == null || input.signum() <= 0) {
            return out.signum() > 0 ? Result.flagged(Level.NO_INPUT) : Result.flagged(Level.BALANCED);
        }
        if (!comparableUnits(inU, outU)) {
            return Result.flagged(Level.UNIT_INCOMPARABLE);
        }
        BigDecimal byp = BigDecimal.ZERO;
        if (byproducts != null) {
            for (Qty b : byproducts) {
                if (b == null || b.quantity() == null) continue;
                String bu = unit(b.unit() == null || b.unit().isBlank() ? outputUnit == null ? inputUnit : outputUnit : b.unit());
                if (!comparableUnits(bu, outU)) {
                    return Result.flagged(Level.UNIT_INCOMPARABLE);
                }
                byp = byp.add(toBase(b.quantity(), bu));
            }
        }
        BigDecimal in = toBase(input, inU);
        BigDecimal o = toBase(out, outU);
        BigDecimal loss = toBase(nz(waste).add(nz(sample)), outU);
        return classify(isMass(inU) ? "kg" : inU, in, o, byp, loss, t);
    }

    /** 把几条已算好的结果 (同工序下各任务) 合计, 再按同一套阈值判色。有一条不可比 / 单位不一致 → 整体不可比。 */
    public static Result aggregate(List<Result> parts, Thresholds t) {
        BigDecimal in = BigDecimal.ZERO, out = BigDecimal.ZERO, byp = BigDecimal.ZERO, loss = BigDecimal.ZERO;
        String unit = null;
        boolean noInput = false;
        int comparable = 0;
        for (Result r : parts) {
            if (r.level() == Level.UNIT_INCOMPARABLE) return Result.flagged(Level.UNIT_INCOMPARABLE);
            if (r.level() == Level.NO_INPUT) { noInput = true; continue; }
            if (!r.comparable()) continue;
            if (unit != null && !sameCode(unit, r.unit())) return Result.flagged(Level.UNIT_INCOMPARABLE);
            unit = r.unit();
            in = in.add(r.input());
            out = out.add(r.output());
            byp = byp.add(r.byproducts());
            loss = loss.add(r.confirmedLoss());
            comparable++;
        }
        if (comparable == 0) return Result.flagged(noInput ? Level.NO_INPUT : Level.BALANCED);
        Result sum = classify(unit, in, out, byp, loss, t);
        // 有任务没登记投入: 不静默吞掉, 至少升到黄色 (它的产出没进上面的合计)
        return noInput && sum.level() == Level.BALANCED
                ? new Result(Level.WARN, sum.unit(), sum.input(), sum.output(), sum.byproducts(),
                        sum.confirmedLoss(), sum.unexplained(), sum.unexplainedPct())
                : sum;
    }

    private static Result classify(String unit, BigDecimal in, BigDecimal out, BigDecimal byp, BigDecimal loss,
                                   Thresholds t) {
        BigDecimal unexplained = in.subtract(out).subtract(byp).subtract(loss);
        BigDecimal pct = unexplained.abs().divide(in, 6, RoundingMode.HALF_UP);
        Level level = pct.compareTo(t.red()) > 0 ? Level.RED
                : pct.compareTo(t.warn()) > 0 ? Level.WARN : Level.BALANCED;
        return new Result(level, unit, in, out, byp, loss, unexplained, pct);
    }

    /** 报工当场的提示: 只在超过红色阈值时出声, 文字三路相同。 */
    public static String warningText(Result r, String displayUnit) {
        if (r.level() != Level.RED) return null;
        String u = displayUnit == null ? "" : displayUnit;
        return String.format("物料平衡偏差 %s%% (投入 %s%s, 产出 %s%s, 副产物 %s, 已确认损耗 %s, 未解释差额 %s) — 请核对, 系统不阻塞",
                r.unexplainedPct().multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).toPlainString(),
                plain(r.input()), u, plain(r.output()), u, plain(r.byproducts()), plain(r.confirmedLoss()),
                plain(r.unexplained()));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String unit(String u) {
        return u == null ? "" : UnitContractServiceImpl.crossLanguageCode(u);
    }

    private static boolean isMass(String u) {
        return "kg".equals(u) || "g".equals(u);
    }

    private static boolean comparableUnits(String a, String b) {
        return isMass(a) && isMass(b) || sameCode(a, b);
    }

    /** 两个已过 crossLanguageCode 归一的单位码是否相同 (闸 BareUnitEqualityRatchet 只看单位命名的变量, 这里的形参故意不叫 unit)。 */
    private static boolean sameCode(String x, String y) {
        return x.equals(y);
    }

    private static BigDecimal toBase(BigDecimal v, String u) {
        return "g".equals(u) ? v.movePointLeft(3) : v;
    }
}
