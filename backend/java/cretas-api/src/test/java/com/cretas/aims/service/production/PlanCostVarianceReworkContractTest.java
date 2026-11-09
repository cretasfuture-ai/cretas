package com.cretas.aims.service.production;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 食品 DAG 缺口 09 补齐 (2026-11-09): 计划成本差异单列「回料」。
 *
 * <p>口径: 从别的计划回投的回料 = 回投量 × 回料池单位成本 (与成本核算同源); 配方不含回料 ⇒ 标准回料 0,
 * 回料差 = 回投金额; 本计划自产回投 = 内部转移不另计; 单价/折算/标准任何一个不知道 ⇒ 无法比较, ⛔ 不按 0 计。</p>
 */
class PlanCostVarianceReworkContractTest {

    private static final PlanCostVarianceCalculator.UnitConverter UNITS =
            (qty, from, to) -> Objects.equals(from, to) ? qty : null;

    private static PlanStandardCostLine raw(String price, String yield) {
        return PlanStandardCostLine.builder().lineType(PlanStandardCostLine.TYPE_MATERIAL)
                .materialTypeId("BEEF").materialName("BEEF").materialCategory("RAW")
                .standardBasis(PlanStandardCostLine.BASIS_RAW_INPUT_SHARE).standardUnit("kg")
                .rawYieldRate(yield == null ? null : new BigDecimal(yield))
                .unitPrice(new BigDecimal(price)).priceUnit("kg").build();
    }

    private static PlanStandardCostLine labor(String perKg) {
        return PlanStandardCostLine.builder().lineType(PlanStandardCostLine.TYPE_LABOR)
                .standardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_KG).standardRate(new BigDecimal(perKg))
                .unitPrice(new BigDecimal(perKg)).priceUnit("kg").build();
    }

    private static PlanCostVarianceCalculator.ReworkUsage rework(String qty, String unitCost, boolean internal) {
        return new PlanCostVarianceCalculator.ReworkUsage("CLK-SEMI-A-P", "卤牛肉 (回料)",
                qty == null ? null : new BigDecimal(qty), "kg", unitCost == null ? null : new BigDecimal(unitCost),
                internal, List.of(PlanCostVarianceDTO.ReworkSource.builder().producerBatchNumber("PB-1")
                        .producerPlanNumber("PLAN-A").quantity(new BigDecimal("1")).unit("kg").build()),
                new ArrayList<>());
    }

    private static PlanCostVarianceCalculator.Result run(String yield, List<PlanCostVarianceCalculator.ReworkUsage> rework,
                                                         String standardUnknownReason) {
        Map<String, PlanCostVarianceCalculator.ActualUsage> actual = new LinkedHashMap<>();
        // 主料 BEEF 实际用 95kg @10 = 950; 出成率 80%, 产出 80kg ⇒ 标准投入 100kg = ¥1000 ⇒ 用量差 −50
        actual.put("BEEF", new PlanCostVarianceCalculator.ActualUsage("BEEF", "BEEF", new BigDecimal("95"), "kg",
                new BigDecimal("950"), false));
        return PlanCostVarianceCalculator.compute(new PlanCostVarianceCalculator.Inputs(
                List.of(raw("10", yield), labor("2")), actual, new BigDecimal("80"), new BigDecimal("80"),
                new BigDecimal("170"), false, UNITS, rework, standardUnknownReason));
    }

    private static PlanCostVarianceDTO.Line reworkLine(PlanCostVarianceCalculator.Result r) {
        return r.lines().stream().filter(l -> "REWORK".equals(l.getLineType())).findFirst().orElseThrow();
    }

    private static BigDecimal sumOfParts(PlanCostVarianceCalculator.Result r) {
        BigDecimal sum = r.priceVariance().add(r.usageVariance()).add(r.substituteVariance());
        if (r.laborVariance() != null) sum = sum.add(r.laborVariance());
        if (r.reworkVariance() != null) sum = sum.add(r.reworkVariance());
        return sum;
    }

    @Test
    void reworkFromAnotherPlanIsPricedAtPoolUnitCostAndLandsInReworkVariance() {
        // 回投 2.5kg 回料, 回料池单位成本 12.9375 ⇒ 32.34375; 配方不含回料 ⇒ 标准 0, 回料差 = 32.34
        PlanCostVarianceCalculator.Result r = run("80", List.of(rework("2.5", "12.9375", false)), null);

        PlanCostVarianceDTO.Line line = reworkLine(r);
        assertThat(line.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_COMPARABLE);
        assertThat(line.getActualQuantity()).isEqualByComparingTo("2.5");
        assertThat(line.getActualUnitPrice()).isEqualByComparingTo("12.9375");
        assertThat(line.getStandardQuantity()).as("配方不含回料: 标准回料就是 0 (配方说的)").isEqualByComparingTo("0");
        assertThat(line.getStandardAmount()).isEqualByComparingTo("0");
        assertThat(line.getActualAmount()).isEqualByComparingTo("32.34");
        assertThat(line.getReworkVariance()).isEqualByComparingTo("32.34");
        assertThat(line.getPriceSource()).contains("回料池单位成本");
        assertThat(line.getReworkSources()).extracting(PlanCostVarianceDTO.ReworkSource::getProducerPlanNumber)
                .containsExactly("PLAN-A");

        assertThat(r.reworkStatus()).isEqualTo(PlanCostVarianceDTO.REWORK_COMPARABLE);
        assertThat(r.reworkVariance()).isEqualByComparingTo("32.34");
        // 合计: 标准 1000 + 160 (人工 80kg×2) = 1160; 实际 950 + 170 + 32.34375 = 1152.34
        assertThat(r.standardCost()).isEqualByComparingTo("1160");
        assertThat(r.actualCost()).isEqualByComparingTo("1152.34");
        assertThat(sumOfParts(r)).as("价格差 + 用量差 + 替代料差 + 人工差 + 回料差 = 实际 − 标准")
                .isEqualByComparingTo(r.totalVariance());
    }

    @Test
    void reworkTotalsDifferFromNoReworkExactlyByTheReworkAmount() {
        PlanCostVarianceCalculator.Result without = run("80", List.of(), null);
        PlanCostVarianceCalculator.Result with = run("80", List.of(rework("4", "10", false)), null);
        assertThat(without.reworkStatus()).isEqualTo(PlanCostVarianceDTO.REWORK_NONE);
        assertThat(without.reworkVariance()).as("没有回料: ⛔ 不报「回料差 ¥0」").isNull();
        assertThat(without.lines()).noneMatch(l -> "REWORK".equals(l.getLineType()));
        assertThat(with.actualCost().subtract(without.actualCost())).isEqualByComparingTo("40");
        assertThat(with.standardCost()).isEqualByComparingTo(without.standardCost());
        assertThat(with.comparableCount()).isEqualTo(without.comparableCount() + 1);
    }

    @Test
    void unknownPoolUnitCostIsIncomparableAndNotCountedAsZero() {
        PlanCostVarianceCalculator.Result without = run("80", List.of(), null);
        PlanCostVarianceCalculator.Result r = run("80", List.of(rework("2", null, false)), null);

        PlanCostVarianceDTO.Line line = reworkLine(r);
        assertThat(line.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        assertThat(line.getReasons()).anyMatch(reason -> reason.contains("回料池没有单位成本"));
        assertThat(line.getActualAmount()).as("⛔ 不是 ¥0").isNull();
        assertThat(line.getReworkVariance()).isNull();
        assertThat(line.getActualQuantity()).as("回投量照样列出").isEqualByComparingTo("2");
        assertThat(r.reworkStatus()).isEqualTo(PlanCostVarianceDTO.REWORK_INCOMPARABLE);
        assertThat(r.reworkVariance()).isNull();
        assertThat(r.actualCost()).as("无法比较的回料不进合计").isEqualByComparingTo(without.actualCost());
        assertThat(r.incomparableCount()).isEqualTo(without.incomparableCount() + 1);
    }

    @Test
    void unconvertibleQuantityIsIncomparable() {
        PlanCostVarianceCalculator.ReworkUsage u = new PlanCostVarianceCalculator.ReworkUsage("POOL", "回料", null, "盒",
                new BigDecimal("3"), false, List.of(), new ArrayList<>(List.of("有一笔回投 1 kg 折算不到回料池单位 盒")));
        PlanCostVarianceCalculator.Result r = run("80", List.of(u), null);
        PlanCostVarianceDTO.Line line = reworkLine(r);
        assertThat(line.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        assertThat(line.getReasons()).anyMatch(reason -> reason.contains("折算不到回料池单位"));
        assertThat(line.getActualAmount()).isNull();
        assertThat(r.reworkVariance()).isNull();
    }

    @Test
    void ownPlanReworkIsInternalTransferNotCountedNorIncomparable() {
        PlanCostVarianceCalculator.Result without = run("80", List.of(), null);
        PlanCostVarianceCalculator.Result r = run("80", List.of(rework("3", "12", true)), null);

        PlanCostVarianceDTO.Line line = reworkLine(r);
        assertThat(line.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INTERNAL);
        assertThat(line.getActualQuantity()).isEqualByComparingTo("3");
        assertThat(line.getActualAmount()).as("内部转移不计金额 (原料已在本计划领用里)").isNull();
        assertThat(line.getReworkVariance()).isNull();
        assertThat(r.reworkStatus()).isEqualTo(PlanCostVarianceDTO.REWORK_INTERNAL_ONLY);
        assertThat(r.actualCost()).isEqualByComparingTo(without.actualCost());
        assertThat(r.comparableCount()).isEqualTo(without.comparableCount());
        assertThat(r.incomparableCount()).isEqualTo(without.incomparableCount());
    }

    @Test
    void recipeThatDeclaresReworkHasNoSnapshotStandardSoReworkIsIncomparable() {
        String reason = "锁定配方登记了回料用量, 但开计划时的快照没有回料标准价, 回料差无法比较";
        PlanCostVarianceCalculator.Result r = run("80", List.of(rework("2", "10", false)), reason);
        PlanCostVarianceDTO.Line line = reworkLine(r);
        assertThat(line.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        assertThat(line.getStandardQuantity()).as("标准回料不是 0, 也不知道是多少").isNull();
        assertThat(line.getActualAmount()).as("实际回投金额照样列出").isEqualByComparingTo("20");
        assertThat(line.getReworkVariance()).isNull();
        assertThat(r.reworkVariance()).isNull();

        // 配方要求回料、本计划一点没回投: 也是一项无法比较, ⛔ 不当「回料差 0」
        PlanCostVarianceCalculator.Result none = run("80", List.of(), reason);
        assertThat(reworkLine(none).getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        assertThat(none.reworkStatus()).isEqualTo(PlanCostVarianceDTO.REWORK_INCOMPARABLE);
    }

    @Test
    void reworkStaysComparableEvenWhenRecipeHasNoYieldRateLikeF006() {
        // F006 配方普遍没有出成率: 主料用量差算不出, 但回料不依赖出成率, 照样可比
        PlanCostVarianceCalculator.Result r = run(null, List.of(rework("1", "34.3867", false)), null);
        assertThat(r.lines().stream().filter(l -> "BEEF".equals(l.getMaterialTypeId())).findFirst().orElseThrow()
                .getUsageVariance()).isNull();
        assertThat(reworkLine(r).getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_COMPARABLE);
        assertThat(r.reworkVariance()).isEqualByComparingTo("34.39");
        assertThat(sumOfParts(r)).isEqualByComparingTo(r.totalVariance());

        String note = PlanCostVarianceService.reworkNote(r, List.of(rework("1", "34.3867", false)));
        assertThat(note).contains("回投 1 kg").contains("回料池单位成本").contains("+¥34.39")
                .as("主料用量差算不出时要提示回料差单看会偏高").contains("回料差单看会偏高");
    }

    @Test
    void noteSaysNoReworkAndQuantityTextSumsPerUnit() {
        assertThat(PlanCostVarianceService.reworkNote(run("80", List.of(), null), List.of()))
                .isEqualTo("本计划没有回投回料");
        assertThat(PlanCostVarianceService.reworkQuantityText(List.of(rework("1.5", "1", false), rework("0.5", "1", false))))
                .isEqualTo("2 kg");
        assertThat(PlanCostVarianceService.reworkQuantityText(List.of(rework(null, "1", false), rework("0.5", "1", false))))
                .isEqualTo("0.5 kg + 另有部分折算不了");
    }
}
