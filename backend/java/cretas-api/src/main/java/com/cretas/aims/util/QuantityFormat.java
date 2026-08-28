package com.cretas.aims.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 数量在**面向人的文案**里怎么写。
 *
 * <h3>为什么有这个类（2026-08-29 逐屏走查）</h3>
 *
 * 仓里当时有 <b>8 份</b> 私有 {@code stripZeros}，而且已经漂了：
 * <ul>
 *   <li>6 份 {@code BigDecimal} 版：null 返回 {@code "0"}，不舍入</li>
 *   <li>1 份 {@code double} 版（StockAlertWorkdeskTool）：舍入到 2 位</li>
 *   <li>1 份是这一轮走查里我自己加的：null 返回 {@code "-"}，<b>不舍入</b>
 *       —— 于是低库存预警在屏上写成 {@code 1475.4333kg}</li>
 * </ul>
 *
 * ▎同一个东西有八份，它一定会漂 —— 这里就是漂完的样子。
 *
 * <h3>两种 null 策略是**故意**留着的，但必须写在方法名上</h3>
 *
 * {@link #orDash} 和 {@link #orZero} 的区别不是风格，是语义：
 * <ul>
 *   <li>{@code orDash} —— 「这个数我不知道」。⛔ 默认选它。</li>
 *   <li>{@code orZero} —— 只在 <b>0 是这个量的合法读数</b> 时才用（例如计数）。</li>
 * </ul>
 *
 * ⚠️ 把「我不知道」写成 {@code 0} 会被下游当成一个真实读数往下传 ——
 * 本仓 {@code COALESCE(food_cost, 0)} 把「没有成本卡」算成「零成本」、
 * 毛利率报到 88.3% 就是这么来的。
 */
public final class QuantityFormat {

    /** 面向人的数量最多留 2 位小数 —— 再多的位数是采集精度，不是给人看的。 */
    private static final int DISPLAY_SCALE = 2;

    private QuantityFormat() {
    }

    /** 数量文案：拿不到就写 {@code -}，⛔ 不要写 0。 */
    public static String orDash(BigDecimal v) {
        return v == null ? "-" : plain(v);
    }

    /** 只在 0 是合法读数时用（计数类）。其余场合用 {@link #orDash}。 */
    public static String orZero(BigDecimal v) {
        return v == null ? "0" : plain(v);
    }

    /** {@code double} 版 —— 原始类型表达不了「没有值」，所以只有 orZero 这一种。 */
    public static String orZero(double v) {
        return plain(BigDecimal.valueOf(v));
    }

    private static String plain(BigDecimal v) {
        BigDecimal rounded = v.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
        BigDecimal stripped = rounded.stripTrailingZeros();
        // ⚠️ stripTrailingZeros 会把 100 变成 1E+2 —— toPlainString 才是给人看的。
        return stripped.toPlainString();
    }
}
