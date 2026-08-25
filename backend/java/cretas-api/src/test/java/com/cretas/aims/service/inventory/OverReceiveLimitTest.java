package com.cretas.aims.service.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OverReceiveLimit} —— 「不限制」这个概念怎么表达，以及优先级梯子。
 *
 * <p>这个类存在的全部理由是：<b>让「没有限制」和「限制是某个数」在读的时候分得开</b>。
 * 之前它们被同一个 {@code 0.00} 混为一谈，于是生产上「没人配过」被执行成
 * 「一件都不许多收」。本文件的每条断言都在守这个区分。
 */
@DisplayName("OverReceiveLimit: 不限制 vs 限制到某个比率")
class OverReceiveLimitTest {

    @Test
    @DisplayName("不限制: rate=null / label=不限制 / maxAllowed=null")
    void unlimited_isNotANumber() {
        OverReceiveLimit limit = OverReceiveLimit.unlimited(OverReceiveLimit.SOURCE_UNCONFIGURED);

        assertTrue(limit.isUnlimited());
        assertNull(limit.rate());
        assertEquals("不限制", limit.label());
        assertNull(limit.maxAllowed(new BigDecimal("100")),
                "不限制时不该算出任何上限数字 —— 算出来就等于凭空造了一个限制");
    }

    @Test
    @DisplayName("⛔ 不限制时问百分比要抛, 不许静默返回 0% (那会让文案说反话)")
    void unlimited_hasNoPercentLabel() {
        OverReceiveLimit limit = OverReceiveLimit.unlimited(OverReceiveLimit.SOURCE_UNCONFIGURED);
        assertThrows(IllegalStateException.class, limit::percentLabel);
    }

    @Test
    @DisplayName("⭐ 0% 是一条真实限制, 与「不限制」不是同一件事")
    void zeroIsALimitNotUnlimited() {
        OverReceiveLimit zero = OverReceiveLimit.of(BigDecimal.ZERO, OverReceiveLimit.SOURCE_PROPERTY);

        assertFalse(zero.isUnlimited(), "0 必须读成「一件都不许多收」, 不能读成「不设限」");
        assertEquals("0%", zero.label());
        assertEquals(0, new BigDecimal("100").compareTo(zero.maxAllowed(new BigDecimal("100"))),
                "0% 的上限就是下单量本身");
    }

    @Test
    @DisplayName("30% → 上限 = 下单量 × 1.30, label 不留尾零")
    void ratioComputesMaxAllowed() {
        OverReceiveLimit limit = OverReceiveLimit.of(new BigDecimal("0.30"), OverReceiveLimit.SOURCE_PROPERTY);

        assertEquals(0, new BigDecimal("130.00").compareTo(limit.maxAllowed(new BigDecimal("100"))));
        assertEquals("30%", limit.label());
        assertEquals("30%", limit.percentLabel());
    }

    @Test
    @DisplayName("小数比率也要有像样的措辞: 0.125 → 12.5%")
    void fractionalRatioLabel() {
        assertEquals("12.5%",
                OverReceiveLimit.of(new BigDecimal("0.125"), OverReceiveLimit.SOURCE_PROPERTY).label());
    }

    @Test
    @DisplayName("⛔ 负数当场抛 —— 不给「负数=无限」这套第二哨兵语义留口子")
    void negativeRateRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OverReceiveLimit.of(new BigDecimal("-1"), OverReceiveLimit.SOURCE_PROPERTY));
        assertTrue(ex.getMessage().contains("unlimited"),
                "报错要指路到正确表达方式, 实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("⛔ 没有来源标记不许构造 —— 没来源就分不清值是配的还是兜底的")
    void sourceIsMandatory() {
        assertThrows(IllegalArgumentException.class,
                () -> new OverReceiveLimit(new BigDecimal("0.30"), "  "));
        assertThrows(IllegalArgumentException.class,
                () -> new OverReceiveLimit(null, null));
    }

    // ─────────────────── 梯子: 工厂级 > 属性 > 不限制 ───────────────────

    @Test
    @DisplayName("⭐ 两边都没配 → 不限制 (owner 默认) —— 变异靶子")
    void resolve_nothingConfigured_isUnlimited() {
        OverReceiveLimit limit = OverReceiveLimit.resolve(Optional.empty(), null);

        assertTrue(limit.isUnlimited(),
                "把这里改成 of(ZERO,...) 就是把缺陷装回去 —— 生产上一件都不能多收");
        assertEquals(OverReceiveLimit.SOURCE_UNCONFIGURED, limit.source());
    }

    @Test
    @DisplayName("只有属性 → 用属性, 来源标记指向属性")
    void resolve_propertyOnly() {
        OverReceiveLimit limit = OverReceiveLimit.resolve(Optional.empty(), new BigDecimal("0.20"));

        assertEquals(OverReceiveLimit.SOURCE_PROPERTY, limit.source());
        assertEquals(0, new BigDecimal("0.20").compareTo(limit.rate()));
    }

    @Test
    @DisplayName("工厂级压过属性")
    void resolve_factoryWins() {
        OverReceiveLimit limit = OverReceiveLimit.resolve(
                Optional.of(new BigDecimal("0.05")), new BigDecimal("0.20"));

        assertEquals(OverReceiveLimit.SOURCE_FACTORY_SETTINGS, limit.source());
        assertEquals(0, new BigDecimal("0.05").compareTo(limit.rate()));
    }

    @Test
    @DisplayName("工厂级显式配 0 也压过属性 —— 0 是配置不是缺席")
    void resolve_factoryZeroStillWins() {
        OverReceiveLimit limit = OverReceiveLimit.resolve(
                Optional.of(BigDecimal.ZERO), new BigDecimal("0.20"));

        assertEquals(OverReceiveLimit.SOURCE_FACTORY_SETTINGS, limit.source());
        assertFalse(limit.isUnlimited());
        assertEquals(0, BigDecimal.ZERO.compareTo(limit.rate()));
    }
}
