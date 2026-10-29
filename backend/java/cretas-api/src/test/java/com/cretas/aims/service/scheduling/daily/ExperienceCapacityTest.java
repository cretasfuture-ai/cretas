package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.service.scheduling.daily.ExperienceCapacity.OutputRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 经验日产能: 有产出日子的中位数; 不足 3 天不给; 换算不了不给。 */
class ExperienceCapacityTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 1);

    private static OutputRow row(int dayOffset, String unit, String qty) {
        return new OutputRow(D.plusDays(dayOffset), unit, new BigDecimal(qty));
    }

    @Test
    void medianOfDaysWithOutputOddCount() {
        // 同一天两笔先合计: 第 0 天 100+50=150; 其余 300, 200 → 排序 150, 200, 300 → 中位 200
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(
                row(0, "袋", "100"), row(0, "袋", "50"), row(3, "袋", "300"), row(5, "袋", "200")), "袋");

        assertThat(e.available()).isTrue();
        assertThat(e.perDay()).isEqualByComparingTo("200");
        assertThat(e.sampleDays()).isEqualTo(3);
        assertThat(e.note()).contains("中位数");
    }

    @Test
    void medianOfEvenCountIsTheAverageOfTheTwoMiddleDays() {
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(
                row(0, "袋", "100"), row(1, "袋", "400"), row(2, "袋", "200"), row(3, "袋", "301")), "袋");

        assertThat(e.perDay()).isEqualByComparingTo("250.5"); // (200 + 301) / 2
        assertThat(e.sampleDays()).isEqualTo(4);
    }

    @Test
    void fewerThanThreeDaysGivesNoValueEvenWithManyRows() {
        // 两天、五笔: 笔数多不算数, 有产出的日子只有 2 天
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(
                row(0, "袋", "100"), row(0, "袋", "100"), row(0, "袋", "100"),
                row(1, "袋", "300"), row(1, "袋", "1")), "袋");

        assertThat(e.available()).isFalse();
        assertThat(e.perDay()).isNull();
        assertThat(e.sampleDays()).isEqualTo(2);
        assertThat(e.note()).contains("不足 3 天");
        // 不够 3 天也把那两天的实际量带出来 (同一天先合计, 按日期升序, 计划单位)
        assertThat(samples(e)).containsExactly(D + "=300袋", D.plusDays(1) + "=301袋");
        assertThat(e.basis()).isEqualTo(ExperienceCapacity.Basis.FINISHED_OUTPUT);
    }

    @Test
    void enoughDaysGiveNoSampleList() {
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(
                row(0, "袋", "100"), row(1, "袋", "200"), row(2, "袋", "300")), "袋");

        assertThat(e.available()).isTrue();
        assertThat(e.samples()).isEmpty();
    }

    @Test
    void noDaysAtAllGiveAnEmptySampleList() {
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(), "袋");

        assertThat(e.available()).isFalse();
        assertThat(e.sampleDays()).isZero();
        assertThat(e.samples()).isEmpty();
        assertThat(e.note()).contains("只有 0 天");
    }

    // ==================== 联产计划: 按原料投入 ====================

    @Test
    void rawInputMedianIsTakenOverDaysWithInputAndNamesTheMaterial() {
        // 第 0 天 120 + 80 = 200; 第 2 天 300; 第 5 天 250 → 中位 250
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(
                row(0, "kg", "120"), row(0, "kg", "80"), row(2, "kg", "300"), row(5, "kg", "250")),
                "kg", ExperienceCapacity.Basis.RAW_INPUT, "西装鸡");

        assertThat(e.available()).isTrue();
        assertThat(e.perDay()).isEqualByComparingTo("250");
        assertThat(e.sampleDays()).isEqualTo(3);
        assertThat(e.basis()).isEqualTo(ExperienceCapacity.Basis.RAW_INPUT);
        assertThat(e.note()).contains("实际投入西装鸡").contains("中位数").contains("3 天");
        assertThat(e.samples()).isEmpty();
    }

    @Test
    void rawInputReturnsAreNettedWithinTheDay() {
        // 差异调整退回是负数行: 第 0 天 300 − 100 = 200; 第 1 天 −50 合计为负不算有投入的日子
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(
                row(0, "kg", "300"), row(0, "kg", "-100"), row(1, "kg", "-50"),
                row(2, "kg", "100"), row(3, "kg", "250")),
                "kg", ExperienceCapacity.Basis.RAW_INPUT, "西装鸡");

        assertThat(e.sampleDays()).isEqualTo(3);
        assertThat(e.perDay()).isEqualByComparingTo("200"); // 100, 200, 250
    }

    @Test
    void rawInputWithFewerThanThreeDaysGivesNoSuggestionButListsTheDaysInThePlanUnit() {
        // 原料批次记的是 g 和斤, 计划单位 kg: 摆出来的量换成 kg
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(
                row(4, "g", "40000"), row(1, "斤", "100"), row(1, "kg", "10")),
                "kg", ExperienceCapacity.Basis.RAW_INPUT, "西装鸡");

        assertThat(e.available()).isFalse();
        assertThat(e.sampleDays()).isEqualTo(2);
        assertThat(e.note()).contains("投入西装鸡").contains("不足 3 天").contains("建议值");
        assertThat(samples(e)).containsExactly(D.plusDays(1) + "=60kg", D.plusDays(4) + "=40kg");
    }

    @Test
    void rawInputUnitsAreConvertedAndUnconvertibleUnitsGiveNoSuggestion() {
        ExperienceCapacity converted = ExperienceCapacity.fromRows(List.of(
                row(0, "kg", "100"), row(1, "公斤", "200"), row(2, "斤", "600")),   // 300 kg
                "kg", ExperienceCapacity.Basis.RAW_INPUT, "西装鸡");
        assertThat(converted.perDay()).isEqualByComparingTo("200");

        ExperienceCapacity unconvertible = ExperienceCapacity.fromRows(List.of(
                row(0, "kg", "100"), row(1, "kg", "200"), row(2, "kg", "300"), row(3, "只", "40")),
                "kg", ExperienceCapacity.Basis.RAW_INPUT, "西装鸡");
        assertThat(unconvertible.available()).isFalse();
        assertThat(unconvertible.note()).contains("只").contains("换算不了");
        assertThat(unconvertible.samples()).isEmpty();
    }

    private static List<String> samples(ExperienceCapacity e) {
        return e.samples().stream()
                .map(s -> s.date() + "=" + s.quantity().stripTrailingZeros().toPlainString() + s.unit())
                .toList();
    }

    @Test
    void zeroOutputDaysDoNotCountAsSamples() {
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(
                row(0, "袋", "100"), row(1, "袋", "0"), row(2, "袋", "200")), "袋");

        assertThat(e.available()).isFalse();
        assertThat(e.sampleDays()).isEqualTo(2);
    }

    @Test
    void convertibleUnitsAreConvertedToThePlanUnit() {
        // 计划 kg; 入库有 kg、g、斤 → 都换成 kg 再按天合计
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(
                row(0, "kg", "100"),
                row(1, "g", "200000"),          // 200 kg
                row(2, "斤", "600"),             // 300 kg
                row(2, "公斤", "0")), "kg");

        assertThat(e.available()).isTrue();
        assertThat(e.perDay()).isEqualByComparingTo("200");
        // 同一个单位的中英写法系数 1
        assertThat(CapacityUnits.factor("bag", "袋")).contains(BigDecimal.ONE);
    }

    @Test
    void unconvertibleUnitGivesNoValueRatherThanAPartialSum() {
        // 计划 kg, 但有一天入的是「袋」: 换不了 —— 不丢掉那一笔只算剩下的
        ExperienceCapacity e = ExperienceCapacity.fromRows(List.of(
                row(0, "kg", "100"), row(1, "kg", "200"), row(2, "kg", "300"), row(3, "袋", "40")), "kg");

        assertThat(e.available()).isFalse();
        assertThat(e.note()).contains("袋").contains("换算不了");
        // 只 ≠ 件 / 盒 ≠ 袋: 计数单位之间也换不了
        assertThat(CapacityUnits.factor("只", "件")).isEmpty();
        assertThat(CapacityUnits.factor("盒", "袋")).isEmpty();
        assertThat(CapacityUnits.factor("袋", "kg")).isEmpty();
    }
}
