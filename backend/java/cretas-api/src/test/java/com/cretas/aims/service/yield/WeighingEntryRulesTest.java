package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 迁移 P5 R36 (时间不晚于现在 5 分钟) 与皮重校验。「现在」= 北京时间 2026-11-14 10:00。 */
class WeighingEntryRulesTest {

    private static final Clock NOW = Clock.fixed(
            ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, WeighingEntryRules.FACTORY_ZONE).toInstant(),
            WeighingEntryRules.FACTORY_ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    private static List<YieldReportRequest.LaborSegment> seg(String start, String end) {
        YieldReportRequest.LaborSegment s = new YieldReportRequest.LaborSegment();
        s.setStartTime(start);
        s.setEndTime(end);
        return List.of(s);
    }

    @Test
    @DisplayName("阴性 R36: 结束时间晚于现在 6 分钟 ⇒ 400「结束时间晚于现在」, 指向 endTime 字段")
    void endSixMinutesLaterIsRejected() {
        assertThatThrownBy(() -> WeighingEntryRules.assertNotLaterThanNow(TODAY, seg("09:00", "10:06"), NOW))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo(400);
                    org.assertj.core.api.Assertions.assertThat(e.getMessage()).isEqualTo("结束时间晚于现在（超过 5 分钟）");
                    org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo("WEIGHING_TIME_IN_FUTURE");
                    org.assertj.core.api.Assertions.assertThat(e.getHintTarget()).isEqualTo("laborSegments[0].endTime");
                });
    }

    @Test
    @DisplayName("阴性 R36: 开始时间在未来 ⇒ 拦开始; businessDate 为空按今天")
    void startInFutureIsRejectedWithNullBusinessDate() {
        assertThatThrownBy(() -> WeighingEntryRules.assertNotLaterThanNow(null, seg("10:30", "10:40"), NOW))
                .hasMessage("开始时间晚于现在（超过 5 分钟）");
    }

    @Test
    @DisplayName("阴性 R36: 报工日期是明天 ⇒ 即使钟点早也算未来")
    void tomorrowIsFuture() {
        assertThatThrownBy(() -> WeighingEntryRules.assertNotLaterThanNow(TODAY.plusDays(1), seg("08:00", "09:00"), NOW))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("阳性 R36: 晚于现在正好 5 分钟以内放行; 昨天跨零点的时段放行; 没有时段不判")
    void withinToleranceAndPastAreAllowed() {
        assertThatCode(() -> WeighingEntryRules.assertNotLaterThanNow(TODAY, seg("09:00", "10:05"), NOW)).doesNotThrowAnyException();
        assertThatCode(() -> WeighingEntryRules.assertNotLaterThanNow(TODAY.minusDays(1), seg("22:00", "02:00"), NOW)).doesNotThrowAnyException();
        assertThatCode(() -> WeighingEntryRules.assertNotLaterThanNow(TODAY, null, NOW)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("阴性 R36: 今天跨零点 (22:00 → 次日 02:00) 结束在未来 ⇒ 拦")
    void overnightEndingTomorrowIsFuture() {
        assertThatThrownBy(() -> WeighingEntryRules.assertNotLaterThanNow(TODAY, seg("22:00", "02:00"), NOW))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("皮重: 负数 400; 净重为负 (皮重大于毛重) 400; 0 与空放行")
    void tare() {
        assertThatThrownBy(() -> WeighingEntryRules.assertTare(new BigDecimal("-0.5"), BigDecimal.TEN))
                .hasMessage("皮重不能是负数");
        assertThatThrownBy(() -> WeighingEntryRules.assertTare(new BigDecimal("2"), new BigDecimal("-1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getHintTarget()).isEqualTo("tareWeight"));
        assertThatCode(() -> WeighingEntryRules.assertTare(BigDecimal.ZERO, BigDecimal.TEN)).doesNotThrowAnyException();
        assertThatCode(() -> WeighingEntryRules.assertTare(null, new BigDecimal("-1"))).doesNotThrowAnyException();
    }
}
