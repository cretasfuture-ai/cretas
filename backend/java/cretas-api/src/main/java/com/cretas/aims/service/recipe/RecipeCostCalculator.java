package com.cretas.aims.service.recipe;

import com.cretas.aims.entity.bom.BomSeasoningItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 调料成本纯函数(无状态). Spec 2026-06-22 §3.2.
 * 注射/kg = Σ INJECTION: dosage_g/1000 × max(p1,p2) — 每锅同量
 * 熟制/kg(全量) = Σ COOKING ∧ countInSeasoning: dosage_g/1000 × max(p1,p2)
 * 注射总 = R注射 × 注射/kg
 * 熟制总 = 每条熟制绑定按自己的续锅比例计算后求和
 *
 * <p>运行时只读取 {@link BomSeasoningItem}，不再保留旧配方数据源或兼容入口。
 */
public final class RecipeCostCalculator {

    private static final int SCALE = 4;
    private static final BigDecimal G_PER_KG = new BigDecimal("1000");
    private RecipeCostCalculator() {}

    /**
     * 核心计算。注射成本按绝对注射量，熟制成本只读每条 binding 的锅序规则。
     *
     * @param ingredients        BOM 调料明细（注射段+熟制段）
     * @param injectionRawKg     注射前生料投入重(kg)
     * @param potRawKgs          逐锅熟制原料(kg), size=锅数N; 第1个=第一锅
     */
    public static SeasoningCost compute(List<BomSeasoningItem> ingredients,
                                        BigDecimal injectionRawKg,
                                        List<BigDecimal> potRawKgs) {
        List<String> unpriced = new ArrayList<>();
        BigDecimal injPerKg = perKg(ingredients, BomSeasoningItem.SECTION_INJECTION, false, unpriced);
        BigDecimal injectionTotal = nz(injectionRawKg).multiply(injPerKg)
                .setScale(SCALE, RoundingMode.HALF_UP);
        SeasoningCost cooking = computeBindingPotRules(ingredients, potRawKgs);
        BigDecimal cookingTotal = cooking.getCookingTotal();
        BigDecimal total = injectionTotal.add(cookingTotal).setScale(SCALE, RoundingMode.HALF_UP);
        for (String name : cooking.getUnpricedNames()) {
            if (!unpriced.contains(name)) unpriced.add(name);
        }
        return new SeasoningCost(injPerKg, cooking.getCookingFullCostPerKg(),
                injectionTotal, cookingTotal, total, List.copyOf(unpriced));
    }

    /**
     * 一条调料绑定按锅序折算后的「有效原料 kg」—— 报工自动扣料与成本计算的**唯一**口径。
     *
     * <p>逐锅: 第一锅按自身原料 ×1, 后续各锅按各自原料 × 比例 (0–1)。比例为 null ⇒ 全部原料 ×1。
     * ⛔ 不按「总量/锅数」均摊 —— 报工侧要求 N>1 逐锅填原料, 正是因为各锅不等;
     * 均摊会把逐锅填的数白白丢掉 (F006【演示L1】卤制 60/55kg、八角 50%: 逐锅 87.5kg, 均摊 86.25kg)。
     */
    public static BigDecimal effectivePotRawKg(List<BigDecimal> potRawKgs, BigDecimal subsequentPotRatio) {
        BigDecimal totalRawKg = BigDecimal.ZERO;
        if (potRawKgs != null) {
            for (BigDecimal potRawKg : potRawKgs) {
                totalRawKg = totalRawKg.add(nz(potRawKg));
            }
        }
        if (subsequentPotRatio == null || potRawKgs == null || potRawKgs.isEmpty()) {
            return totalRawKg;
        }
        BigDecimal firstPotRawKg = nz(potRawKgs.get(0));
        return firstPotRawKg.add(totalRawKg.subtract(firstPotRawKg).multiply(subsequentPotRatio));
    }

    /**
     * Calculate cooking cost with a pot rule attached to each BOM seasoning binding.
     * The first pot is always 100%; every later pot uses the same (non-compounding)
     * binding ratio. A binding with a null ratio is applied to the full process input once.
     */
    public static SeasoningCost computeBindingPotRules(List<BomSeasoningItem> ingredients,
                                                       List<BigDecimal> potRawKgs) {
        List<String> unpriced = new ArrayList<>();
        BigDecimal cookPerKg = perKg(ingredients, BomSeasoningItem.SECTION_COOKING, true, unpriced);
        BigDecimal cookingTotal = BigDecimal.ZERO;
        if (ingredients != null) {
            for (BomSeasoningItem item : ingredients) {
                if (!BomSeasoningItem.SECTION_COOKING.equals(item.getSection())
                        || !Boolean.TRUE.equals(item.getCountInSeasoning())) {
                    continue;
                }
                BigDecimal effectiveRawKg = effectivePotRawKg(potRawKgs, item.getSubsequentPotRatio());
                BigDecimal itemPerKg = nz(item.getDosagePerKgG())
                        .divide(G_PER_KG, 8, RoundingMode.HALF_UP)
                        .multiply(maxPrice(item, unpriced));
                cookingTotal = cookingTotal.add(effectiveRawKg.multiply(itemPerKg));
            }
        }
        cookingTotal = cookingTotal.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal zero = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        return new SeasoningCost(zero, cookPerKg, zero, cookingTotal, cookingTotal, List.copyOf(unpriced));
    }

    /** Σ section 明细: dosage_g/1000 × max(p1,p2); applyCountInSeasoning 时跳过 countInSeasoning=false 的行. */
    private static BigDecimal perKg(List<BomSeasoningItem> ingredients,
                                     String section, boolean applyCountInSeasoning,
                                     List<String> unpriced) {
        BigDecimal sum = BigDecimal.ZERO;
        if (ingredients == null) return sum.setScale(SCALE, RoundingMode.HALF_UP);
        for (BomSeasoningItem ing : ingredients) {
            if (!section.equals(ing.getSection())) continue;
            if (applyCountInSeasoning && !Boolean.TRUE.equals(ing.getCountInSeasoning())) continue;
            BigDecimal dosageKgPerKg = nz(ing.getDosagePerKgG()).divide(G_PER_KG, 8, RoundingMode.HALF_UP);
            sum = sum.add(dosageKgPerKg.multiply(maxPrice(ing, unpriced)));
        }
        return sum.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * max(p1,p2)。两个价都没有 ⇒ 按 0 算, 但把名字登记进 {@code unpriced} ——
     * 2026-09-02 起工作区允许无价绑定, 这里的 0 必须能被调用方看见 (报工 warning), 不许静默。
     */
    private static BigDecimal maxPrice(BomSeasoningItem ing, List<String> unpriced) {
        BigDecimal p1 = ing.getPriceSource1();
        BigDecimal p2 = ing.getPriceSource2();
        if (p1 == null && p2 == null) {
            String name = ing.getName() == null || ing.getName().isBlank() ? "(未命名调料)" : ing.getName();
            if (!unpriced.contains(name)) unpriced.add(name);
            return BigDecimal.ZERO;
        }
        if (p1 == null) return p2;
        if (p2 == null) return p1;
        return p1.max(p2);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
