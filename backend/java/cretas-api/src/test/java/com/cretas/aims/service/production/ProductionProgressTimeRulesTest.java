package com.cretas.aims.service.production;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionProgressTimeRulesTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 10);
    private static final LocalDate DUE = LocalDate.of(2026, 11, 20);

    @Test
    void timeProgressIsLinearClampedAndNullWithoutDates() {
        assertThat(ProductionProgressDashboardService.timeProgressPct(START, DUE, LocalDate.of(2026, 11, 15))).isEqualTo(50);
        assertThat(ProductionProgressDashboardService.timeProgressPct(START, DUE, LocalDate.of(2026, 11, 1))).isZero();
        assertThat(ProductionProgressDashboardService.timeProgressPct(START, DUE, LocalDate.of(2026, 12, 1))).isEqualTo(100);
        assertThat(ProductionProgressDashboardService.timeProgressPct(null, DUE, START)).isNull();
        assertThat(ProductionProgressDashboardService.timeProgressPct(START, null, START)).isNull();
    }

    @Test
    void sameDayWindowIsZeroBeforeAndFullOnDueDay() {
        assertThat(ProductionProgressDashboardService.timeProgressPct(START, START, START.minusDays(1))).isZero();
        assertThat(ProductionProgressDashboardService.timeProgressPct(START, START, START)).isEqualTo(100);
    }

    @Test
    void overdueNeedsDueDatePastAndNotDone() {
        assertThat(ProductionProgressDashboardService.isOverdue(DUE, DUE.plusDays(1), false)).isTrue();
        assertThat(ProductionProgressDashboardService.isOverdue(DUE, DUE, false)).isFalse();      // 交期当天不算超期
        assertThat(ProductionProgressDashboardService.isOverdue(DUE, DUE.plusDays(5), true)).isFalse();
        assertThat(ProductionProgressDashboardService.isOverdue(null, DUE, false)).isFalse();     // 不知道交期 ≠ 超期
    }

    @Test
    void behindNeedsTimeProgressAndLowerQuantityProgress() {
        assertThat(ProductionProgressDashboardService.isBehind(60, 40, false)).isTrue();
        assertThat(ProductionProgressDashboardService.isBehind(60, 60, false)).isFalse();
        assertThat(ProductionProgressDashboardService.isBehind(60, 10, true)).isFalse();
        assertThat(ProductionProgressDashboardService.isBehind(null, 0, false)).isFalse();
    }
}
