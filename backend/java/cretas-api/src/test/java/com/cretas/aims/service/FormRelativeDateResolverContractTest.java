package com.cretas.aims.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 录入相对日期换算。2026-11-18（周三）实测：销售「下周五」模型给 11-26（周四），
 * 采购「下周三」给 12-01（周二）——两个入口共用同一函数，必须是确定性的。
 */
class FormRelativeDateResolverContractTest {

    /** 2026-11-18 周三。 */
    private static final LocalDate WED = LocalDate.of(2026, 11, 18);

    private static Optional<LocalDate> d(String text, LocalDate today) {
        return FormRelativeDateResolver.resolveDelivery(text, today);
    }

    @Test
    @DisplayName("销售原话：下周五交货 → 2026-11-27（不是 11-26）")
    void salesUtterance() {
        assertThat(d("给演示·上海连锁餐饮创建100盒演示·酱卤牛肉250g的订单，下周五交货", WED))
                .contains(LocalDate.of(2026, 11, 27));
    }

    @Test
    @DisplayName("采购原话：下周三交货 → 2026-11-25（不是 12-01）")
    void purchaseUtterance() {
        assertThat(d("采购50kg演示·藤椒调味粉，下周三交货", WED)).contains(LocalDate.of(2026, 11, 25));
    }

    @Test
    @DisplayName("本周X / 这周X / 下周X / 下下周X")
    void weekForms() {
        assertThat(d("本周五送到", WED)).contains(LocalDate.of(2026, 11, 20));
        assertThat(d("这周一到货", WED)).contains(LocalDate.of(2026, 11, 16)); // 已过去也按字面本周
        assertThat(d("下周一交货", WED)).contains(LocalDate.of(2026, 11, 23));
        assertThat(d("下个星期四交货", WED)).contains(LocalDate.of(2026, 11, 26));
        assertThat(d("下下周三交货", WED)).contains(LocalDate.of(2026, 12, 2));
    }

    @Test
    @DisplayName("单说周X：未过=本周，已过=下周")
    void bareWeekday() {
        assertThat(d("周五交货", WED)).contains(LocalDate.of(2026, 11, 20));
        assertThat(d("周二交货", WED)).contains(LocalDate.of(2026, 11, 24));
        assertThat(d("周三交货", WED)).contains(LocalDate.of(2026, 11, 18));
    }

    @Test
    @DisplayName("周日：周一起始，本周日=本周末，下周日=下周末；今天是周日时下周一是明天")
    void sundayHandling() {
        assertThat(d("本周日交货", WED)).contains(LocalDate.of(2026, 11, 22));
        assertThat(d("下周日交货", WED)).contains(LocalDate.of(2026, 11, 29));
        assertThat(d("下周天交货", WED)).contains(LocalDate.of(2026, 11, 29));
        LocalDate sunday = LocalDate.of(2026, 11, 22);
        assertThat(d("下周一交货", sunday)).contains(LocalDate.of(2026, 11, 23));
        assertThat(d("本周日交货", sunday)).contains(sunday);
        assertThat(d("下周五交货", sunday)).contains(LocalDate.of(2026, 11, 27));
    }

    @Test
    @DisplayName("跨月/跨年：下周X 落在下个月、下一年")
    void crossMonthAndYear() {
        LocalDate monthEnd = LocalDate.of(2026, 11, 26); // 周四
        assertThat(d("下周三交货", monthEnd)).contains(LocalDate.of(2026, 12, 2));
        assertThat(d("下下周五交货", monthEnd)).contains(LocalDate.of(2026, 12, 11));
        LocalDate yearEnd = LocalDate.of(2026, 12, 30); // 周三
        assertThat(d("下周五交货", yearEnd)).contains(LocalDate.of(2027, 1, 8));
        assertThat(d("1月5日交货", yearEnd)).contains(LocalDate.of(2027, 1, 5));
    }

    @Test
    @DisplayName("明天/后天/N天后/下个月N号")
    void dayOffsets() {
        assertThat(d("明天交货", WED)).contains(LocalDate.of(2026, 11, 19));
        assertThat(d("后天交货", WED)).contains(LocalDate.of(2026, 11, 20));
        assertThat(d("3天后交货", WED)).contains(LocalDate.of(2026, 11, 21));
        assertThat(d("十天后交货", WED)).contains(LocalDate.of(2026, 11, 28));
        assertThat(d("下个月5号交货", WED)).contains(LocalDate.of(2026, 12, 5));
        assertThat(d("11月30日交货", WED)).contains(LocalDate.of(2026, 11, 30));
        assertThat(d("2月30日交货", WED)).isEmpty();
    }

    @Test
    @DisplayName("多个日期取紧贴交货字样的；认不准不猜")
    void multipleDates() {
        assertThat(d("明天下单，下周五交货", WED)).contains(LocalDate.of(2026, 11, 27));
        assertThat(d("明天 下周五", WED)).isEmpty();
        assertThat(d("没有日期的一句话", WED)).isEmpty();
        assertThat(d("上周五交过一次", WED)).isEmpty();
    }

    @Test
    @DisplayName("prompt 日历对照表：周三 11-18 的下周五=11-27，且是确定性数据")
    void calendarTable() {
        String cal = FormRelativeDateResolver.calendarForPrompt(WED);
        assertThat(cal).contains("今天是周三").contains("下周: 周一=2026-11-23")
                .contains("周五=2026-11-27").contains("下下周: 周一=2026-11-30");
    }
}
