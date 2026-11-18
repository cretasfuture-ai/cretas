package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通用: 用户没说时间时, 预处理补出的日期不能被当成「你说的是…」(修前 C/D 句子出现
 * 「你说的是 2026-11-18 至 2026-11-18」「这个查询不能按时间筛选」)。
 */
class TimeSaidContractTest {

    @Test
    void sentencesWithoutTimeAreNotTreatedAsTimed() {
        assertThat(TimeRangeFilter.mentionsTime("哪些订单可能交不了货？缺什么料？")).isFalse();
        assertThat(TimeRangeFilter.mentionsTime("哪些原料库存快不够了？有没有快过期的？")).isFalse();
        assertThat(TimeRangeFilter.mentionsTime("哪些原料需要补货？推荐哪个供应商？")).isFalse();
        assertThat(TimeRangeFilter.mentionsTime("客户收入趋势")).isFalse();
    }

    @Test
    void sentencesWithTimeStillCarryIt() {
        // 阴性对照: 真说了时间的不能被吞掉
        assertThat(TimeRangeFilter.mentionsTime("本月销售订单按客户汇总，哪个客户下单最多？")).isTrue();
        assertThat(TimeRangeFilter.mentionsTime("最近有哪些销售订单？按客户汇总一下")).isTrue();
        assertThat(TimeRangeFilter.mentionsTime("上个月的库存")).isTrue();
        assertThat(TimeRangeFilter.mentionsTime("11月5号的订单")).isTrue();
        assertThat(TimeRangeFilter.mentionsTime("今天有什么要收的货")).isTrue();
    }

    @Test
    void nullIsNotTimed() {
        assertThat(TimeRangeFilter.mentionsTime(null)).isFalse();
    }
}
