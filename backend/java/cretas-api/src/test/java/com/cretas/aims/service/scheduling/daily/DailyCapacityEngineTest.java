package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.DayOut;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.JobIn;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.JobOut;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** APS v2 按天分配引擎: 纯计算。 */
class DailyCapacityEngineTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 29);
    private static final LocalDate D2 = D1.plusDays(1);
    private static final LocalDate D3 = D1.plusDays(2);

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static JobIn job(String key, String qty, String cap) {
        return new JobIn(key, qty == null ? null : n(qty), cap == null ? null : n(cap));
    }

    /** 「哪天做多少」逐日读出来, 比较时不看小数位写法。 */
    private static void assertDays(JobOut out, Object... dateQtyShare) {
        assertThat(out.days()).hasSize(dateQtyShare.length / 3);
        for (int i = 0; i < out.days().size(); i++) {
            DayOut d = out.days().get(i);
            assertThat(d.date()).as("day %d date", i).isEqualTo(dateQtyShare[i * 3]);
            assertThat(d.quantity()).as("day %d qty", i).isEqualByComparingTo(n((String) dateQtyShare[i * 3 + 1]));
            assertThat(d.shareOfDay()).as("day %d share", i).isEqualByComparingTo(n((String) dateQtyShare[i * 3 + 2]));
        }
    }

    @Test
    void singlePlanSpillsOverThreeDays() {
        // 700 袋, 一天 300 袋 → 300 / 300 / 100
        JobOut out = DailyCapacityEngine.allocate(List.of(job("P1", "700", "300")), D1).get(0);

        assertThat(out.status()).isEqualTo(Status.SCHEDULED);
        assertDays(out, D1, "300", "1", D2, "300", "1", D3, "100", "0.333334");
        assertThat(out.firstDay()).isEqualTo(D1);
        assertThat(out.lastDay()).isEqualTo(D3);
        assertThat(out.totalQuantity()).isEqualByComparingTo("700");
    }

    @Test
    void secondPlanStartsFromWhatIsLeftOfTheSharedDay() {
        // A: 40 袋 / 日产能 100 → 用掉第一天 40%
        // B: 300 kg / 日产能 200 → 第一天只剩 60% = 120 kg, 第二天 180 kg (0.9 天)
        // C: 日产能 50 → 第二天剩 10% = 5, 第三天接着做
        List<JobOut> out = DailyCapacityEngine.allocate(List.of(
                job("A", "40", "100"),
                job("B", "300", "200"),
                job("C", "20", "50")), D1);

        assertDays(out.get(0), D1, "40", "0.4");
        assertDays(out.get(1), D1, "120", "0.6", D2, "180", "0.9");
        assertDays(out.get(2), D2, "5", "0.1", D3, "15", "0.3");
    }

    @Test
    void orderOfPlansDecidesWhoGoesFirst() {
        JobIn a = job("A", "100", "100");
        JobIn b = job("B", "100", "100");

        List<JobOut> ab = DailyCapacityEngine.allocate(List.of(a, b), D1);
        List<JobOut> ba = DailyCapacityEngine.allocate(List.of(b, a), D1);

        assertThat(ab.get(0).key()).isEqualTo("A");
        assertThat(ab.get(0).firstDay()).isEqualTo(D1);
        assertThat(ab.get(1).firstDay()).isEqualTo(D2);
        assertThat(ba.get(0).key()).isEqualTo("B");
        assertThat(ba.get(0).firstDay()).isEqualTo(D1);
        assertThat(ba.get(1).key()).isEqualTo("A");
        assertThat(ba.get(1).firstDay()).isEqualTo(D2);
    }

    @Test
    void missingOrZeroCapacityIsListedAndTakesNoShareOfTheDay() {
        List<JobOut> out = DailyCapacityEngine.allocate(List.of(
                job("NULL_CAP", "50", null),
                job("ZERO_CAP", "50", "0"),
                job("NEXT", "100", "100")), D1);

        assertThat(out.get(0).status()).isEqualTo(Status.NEEDS_CAPACITY);
        assertThat(out.get(0).days()).isEmpty();
        assertThat(out.get(1).status()).isEqualTo(Status.NEEDS_CAPACITY);
        assertThat(out.get(1).days()).isEmpty();
        // 阳性对照: 后面的计划仍从第一天的整天开始
        assertDays(out.get(2), D1, "100", "1");
    }

    @Test
    void nothingRemainingIsNotScheduled() {
        List<JobOut> out = DailyCapacityEngine.allocate(List.of(
                job("DONE", "0", "100"),
                job("NEXT", "50", "100")), D1);

        assertThat(out.get(0).status()).isEqualTo(Status.NOTHING_REMAINING);
        assertDays(out.get(1), D1, "50", "0.5");
    }

    @Test
    void absurdHorizonIsRefusedAndReservesNothing() {
        // 一天 1 袋要做 10000 袋 → 超过 366 天, 不排也不占产能
        List<JobOut> out = DailyCapacityEngine.allocate(List.of(
                job("TOO_LONG", "10000", "1"),
                job("NEXT", "100", "100")), D1);

        assertThat(out.get(0).status()).isEqualTo(Status.EXCEEDS_HORIZON);
        assertThat(out.get(0).days()).isEmpty();
        assertDays(out.get(1), D1, "100", "1");
    }

    @Test
    void decimalQuantitiesKeepTwoPlacesAndSumToTheRemaining() {
        JobOut out = DailyCapacityEngine.allocate(List.of(job("P", "250.5", "100.25")), D1).get(0);

        assertThat(out.totalQuantity()).isEqualByComparingTo("250.5");
        assertThat(out.days()).hasSize(3);
        assertThat(out.days().get(0).quantity()).isEqualByComparingTo("100.25");
    }
}
