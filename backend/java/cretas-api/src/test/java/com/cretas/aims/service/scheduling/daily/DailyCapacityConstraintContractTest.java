package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.DelayCode;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.EquipmentDayBlock;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.FixedOccupancy;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.Input;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.Job;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.JobOut;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.Status;
import com.cretas.aims.service.scheduling.daily.MaterialReadiness.Arrival;
import com.cretas.aims.service.scheduling.daily.MaterialReadiness.Need;
import com.cretas.aims.service.scheduling.daily.MaterialReadiness.Ready;
import com.cretas.aims.service.scheduling.daily.MaterialReadiness.Supply;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 食品 DAG 缺口 18: 排程约束的纯计算契约 (Steve 2026-11-09 裁定)。
 * 设备同日只做一个产品 / 同产品可共用 / 保养日 / 原料到货次日 / 半成品依赖不留缓冲 / 已有排程占用 / 缺数据照排。
 */
class DailyCapacityConstraintContractTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 10);

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static Job job(String key, String product, String qty, String cap, Set<String> equipment,
                           Set<String> predecessors) {
        return new Job(key, "PLAN-" + key, product, n(qty), n(cap), null, null, null, equipment, predecessors, null);
    }

    private static Job withEarliest(Job j, LocalDate earliest, String text) {
        return new Job(j.key(), j.label(), j.productTypeId(), j.quantity(), j.capacityPerDay(), earliest,
                DelayCode.MATERIAL_ARRIVAL, text, j.equipmentIds(), j.predecessorKeys(), j.unavailableReason());
    }

    private static List<JobOut> run(List<Job> jobs) {
        return DailyCapacityEngine.schedule(new Input(D1, jobs, List.of(), List.of()));
    }

    private static List<LocalDate> dates(JobOut out) {
        return out.days().stream().map(DailyCapacityEngine.DayOut::date).toList();
    }

    // ==================== 设备 ====================

    @Test
    void twoProductsOnTheSameEquipmentAreStaggeredToDifferentDays() {
        // A、B 都用 1 号夹层锅, 各 40% 天的量: 没约束时会挤在同一天, 有约束 B 推到第二天
        List<JobOut> out = run(List.of(
                job("A", "PT-A", "40", "100", Set.of("E1"), null),
                job("B", "PT-B", "60", "100", Set.of("E1"), null)));

        assertThat(dates(out.get(0))).containsExactly(D1);
        assertThat(dates(out.get(1))).containsExactly(D1.plusDays(1));
        assertThat(out.get(1).delays()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(DelayCode.EQUIPMENT_BUSY);
            assertThat(d.from()).isEqualTo(D1);
            assertThat(d.detail()).contains("PLAN-A");
        });
    }

    @Test
    void negativeControlWithoutSharedEquipmentBothRunOnDayOne() {
        List<JobOut> out = run(List.of(
                job("A", "PT-A", "40", "100", Set.of("E1"), null),
                job("B", "PT-B", "60", "100", Set.of("E2"), null)));

        assertThat(dates(out.get(0))).containsExactly(D1);
        assertThat(dates(out.get(1))).containsExactly(D1);
        assertThat(out.get(1).delays()).isEmpty();
    }

    @Test
    void sameProductMayShareEquipmentOnTheSameDay() {
        List<JobOut> out = run(List.of(
                job("A", "PT-A", "40", "100", Set.of("E1"), null),
                job("B", "PT-A", "30", "100", Set.of("E1"), null)));

        assertThat(dates(out.get(1))).containsExactly(D1);
    }

    @Test
    void equipmentMaintenanceDayIsSkipped() {
        List<JobOut> out = DailyCapacityEngine.schedule(new Input(D1,
                List.of(job("A", "PT-A", "250", "100", Set.of("E1"), null)), List.of(),
                List.of(new EquipmentDayBlock("E1", D1.plusDays(1), "设备 E1 保养"))));

        assertThat(dates(out.get(0))).containsExactly(D1, D1.plusDays(2), D1.plusDays(3));
        assertThat(out.get(0).delays()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(DelayCode.MAINTENANCE);
            assertThat(d.from()).isEqualTo(D1.plusDays(1));
        });
    }

    @Test
    void fixedOccupancyOfAnotherProductBlocksEquipmentAndFixedShareFillsTheLine() {
        // 已落库: 第一天 E1 被别的产品占 (别的线); 第二天本线被占 70%
        List<FixedOccupancy> fixed = List.of(
                new FixedOccupancy(D1, false, n("1"), "PT-X", Set.of("E1"), "计划 OLD-1"),
                new FixedOccupancy(D1.plusDays(1), true, n("0.7"), "PT-Y", Set.of(), "计划 OLD-2"));
        List<JobOut> out = DailyCapacityEngine.schedule(new Input(D1,
                List.of(job("A", "PT-A", "100", "100", Set.of("E1"), null)), fixed, List.of()));

        // 第一天设备被占 → 跳过; 第二天只剩 30% → 30; 第三天 70
        assertThat(out.get(0).days()).extracting(d -> d.date() + "=" + d.quantity().stripTrailingZeros().toPlainString())
                .containsExactly(D1.plusDays(1) + "=30", D1.plusDays(2) + "=70");
        assertThat(out.get(0).delays()).extracting(DailyCapacityEngine.Delay::code)
                .containsExactly(DelayCode.EQUIPMENT_BUSY);
        assertThat(out.get(0).delays().get(0).detail()).contains("OLD-1");
    }

    @Test
    void missingEquipmentConfigMeansNoEquipmentCheckNotFailure() {
        List<JobOut> out = run(List.of(
                job("A", "PT-A", "40", "100", Set.of(), null),
                job("B", "PT-B", "60", "100", Set.of(), null)));

        assertThat(out).allSatisfy(o -> assertThat(o.status()).isEqualTo(Status.SCHEDULED));
        assertThat(dates(out.get(1))).containsExactly(D1);
    }

    @Test
    void scrappedEquipmentLeavesOnlyThatPlanUnscheduled() {
        Job broken = new Job("A", "PLAN-A", "PT-A", n("40"), n("100"), null, null, null, Set.of(), Set.of(),
                "设备已报废");
        List<JobOut> out = run(List.of(broken, job("B", "PT-B", "60", "100", Set.of(), null)));

        assertThat(out.get(0).status()).isEqualTo(Status.EQUIPMENT_UNAVAILABLE);
        assertThat(out.get(0).days()).isEmpty();
        assertThat(out.get(0).notes()).contains("设备已报废");
        assertThat(out.get(1).status()).isEqualTo(Status.SCHEDULED);
    }

    // ==================== 最早开工 (原料到货) ====================

    @Test
    void earliestStartIsRespectedAndRecordedAsDelay() {
        LocalDate arrivalNextDay = D1.plusDays(3);
        List<JobOut> out = run(List.of(withEarliest(job("A", "PT-A", "150", "100", null, null), arrivalNextDay,
                "原料「鸡胸」库存不够，采购预计 2026-11-12 到货")));

        assertThat(out.get(0).firstDay()).isEqualTo(arrivalNextDay);
        assertThat(out.get(0).days()).allSatisfy(d -> assertThat(d.date()).isAfterOrEqualTo(arrivalNextDay));
        assertThat(out.get(0).delays()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(DelayCode.MATERIAL_ARRIVAL);
            assertThat(d.from()).isEqualTo(D1);
            assertThat(d.to()).isEqualTo(arrivalNextDay.minusDays(1));
            assertThat(d.detail()).contains("鸡胸");
        });
    }

    // ==================== 半成品依赖 ====================

    @Test
    void consumerStartsOnTheProducersLastDayWithoutBuffer() {
        // 酱料 A 要 2.5 天; 酱牛肉 B 用它, 传入顺序 B 在前也要等 A; Steve: 不留缓冲 ⇒ B 从 A 最后一天开始
        List<JobOut> out = run(List.of(
                job("B", "PT-B", "50", "100", null, Set.of("A")),
                job("A", "PT-A", "250", "100", null, null)));

        JobOut a = out.get(1);
        JobOut b = out.get(0);
        assertThat(a.lastDay()).isEqualTo(D1.plusDays(2));
        assertThat(b.firstDay()).isEqualTo(a.lastDay());
        assertThat(b.delays()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(DelayCode.PREDECESSOR);
            assertThat(d.detail()).contains("PLAN-A");
        });
    }

    @Test
    void consumerIsNotScheduledWhenProducerCouldNotBe() {
        Job producer = new Job("A", "PLAN-A", "PT-A", n("40"), n("100"), null, null, null, Set.of(), Set.of(),
                "设备已报废");
        List<JobOut> out = run(List.of(producer, job("B", "PT-B", "50", "100", null, Set.of("A"))));

        assertThat(out.get(1).status()).isEqualTo(Status.PREDECESSOR_NOT_SCHEDULED);
        assertThat(out.get(1).notes()).anySatisfy(n -> assertThat(n).contains("PLAN-A"));
    }

    @Test
    void producerAlreadyDoneDoesNotHoldTheConsumer() {
        List<JobOut> out = run(List.of(
                job("A", "PT-A", "0", "100", null, null),
                job("B", "PT-B", "50", "100", null, Set.of("A"))));

        assertThat(out.get(0).status()).isEqualTo(Status.NOTHING_REMAINING);
        assertThat(out.get(1).firstDay()).isEqualTo(D1);
    }

    @Test
    void dependencyCycleIsDroppedWithANoteInsteadOfFailing() {
        List<JobOut> out = run(List.of(
                job("A", "PT-A", "50", "100", null, Set.of("B")),
                job("B", "PT-B", "50", "100", null, Set.of("A"))));

        assertThat(out).allSatisfy(o -> assertThat(o.status()).isEqualTo(Status.SCHEDULED));
        assertThat(out.get(0).notes()).anySatisfy(n -> assertThat(n).contains("成环"));
    }

    // ==================== 原料先到先得 ====================

    @Test
    void stockIsUsedFirstThenArrivalsInDateOrderAndStartIsTheDayAfterArrival() {
        LocalDate po1 = D1.plusDays(2);
        LocalDate po2 = D1.plusDays(5);
        Map<String, Supply> supply = Map.of("M1", new Supply(n("100"),
                List.of(new Arrival(po2, n("500")), new Arrival(po1, n("50"))), false));
        // A 要 80 (库存够), B 要 60 (库存剩 20, 差 40 → 第一张 50 到 po1), C 要 100 (第一张剩 10, 差 90 → po2)
        Map<String, Ready> ready = MaterialReadiness.compute(List.of("A", "B", "C"), Map.of(
                "A", List.of(new Need("M1", "鸡胸", n("80"))),
                "B", List.of(new Need("M1", "鸡胸", n("60"))),
                "C", List.of(new Need("M1", "鸡胸", n("100")))), supply);

        assertThat(ready.get("A").earliestStart()).isNull();
        assertThat(ready.get("B").earliestStart()).isEqualTo(po1.plusDays(1));
        assertThat(ready.get("B").reason()).contains("鸡胸").contains(po1.toString());
        assertThat(ready.get("C").earliestStart()).isEqualTo(po2.plusDays(1));
        // 提示里不写用量 (可能是工艺保密)
        assertThat(ready.get("B").reason()).doesNotContain("60").doesNotContain("40");
    }

    @Test
    void shortWithUndatedPurchaseIsScheduledAnywayWithANote() {
        Map<String, Ready> ready = MaterialReadiness.compute(List.of("A"),
                Map.of("A", List.of(new Need("M1", "鸡胸", n("80")))),
                Map.of("M1", new Supply(n("10"), List.of(), true)));

        assertThat(ready.get("A").earliestStart()).isNull();
        assertThat(ready.get("A").notes()).singleElement().satisfies(s ->
                assertThat(s).contains("鸡胸").contains("没填预计到货日"));
    }

    @Test
    void shortWithNoPurchaseAtAllIsScheduledAnywayWithANote() {
        Map<String, Ready> ready = MaterialReadiness.compute(List.of("A"),
                Map.of("A", List.of(new Need("M1", "鸡胸", n("80")))),
                Map.of("M1", new Supply(n("10"), List.of(), false)));

        assertThat(ready.get("A").earliestStart()).isNull();
        assertThat(ready.get("A").notes()).singleElement().satisfies(s ->
                assertThat(s).contains("没有在走的采购单"));
    }

    @Test
    void anUncoverableShortageDoesNotEatStockReservedForLaterPlans() {
        // A 要 500 凑不够 (照排, 不占); B 要 50, 库存 100 仍够
        Map<String, Ready> ready = MaterialReadiness.compute(List.of("A", "B"), Map.of(
                        "A", List.of(new Need("M1", "鸡胸", n("500"))),
                        "B", List.of(new Need("M1", "鸡胸", n("50")))),
                Map.of("M1", new Supply(n("100"), List.of(), false)));

        assertThat(ready.get("A").notes()).isNotEmpty();
        assertThat(ready.get("B").earliestStart()).isNull();
        assertThat(ready.get("B").notes()).isEmpty();
    }
}
