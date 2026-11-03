package com.cretas.aims.service.foodsafety.coldchain;

import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Excursion;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Exposure;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.LocationSpan;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Reading;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Segment;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Segments;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Stay;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 缺口 15 纯计算: 超温段 / 库位段 / 批次暴露 (清单场景 02:00–05:00)。 */
@DisplayName("温度关联批次 — 纯计算")
class ColdChainExposureCalculatorContractTest {

    private static final BigDecimal MIN = new BigDecimal("0");
    private static final BigDecimal MAX = new BigDecimal("8");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    private static LocalDateTime t(int h, int m) {
        return DAY.atTime(h, m);
    }

    private static List<Reading> readings() {
        List<Reading> out = new ArrayList<>();
        for (LocalDateTime x = t(0, 0); !x.isAfter(t(7, 0)); x = x.plusMinutes(5)) {
            boolean hot = !x.isBefore(t(2, 0)) && !x.isAfter(t(5, 0));
            out.add(new Reading(x, new BigDecimal(hot ? "12" : "4")));
        }
        return out;
    }

    private static final List<Segment> COLD = List.of(new Segment("COLD", t(2, 0), t(5, 0)));

    @Test
    @DisplayName("超温段 = 首个到最后一个超温读数; 锚点在段中间也能扩到两端")
    void excursionFromReadings() {
        Excursion ex = ColdChainExposureCalculator.excursionAround(readings(), MIN, MAX, t(2, 15)).orElseThrow();
        assertThat(ex.start()).isEqualTo(t(2, 0));
        assertThat(ex.end()).isEqualTo(t(5, 0));
        assertThat(ex.minutes()).isEqualTo(180);
        assertThat(ex.ongoing()).isFalse();
        // 阴性对照: 锚点在超温之前 → 找不到, 不拿后面的超温段凑数
        assertThat(ColdChainExposureCalculator.excursionAround(readings(), MIN, MAX, t(1, 0))).isEmpty();
    }

    @Test
    @DisplayName("清单场景: B 全程在库 180 / C 04:30 入库 30 / A 已出库 0 / D 事后入库 0")
    void listedScenario() {
        Exposure b = ColdChainExposureCalculator.expose(Stay.exact(t(0, 0).minusDays(1), null), COLD);
        Exposure c = ColdChainExposureCalculator.expose(Stay.exact(t(4, 30), null), COLD);
        Exposure a = ColdChainExposureCalculator.expose(Stay.exact(t(0, 0).minusDays(1), t(1, 0)), COLD);
        Exposure d = ColdChainExposureCalculator.expose(Stay.exact(t(6, 0), null), COLD);

        assertThat(b.verdict()).isEqualTo(Verdict.AFFECTED);
        assertThat(b.minutes()).isEqualTo(180L);
        assertThat(c.minutes()).isEqualTo(30L);
        assertThat(c.firstExposedAt()).isEqualTo(t(4, 30));
        assertThat(a.verdict()).isEqualTo(Verdict.NOT_AFFECTED);
        assertThat(d.verdict()).isEqualTo(Verdict.NOT_AFFECTED);
    }

    @Test
    @DisplayName("在库时刻只知道范围: 范围跨进超温段 ⇒ 不确定 (给出最多多少); 范围在段外 ⇒ 仍是确定值")
    void uncertainBounds() {
        // 出库时刻 ∈ [01:00, 06:00]: 可能 0, 可能 180
        Exposure u = ColdChainExposureCalculator.expose(
                new Stay(t(0, 0).minusDays(1), t(0, 0).minusDays(1), t(1, 0), t(6, 0), "没有找到出库流水"), COLD);
        assertThat(u.verdict()).isEqualTo(Verdict.UNCERTAIN);
        assertThat(u.minutes()).isNull();
        assertThat(u.maxMinutes()).isEqualTo(180);
        assertThat(u.reason()).isEqualTo("没有找到出库流水");

        // 入库时刻 ∈ [前一天 00:00, 前一天 23:59] 全在超温之前, 一直在库 ⇒ 仍然确定 180
        Exposure sure = ColdChainExposureCalculator.expose(
                new Stay(t(0, 0).minusDays(1), t(23, 59).minusDays(1), null, null, "入库日期与登记时刻不在同一天"), COLD);
        assertThat(sure.verdict()).isEqualTo(Verdict.AFFECTED);
        assertThat(sure.minutes()).isEqualTo(180L);
    }

    @Test
    @DisplayName("设备中途换库: 两段分别落到两个库; 没登记的那段单独报出来")
    void segmentsFollowLocationHistory() {
        Segments s = ColdChainExposureCalculator.segments(t(2, 0), t(5, 0), List.of(
                new LocationSpan("COLD", t(0, 0).minusDays(10), t(3, 30)),
                new LocationSpan("DRY", t(4, 0), null)));
        assertThat(s.covered()).containsExactly(
                new Segment("COLD", t(2, 0), t(3, 30)),
                new Segment("DRY", t(4, 0), t(5, 0)));
        assertThat(s.uncovered()).hasSize(1);
        assertThat(s.uncovered().get(0).start()).isEqualTo(t(3, 30));
        assertThat(s.uncovered().get(0).end()).isEqualTo(t(4, 0));
    }
}
