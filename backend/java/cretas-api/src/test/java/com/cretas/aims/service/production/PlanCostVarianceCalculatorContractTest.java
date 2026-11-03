package com.cretas.aims.service.production;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 食品 DAG 缺口 09 (Steve 2026-11-03): 标准成本 = 锁定配方标准用量 × 开计划时单价快照;
 * 差异拆价格差 / 用量差 / 替代料差 / 人工差; 快照缺价的项「无法比较」, 不猜。
 */
class PlanCostVarianceCalculatorContractTest {

    /** 同单位直接返回, kg↔g 换算, 其余换不了。 */
    private static final PlanCostVarianceCalculator.UnitConverter UNITS = (qty, from, to) -> {
        if (Objects.equals(from, to)) return qty;
        if ("g".equals(from) && "kg".equals(to)) return qty.movePointLeft(3);
        if ("kg".equals(from) && "g".equals(to)) return qty.movePointRight(3);
        return null;
    };

    private static PlanStandardCostLine raw(String id, String snapshotPrice, String yield) {
        return PlanStandardCostLine.builder().lineType(PlanStandardCostLine.TYPE_MATERIAL)
                .materialTypeId(id).materialName(id).materialCategory("RAW")
                .standardBasis(PlanStandardCostLine.BASIS_RAW_INPUT_SHARE).standardUnit("kg")
                .rawYieldRate(new BigDecimal(yield))
                .unitPrice(snapshotPrice == null ? null : new BigDecimal(snapshotPrice)).priceUnit("kg").build();
    }

    private static PlanStandardCostLine packaging(String id, String perUnit, String snapshotPrice) {
        return PlanStandardCostLine.builder().lineType(PlanStandardCostLine.TYPE_MATERIAL)
                .materialTypeId(id).materialName(id).materialCategory("PACKAGING")
                .standardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT).standardRate(new BigDecimal(perUnit))
                .standardUnit("个").unitPrice(snapshotPrice == null ? null : new BigDecimal(snapshotPrice))
                .priceUnit("个").build();
    }

    private static PlanStandardCostLine substitute(String id, String parent, String snapshotPrice) {
        return PlanStandardCostLine.builder().lineType(PlanStandardCostLine.TYPE_SUBSTITUTE)
                .materialTypeId(id).materialName(id).parentMaterialTypeId(parent)
                .standardBasis(PlanStandardCostLine.BASIS_NONE)
                .unitPrice(new BigDecimal(snapshotPrice)).priceUnit("kg").build();
    }

    private static PlanStandardCostLine labor(String perKg) {
        return PlanStandardCostLine.builder().lineType(PlanStandardCostLine.TYPE_LABOR)
                .standardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_KG).standardRate(new BigDecimal(perKg))
                .unitPrice(new BigDecimal(perKg)).priceUnit("kg").build();
    }

    private static PlanCostVarianceCalculator.ActualUsage used(String id, String qty, String unit, String amount) {
        return new PlanCostVarianceCalculator.ActualUsage(id, id, new BigDecimal(qty), unit,
                amount == null ? null : new BigDecimal(amount), false);
    }

    private static PlanCostVarianceCalculator.Result run(List<PlanStandardCostLine> lines,
                                                         List<PlanCostVarianceCalculator.ActualUsage> actual,
                                                         String outputKg, String outputQty, String actualLabor) {
        Map<String, PlanCostVarianceCalculator.ActualUsage> byMaterial = new LinkedHashMap<>();
        actual.forEach(a -> byMaterial.put(a.materialTypeId(), a));
        return PlanCostVarianceCalculator.compute(new PlanCostVarianceCalculator.Inputs(lines, byMaterial,
                outputKg == null ? null : new BigDecimal(outputKg),
                outputQty == null ? null : new BigDecimal(outputQty),
                actualLabor == null ? null : new BigDecimal(actualLabor), false, UNITS));
    }

    private static PlanCostVarianceDTO.Line line(PlanCostVarianceCalculator.Result r, String id) {
        return r.lines().stream().filter(l -> id.equals(l.getMaterialTypeId())).findFirst().orElseThrow();
    }

    @Test
    void priceRiseAfterPlanOpensLandsInPriceVariance() {
        // 开计划时主料 10 元/kg; 出成率 80%, 产出 80kg ⇒ 标准投入 100kg = ¥1000。
        // 之后涨到 12 元/kg, 实际照样用 100kg = ¥1200 ⇒ 差 200 全是价格差, 用量差 0。
        PlanCostVarianceCalculator.Result r = run(List.of(raw("BEEF", "10", "80")),
                List.of(used("BEEF", "100", "kg", "1200")), "80", "80", null);

        PlanCostVarianceDTO.Line beef = line(r, "BEEF");
        assertThat(beef.getStandardQuantity()).isEqualByComparingTo("100");
        assertThat(beef.getPriceVariance()).isEqualByComparingTo("200");
        assertThat(beef.getUsageVariance()).isEqualByComparingTo("0");
        assertThat(beef.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_COMPARABLE);
        assertThat(r.standardCost()).isEqualByComparingTo("1000");
        assertThat(r.actualCost()).isEqualByComparingTo("1200");
        assertThat(r.priceVariance()).isEqualByComparingTo("200");
        assertThat(r.usageVariance()).isEqualByComparingTo("0");
        assertThat(r.totalVariance()).isEqualByComparingTo("200");
        assertThat(r.varianceRate()).isEqualByComparingTo("20.00");
    }

    @Test
    void overUsageAtSnapshotPriceLandsInUsageVariance() {
        PlanCostVarianceCalculator.Result r = run(List.of(raw("BEEF", "10", "80")),
                List.of(used("BEEF", "110", "kg", "1100")), "80", "80", null);
        assertThat(r.priceVariance()).isEqualByComparingTo("0");
        assertThat(r.usageVariance()).isEqualByComparingTo("100");
        assertThat(r.totalVariance()).isEqualByComparingTo("100");
    }

    @Test
    void missingSnapshotPriceIsIncomparableAndKeptOutOfTotals() {
        // 包材开计划时档案没单价: 那一项「无法比较」, 不按 0 计入; 主料照常可比。
        PlanCostVarianceCalculator.Result r = run(
                List.of(raw("BEEF", "10", "80"), packaging("BOX", "1", null)),
                List.of(used("BEEF", "100", "kg", "1200"), used("BOX", "82", "个", "164")), "80", "80", null);

        PlanCostVarianceDTO.Line box = line(r, "BOX");
        assertThat(box.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        assertThat(box.getReasons()).anyMatch(reason -> reason.contains("开计划时物料档案没有单价"));
        assertThat(box.getPriceVariance()).isNull();
        assertThat(box.getUsageVariance()).isNull();
        assertThat(box.getStandardAmount()).isNull();
        assertThat(box.getActualAmount()).as("实际金额照样列出").isEqualByComparingTo("164");
        // 合计只含主料 —— 包材的 164 没被当成「标准 0 实际 164」的超支
        assertThat(r.standardCost()).isEqualByComparingTo("1000");
        assertThat(r.actualCost()).isEqualByComparingTo("1200");
        assertThat(r.incomparableCount()).isGreaterThanOrEqualTo(1);
        assertThat(r.comparable()).isTrue();
    }

    @Test
    void allSnapshotPricesMissingMeansNothingComparable() {
        PlanCostVarianceCalculator.Result r = run(List.of(raw("BEEF", null, "80")),
                List.of(used("BEEF", "100", "kg", "1200")), "80", "80", null);
        assertThat(r.comparable()).isFalse();
        assertThat(r.totalVariance()).isNull();
        assertThat(r.varianceRate()).isNull();
        assertThat(line(r, "BEEF").getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
    }

    @Test
    void substituteSplitsIntoPriceSubstituteAndUsageAndSumsToTotal() {
        // 主料 10 元/kg, 替代料开计划时 8 元/kg; 标准 100kg 主料。
        // 实际: 主料 60kg @10.5 = 630, 替代料 45kg @8.4 = 378。
        // 价格差 = 60×0.5 + 45×0.4 = 48; 替代料差 = 45×(8−10) = −90; 用量差 = (105−100)×10 = 50; 合计 8。
        PlanCostVarianceCalculator.Result r = run(
                List.of(raw("BEEF", "10", "80"), substitute("PORK", "BEEF", "8")),
                List.of(used("BEEF", "60", "kg", "630"), used("PORK", "45", "kg", "378")), "80", "80", null);

        assertThat(r.priceVariance()).isEqualByComparingTo("48");
        assertThat(r.substituteVariance()).isEqualByComparingTo("-90");
        assertThat(r.usageVariance()).isEqualByComparingTo("50");
        assertThat(r.totalVariance()).isEqualByComparingTo("8");
        assertThat(r.priceVariance().add(r.substituteVariance()).add(r.usageVariance()).add(r.laborVariance()))
                .as("分项之和 = 实际 − 标准").isEqualByComparingTo(r.totalVariance());
        assertThat(line(r, "PORK").getSubstituteVariance()).isEqualByComparingTo("-90");
    }

    @Test
    void laborVarianceUsesSnapshotRatePerOutputKg() {
        PlanCostVarianceCalculator.Result r = run(List.of(raw("BEEF", "10", "80"), labor("2")),
                List.of(used("BEEF", "100", "kg", "1000")), "80", "80", "200");
        assertThat(r.laborVariance()).isEqualByComparingTo("40"); // 200 − 80kg × 2
        assertThat(r.standardCost()).isEqualByComparingTo("1160");
        assertThat(r.totalVariance()).isEqualByComparingTo("40");
    }

    @Test
    void noLaborSnapshotSaysLaborIncomparable() {
        PlanCostVarianceCalculator.Result r = run(List.of(raw("BEEF", "10", "80")),
                List.of(used("BEEF", "100", "kg", "1000")), "80", "80", "200");
        PlanCostVarianceDTO.Line laborLine = r.lines().stream().filter(l -> "LABOR".equals(l.getLineType())).findFirst().orElseThrow();
        assertThat(laborLine.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        assertThat(r.laborVariance()).isEqualByComparingTo("0");
        assertThat(r.actualCost()).as("人工不可比时实际人工不进合计").isEqualByComparingTo("1000");
    }

    @Test
    void packagingStandardScalesWithOutputAndConvertsUnits() {
        PlanStandardCostLine film = PlanStandardCostLine.builder().lineType(PlanStandardCostLine.TYPE_MATERIAL)
                .materialTypeId("FILM").materialCategory("PACKAGING")
                .standardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT).standardRate(new BigDecimal("50"))
                .standardUnit("g").unitPrice(new BigDecimal("20")).priceUnit("kg").build();
        // 100 盒 × 50g = 5kg 标准; 实际 5.5kg @20 = 110 ⇒ 用量差 10
        PlanCostVarianceCalculator.Result r = run(List.of(film), List.of(used("FILM", "5.5", "kg", "110")),
                null, "100", null);
        assertThat(line(r, "FILM").getStandardQuantity()).isEqualByComparingTo("5");
        assertThat(r.usageVariance()).isEqualByComparingTo("10");
    }

    @Test
    void recipeItemWithoutAnyConsumptionRecordIsIncomparableNotASaving() {
        // 调料配方 10 g/kg, 本计划没有逐料领用记录 (常见: 按配比估算) —— ⛔ 不能算成「用了 0, 节约 ¥10」
        PlanStandardCostLine salt = PlanStandardCostLine.builder().lineType(PlanStandardCostLine.TYPE_MATERIAL)
                .materialTypeId("SALT").materialCategory("SEASONING")
                .standardBasis(PlanStandardCostLine.BASIS_PER_RAW_INPUT_KG).standardRate(new BigDecimal("0.01"))
                .rawYieldRate(new BigDecimal("80")).unitPrice(new BigDecimal("10")).priceUnit("kg").build();
        PlanCostVarianceCalculator.Result r = run(List.of(raw("BEEF", "10", "80"), salt),
                List.of(used("BEEF", "100", "kg", "1000")), "80", "80", null);
        PlanCostVarianceDTO.Line saltLine = line(r, "SALT");
        assertThat(saltLine.getStandardQuantity()).isEqualByComparingTo("1");
        assertThat(saltLine.getUsageVariance()).isNull();
        assertThat(saltLine.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        assertThat(saltLine.getReasons()).contains("本计划没有这项的实际领用记录");
        assertThat(r.usageVariance()).isEqualByComparingTo("0");
        assertThat(r.standardCost()).isEqualByComparingTo("1000");
    }

    @Test
    void unplannedMaterialIsListedAsIncomparable() {
        PlanCostVarianceCalculator.Result r = run(List.of(raw("BEEF", "10", "80")),
                List.of(used("BEEF", "100", "kg", "1000"), used("SALT", "2", "kg", "6")), "80", "80", null);
        PlanCostVarianceDTO.Line salt = line(r, "SALT");
        assertThat(salt.getLineType()).isEqualTo("UNPLANNED");
        assertThat(salt.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        assertThat(r.actualCost()).isEqualByComparingTo("1000");
    }
}
