package com.cretas.aims.service.inventory;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * 采购收货【超收上限】的取值 —— 执行侧与展示侧共用的唯一表示。
 *
 * <p>它只有两种形态，没有第三种：
 * <ul>
 *   <li><b>不限制</b> —— {@code rate == null}。收多少都放行，不做上限校验。</li>
 *   <li><b>限制到 rate</b> —— 累计可收上限 = 下单量 × (1 + rate)。
 *       ⚠️ {@code rate = 0} 是一个<b>真实存在的限制</b>（一件都不许多收），
 *       ⛔ 它不等于"不限制"。这两者过去被同一个 {@code 0.00} 默认值混为一谈，
 *       正是 2026-08-25 这个缺陷的根：展示侧告诉仓管员"还能收 30%"，
 *       执行侧因为"没人配过"而按 0% 拦，货堵在门口。</li>
 * </ul>
 *
 * <h3>为什么"不限制"用 null，而不是负数 / 999 / 一个巨大的数</h3>
 * 判据是这一句：<b>别人读到这个值，看得出来它是"没有限制"还是"限制是 999 倍"吗？</b>
 * <ul>
 *   <li>{@code 999} —— 读不出来。它是一个合法的比率，谁都可以把它理解成 99900% 的上限，
 *       而且它会真的参与乘法算出一个有限的 maxAllowed，异常文案里还会把 "99900%" 打给仓管员。</li>
 *   <li>负数 —— 也读不出来，而且它给"负数还有别的含义"留了第二套哨兵语义的口子。
 *       本类构造时<b>直接拒绝负数</b>，不给这个口子。</li>
 *   <li>{@code null} —— 读不出第二种解释：它不参与任何算术，{@link #maxAllowed} 对它返回
 *       null（"没有上限"），{@link #label()} 对它返回"不限制"。</li>
 * </ul>
 * 而且 null 同时是<b>配置缺席时 Spring / Jackson 天然给出的值</b>。按 owner 2026-08-25 裁定
 * 「先默认允许无限超收」，"没配过"与"不限制"在本规则里<b>本来就是同一件事</b> ——
 * 让它们共用一个表示是准确，不是偷懒。
 *
 * <h3>每个取值都带来源</h3>
 * {@link #source()} 说明这个上限是从哪来的（工厂设置 / 全局属性 / 没人配过）。
 * 没有来源标记时，"两次读数相等"分不清是真的相等还是它们本来就是同一个兜底值。
 *
 * @since 2026-08-25
 */
public record OverReceiveLimit(BigDecimal rate, String source) {

    /** 谁也没配过 → 不限制（owner 默认裁定）。 */
    public static final String SOURCE_UNCONFIGURED = "unconfigured";

    /** 全局属性 {@code cretas.purchase.over-receive-rate}（ops 兜底，全工厂生效）。 */
    public static final String SOURCE_PROPERTY = "property:cretas.purchase.over-receive-rate";

    /** 工厂级 {@code inventorySettings.purchaseOverReceiveRate}（优先级最高）。 */
    public static final String SOURCE_FACTORY_SETTINGS =
            "factory-settings:inventorySettings.purchaseOverReceiveRate";

    public OverReceiveLimit {
        if (rate != null && rate.signum() < 0) {
            throw new IllegalArgumentException("超收上限比率不能为负: " + rate.toPlainString()
                    + " —— 要表达「不限制」请用 OverReceiveLimit.unlimited(source), "
                    + "⛔ 不要拿负数当哨兵");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(
                    "超收上限必须带来源标记 —— 没有来源就分不清这个值是配出来的还是兜底出来的");
        }
    }

    /** 不限制超收。 */
    public static OverReceiveLimit unlimited(String source) {
        return new OverReceiveLimit(null, source);
    }

    /** 限制到 {@code rate}（{@code 0} 合法，表示一件都不许多收）。 */
    public static OverReceiveLimit of(BigDecimal rate, String source) {
        return new OverReceiveLimit(
                Objects.requireNonNull(rate, "rate 为 null 请显式调 unlimited(source)"), source);
    }

    /**
     * <b>唯一的解析梯子</b>：工厂级 &gt; 全局属性 &gt; 不限制。
     *
     * <p>⛔ 不要在别处再写一遍这个优先级 —— 同一个梯子有两份，它一定会漂。
     * 执行侧（{@code PurchaseServiceImpl}）和任何展示侧都必须调这一个方法。
     *
     * @param factoryConfiguredRate 工厂级显式配置；{@code Optional.empty()} = 该工厂没配
     * @param globalPropertyRate    全局属性值；{@code null} = 属性没设（这正是当前生产的状态）
     */
    public static OverReceiveLimit resolve(Optional<BigDecimal> factoryConfiguredRate,
            BigDecimal globalPropertyRate) {
        if (factoryConfiguredRate != null && factoryConfiguredRate.isPresent()) {
            return of(factoryConfiguredRate.get(), SOURCE_FACTORY_SETTINGS);
        }
        if (globalPropertyRate != null) {
            return of(globalPropertyRate, SOURCE_PROPERTY);
        }
        return unlimited(SOURCE_UNCONFIGURED);
    }

    public boolean isUnlimited() {
        return rate == null;
    }

    /**
     * 累计可收上限 = {@code orderedQty × (1 + rate)}。
     *
     * @return 不限制时返回 {@code null}（这里的 null 同样只能读成"没有上限"，
     *         ⛔ 调用方不许把它兜底成 0 —— 那会把"不限制"翻译成"一件都不许收"）
     */
    public BigDecimal maxAllowed(BigDecimal orderedQty) {
        if (isUnlimited() || orderedQty == null) {
            return null;
        }
        return orderedQty.multiply(BigDecimal.ONE.add(rate));
    }

    /** 面向用户的措辞：{@code "不限制"} / {@code "30%"} / {@code "12.5%"}。 */
    public String label() {
        return isUnlimited() ? "不限制" : percentLabel();
    }

    /**
     * 百分比措辞，如 {@code "30%"}（不留尾零）。
     *
     * @throws IllegalStateException 不限制时调用 —— 不限制根本没有百分比可言，
     *         静默返回 "0%" 会让文案说反话
     */
    public String percentLabel() {
        if (isUnlimited()) {
            throw new IllegalStateException("不限制超收时没有百分比可言, 请改用 label()");
        }
        return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }
}
