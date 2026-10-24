package com.cretas.aims.service.recipe;

import com.cretas.aims.entity.bom.BomSeasoningItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeCostCalculatorBindingPotTest {
    @Test
    void eachBindingUsesItsOwnNonCompoundingPotRule() {
        BomSeasoningItem chili = cooking("MAT-CHILI", "10", "1000", "0.5");
        BomSeasoningItem salt = cooking("MAT-SALT", "10", "1000", null);
        SeasoningCost result = RecipeCostCalculator.computeBindingPotRules(
                List.of(chili, salt),
                List.of(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100")));
        // price=1000/kg makes numeric cost equal grams: chili=2000g, salt=3000g.
        assertEquals(0, new BigDecimal("5000.0000").compareTo(result.getCookingTotal()));
    }

    @Test
    void unequalPotsUseEachPotsOwnRawWeightNotTheAverage() {
        // F006【演示L1】卤制 2 锅 60/55kg、八角 2g/kg、后续锅 50%:
        // 逐锅 60×2 + 55×2×0.5 = 175g; 均摊 57.5×(1+0.5)×2 = 172.5g。price=1000/kg ⇒ 成本数值 = 克数。
        BomSeasoningItem anise = cooking("MAT-ANISE", "2", "1000", "0.5");
        SeasoningCost result = RecipeCostCalculator.computeBindingPotRules(
                List.of(anise), List.of(new BigDecimal("60"), new BigDecimal("55")));
        assertEquals(0, new BigDecimal("175.0000").compareTo(result.getCookingTotal()));
    }

    @Test
    void effectivePotRawKgIsTheSingleRuleForDeductionAndCost() {
        assertEquals(0, new BigDecimal("87.5").compareTo(RecipeCostCalculator.effectivePotRawKg(
                List.of(new BigDecimal("60"), new BigDecimal("55")), new BigDecimal("0.5"))));
        // 无比例 ⇒ 全部原料 ×1; 单锅 ⇒ 比例不起作用
        assertEquals(0, new BigDecimal("115").compareTo(RecipeCostCalculator.effectivePotRawKg(
                List.of(new BigDecimal("60"), new BigDecimal("55")), null)));
        assertEquals(0, new BigDecimal("60").compareTo(RecipeCostCalculator.effectivePotRawKg(
                List.of(new BigDecimal("60")), new BigDecimal("0.5"))));
    }

    @Test
    void nullBindingRatioAppliesToTheFullInputWithoutAnyFallback() {
        BomSeasoningItem legacy = cooking(null, "10", "1000", null);
        SeasoningCost result = RecipeCostCalculator.computeBindingPotRules(
                List.of(legacy),
                List.of(new BigDecimal("100"), new BigDecimal("100")));
        assertEquals(0, new BigDecimal("2000.0000").compareTo(result.getCookingTotal()));
    }

    private BomSeasoningItem cooking(String materialTypeId, String dosage, String price, String ratio) {
        BomSeasoningItem item = new BomSeasoningItem();
        item.setMaterialTypeId(materialTypeId);
        item.setSection("COOKING");
        item.setDosagePerKgG(new BigDecimal(dosage));
        item.setPriceSource1(new BigDecimal(price));
        item.setCountInSeasoning(true);
        item.setSubsequentPotRatio(ratio == null ? null : new BigDecimal(ratio));
        return item;
    }
}
