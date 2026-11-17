package com.cretas.aims.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-17 F006「上个月毛利率多少？」: 工具按显式日期查了 10-01~10-31, period 却是默认 month,
 * 标题写成「本月 (10-01 ~ 10-31)」。「本月 / 本周 …」只在窗口截到今天时成立。
 */
@DisplayName("报表标题: 过去的一段不叫「本月」")
class ReportPeriodLabelContractTest {

    @Test
    @DisplayName("上个月 (窗口在今天之前结束) → 只写日期, 不写「本月」")
    void pastWindowIsNotCalledThisMonth() {
        LocalDate firstOfThisMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate start = firstOfThisMonth.minusMonths(1);
        LocalDate end = firstOfThisMonth.minusDays(1);
        String label = ResultFormatterServiceImpl.buildPeriodLabel("month", start.toString(), end.toString());
        assertThat(label).doesNotContain("本月").isEqualTo(start + " ~ " + end);
    }

    @Test
    @DisplayName("阳性对照: 截到今天的窗口照旧叫「本月」")
    void windowEndingTodayKeepsRelativeName() {
        LocalDate today = LocalDate.now();
        String label = ResultFormatterServiceImpl.buildPeriodLabel("month", today.minusMonths(1).toString(), today.toString());
        assertThat(label).startsWith("本月 (");
    }
}
