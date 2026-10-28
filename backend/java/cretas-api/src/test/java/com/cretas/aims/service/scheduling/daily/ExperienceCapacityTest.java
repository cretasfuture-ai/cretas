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
